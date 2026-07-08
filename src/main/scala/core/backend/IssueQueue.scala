package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * Issue Queue：保存等待操作数就绪的 uop，就绪后发射到执行单元。
 *
 * 结构：8 项，每项记录 uop + 操作数 pdst + ready 位 + robIdx。
 * 监听 CDB：匹配 pdst 则置 ready + 捕获数据。
 *
 * 发射策略：扫描最低下标的就绪项，访存/fence 用 ROB 相对顺序额外保序。
 *
 * 单发射：每周期最多发射 1 条 + 接收 1 条 dispatch。
 */
class IssueQueue extends Module {
  val io = IO(new Bundle {
    // dispatch 入口
    val enq = Input(Valid(new Bundle {
      val uop    = Uop()
      val pc     = UInt(PcWidth.W)
      val rd     = UInt(LogNumLogical.W)
      val pdst   = UInt(LogNumPhys.W)
      val prs1   = UInt(LogNumPhys.W)
      val prs2   = UInt(LogNumPhys.W)
      val rs1Ready = Bool()
      val rs2Ready = Bool()
      val zimm   = UInt(5.W)
      val imm    = SInt(ImmWidth.W)
      val usesRs1 = Bool()
      val usesRs2 = Bool()
      val predTaken = Bool()
      val predTarget = UInt(PcWidth.W)
      val branchMask = UInt(BranchCheckpointEntries.W)
      val robIdx = UInt(RobIdWidth.W)
    }))
    val enqReady = Output(Bool())

    // 发射出口（给执行单元）
    val deq = Valid(new Bundle {
      val uop    = Uop()
      val pc     = UInt(PcWidth.W)
      val rd     = UInt(LogNumLogical.W)
      val pdst   = UInt(LogNumPhys.W)
      val a      = UInt(XLen.W)   // 操作数 a（由 PRF 读）
      val b      = UInt(XLen.W)   // 操作数 b
      val zimm   = UInt(5.W)
      val imm    = SInt(ImmWidth.W)
      val usesRs2 = Bool()        // true=R型 b=rs2; false=I型 b=imm
      val predTaken = Bool()
      val predTarget = UInt(PcWidth.W)
      val branchMask = UInt(BranchCheckpointEntries.W)
      val robIdx = UInt(RobIdWidth.W)
    })
    val deqReady = Input(Bool())
    val robHead = Input(UInt(RobIdWidth.W))

    // PRF 读口
    val prf = new Bundle {
      val rs1 = Output(UInt(LogNumPhys.W))
      val rs2 = Output(UInt(LogNumPhys.W))
      val rs1Data = Input(UInt(XLen.W))
      val rs2Data = Input(UInt(XLen.W))
    }

    // CDB 监听
    val cdb = Input(Valid(new CdbEntry))

    // flush（来自 ROB 的重定向）
    val flush = Input(Valid(UInt(RobIdWidth.W)))  // flush robIdx 之后的所有项
    val clearBranchMask = Input(Valid(UInt(BranchCheckpointEntries.W)))
    val flushBranchMask = Input(Valid(UInt(BranchCheckpointEntries.W)))

    // 调试
    val dbgCount = Output(UInt((log2Ceil(IssueEntries) + 1).W))
    val dbgHead  = Output(UInt(log2Ceil(IssueEntries).W))
    val dbgHasReady = Output(Bool())
    val dbgMemoryOrderBlocked = Output(Bool())
  })

  // 队列项定义
  class QEntry extends Bundle {
    val valid      = Bool()
    val uop        = Uop()
    val pc         = UInt(PcWidth.W)
    val rd         = UInt(LogNumLogical.W)
    val pdst       = UInt(LogNumPhys.W)
    val prs1       = UInt(LogNumPhys.W)
    val prs2       = UInt(LogNumPhys.W)
    val rs1Ready   = Bool()
    val rs2Ready   = Bool()
    val imm        = SInt(ImmWidth.W)
    val zimm       = UInt(5.W)
    val usesRs1    = Bool()
    val usesRs2    = Bool()
    val predTaken  = Bool()
    val predTarget = UInt(PcWidth.W)
    val branchMask = UInt(BranchCheckpointEntries.W)
    val robIdx     = UInt(RobIdWidth.W)
  }

  val entries = RegInit(VecInit(Seq.fill(IssueEntries)(0.U.asTypeOf(new QEntry))))
  val deqValidReg = RegInit(false.B)
  val deqBitsReg = RegInit(0.U.asTypeOf(io.deq.bits))

  // CDB 监听：更新所有项的 ready 位
  for (e <- entries) {
    when(e.valid && io.cdb.valid) {
      when(e.usesRs1 && e.prs1 === io.cdb.bits.pdst) {
        e.rs1Ready := true.B
      }
      when(e.usesRs2 && e.prs2 === io.cdb.bits.pdst) {
        e.rs2Ready := true.B
      }
    }
  }

  // flush：robIdx 匹配或之后的项失效
  // 简化：flush 时全部清空（单发射下足够）
  when(io.flush.valid) {
    for (e <- entries) { e.valid := false.B }
  }.elsewhen(io.flushBranchMask.valid) {
    for (e <- entries) {
      when((e.branchMask & io.flushBranchMask.bits).orR) {
        e.valid := false.B
      }
    }
  }.elsewhen(io.clearBranchMask.valid) {
    for (e <- entries) {
      e.branchMask := e.branchMask & ~io.clearBranchMask.bits
    }
  }

  // FPGA-friendly selection: pick the lowest-index ready entry. Memory ordering
  // is preserved separately with ROB-relative age, avoiding the old all-pairs
  // 32-bit age comparator network on the critical path.
  val readyMask = entries.map(e => e.valid && e.rs1Ready && e.rs2Ready)
  val hasReady = readyMask.reduce(_ || _)
  val readyIdx = PriorityEncoder(readyMask)
  val selectedIsMem = hasReady &&
    (UopKind.isMem(entries(readyIdx).uop) || entries(readyIdx).uop === FENCE)
  val selectedRobDist = entries(readyIdx).robIdx - io.robHead
  val olderMemPending = hasReady && entries.zipWithIndex.map { case (e, i) =>
      val entryRobDist = e.robIdx - io.robHead
      e.valid && i.U =/= readyIdx &&
        (UopKind.isMem(e.uop) || e.uop === FENCE) &&
        entryRobDist < selectedRobDist
  }.reduce(_ || _)
  val memoryOrderBlocked = selectedIsMem && olderMemPending

  val canSelect = hasReady && !io.flush.valid &&
    !io.flushBranchMask.valid && !memoryOrderBlocked && !deqValidReg

  // PRF 读口：连到即将进入发射缓冲的就绪项。
  io.prf.rs1 := Mux(canSelect, entries(readyIdx).prs1, 0.U)
  io.prf.rs2 := Mux(canSelect, entries(readyIdx).prs2, 0.U)

  // 发射出口使用一项寄存器缓冲，切断 Issue 选择/PRF 读到执行/写回的长组合路径。
  io.deq.valid := deqValidReg
  io.deq.bits := deqBitsReg

  // 入队
  val freeMask = entries.map(e => !e.valid)
  val hasFree = freeMask.reduce(_ || _)
  val enqIdx = PriorityEncoder(freeMask)
  val canEnq = hasFree
  io.enqReady := canEnq && !io.flush.valid

  // enq 时如果 CDB 同周期广播且匹配，直接置 ready
  val enqRs1Wakeup = io.enq.bits.usesRs1 && io.cdb.valid &&
                     io.enq.bits.prs1 === io.cdb.bits.pdst
  val enqRs2Wakeup = io.enq.bits.usesRs2 && io.cdb.valid &&
                     io.enq.bits.prs2 === io.cdb.bits.pdst

  when(io.enq.valid && canEnq && !io.flush.valid && !io.flushBranchMask.valid) {
    val idx = enqIdx
    entries(idx).valid      := true.B
    entries(idx).uop        := io.enq.bits.uop
    entries(idx).pc         := io.enq.bits.pc
    entries(idx).rd         := io.enq.bits.rd
    entries(idx).pdst       := io.enq.bits.pdst
    entries(idx).prs1       := io.enq.bits.prs1
    entries(idx).prs2       := io.enq.bits.prs2
    entries(idx).rs1Ready   := io.enq.bits.rs1Ready || enqRs1Wakeup
    entries(idx).rs2Ready   := io.enq.bits.rs2Ready || enqRs2Wakeup
    entries(idx).imm        := io.enq.bits.imm
    entries(idx).zimm       := io.enq.bits.zimm
    entries(idx).usesRs1    := io.enq.bits.usesRs1
    entries(idx).usesRs2    := io.enq.bits.usesRs2
    entries(idx).predTaken  := io.enq.bits.predTaken
    entries(idx).predTarget := io.enq.bits.predTarget
    entries(idx).branchMask := io.enq.bits.branchMask
    entries(idx).robIdx     := io.enq.bits.robIdx
  }

  // 把就绪项捕获进发射缓冲，同时从 IssueQueue 删除。
  when(canSelect) {
    val e = entries(readyIdx)
    val rs1Bypass = e.usesRs1 && io.cdb.valid && e.prs1 === io.cdb.bits.pdst
    val rs2Bypass = e.usesRs2 && io.cdb.valid && e.prs2 === io.cdb.bits.pdst
    deqValidReg := true.B
    deqBitsReg.uop        := e.uop
    deqBitsReg.pc         := e.pc
    deqBitsReg.rd         := e.rd
    deqBitsReg.pdst       := e.pdst
    deqBitsReg.a          := Mux(e.usesRs1, Mux(rs1Bypass, io.cdb.bits.data, io.prf.rs1Data), 0.U)
    deqBitsReg.b          := Mux(e.usesRs2, Mux(rs2Bypass, io.cdb.bits.data, io.prf.rs2Data), 0.U)
    deqBitsReg.imm        := e.imm
    deqBitsReg.zimm       := e.zimm
    deqBitsReg.usesRs2    := e.usesRs2
    deqBitsReg.predTaken  := e.predTaken
    deqBitsReg.predTarget := e.predTarget
    deqBitsReg.branchMask := e.branchMask
    deqBitsReg.robIdx     := e.robIdx
    entries(readyIdx).valid := false.B
  }

  val deqFire = io.deq.valid && io.deqReady
  when(deqFire) {
    deqValidReg := false.B
  }
  when(io.flush.valid) {
    deqValidReg := false.B
  }.elsewhen(io.flushBranchMask.valid && (deqBitsReg.branchMask & io.flushBranchMask.bits).orR) {
    deqValidReg := false.B
  }.elsewhen(io.clearBranchMask.valid) {
    deqBitsReg.branchMask := deqBitsReg.branchMask & ~io.clearBranchMask.bits
  }

  // 调试
  io.dbgCount := PopCount(entries.map(_.valid))
  io.dbgHead  := enqIdx
  io.dbgHasReady := hasReady
  io.dbgMemoryOrderBlocked := memoryOrderBlocked
}
