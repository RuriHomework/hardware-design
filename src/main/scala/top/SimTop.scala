package top

import chisel3._

class SimTop extends RawModule {
  val clock = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val uartRx = IO(Input(Bool()))
  val uartTx = IO(Output(Bool()))
  val exitValid = IO(Output(Bool()))
  val exitCode = IO(Output(UInt(32.W)))
  val dbgCommitValid = IO(Output(Bool()))
  val dbgCommitPc = IO(Output(UInt(32.W)))
  val dbgCommitRd = IO(Output(UInt(5.W)))
  val dbgCommitData = IO(Output(UInt(32.W)))
  val dbgCommitWritesReg = IO(Output(Bool()))

  withClockAndReset(clock, reset) {
    val top = Module(new Top)

    top.io.uartRx := uartRx
    uartTx := top.io.uartTx
    exitValid := top.io.exitValid
    exitCode := top.io.exitCode
    dbgCommitValid := top.io.dbgCommitValid
    dbgCommitPc := top.io.dbgCommit.pc
    dbgCommitRd := top.io.dbgCommitRd
    dbgCommitData := top.io.dbgCommitData
    dbgCommitWritesReg := top.io.dbgCommitWritesReg
  }
}
