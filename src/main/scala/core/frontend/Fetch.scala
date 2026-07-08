package core.frontend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._
import isa.Instr._

/**
 * 取指单元：管理 PC、向 IMem 发请求、对接分支预测器、对接重定向。
 *
 * 流水位置：IF 阶段。输出 DecodedInstr 给后端 Dispatch。
 *
 * 单发射简化：每周期取 1 条指令。
 * PC 来源（优先级）：
 *   1. 重定向（来自后端 mispred / exception）
 *   2. 预测 taken 的目标
 *   3. PC + 4
 *
 * 与 IMem 的时序：IMem 同步读，所以 IF 拍发地址，下一拍 ID 拍拿到指令。
 * 本模块只负责 IF：发地址 + 接收指令 + 调用 Decoder。
 */
class Fetch extends Module {
  val io = IO(new Bundle {
    // 分支预测器查询（外部连入）
    val bpQuery = new Bundle {
      val pc     = Output(UInt(PcWidth.W))
      val taken  = Input(Bool())
      val target = Input(UInt(PcWidth.W))
    }
    val bpUpdate = Input(new RetireInfo)
    val bpUpdateValid = Input(Bool())
    val bpRasPush  = Output(Bool())
    val bpRasPop   = Output(Bool())
    val bpRasData  = Output(UInt(PcWidth.W))
    val bpQueryIsCall = Output(Bool())
    val bpQueryIsRet  = Output(Bool())
    val bpQueryTarget = Output(UInt(PcWidth.W))

    // 重定向（后端 → 前端）
    val redirect = Input(Valid(new Redirect))

    // IMem 接口
    val imem = new Bundle {
      val addr = Output(UInt(PcWidth.W))
      val inst = Input(UInt(32.W))
    }

    // 派发请求（前端 → 后端）
    val dispatch = Output(new DispatchReq)
    val dispatchReady = Input(Bool())  // 后端能否接收

    // 调试
    val dbgPc = Output(UInt(PcWidth.W))
    val dbgInstReg = Output(UInt(32.W))
  })

  // ===== PC 寄存器 =====
  val pc = RegInit(0.U(PcWidth.W))

  // ===== 取指地址选择 =====
  val redirValid = io.redirect.valid
  val redirTarget = io.redirect.bits.target

  // 把当前 PC 喂给 BP，拿预测
  io.bpQuery.pc := pc

  val bpTaken  = io.bpQuery.taken
  val bpTarget = io.bpQuery.target

  // 下一 PC
  val nextPc = WireDefault(pc + 4.U)
  when(redirValid) {
    nextPc := redirTarget
  }.elsewhen(bpTaken) {
    nextPc := bpTarget
  }

  // ===== IMem 请求 =====
  io.imem.addr := pc

  // ===== 指令寄存器（IF→ID 流水寄存器） =====
  val instReg = RegInit(Instr.NOP)
  val pcReg   = RegInit(0.U(PcWidth.W))
  val predTakenReg = RegInit(false.B)
  val predTargetReg = RegInit(0.U(PcWidth.W))
  val redirectBubble = RegInit(false.B)
  val ras = RegInit(VecInit(Seq.fill(RasEntries)(0.U(PcWidth.W))))
  val rasPtr = RegInit(0.U(log2Ceil(RasEntries + 1).W))

  // ===== 译码 =====
  val decoder = Module(new Decoder)
  decoder.io.inst := instReg
  decoder.io.pc   := pcReg

  val rawDecoded = decoder.io.out
  val isCall = rawDecoded.uop === JAL && (rawDecoded.rd === 1.U || rawDecoded.rd === 5.U)
  val isRet  = rawDecoded.uop === JALR && (rawDecoded.rs1 === 1.U || rawDecoded.rs1 === 5.U)
  val jalTarget = (pcReg.asSInt + rawDecoded.imm).asUInt
  val rasTopIdx = (rasPtr - 1.U)(log2Ceil(RasEntries) - 1, 0)
  val rasTarget = Mux(rasPtr === 0.U, 0.U, ras(rasTopIdx))
  val rasCanPredict = isRet && rasPtr =/= 0.U
  val decodePredTaken = rawDecoded.uop === JAL || rasCanPredict
  val decodePredTarget = Mux(rawDecoded.uop === JAL, jalTarget, rasTarget)
  val decodeOverride = decodePredTaken &&
    (!predTakenReg || predTargetReg =/= decodePredTarget)

  // 把预测信息塞回 DecodedInstr。JAL/ret 在 decode 拍可得到更准目标，提前修正前端。
  val decoded = WireDefault(decoder.io.out)
  decoded.predTaken  := Mux(decodePredTaken, true.B, predTakenReg)
  decoded.predTarget := Mux(decodePredTaken, decodePredTarget, predTargetReg)

  // ===== 派发 =====
  // dispatch.valid：当前 instReg 是否为有效指令（寄存器值，无组合环）
  val dispatchValid = instReg =/= Instr.NOP
  io.dispatch.instr := decoded
  io.dispatch.valid := dispatchValid

  // 流水推进：后端没准备好且本拍有有效指令要派发时 stall
  val stall = dispatchValid && !io.dispatchReady

  val dispatchFire = dispatchValid && !stall

  when(redirValid) {
    pc := redirTarget
    pcReg := pc
    predTakenReg := false.B
    predTargetReg := 0.U
    instReg := Instr.NOP
    redirectBubble := true.B
  }.elsewhen(dispatchFire && decodeOverride) {
    pc := decodePredTarget
    pcReg := pc
    predTakenReg := false.B
    predTargetReg := 0.U
    instReg := Instr.NOP
    redirectBubble := true.B
  }.elsewhen(redirectBubble) {
    pcReg := pc
    predTakenReg := false.B
    predTargetReg := 0.U
    instReg := Instr.NOP
    redirectBubble := false.B
  }.elsewhen(!stall) {
    pc := nextPc
    pcReg   := pc
    predTakenReg  := bpTaken
    predTargetReg := bpTarget
    instReg := io.imem.inst
  }

  // ===== 静态 JAL/JALR 目标补全 =====
  // Decoder 知道 uop 和 imm，可以算 JAL 目标
  when(decoded.uop === JAL || decoded.uop === JALR) {
    // 若 BP 没预测 taken，补一个静态目标
    when(!predTakenReg) {
      // 不强行改 taken，留给后端 mispred 修正——保持预测不变
      // 但 predTarget 应至少给个合理值
    }
  }

  // ===== 派发 =====
  io.dispatch.instr := decoded
  io.dispatch.valid := dispatchValid

  // ===== BP 更新（来自 commit 的 retire） =====
  // io.bpUpdate 由 Core 直接传给 BP，Fetch 不再写

  // ===== RAS 控制（投机） =====
  when(dispatchFire) {
    when(isCall && rasPtr =/= RasEntries.U) {
      ras(rasPtr(log2Ceil(RasEntries) - 1, 0)) := pcReg + 4.U
      rasPtr := rasPtr + 1.U
    }.elsewhen(isRet && rasPtr =/= 0.U) {
      rasPtr := rasPtr - 1.U
    }
  }

  io.bpRasPush := isCall && dispatchFire
  io.bpRasPop  := isRet  && dispatchFire
  io.bpRasData := pcReg + 4.U
  // BP 查询用的是当前取指 PC；这里的 decoded/pcReg 属于上一拍指令，不能参与同周期预测。
  io.bpQueryIsCall := false.B
  io.bpQueryIsRet  := false.B
  io.bpQueryTarget := 0.U

  // 调试
  io.dbgPc := pc
  io.dbgInstReg := instReg
}
