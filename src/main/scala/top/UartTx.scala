package top

import chisel3._
import chisel3.util._

class UartTx(clockHz: Int, baud: Int) extends Module {
  private val clocksPerBit = math.max(1, (clockHz + baud / 2) / baud)
  private val divWidth = math.max(1, log2Ceil(clocksPerBit))

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(8.W)))
    val tx = Output(Bool())
    val busy = Output(Bool())
  })

  val shreg = RegInit("h3ff".U(10.W))
  val bitsLeft = RegInit(0.U(4.W))
  val bitTimer = RegInit(0.U(divWidth.W))

  io.in.ready := bitsLeft === 0.U
  io.tx := Mux(bitsLeft === 0.U, true.B, shreg(0))
  io.busy := bitsLeft =/= 0.U

  when(bitsLeft =/= 0.U) {
    when(bitTimer === 0.U) {
      shreg := Cat(1.U(1.W), shreg(9, 1))
      bitsLeft := bitsLeft - 1.U
      bitTimer := (clocksPerBit - 1).U
    }.otherwise {
      bitTimer := bitTimer - 1.U
    }
  }.elsewhen(io.in.fire) {
    shreg := Cat(1.U(1.W), io.in.bits, 0.U(1.W))
    bitsLeft := 10.U
    bitTimer := (clocksPerBit - 1).U
  }
}
