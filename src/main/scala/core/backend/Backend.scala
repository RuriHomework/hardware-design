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
  })

  // ===== 实例化部件 =====
  val prf   = Module(new PhysRegFile)
  val ready = Module(new PhysRegReady)
  val free  = Module(new FreeList)
  val rmt   = Module(new RenameTable)
  val issue = Module(new IssueQueue)
  val rob   = Module(new Rob)

  val alu = Module(new Alu)
  val bru = Module(new Bru)
  val lsu = Module(new Lsu)
  val mdu = Module(new MulDiv)

  // ===== Dispatch: rename + 分配 =====
  val dispatchValid = io.dispatch.valid
  val instr = io.dispatch.instr

  // rename 查询
  rmt.io.rs1 := instr.rs1
  rmt.io.rs2 := instr.rs2
  rmt.io.usesRs1 := instr.usesRs1
  rmt.io.usesRs2 := instr.usesRs2
  rmt.io.rd := instr.rd
  rmt.io.writesReg := instr.writesReg
  rmt.io.newPdst := free.io.allocPdst
  rmt.io.update := dispatchValid && instr.writesReg && free.io.allocAvail && rob.io.enqReady
  rmt.io.rollback := rob.io.rollback

  // FreeList 分配
  free.io.allocReq := dispatchValid && instr.writesReg && rob.io.enqReady
  free.io.freeReq  := rob.io.freeReq
  free.io.freePdst := rob.io.freePdst

  // ROB 入队
  rob.io.enq.valid := dispatchValid && free.io.allocAvail && rob.io.enqReady
  rob.io.enq.bits.uop        := instr.uop
  rob.io.enq.bits.pc         := instr.pc
  rob.io.enq.bits.rd         := instr.rd
  rob.io.enq.bits.pdst       := free.io.allocPdst
  rob.io.enq.bits.stalePdst  := rmt.io.stalePdst
  rob.io.enq.bits.writesReg  := instr.writesReg
  rob.io.enq.bits.predTaken  := instr.predTaken
  rob.io.enq.bits.predTarget := instr.predTarget

  // IssueQueue 入队
  issue.io.enq.valid := rob.io.enq.valid  // 同步：rob 能收，issue 也能收
  issue.io.enq.bits.uop        := instr.uop
  issue.io.enq.bits.pc         := instr.pc
  issue.io.enq.bits.rd         := instr.rd
  issue.io.enq.bits.pdst       := free.io.allocPdst
  issue.io.enq.bits.prs1       := rmt.io.rs1Pdst
  issue.io.enq.bits.prs2       := rmt.io.rs2Pdst
  issue.io.enq.bits.rs1Ready   := rmt.io.rs1Ready
  issue.io.enq.bits.rs2Ready   := rmt.io.rs2Ready
  issue.io.enq.bits.imm        := instr.imm
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

  // ===== CDB：执行单元结果广播 =====
  // 单发射下，4 个执行单元结果做优先级仲裁，选一个上 CDB
  val cdb = Wire(Valid(new CdbEntry))
  val aluDone = WireDefault(false.B)
  val bruDone = WireDefault(false.B)
  val lsuDone = WireDefault(false.B)
  val mduDone = WireDefault(false.B)

  // 仲裁：ALU > BRU > LSU > MulDiv
  cdb.valid := false.B
  cdb.bits := 0.U.asTypeOf(new CdbEntry)
  when(aluDone) {
    cdb.valid := true.B
    cdb.bits.pdst := issue.io.deq.bits.pdst
    cdb.bits.data := alu.io.out
    cdb.bits.robIdx := issue.io.deq.bits.robIdx
  }.elsewhen(bruDone) {
    cdb.valid := true.B
    cdb.bits.pdst := issue.io.deq.bits.pdst
    cdb.bits.data := 0.U  // BRU 不写寄存器（JAL 的 rd 由 ALU 写）
    cdb.bits.robIdx := issue.io.deq.bits.robIdx
  }.elsewhen(lsuDone) {
    cdb.valid := true.B
    cdb.bits.pdst := issue.io.deq.bits.pdst
    cdb.bits.data := lsu.io.result
    cdb.bits.robIdx := issue.io.deq.bits.robIdx
  }.elsewhen(mduDone) {
    cdb.valid := true.B
    cdb.bits.pdst := issue.io.deq.bits.pdst
    cdb.bits.data := mdu.io.result
    cdb.bits.robIdx := issue.io.deq.bits.robIdx
  }

  // IssueQueue 监听 CDB
  issue.io.cdb := cdb

  // PRF 写回
  prf.io.wen   := cdb.valid && cdb.bits.pdst =/= 0.U
  prf.io.waddr := cdb.bits.pdst
  prf.io.wdata := cdb.bits.data

  // ready array：写回时置位（暂未完整使用）
  ready.io.query := 0.U
  ready.io.setReady.valid := cdb.valid
  ready.io.setReady.bits  := cdb.bits.pdst
  ready.io.clearReady.valid := issue.io.enq.valid
  ready.io.clearReady.bits  := free.io.allocPdst

  // ===== 执行单元派发 =====
  val deq = issue.io.deq
  // 默认不派发
  aluDone := false.B
  bruDone := false.B
  lsuDone := false.B
  mduDone := false.B

  alu.io.uop := deq.bits.uop
  alu.io.a   := deq.bits.a
  alu.io.b   := Mux(deq.bits.uop === LUI, deq.bits.imm.asUInt,
              Mux(deq.bits.uop === AUIPC, deq.bits.imm.asUInt,
              Mux(deq.bits.uop === ADD || deq.bits.uop === SLT || deq.bits.uop === SLTU ||
                  deq.bits.uop === XOR || deq.bits.uop === OR  || deq.bits.uop === AND ||
                  deq.bits.uop === SLL || deq.bits.uop === SRL || deq.bits.uop === SRA,
                  deq.bits.imm.asUInt, deq.bits.b)))
  // 注意：R 型指令 b = rs2；I 型指令 b = imm。Decoder 已标 usesRs2，
  // IssueQueue 读 PRF 时 usesRs2=false 则 b=0，这里需要补 imm
  // 简化：对 I 型算术，b 取 imm
  val isITypeArith = deq.bits.uop === ADD || deq.bits.uop === SLT || deq.bits.uop === SLTU ||
                     deq.bits.uop === XOR || deq.bits.uop === OR  || deq.bits.uop === AND ||
                     deq.bits.uop === SLL || deq.bits.uop === SRL || deq.bits.uop === SRA
  when(isITypeArith && !deq.bits.uop.isOneOf(SLL, SRL, SRA)) {
    alu.io.b := deq.bits.imm.asUInt
  }

  bru.io.uop       := deq.bits.uop
  bru.io.a         := deq.bits.a
  bru.io.b         := deq.bits.b
  bru.io.pc        := deq.bits.pc
  bru.io.imm       := deq.bits.imm
  bru.io.predTaken := deq.bits.predTaken
  bru.io.predTarget := deq.bits.predTarget

  lsu.io.cmd.valid := deq.valid && Lsu.accepts(deq.bits.uop)
  lsu.io.cmd.bits.uop := deq.bits.uop
  lsu.io.cmd.bits.a   := deq.bits.a
  lsu.io.cmd.bits.b   := deq.bits.b
  lsu.io.cmd.bits.imm := deq.bits.imm
  lsu.io.dmem.rdata := io.dmem.rdata
  io.dmem.addr  := lsu.io.dmem.addr
  io.dmem.wdata := lsu.io.dmem.wdata
  io.dmem.wmask := lsu.io.dmem.wmask
  io.dmem.wen   := lsu.io.dmem.wen

  mdu.io.cmd.valid := deq.valid && MulDiv.accepts(deq.bits.uop)
  mdu.io.cmd.bits.uop := deq.bits.uop
  mdu.io.cmd.bits.a   := deq.bits.a
  mdu.io.cmd.bits.b   := deq.bits.b

  // 派发逻辑：根据 uop 路由到对应单元
  when(deq.valid) {
    when(Alu.accepts(deq.bits.uop)) {
      aluDone := true.B
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
      lsuDone := lsu.io.done
    }.elsewhen(MulDiv.accepts(deq.bits.uop)) {
      mduDone := mdu.io.done
    }
  }

  // ===== ROB 写回 =====
  // CDB valid 即写回 ROB
  rob.io.wb.valid := cdb.valid
  rob.io.wb.bits.robIdx := cdb.bits.robIdx
  rob.io.wb.bits.pdst   := cdb.bits.pdst
  rob.io.wb.bits.data   := cdb.bits.data
  // 分支误预测信息
  rob.io.wb.bits.cause := Mux(bru.io.mispred && bruDone,
    RedirectCause.MISPRED, RedirectCause.NONE)
  // 把分支实际结果写进 ROB entry（用于 commit 时 retire BP）
  // 简化：在 wb 时同时更新 taken/target
  // 这需要在 ROB entry 里记录 taken/target，上面 Rob.io.wb 没带这些字段
  // → 留作下一步：扩展 WritebackBundle 带 taken/target

  // ===== IssueQueue flush =====
  issue.io.flush.valid := rob.io.flushIssue
  issue.io.flush.bits  := rob.io.redirect.bits.robIdx

  // ===== 对外接口 =====
  io.redirect := rob.io.redirect

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
  io.dispatchReady := rob.io.enqReady && issue.io.enqReady &&
    (!instr.writesReg || free.io.allocAvail)
}
