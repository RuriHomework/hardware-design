package core.backend.units

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * ALU：纯组合，单周期。处理整数算术/逻辑/移位。
 *
 * 输入：操作数 a/b（已由 Issue 阶段从 PRF 读出或 bypass）+ uop
 * 输出：结果 + 该 uop 是否属于 ALU 类
 */
class Alu extends Module {
  val io = IO(new Bundle {
    val uop = Input(Uop())
    val a   = Input(UInt(XLen.W))
    val b   = Input(UInt(XLen.W))   // 对 imm 型指令，b 由 Issue 拼好立即数
    val out = Output(UInt(XLen.W))
    val ready = Output(Bool())       // 该 uop 是否可由 ALU 处理
  })

  val a = io.a
  val b = io.b
  val shamt = b(4, 0)               // RV32 移位量取低 5 位

  val result = WireDefault(0.U(XLen.W))
  io.ready := false.B

  switch(io.uop) {
    is(ADD)  { result := a + b;                  io.ready := true.B }
    is(SUB)  { result := a - b;                  io.ready := true.B }
    is(SLT)  { result := (a.asSInt < b.asSInt);  io.ready := true.B }
    is(SLTU) { result := (a < b);                io.ready := true.B }
    is(XOR)  { result := a ^ b;                  io.ready := true.B }
    is(OR)   { result := a | b;                  io.ready := true.B }
    is(AND)  { result := a & b;                  io.ready := true.B }
    is(SLL)  { result := a << shamt;             io.ready := true.B }
    is(SRL)  { result := a >> shamt;             io.ready := true.B }
    is(SRA)  { result := (a.asSInt >> shamt).asUInt; io.ready := true.B }
    is(LUI)  { result := b;                      io.ready := true.B }  // b = imm
    is(AUIPC) { result := io.a + b;              io.ready := true.B }  // a = pc, b = imm
  }

  io.out := result
}

object Alu {
  /** 判定一个 uop 是否走 ALU（供 Issue 路由用） */
  def accepts(u: Uop.Type): Bool = {
    import Uop._
    u === ADD || u === SUB || u === SLT || u === SLTU ||
    u === XOR || u === OR  || u === AND ||
    u === SLL || u === SRL || u === SRA ||
    u === LUI || u === AUIPC
  }
}
