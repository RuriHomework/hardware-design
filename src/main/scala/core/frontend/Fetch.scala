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

  // ===== 译码 =====
  val decoder = Module(new Decoder)
  decoder.io.inst := instReg
  decoder.io.pc   := pcReg

  // 把预测信息塞回 DecodedInstr
  val decoded = WireDefault(decoder.io.out)
  decoded.predTaken  := predTakenReg
  decoded.predTarget := predTargetReg

  // ===== 派发 =====
  // dispatch.valid：当前 instReg 是否为有效指令（寄存器值，无组合环）
  val dispatchValid = instReg =/= Instr.NOP
  io.dispatch.instr := decoded
  io.dispatch.valid := dispatchValid

  // 流水推进：后端没准备好且本拍有有效指令要派发时 stall
  val stall = dispatchValid && !io.dispatchReady

  when(!stall) {
    pc := nextPc
    instReg := io.imem.inst
    pcReg   := pc
    predTakenReg  := bpTaken
    predTargetReg := bpTarget
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
  io.dispatch.valid := !stall && instReg =/= Instr.NOP

  // ===== BP 更新（来自 commit 的 retire） =====
  // io.bpUpdate 由 Core 直接传给 BP，Fetch 不再写

  // ===== RAS 控制（投机） =====
  // call: JAL/JALR 且目标指向函数入口（简化：JAL + rd=ra 即 x1，或 rd=x5）
  // ret: JALR 且 rs1=x1/x5
  val isCall = decoded.uop === JAL && (decoded.rd === 1.U || decoded.rd === 5.U)
  val isRet  = decoded.uop === JALR && (decoded.rs1 === 1.U || decoded.rs1 === 5.U)
  io.bpRasPush := isCall && dispatchValid && !stall
  io.bpRasPop  := isRet  && dispatchValid && !stall
  io.bpRasData := pcReg + 4.U
  io.bpQueryIsCall := isCall
  io.bpQueryIsRet  := isRet
  io.bpQueryTarget := Mux(decoded.uop === JAL,
    (pcReg.asSInt + decoded.imm).asUInt, 0.U)
}
