package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import mem._

class MemorySpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "DMem"

  it should "apply byte write masks" in {
    test(new DMem) { d =>
      def write(addr: Int, data: BigInt, mask: Int): Unit = {
        d.io.addr.poke(addr.U)
        d.io.wdata.poke(data.U)
        d.io.wmask.poke(mask.U)
        d.io.wen.poke(true.B)
        d.clock.step()
        d.io.wen.poke(false.B)
      }

      def read(addr: Int): BigInt = {
        d.io.addr.poke(addr.U)
        d.io.wen.poke(false.B)
        d.clock.step()
        d.io.rdata.peek().litValue
      }

      write(0, 0x11223344L, 0xf)
      assert(read(0) == 0x11223344L)

      write(1, 0x0000aa00L, 0x2)
      assert(read(0) == 0x1122aa44L)

      write(2, 0xbbbb0000L, 0xc)
      assert(read(0) == 0xbbbbaa44L)
    }
  }
}
