import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class CounterTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Counter"

  it should "count up when enabled" in {
    test(new Counter).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      // 初始值为 0
      c.io.out.expect(0.U)
      c.io.en.poke(true.B)

      // 时钟上升沿计数
      c.clock.step(3)
      c.io.out.expect(3.U)

      // 禁用后保持
      c.io.en.poke(false.B)
      c.clock.step(2)
      c.io.out.expect(3.U)
    }
  }
}
