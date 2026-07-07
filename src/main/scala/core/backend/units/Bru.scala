package core.backend.units

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * BRU：分支解析单元，纯组合。
 *
 * 输入：uop、源操作数 a/b、当前 PC、立即数（分支偏移 / JAL 目标）
 * 输出：
 *   - taken：分支/跳转实际是否发生
 *   - target：实际目标 PC
 *   - mispred：与预测（predTaken/predTarget）是否不符
 *   - ready：该 uop 是否属于 BRU
 *
 * JAL/JALR 始终 taken；JALR 目标 = (rs1 + imm) & ~1，分支比较由 BRU 完成。
 */
class Bru extends Module {
  val io = IO(new Bundle {
    val uop       = Input(Uop())
    val a         = Input(UInt(XLen.W))   // rs1
    val b         = Input(UInt(XLen.W))   // rs2
    val pc        = Input(UInt(PcWidth.W))
    val imm       = Input(SInt(ImmWidth.W))
    val predTaken = Input(Bool())
    val predTarget = Input(UInt(PcWidth.W))

    val taken     = Output(Bool())
    val target    = Output(UInt(PcWidth.W))
    val mispred   = Output(Bool())
    val ready     = Output(Bool())
  })

  val a = io.a
  val b = io.b

  // 条件比较
  val eq  = a === b
  val lt  = a.asSInt < b.asSInt
  val ltu = a < b

  val condTaken = WireDefault(false.B)
  switch(io.uop) {
    is(BEQ)  { condTaken := eq }
    is(BNE)  { condTaken := !eq }
    is(BLT)  { condTaken := lt }
    is(BGE)  { condTaken := !lt }
    is(BLTU) { condTaken := ltu }
    is(BGEU) { condTaken := !ltu }
  }

  // 目标计算
  val branchTarget = (io.pc.asSInt + io.imm).asUInt
  val jalTarget    = (io.pc.asSInt + io.imm).asUInt
  val jalrTarget   = (a.asSInt + io.imm).asUInt & "hFFFFFFFE".U(32.W)

  val taken  = WireDefault(false.B)
  val target = WireDefault(0.U(PcWidth.W))
  io.ready := false.B

  switch(io.uop) {
    is(JAL)  { taken := true.B; target := jalTarget;    io.ready := true.B }
    is(JALR) { taken := true.B; target := jalrTarget;   io.ready := true.B }
    is(BEQ)  { taken := condTaken; target := branchTarget; io.ready := true.B }
    is(BNE)  { taken := condTaken; target := branchTarget; io.ready := true.B }
    is(BLT)  { taken := condTaken; target := branchTarget; io.ready := true.B }
    is(BGE)  { taken := condTaken; target := branchTarget; io.ready := true.B }
    is(BLTU) { taken := condTaken; target := branchTarget; io.ready := true.B }
    is(BGEU) { taken := condTaken; target := branchTarget; io.ready := true.B }
  }

  // 误预测：taken 不符，或 taken 但目标不符
  val takenMismatch  = taken =/= io.predTaken
  val targetMismatch = taken && io.predTaken && (target =/= io.predTarget)
  io.taken   := taken
  io.target  := target
  io.mispred := takenMismatch || targetMismatch
}

object Bru {
  def accepts(u: Uop.Type): Bool = {
    import Uop._
    u === BEQ || u === BNE || u === BLT || u === BGE ||
    u === BLTU || u === BGEU || u === JAL || u === JALR
  }
}
