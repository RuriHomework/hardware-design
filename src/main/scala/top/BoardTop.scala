package top

import chisel3._

/** Minimal EBAZ4205 board wrapper.
  *
  * The CPU Top exposes far more debug pins than this board has usable PL IOs, so
  * keep those signals internal and export only two observable LED pins.
  */
class BoardTop extends RawModule {
  val clock = IO(Input(Clock()))
  val greenLED = IO(Output(Bool()))
  val redLED = IO(Output(Bool()))
  val uartRx = IO(Input(Bool()))
  val uartTx = IO(Output(Bool()))

  withClockAndReset(clock, false.B) {
    val top = Module(new Top)

    top.io.uartRx := uartRx

    greenLED := !top.io.loaderActive && (!top.io.exitValid || top.io.exitCode === 0.U)
    redLED := Mux(top.io.loaderActive, top.io.loaderError,
      top.io.exitValid && top.io.exitCode =/= 0.U)
    uartTx := top.io.uartTx
  }
}
