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
    val predTaken  = Bool()
    val predTarget = UInt(PcWidth.W)
  }

  val entries = RegInit(VecInit(Seq.fill(RobEntries)(0.U.asTypeOf(new RobEntry))))
  val head = RegInit(0.U(RobIdWidth.W))
  val tail = RegInit(0.U(RobIdWidth.W))
  val count = RegInit(0.U((log2Ceil(RobEntries) + 1).W))

  // 入队
  val canEnq = count < RobEntries.U
  io.enqReady := canEnq
  io.enqIdx   := tail
  when(io.flushAll) {
    for (e <- entries) {
      e.valid := false.B
      e.done := false.B
    }
    head := tail
    count := 0.U
  }.elsewhen(io.enq.valid && canEnq) {
    entries(tail).valid      := true.B
    entries(tail).uop        := io.enq.bits.uop
    entries(tail).pc         := io.enq.bits.pc
    entries(tail).rd         := io.enq.bits.rd
    entries(tail).pdst       := io.enq.bits.pdst
    entries(tail).stalePdst  := io.enq.bits.stalePdst
    entries(tail).writesReg  := io.enq.bits.writesReg
    entries(tail).done       := false.B
    entries(tail).exception  := false.B
    entries(tail).taken      := false.B
    entries(tail).target     := 0.U
    entries(tail).mispred    := false.B
    entries(tail).predTaken  := io.enq.bits.predTaken
    entries(tail).predTarget := io.enq.bits.predTarget
    tail  := tail + 1.U
  }

  // 写回：根据 robIdx 定位
  when(io.wb.valid && !io.flushAll) {
    entries(io.wb.bits.robIdx).done := true.B
    entries(io.wb.bits.robIdx).taken := io.wb.bits.taken
    entries(io.wb.bits.robIdx).target := io.wb.bits.target
    entries(io.wb.bits.robIdx).exception :=
      io.wb.bits.cause === RedirectCause.EXCEPTION || io.wb.bits.cause === RedirectCause.FLUSH
    when(io.wb.bits.cause === RedirectCause.MISPRED) {
      entries(io.wb.bits.robIdx).mispred := true.B
    }
  }

  // 提交：从 head 顺序
  val headEntry = entries(head)
  val headIsStore = UopKind.isStore(headEntry.uop)
  val canCommit = count > 0.U && headEntry.valid && headEntry.done &&
    (!headIsStore || io.commitStoreReady)

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
  io.redirect.valid := canCommit && (headEntry.mispred || headEntry.exception)
  io.redirect.bits.target := Mux(headEntry.exception || headEntry.taken,
    headEntry.target,
    headEntry.pc + 4.U)
  io.redirect.bits.robIdx := head
  io.redirect.bits.cause  := Mux(headEntry.exception, RedirectCause.EXCEPTION,
    Mux(headEntry.mispred, RedirectCause.MISPRED, RedirectCause.NONE))

  // 回滚 rename table：commit 误预测时，把 rd 恢复为 stalePdst
  io.rollback.valid := canCommit && headEntry.mispred && !UopKind.isJump(headEntry.uop) &&
    headEntry.writesReg && headEntry.rd =/= 0.U
  io.rollback.bits.lrd  := headEntry.rd
  io.rollback.bits.pdst := headEntry.stalePdst

  // flush IssueQueue：发生重定向时
  io.flushIssue := io.redirect.valid

  val commitRedirect = io.redirect.valid

  // 推进 head。redirect 在 commit 点发生时，丢弃所有年轻项。
  when(commitRedirect && !io.flushAll) {
    for (e <- entries) {
      e.valid := false.B
      e.done := false.B
    }
    head := tail
  }.elsewhen(canCommit && !io.flushAll) {
    entries(head).valid := false.B
    head  := head + 1.U
  }

  // count 同步更新：入队 +1, commit -1, 二者可同周期发生
  val doEnq = io.enq.valid && canEnq
  val delta = Mux(doEnq, 1.S(2.W), 0.S) - Mux(canCommit, 1.S(2.W), 0.S)
  when(!io.flushAll) {
    when(commitRedirect) {
      count := 0.U
    }.otherwise {
      count := (count.asSInt + delta).asUInt
    }
  }

  // 调试
  io.dbgCount := count
  io.dbgHead  := head
  io.dbgTail  := tail
}
