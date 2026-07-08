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
 * 发射策略：最老就绪指令优先（oldest-ready-first），简化为扫描第一个就绪项。
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
      val robIdx = UInt(RobIdWidth.W)
    })
    val deqReady = Input(Bool())

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

    // 调试
    val dbgCount = Output(UInt((log2Ceil(IssueEntries) + 1).W))
    val dbgHead  = Output(UInt(log2Ceil(IssueEntries).W))
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
    val robIdx     = UInt(RobIdWidth.W)
    val age         = UInt(32.W)
  }

  val entries = RegInit(VecInit(Seq.fill(IssueEntries)(0.U.asTypeOf(new QEntry))))
  val count = RegInit(0.U((log2Ceil(IssueEntries) + 1).W))
  val nextAge = RegInit(0.U(32.W))

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
    count := 0.U
  }

  // 找最老的就绪项，避免低下标空洞让年轻 store 越过更老 store。
  val readyMask = entries.map(e => e.valid && e.rs1Ready && e.rs2Ready)
  val hasReady = count > 0.U && readyMask.reduce(_ || _)
  val oldestReadyMask = (0 until IssueEntries).map { i =>
    val olderReady = (0 until IssueEntries).map { j =>
      readyMask(j) && entries(j).age < entries(i).age
    }.reduce(_ || _)
    readyMask(i) && !olderReady
  }
  val readyIdx = PriorityEncoder(oldestReadyMask)
  val selectedIsMem = hasReady &&
    (UopKind.isMem(entries(readyIdx).uop) || entries(readyIdx).uop === FENCE)
  val olderMemPending = hasReady && entries.zipWithIndex.map { case (e, i) =>
    e.valid && i.U =/= readyIdx &&
      (UopKind.isMem(e.uop) || e.uop === FENCE) &&
      e.age < entries(readyIdx).age
  }.reduce(_ || _)
  val memoryOrderBlocked = selectedIsMem && olderMemPending

  // PRF 读口：连到就绪项
  io.prf.rs1 := Mux(hasReady, entries(readyIdx).prs1, 0.U)
  io.prf.rs2 := Mux(hasReady, entries(readyIdx).prs2, 0.U)

  // 发射
  io.deq.valid := hasReady && count > 0.U && !io.flush.valid && !memoryOrderBlocked
  when(hasReady && !io.flush.valid) {
    val e = entries(readyIdx)
    io.deq.bits.uop        := e.uop
    io.deq.bits.pc         := e.pc
    io.deq.bits.rd         := e.rd
    io.deq.bits.pdst       := e.pdst
    // 操作数：从 PRF 读，rs1/rs2 ready 时数据有效
    io.deq.bits.a := Mux(e.usesRs1, io.prf.rs1Data, 0.U)
    io.deq.bits.b := Mux(e.usesRs2, io.prf.rs2Data, 0.U)
    io.deq.bits.imm        := e.imm
    io.deq.bits.zimm       := e.zimm
    io.deq.bits.usesRs2    := e.usesRs2
    io.deq.bits.predTaken  := e.predTaken
    io.deq.bits.predTarget := e.predTarget
    io.deq.bits.robIdx     := e.robIdx
  }.otherwise {
    io.deq.bits := 0.U.asTypeOf(io.deq.bits)
  }

  // 入队
  val freeMask = entries.map(e => !e.valid)
  val hasFree = freeMask.reduce(_ || _)
  val enqIdx = PriorityEncoder(freeMask)
  val canEnq = count < IssueEntries.U && hasFree
  io.enqReady := canEnq && !io.flush.valid

  // enq 时如果 CDB 同周期广播且匹配，直接置 ready
  val enqRs1Wakeup = io.enq.bits.usesRs1 && io.cdb.valid &&
                     io.enq.bits.prs1 === io.cdb.bits.pdst
  val enqRs2Wakeup = io.enq.bits.usesRs2 && io.cdb.valid &&
                     io.enq.bits.prs2 === io.cdb.bits.pdst

  when(io.enq.valid && canEnq && !io.flush.valid) {
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
    entries(idx).robIdx     := io.enq.bits.robIdx
    entries(idx).age        := nextAge
    nextAge := nextAge + 1.U
  }

  // 出队后清空
  val deqFire = io.deq.valid && io.deqReady
  when(deqFire) {
    entries(readyIdx).valid := false.B
  }

  // count 同步更新：入队 +1, 出队 -1, flush 清零
  val doEnqIssue = io.enq.valid && canEnq && !io.flush.valid
  val doDeqIssue = deqFire
  when(io.flush.valid) {
    count := 0.U
  }.otherwise {
    val deltaI = Mux(doEnqIssue, 1.S(2.W), 0.S) - Mux(doDeqIssue, 1.S(2.W), 0.S)
    count := (count.asSInt + deltaI).asUInt
  }

  // 调试
  io.dbgCount := count
  io.dbgHead  := enqIdx
}
