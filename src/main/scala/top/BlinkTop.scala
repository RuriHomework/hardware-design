package top

import chisel3._

class BlinkTop extends RawModule {
  val clock = IO(Input(Clock()))
  val greenLED = IO(Output(Bool()))
  val redLED = IO(Output(Bool()))

  withClockAndReset(clock, false.B) {
    val counter = RegInit(0.U(26.W))
    counter := counter + 1.U

    greenLED := counter(24)
    redLED := counter(25)
  }
}
