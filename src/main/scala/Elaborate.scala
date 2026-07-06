import chisel3._
import circt.stage.ChiselStage

/** 生成 Counter 模块的 SystemVerilog */
object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new Counter,
    args = Array("--target-dir", "build")
  )
}
