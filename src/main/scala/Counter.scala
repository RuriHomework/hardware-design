import chisel3._

/** 最简单的 Chisel 模块：带使能的 8 位计数器 */
class Counter extends Module {
  val io = IO(new Bundle {
    val en  = Input(Bool())
    val out = Output(UInt(8.W))
  })

  val count = RegInit(0.U(8.W))
  when(io.en) {
    count := count + 1.U
  }

  io.out := count
}
