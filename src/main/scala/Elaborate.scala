import chisel3._
import circt.stage.ChiselStage

object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new Counter,
    args = Array("--target-dir", "build")
  )
}
