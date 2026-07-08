package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._
import core.backend.units._

/**
 * 后端：把 Rename + FreeList + IssueQueue + PRF + 执行单元 + ROB + CDB 连起来。
 *
 * 对前端接口：
 *   - dispatch: 接收 DecodedInstr（已带预测信息）
 *   - redirect: 输出重定向
 *   - retire:   输出提交信息（更新 BP）
 *
 * 对内存接口：
 *   - dmem: LSU 直连 DMem
 *
 * 本模块是"乱序骨架第一版"——单发射、单 ALU/BRU/LSU/MulDiv。
 * 后续可扩展为多发射、多执行单元、多 CDB。
 */
class Backend extends Module {
  val io = IO(new Bundle {
    // 前端 → 后端
    val dispatch = Flipped(new DispatchReq)
    val dispatchReady = Output(Bool())   // 后端能否接收新指令

    // 后端 → 前端
    val redirect = Valid(new Redirect)
    val retire   = Valid(new RetireInfo)

    // DMem 接口
    val dmem = new Bundle {
      val addr  = Output(UInt(PcWidth.W))
      val wdata = Output(UInt(XLen.W))
      val wmask = Output(UInt(4.W))
      val wen   = Output(Bool())
      val rdata = Input(UInt(XLen.W))
    }
    val timerInterrupt = Input(Bool())

    // 调试：commit 时的逻辑寄存器号 + 数据（从 PRF 读 pdst）
    val dbgCommitRd   = Output(UInt(LogNumLogical.W))
    val dbgCommitData = Output(UInt(XLen.W))
    val dbgCommitWritesReg = Output(Bool())
    // 内部状态（调试）
    val dbgRobCount = Output(UInt((log2Ceil(RobEntries) + 1).W))
    val dbgIssueCount = Output(UInt((log2Ceil(IssueEntries) + 1).W))
    val dbgEnqValid = Output(Bool())
    val dbgFreeAvail = Output(Bool())
    val dbgCdbValid = Output(Bool())
    val dbgCdbPdst = Output(UInt(LogNumPhys.W))
    val dbgTimerPending = Output(Bool())
    val dbgInterruptFire = Output(Bool())
    val dbgCommitIsControl = Output(Bool())
    val dbgCommitIsMret = Output(Bool())
    val dbgDispatchBlockedInterrupt = Output(Bool())
    val dbgDispatchBlockedSystem = Output(Bool())
    val dbgDispatchBlockedBranchCheckpoint = Output(Bool())
    val dbgDispatchBlockedStoreBehindBranch = Output(Bool())
    val dbgDispatchBlockedRobFull = Output(Bool())
    val dbgDispatchBlockedIssueFull = Output(Bool())
    val dbgDispatchBlockedFreeList = Output(Bool())
    val dbgIssueDeqValid = Output(Bool())
    val dbgIssueDeqReady = Output(Bool())
    val dbgIssueHasReady = Output(Bool())
    val dbgIssueMemoryOrderBlocked = Output(Bool())
    val dbgIssueUop = Output(Uop())
    val dbgIssueIsLoad = Output(Bool())
    val dbgIssueIsStore = Output(Bool())
    val dbgIssueIsBranch = Output(Bool())
    val dbgIssueIsLsu = Output(Bool())
    val dbgIssueIsMdu = Output(Bool())
    val dbgLsuBusy = Output(Bool())
    val dbgMduBusy = Output(Bool())
    val dbgWbBusy = Output(Bool())
    val dbgStoreBufferFull = Output(Bool())
    val dbgStoreCommitWait = Output(Bool())
    val dbgLoadStoreWait = Output(Bool())
    val dbgStoreForward = Output(Bool())
  })

  // ===== 实例化部件 =====
  val prf   = Module(new PhysRegFile)
  val ready = Module(new PhysRegReady)
  val free  = Module(new FreeList)
  val rmt   = Module(new RenameTable)
  val issue = Module(new IssueQueue)
  val rob   = Module(new Rob)
  val storeBuffer = Module(new StoreBuffer)
  val storeBufferFlush = Wire(Bool())

  val alu = Module(new Alu)
  val bru = Module(new Bru)
  val lsu = Module(new Lsu)
  val mdu = Module(new MulDiv)
  val csr = Module(new CsrFile)
  csr.io.timerInterrupt := io.timerInterrupt

  // ===== Dispatch: rename + 分配 =====
  val dispatchValid = io.dispatch.valid
  val instr = io.dispatch.instr
  val instrWritesReg = instr.writesReg && instr.rd =/= 0.U
  val interruptPending = csr.io.interrupt.pending
  val dispatchAccept = dispatchValid && !interruptPending
  val systemInFlight = RegInit(false.B)
  val branchCheckpointValid = RegInit(false.B)
  val branchCheckpointRmt = Reg(Vec(NumLogicalRegs, UInt(LogNumPhys.W)))
  val branchCheckpointFreeList = Reg(Vec(NumPhysRegs - NumLogicalRegs, UInt(LogNumPhys.W)))
  val branchCheckpointFreeHead = Reg(UInt(log2Ceil(NumPhysRegs - NumLogicalRegs).W))
  val branchCheckpointFreeTail = Reg(UInt(log2Ceil(NumPhysRegs - NumLogicalRegs).W))
  val branchCheckpointFreeCount = Reg(UInt(log2Ceil(NumPhysRegs - NumLogicalRegs + 1).W))
  val dispatchIsBranch = UopKind.isBranch(instr.uop)
  val dispatchIsSystem = UopKind.isSystem(instr.uop)
  val commitIsBranch = UopKind.isBranch(rob.io.commit.bits.uop)
  val commitIsSystem = UopKind.isSystem(rob.io.commit.bits.uop)
  val branchCheckpointBusy = branchCheckpointValid && (dispatchIsBranch || dispatchIsSystem)
  val canDispatch = dispatchAccept && !systemInFlight && !branchCheckpointBusy &&
    rob.io.enqReady && issue.io.enqReady &&
    (!instrWritesReg || free.io.allocAvail)

  // rename 查询
  rmt.io.rs1 := instr.rs1
  rmt.io.rs2 := instr.rs2
  rmt.io.rd := instr.rd
  rmt.io.writesReg := instrWritesReg
  rmt.io.newPdst := free.io.allocPdst
  rmt.io.update := canDispatch && instrWritesReg
  rmt.io.rollback := rob.io.rollback
  val restoreBranchCheckpoint = rob.io.redirect.valid &&
    rob.io.redirect.bits.cause === RedirectCause.MISPRED && branchCheckpointValid
  rmt.io.restore.valid := restoreBranchCheckpoint
  rmt.io.restore.bits := branchCheckpointRmt

  // ready 查询：用 PhysRegReady（以 pdst 为键）
  ready.io.query1 := rmt.io.rs1Pdst
  ready.io.query2 := rmt.io.rs2Pdst
  val rs1ReadyVal = Mux(instr.usesRs1, ready.io.ready1, true.B)
  val rs2ReadyVal = Mux(instr.usesRs2, ready.io.ready2, true.B)

  // FreeList 分配
  free.io.allocReq := canDispatch && instrWritesReg
  free.io.freeReq  := rob.io.freeReq
  free.io.freePdst := rob.io.freePdst
  free.io.restore.valid := restoreBranchCheckpoint
  val restoredFreeList = Wire(Vec(NumPhysRegs - NumLogicalRegs, UInt(LogNumPhys.W)))
  restoredFreeList := branchCheckpointFreeList
  val restoredFreeTailAfterCommit = Mux(branchCheckpointFreeTail === ((NumPhysRegs - NumLogicalRegs) - 1).U,
    0.U, branchCheckpointFreeTail + 1.U)
  when(rob.io.freeReq) {
    restoredFreeList(branchCheckpointFreeTail) := rob.io.freePdst
  }
  free.io.restore.bits.freeList := restoredFreeList
  free.io.restore.bits.head := branchCheckpointFreeHead
  free.io.restore.bits.tail := Mux(rob.io.freeReq, restoredFreeTailAfterCommit, branchCheckpointFreeTail)
  free.io.restore.bits.count := branchCheckpointFreeCount + Mux(rob.io.freeReq, 1.U, 0.U)

  // ROB 入队
  rob.io.enq.valid := canDispatch
  rob.io.enq.bits.uop        := instr.uop
  rob.io.enq.bits.pc         := instr.pc
  rob.io.enq.bits.rd         := instr.rd
  rob.io.enq.bits.pdst       := Mux(instrWritesReg, free.io.allocPdst, 0.U)
  rob.io.enq.bits.stalePdst  := rmt.io.stalePdst
  rob.io.enq.bits.writesReg  := instrWritesReg
  rob.io.enq.bits.predTaken  := instr.predTaken
  rob.io.enq.bits.predTarget := instr.predTarget

  // IssueQueue 入队
  issue.io.enq.valid := rob.io.enq.valid  // 同步：rob 能收，issue 也能收
  issue.io.enq.bits.uop        := instr.uop
  issue.io.enq.bits.pc         := instr.pc
  issue.io.enq.bits.rd         := instr.rd
  issue.io.enq.bits.pdst       := Mux(instrWritesReg, free.io.allocPdst, 0.U)
  issue.io.enq.bits.prs1       := rmt.io.rs1Pdst
  issue.io.enq.bits.prs2       := rmt.io.rs2Pdst
  issue.io.enq.bits.rs1Ready   := rs1ReadyVal
  issue.io.enq.bits.rs2Ready   := rs2ReadyVal
  issue.io.enq.bits.imm        := instr.imm
  issue.io.enq.bits.zimm       := instr.zimm
  issue.io.enq.bits.usesRs1    := instr.usesRs1
  issue.io.enq.bits.usesRs2    := instr.usesRs2
  issue.io.enq.bits.predTaken  := instr.predTaken
  issue.io.enq.bits.predTarget := instr.predTarget
  issue.io.enq.bits.robIdx     := rob.io.enqIdx

  // ===== PRF 读口 =====
  prf.io.rs1 := issue.io.prf.rs1
  prf.io.rs2 := issue.io.prf.rs2
  issue.io.prf.rs1Data := prf.io.rs1Data
  issue.io.prf.rs2Data := prf.io.rs2Data

  // ready array 供 issue 查询用——这里简化：ready 由 rmt 直接给
  // （rmt.rs1Ready 已经综合了 PRF 的 ready 位）

  val deq = issue.io.deq
  val deqIsAlu = deq.valid && Alu.accepts(deq.bits.uop)
  val deqIsLsu = deq.valid && Lsu.accepts(deq.bits.uop)
  val deqIsLoad = deq.valid && UopKind.isLoad(deq.bits.uop)
  val deqIsStore = deq.valid && UopKind.isStore(deq.bits.uop)
  val deqIsFence = deq.valid && deq.bits.uop === FENCE
  val deqIsMdu = deq.valid && MulDiv.accepts(deq.bits.uop)
  val deqMemAddr = deq.bits.a + deq.bits.imm.asUInt
  storeBuffer.io.robHead := rob.io.dbgHead
  storeBuffer.io.commitRobIdx := rob.io.dbgHead
  rob.io.commitStoreReady := storeBuffer.io.commitReady
  val storeCommitFire = rob.io.commit.valid && UopKind.isStore(rob.io.commit.bits.uop)
  val storeCommitWait = rob.io.dbgCount > 0.U && UopKind.isStore(rob.io.commit.bits.uop) &&
    !rob.io.commit.valid && !storeBuffer.io.commitReady
  storeBuffer.io.commitFire := storeCommitFire
  storeBuffer.io.load.valid := deqIsLoad
  storeBuffer.io.load.bits.robIdx := deq.bits.robIdx
  storeBuffer.io.load.bits.addr := deqMemAddr
  val loadStoreWait = deqIsLoad && storeBuffer.io.loadWait
  val storeForward = deqIsLoad && storeBuffer.io.loadForward.valid
  val lsuRespDone = lsu.io.busy
  val mduRespDone = mdu.io.done
  val deferredAluValid = RegInit(false.B)
  val deferredAluCdb = RegInit(0.U.asTypeOf(new CdbEntry))
  val deferredAluWritesReg = RegInit(false.B)
  val canBufferAluDuringLsuResp = lsuRespDone && deqIsAlu && !deferredAluValid && !mduRespDone
  val wbBusy = deferredAluValid || mduRespDone || (lsuRespDone && !canBufferAluDuringLsuResp)
  val canIssueBase = !deferredAluValid && !mduRespDone &&
    (!lsuRespDone || deqIsAlu) &&
    !(deqIsLsu && lsu.io.busy) && !(deqIsMdu && mdu.io.busy) &&
    !(deqIsStore && !storeBuffer.io.enqReady) &&
    !(deqIsLoad && loadStoreWait) &&
    !(deqIsFence && !storeBuffer.io.empty)
  val canIssue = canIssueBase
  val loadIssueNeedsDmem = deqIsLoad && canIssueBase && !storeBuffer.io.loadForward.valid
  val storeDrainFire = storeBuffer.io.drainValid && !loadIssueNeedsDmem && !storeBufferFlush
  storeBuffer.io.drainFire := storeDrainFire
  issue.io.deqReady := canIssue
  val lsuImmediateDone = deq.valid && canIssue &&
    (UopKind.isStore(deq.bits.uop) || deq.bits.uop === FENCE)

  // ===== CDB：执行单元结果广播 =====
  // 单发射下，4 个执行单元结果做优先级仲裁，选一个上 CDB
  val cdb = Wire(Valid(new CdbEntry))
  val aluDone = WireDefault(false.B)
  val bruDone = WireDefault(false.B)
  val lsuDone = WireDefault(false.B)
  val mduDone = WireDefault(false.B)
  val csrDone = WireDefault(false.B)
  val lsuWbRobIdx = RegInit(0.U(RobIdWidth.W))
  val lsuWbPdst = RegInit(0.U(LogNumPhys.W))
  val lsuWbUop = RegInit(Uop.NOP)
  val mduWbRobIdx = RegInit(0.U(RobIdWidth.W))
  val mduWbPdst = RegInit(0.U(LogNumPhys.W))
  val mduWbUop = RegInit(Uop.NOP)

  lsuDone := lsuRespDone || lsuImmediateDone
  mduDone := mdu.io.done

  // 仲裁：ALU > BRU > LSU > MulDiv > CSR
  cdb.valid := false.B
  cdb.bits := 0.U.asTypeOf(new CdbEntry)
  val cdbWritesReg = WireDefault(false.B)
  val wbCause = WireDefault(RedirectCause.NONE)
  val wbTaken = WireDefault(false.B)
  val wbTarget = WireDefault(0.U(PcWidth.W))
  val drainDeferredAlu = deferredAluValid && !lsuDone && !mduDone
  when(aluDone) {
    cdb.valid := true.B
    cdb.bits.pdst := issue.io.deq.bits.pdst
    cdb.bits.data := alu.io.out
    cdb.bits.robIdx := issue.io.deq.bits.robIdx
    cdbWritesReg := UopKind.writesReg(issue.io.deq.bits.uop)
    when(bruDone) {
      wbCause := Mux(bru.io.mispred, RedirectCause.MISPRED, RedirectCause.NONE)
      wbTaken := bru.io.taken
      wbTarget := bru.io.target
    }
  }.elsewhen(bruDone) {
    cdb.valid := true.B
    cdb.bits.pdst := issue.io.deq.bits.pdst
    cdb.bits.data := 0.U  // BRU 不写寄存器（JAL 的 rd 由 ALU 写）
    cdb.bits.robIdx := issue.io.deq.bits.robIdx
    cdbWritesReg := false.B
    wbCause := Mux(bru.io.mispred, RedirectCause.MISPRED, RedirectCause.NONE)
    wbTaken := bru.io.taken
    wbTarget := bru.io.target
  }.elsewhen(lsuDone) {
    cdb.valid := true.B
    cdb.bits.pdst := Mux(lsu.io.busy, lsuWbPdst, issue.io.deq.bits.pdst)
    cdb.bits.data := lsu.io.result
    cdb.bits.robIdx := Mux(lsu.io.busy, lsuWbRobIdx, issue.io.deq.bits.robIdx)
    cdbWritesReg := UopKind.writesReg(Mux(lsu.io.busy, lsuWbUop, issue.io.deq.bits.uop))
  }.elsewhen(mduDone) {
    cdb.valid := true.B
    cdb.bits.pdst := mduWbPdst
    cdb.bits.data := mdu.io.result
    cdb.bits.robIdx := mduWbRobIdx
    cdbWritesReg := UopKind.writesReg(mduWbUop)
  }.elsewhen(csrDone) {
    cdb.valid := true.B
    cdb.bits.pdst := issue.io.deq.bits.pdst
    cdb.bits.data := csr.io.result
    cdb.bits.robIdx := issue.io.deq.bits.robIdx
    cdbWritesReg := UopKind.writesReg(issue.io.deq.bits.uop)
    wbCause := csr.io.cause
    wbTaken := csr.io.redirect
    wbTarget := csr.io.target
  }.elsewhen(drainDeferredAlu) {
    cdb.valid := true.B
    cdb.bits := deferredAluCdb
    cdbWritesReg := deferredAluWritesReg
  }

  // IssueQueue 监听 CDB
  issue.io.cdb := cdb

  // PRF 写回
  prf.io.wen   := cdb.valid && cdbWritesReg && cdb.bits.pdst =/= 0.U
  prf.io.waddr := cdb.bits.pdst
  prf.io.wdata := cdb.bits.data

  // ready array：写回时置位；dispatch 分配新 pdst 时清 ready
  ready.io.setReady.valid := cdb.valid && cdbWritesReg
  ready.io.setReady.bits  := cdb.bits.pdst
  ready.io.clearReady.valid := issue.io.enq.valid && instrWritesReg
  ready.io.clearReady.bits  := free.io.allocPdst

  // ===== 执行单元派发 =====
  // 默认不派发
  aluDone := false.B
  bruDone := false.B

  alu.io.uop := deq.bits.uop
  alu.io.a   := Mux(deq.bits.uop === AUIPC, deq.bits.pc, deq.bits.a)
  // b 选择：LUI/AUIPC → imm；I型算术(usesRs2=false) → imm；R型 → rs2 数据
  alu.io.b   := Mux(deq.bits.uop === LUI || deq.bits.uop === AUIPC || !deq.bits.usesRs2,
                    deq.bits.imm.asUInt, deq.bits.b)

  bru.io.uop       := deq.bits.uop
  bru.io.a         := deq.bits.a
  bru.io.b         := deq.bits.b
  bru.io.pc        := deq.bits.pc
  bru.io.imm       := deq.bits.imm
  bru.io.predTaken := deq.bits.predTaken
  bru.io.predTarget := deq.bits.predTarget

  lsu.io.cmd.valid := deq.valid && canIssue && Lsu.accepts(deq.bits.uop)
  lsu.io.cmd.bits.uop := deq.bits.uop
  lsu.io.cmd.bits.a   := deq.bits.a
  lsu.io.cmd.bits.b   := deq.bits.b
  lsu.io.cmd.bits.imm := deq.bits.imm
  lsu.io.dmem.rdata := io.dmem.rdata
  lsu.io.forward := storeBuffer.io.loadForward
  storeBuffer.io.enq.valid := deqIsStore && canIssue
  storeBuffer.io.enq.bits.robIdx := deq.bits.robIdx
  storeBuffer.io.enq.bits.addr := lsu.io.dmem.addr
  storeBuffer.io.enq.bits.wdata := lsu.io.dmem.wdata
  storeBuffer.io.enq.bits.wmask := lsu.io.dmem.wmask
  io.dmem.addr  := Mux(storeDrainFire, storeBuffer.io.drain.addr, lsu.io.dmem.addr)
  io.dmem.wdata := Mux(storeDrainFire, storeBuffer.io.drain.wdata, lsu.io.dmem.wdata)
  io.dmem.wmask := Mux(storeDrainFire, storeBuffer.io.drain.wmask, lsu.io.dmem.wmask)
  io.dmem.wen   := storeBuffer.io.drain.wen

  mdu.io.cmd.valid := deq.valid && canIssue && MulDiv.accepts(deq.bits.uop)
  mdu.io.cmd.bits.uop := deq.bits.uop
  mdu.io.cmd.bits.a   := deq.bits.a
  mdu.io.cmd.bits.b   := deq.bits.b

  csr.io.cmd.valid := deq.valid && canIssue && CsrFile.accepts(deq.bits.uop)
  csr.io.cmd.bits.uop := deq.bits.uop
  csr.io.cmd.bits.pc := deq.bits.pc
  csr.io.cmd.bits.addr := deq.bits.imm.asUInt(11, 0)
  csr.io.cmd.bits.src := Mux(CsrFile.isImm(deq.bits.uop), deq.bits.zimm, deq.bits.a)
  val backendDrainedForInterrupt = rob.io.dbgCount === 0.U && issue.io.dbgCount === 0.U &&
    !deferredAluValid &&
    !lsu.io.busy && !mdu.io.busy && storeBuffer.io.empty
  val interruptFire = interruptPending && backendDrainedForInterrupt && io.dispatch.valid
  csr.io.interrupt.fire := interruptFire
  csr.io.interrupt.pc := instr.pc

  // 派发逻辑：根据 uop 路由到对应单元
  when(deq.valid && canIssue) {
    when(Alu.accepts(deq.bits.uop)) {
      when(lsuRespDone) {
        deferredAluValid := true.B
        deferredAluCdb.robIdx := deq.bits.robIdx
        deferredAluCdb.pdst := deq.bits.pdst
        deferredAluCdb.data := alu.io.out
        deferredAluCdb.exception := false.B
        deferredAluWritesReg := UopKind.writesReg(deq.bits.uop)
      }.otherwise {
        aluDone := true.B
      }
    }.elsewhen(Bru.accepts(deq.bits.uop)) {
      // JAL/JALR 的 rd 需要写 PC+4，由 ALU 算；这里简化用 BRU 解析重定向
      bruDone := true.B
      // JAL/JALR 写 rd = PC+4：额外让 ALU 也算一下
      when(deq.bits.uop === JAL || deq.bits.uop === JALR) {
        // 让 ALU 算 PC+4 写 rd，BRU 算跳转目标
        aluDone := true.B
        alu.io.uop := ADD
        alu.io.a   := deq.bits.pc
        alu.io.b   := 4.U
      }
    }.elsewhen(Lsu.accepts(deq.bits.uop)) {
      // LSU 多周期，done 由内部给
      lsuWbRobIdx := deq.bits.robIdx
      lsuWbPdst := deq.bits.pdst
      lsuWbUop := deq.bits.uop
    }.elsewhen(MulDiv.accepts(deq.bits.uop)) {
      mduWbRobIdx := deq.bits.robIdx
      mduWbPdst := deq.bits.pdst
      mduWbUop := deq.bits.uop
    }.elsewhen(CsrFile.accepts(deq.bits.uop)) {
      csrDone := true.B
    }
  }
  when(drainDeferredAlu) {
    deferredAluValid := false.B
  }

  val freeDepth = NumPhysRegs - NumLogicalRegs
  val nextFreeSnapshotList = Wire(Vec(freeDepth, UInt(LogNumPhys.W)))
  nextFreeSnapshotList := free.io.checkpoint.freeList
  val freeHeadAfterAlloc = Mux(free.io.checkpoint.head === (freeDepth - 1).U,
    0.U, free.io.checkpoint.head + 1.U)
  val freeTailAfterFree = Mux(free.io.checkpoint.tail === (freeDepth - 1).U,
    0.U, free.io.checkpoint.tail + 1.U)
  val snapshotDoesAlloc = rob.io.enq.valid && instrWritesReg
  val snapshotDoesFree = rob.io.freeReq
  when(snapshotDoesFree) {
    nextFreeSnapshotList(free.io.checkpoint.tail) := rob.io.freePdst
  }
  val nextFreeSnapshotHead = Mux(snapshotDoesAlloc, freeHeadAfterAlloc, free.io.checkpoint.head)
  val nextFreeSnapshotTail = Mux(snapshotDoesFree, freeTailAfterFree, free.io.checkpoint.tail)
  val nextFreeSnapshotCount = free.io.checkpoint.count +
    Mux(snapshotDoesFree, 1.U, 0.U) - Mux(snapshotDoesAlloc, 1.U, 0.U)

  when(interruptFire || restoreBranchCheckpoint) {
    systemInFlight := false.B
    branchCheckpointValid := false.B
  }.otherwise {
    when(rob.io.commit.valid && commitIsSystem) {
      systemInFlight := false.B
    }
    when(rob.io.enq.valid && dispatchIsSystem) {
      systemInFlight := true.B
    }
    when(rob.io.commit.valid && commitIsBranch) {
      branchCheckpointValid := false.B
    }
    when(branchCheckpointValid && rob.io.freeReq && !commitIsBranch) {
      branchCheckpointFreeList(branchCheckpointFreeTail) := rob.io.freePdst
      branchCheckpointFreeTail := Mux(branchCheckpointFreeTail === (freeDepth - 1).U,
        0.U, branchCheckpointFreeTail + 1.U)
      branchCheckpointFreeCount := branchCheckpointFreeCount + 1.U
    }
    when(rob.io.enq.valid && dispatchIsBranch && !branchCheckpointValid) {
      branchCheckpointValid := true.B
      branchCheckpointRmt := rmt.io.checkpoint
      when(instrWritesReg) {
        branchCheckpointRmt(instr.rd) := free.io.allocPdst
      }
      branchCheckpointFreeList := nextFreeSnapshotList
      branchCheckpointFreeHead := nextFreeSnapshotHead
      branchCheckpointFreeTail := nextFreeSnapshotTail
      branchCheckpointFreeCount := nextFreeSnapshotCount
    }
  }

  // ===== ROB 写回 =====
  // CDB valid 即写回 ROB
  rob.io.wb.valid := cdb.valid
  rob.io.wb.bits.robIdx := cdb.bits.robIdx
  rob.io.wb.bits.pdst   := cdb.bits.pdst
  rob.io.wb.bits.data   := cdb.bits.data
  // 分支误预测 + 实际 taken/target（来自 BRU）
  rob.io.wb.bits.cause  := wbCause
  rob.io.wb.bits.taken  := wbTaken
  rob.io.wb.bits.target := wbTarget

  // ===== IssueQueue flush =====
  issue.io.flush.valid := rob.io.flushIssue || interruptFire
  issue.io.flush.bits  := rob.io.redirect.bits.robIdx
  rob.io.flushAll := interruptFire
  storeBufferFlush := rob.io.flushIssue || interruptFire
  storeBuffer.io.flush := storeBufferFlush
  when(rob.io.flushIssue || interruptFire) {
    deferredAluValid := false.B
  }

  // ===== 对外接口 =====
  io.redirect.valid := rob.io.redirect.valid || interruptFire
  io.redirect.bits := rob.io.redirect.bits
  when(interruptFire) {
    io.redirect.bits.target := csr.io.interrupt.target
    io.redirect.bits.robIdx := 0.U
    io.redirect.bits.cause := RedirectCause.EXCEPTION
  }

  io.retire.valid := rob.io.commit.valid
  io.retire.bits.pc      := rob.io.commit.bits.pc
  io.retire.bits.uop     := rob.io.commit.bits.uop
  io.retire.bits.taken   := rob.io.commit.bits.taken
  io.retire.bits.target  := rob.io.commit.bits.target
  io.retire.bits.isCall  := rob.io.commit.bits.isCall
  io.retire.bits.isRet   := rob.io.commit.bits.isRet
  io.retire.bits.mispred := rob.io.commit.bits.mispred

  // ===== dispatchReady =====
  // ROB 有空 + (不写寄存器 或 FreeList 有空) + IssueQueue 有空
  io.dispatchReady := !interruptPending && !systemInFlight && !branchCheckpointBusy &&
    rob.io.enqReady && issue.io.enqReady &&
    (!instrWritesReg || free.io.allocAvail)

  // ===== 调试：commit 时的 rd + data =====
  prf.io.dbgRaddr := rob.io.commit.bits.pdst
  io.dbgCommitRd   := rob.io.commit.bits.rd
  io.dbgCommitData := prf.io.dbgRdata
  io.dbgCommitWritesReg := rob.io.commit.bits.writesReg
  io.dbgRobCount := rob.io.dbgCount
  io.dbgIssueCount := issue.io.dbgCount
  io.dbgEnqValid := rob.io.enq.valid
  io.dbgFreeAvail := free.io.allocAvail
  io.dbgCdbValid := cdb.valid
  io.dbgCdbPdst := cdb.bits.pdst
  io.dbgTimerPending := interruptPending
  io.dbgInterruptFire := interruptFire
  io.dbgCommitIsControl := UopKind.isBranch(rob.io.commit.bits.uop) || UopKind.isJump(rob.io.commit.bits.uop)
  io.dbgCommitIsMret := rob.io.commit.bits.uop === MRET
  io.dbgDispatchBlockedInterrupt := dispatchValid && interruptPending
  io.dbgDispatchBlockedSystem := dispatchValid && !interruptPending && systemInFlight
  io.dbgDispatchBlockedBranchCheckpoint := dispatchValid && !interruptPending && !systemInFlight &&
    branchCheckpointBusy
  io.dbgDispatchBlockedStoreBehindBranch := false.B
  io.dbgDispatchBlockedRobFull := dispatchValid && !interruptPending && !systemInFlight &&
    !branchCheckpointBusy && !rob.io.enqReady
  io.dbgDispatchBlockedIssueFull := dispatchValid && !interruptPending && !systemInFlight &&
    !branchCheckpointBusy && rob.io.enqReady && !issue.io.enqReady
  io.dbgDispatchBlockedFreeList := dispatchValid && !interruptPending && !systemInFlight &&
    !branchCheckpointBusy && rob.io.enqReady && issue.io.enqReady &&
    instrWritesReg && !free.io.allocAvail
  io.dbgIssueDeqValid := deq.valid
  io.dbgIssueDeqReady := issue.io.deqReady
  io.dbgIssueHasReady := issue.io.dbgHasReady
  io.dbgIssueMemoryOrderBlocked := issue.io.dbgMemoryOrderBlocked
  io.dbgIssueUop := deq.bits.uop
  io.dbgIssueIsLoad := deq.valid && UopKind.isLoad(deq.bits.uop)
  io.dbgIssueIsStore := deq.valid && UopKind.isStore(deq.bits.uop)
  io.dbgIssueIsBranch := deq.valid && UopKind.isBranch(deq.bits.uop)
  io.dbgIssueIsLsu := deqIsLsu
  io.dbgIssueIsMdu := deqIsMdu
  io.dbgLsuBusy := lsu.io.busy
  io.dbgMduBusy := mdu.io.busy
  io.dbgWbBusy := wbBusy
  io.dbgStoreBufferFull := storeBuffer.io.dbgFull
  io.dbgStoreCommitWait := storeCommitWait
  io.dbgLoadStoreWait := loadStoreWait
  io.dbgStoreForward := storeForward
}
