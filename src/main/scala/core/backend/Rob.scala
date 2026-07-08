package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * ROB（Reorder Buffer）：乱序执行、顺序提交。
 *
 * 每项记录：
 *   - uop, pc, rd, pdst, stalePdst（旧映射，commit 时释放给 FreeList）
 *   - done, exception, mispred, taken, target
 *   - isCall, isRet（retire 时更新 RAS）
 *
 * 提交：从 head 顺序提交 done 的项，最多 1 条/周期。
 * 释放：commit 时把 stalePdst 归还 FreeList（前提：这条指令确实写了 rd）。
 *
 * 重定向：分支误预测 / 异常 → 冲刷 ROB 中所有 robIdx > 触发项 的项，
 *         并把 rename table 回滚。
 */
class Rob extends Module {
  val io = IO(new Bundle {
    // dispatch 入口
    val enq = Input(Valid(new Bundle {
      val uop    = Uop()
      val pc     = UInt(PcWidth.W)
      val rd     = UInt(LogNumLogical.W)
      val pdst   = UInt(LogNumPhys.W)
      val stalePdst = UInt(LogNumPhys.W)
      val writesReg = Bool()
      val predTaken = Bool()
      val predTarget = UInt(PcWidth.W)
      val branchMask = UInt(BranchCheckpointEntries.W)
    }))
    val enqReady = Output(Bool())
    val enqIdx   = Output(UInt(RobIdWidth.W))  // 分配的 ROB 槽号

    // 执行结果写回
    val wb = Input(Valid(new WritebackBundle))
    val commitStoreReady = Input(Bool())

    // 提交出口
    val commit = Valid(new Bundle {
      val uop    = Uop()
      val pc     = UInt(PcWidth.W)
      val rd     = UInt(LogNumLogical.W)
      val pdst   = UInt(LogNumPhys.W)
      val stalePdst = UInt(LogNumPhys.W)
      val writesReg = Bool()
      val taken  = Bool()
      val target = UInt(PcWidth.W)
      val mispred = Bool()
      val illegal = Bool()
      val isCall = Bool()
      val isRet  = Bool()
    })

    val clearBranchMask = Input(Valid(UInt(BranchCheckpointEntries.W)))
    val flushBranchMask = Input(Valid(new Bundle {
      val mask = UInt(BranchCheckpointEntries.W)
      val robIdx = UInt(RobIdWidth.W)
    }))

    // 释放物理寄存器给 FreeList
    val freeReq   = Output(Bool())
    val freePdst  = Output(UInt(LogNumPhys.W))

    // 重定向（给前端 + rename + issue queue）
    val redirect = Valid(new Redirect)

    // 回滚 rename table（mispred / exception 时）
    val rollback = Valid(new Bundle {
      val lrd  = UInt(LogNumLogical.W)
      val pdst = UInt(LogNumPhys.W)
    })

    // flush IssueQueue
    val flushIssue = Output(Bool())
    val flushAll = Input(Bool())

    // 调试
    val dbgCount = Output(UInt((log2Ceil(RobEntries) + 1).W))
    val dbgHead  = Output(UInt(RobIdWidth.W))
    val dbgTail  = Output(UInt(RobIdWidth.W))
    val empty = Output(Bool())
  })

  class RobEntry extends Bundle {
    val valid      = Bool()
    val uop        = Uop()
    val pc         = UInt(PcWidth.W)
    val rd         = UInt(LogNumLogical.W)
    val pdst       = UInt(LogNumPhys.W)
    val stalePdst  = UInt(LogNumPhys.W)
    val writesReg  = Bool()
    val done       = Bool()
    val exception  = Bool()
    val taken      = Bool()
    val target     = UInt(PcWidth.W)
    val mispred    = Bool()
    val redirectHandled = Bool()
    val predTaken  = Bool()
    val predTarget = UInt(PcWidth.W)
    val branchMask = UInt(BranchCheckpointEntries.W)
  }

  val entries = RegInit(VecInit(Seq.fill(RobEntries)(0.U.asTypeOf(new RobEntry))))
  val head = RegInit(0.U(RobIdWidth.W))
  val tail = RegInit(0.U(RobIdWidth.W))
  val count = RegInit(0.U((log2Ceil(RobEntries) + 1).W))
  val validPop = PopCount(entries.map(_.valid))
  val headEntry = entries(head)
  val headIsStore = UopKind.isStore(headEntry.uop)
  val canCommit = count =/= 0.U && headEntry.valid && headEntry.done &&
    (!headIsStore || io.commitStoreReady)

  // 入队
  val canEnq = count < RobEntries.U
  io.enqReady := canEnq
  io.enqIdx   := tail

  io.commit.valid := canCommit
  io.commit.bits.uop        := headEntry.uop
  io.commit.bits.pc         := headEntry.pc
  io.commit.bits.rd         := headEntry.rd
  io.commit.bits.pdst       := headEntry.pdst
  io.commit.bits.stalePdst  := headEntry.stalePdst
  io.commit.bits.writesReg  := headEntry.writesReg
  io.commit.bits.taken      := headEntry.taken
  io.commit.bits.target     := headEntry.target
  io.commit.bits.mispred    := headEntry.mispred
  io.commit.bits.illegal    := headEntry.exception
  io.commit.bits.isCall     := headEntry.uop === JAL &&
    (headEntry.rd === 1.U || headEntry.rd === 5.U)
  io.commit.bits.isRet      := headEntry.uop === JALR &&
    (headEntry.rd === 1.U || headEntry.rd === 5.U)

  // 释放旧物理寄存器
  io.freeReq  := canCommit && headEntry.writesReg && headEntry.rd =/= 0.U
  io.freePdst := headEntry.stalePdst

  // 重定向：误预测或异常
  io.redirect.valid := canCommit && ((headEntry.mispred && !headEntry.redirectHandled) || headEntry.exception)
  io.redirect.bits.target := Mux(headEntry.exception || headEntry.taken,
    headEntry.target,
    headEntry.pc + 4.U)
  io.redirect.bits.robIdx := head
  io.redirect.bits.cause  := Mux(headEntry.exception, RedirectCause.EXCEPTION,
    Mux(headEntry.mispred, RedirectCause.MISPRED, RedirectCause.NONE))

  // 回滚 rename table：commit 误预测时，把 rd 恢复为 stalePdst
  io.rollback.valid := canCommit && headEntry.mispred && !headEntry.redirectHandled &&
    !UopKind.isJump(headEntry.uop) &&
    headEntry.writesReg && headEntry.rd =/= 0.U
  io.rollback.bits.lrd  := headEntry.rd
  io.rollback.bits.pdst := headEntry.stalePdst

  // flush IssueQueue：发生重定向时
  io.flushIssue := io.redirect.valid

  val commitRedirect = io.redirect.valid

  val nextEntries = Wire(Vec(RobEntries, new RobEntry))
  val nextHead = WireDefault(head)
  val nextTail = WireDefault(tail)
  nextEntries := entries

  val flushBranchDist = io.flushBranchMask.bits.robIdx - head
  val wbDist = io.wb.bits.robIdx - head
  val wbYoungerThanFlushBranch = wbDist > flushBranchDist
  val doCommit = canCommit && !commitRedirect && !io.flushAll
  val doEnq = io.enq.valid && canEnq && !io.flushAll && !commitRedirect && !io.flushBranchMask.valid
  val wbTargetsCommittedHead = doCommit && io.wb.bits.robIdx === head
  val wbTargetsFlushedBranchTail =
    io.flushBranchMask.valid && wbYoungerThanFlushBranch
  val doWb = io.wb.valid && !io.flushAll && !commitRedirect &&
    !wbTargetsCommittedHead && !wbTargetsFlushedBranchTail &&
    entries(io.wb.bits.robIdx).valid

  when(io.flushAll) {
    for (i <- 0 until RobEntries) {
      nextEntries(i).valid := false.B
      nextEntries(i).done := false.B
    }
    nextHead := tail
    nextTail := tail
  }.elsewhen(commitRedirect) {
    for (i <- 0 until RobEntries) {
      nextEntries(i).valid := false.B
      nextEntries(i).done := false.B
    }
    nextHead := tail
    nextTail := tail
  }.otherwise {
    when(io.flushBranchMask.valid) {
      for (i <- 0 until RobEntries) {
        val entryDist = i.U(RobIdWidth.W) - head
        val youngerThanBranch = entryDist > flushBranchDist
        when(entries(i).valid && youngerThanBranch) {
          nextEntries(i).valid := false.B
          nextEntries(i).done := false.B
        }
      }
      nextTail := io.flushBranchMask.bits.robIdx + 1.U
    }.elsewhen(io.clearBranchMask.valid) {
      for (i <- 0 until RobEntries) {
        nextEntries(i).branchMask := entries(i).branchMask & ~io.clearBranchMask.bits
      }
    }

    when(doWb) {
      nextEntries(io.wb.bits.robIdx).done := true.B
      nextEntries(io.wb.bits.robIdx).taken := io.wb.bits.taken
      nextEntries(io.wb.bits.robIdx).target := io.wb.bits.target
      nextEntries(io.wb.bits.robIdx).exception :=
        io.wb.bits.cause === RedirectCause.EXCEPTION || io.wb.bits.cause === RedirectCause.FLUSH
      when(io.wb.bits.cause === RedirectCause.MISPRED) {
        nextEntries(io.wb.bits.robIdx).mispred := true.B
        nextEntries(io.wb.bits.robIdx).redirectHandled := io.wb.bits.redirectHandled
      }
    }

    when(doCommit) {
      nextEntries(head).valid := false.B
      nextEntries(head).done := false.B
      nextHead := head + 1.U
    }

    when(doEnq) {
      nextEntries(tail).valid      := true.B
      nextEntries(tail).uop        := io.enq.bits.uop
      nextEntries(tail).pc         := io.enq.bits.pc
      nextEntries(tail).rd         := io.enq.bits.rd
      nextEntries(tail).pdst       := io.enq.bits.pdst
      nextEntries(tail).stalePdst  := io.enq.bits.stalePdst
      nextEntries(tail).writesReg  := io.enq.bits.writesReg
      nextEntries(tail).done       := false.B
      nextEntries(tail).exception  := false.B
      nextEntries(tail).taken      := false.B
      nextEntries(tail).target     := 0.U
      nextEntries(tail).mispred    := false.B
      nextEntries(tail).redirectHandled := false.B
      nextEntries(tail).predTaken  := io.enq.bits.predTaken
      nextEntries(tail).predTarget := io.enq.bits.predTarget
      nextEntries(tail).branchMask := io.enq.bits.branchMask
      nextTail := tail + 1.U
    }
  }

  val nextCount = PopCount(nextEntries.map(_.valid))
  entries := nextEntries
  head := nextHead
  tail := nextTail
  count := nextCount

  assert(count === validPop, "ROB count/valid invariant failed")

  // 调试
  io.dbgCount := count
  io.dbgHead  := head
  io.dbgTail  := tail
  io.empty := count === 0.U
}
