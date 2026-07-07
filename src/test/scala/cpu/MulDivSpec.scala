package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa.Uop._
import core.backend.units._

class MulDivSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "MulDiv"

  private def runOp(m: MulDiv, op: isa.Uop.Type, a: BigInt, b: BigInt): BigInt = {
    m.io.cmd.valid.poke(true.B)
    m.io.cmd.bits.uop.poke(op)
    m.io.cmd.bits.a.poke(a.U)
    m.io.cmd.bits.b.poke(b.U)
    m.clock.step()
    m.io.cmd.valid.poke(false.B)

    var cycles = 0
    while (!m.io.done.peek().litToBoolean && cycles < 40) {
      m.clock.step()
      cycles += 1
    }
    assert(m.io.done.peek().litToBoolean, s"$op did not finish")
    m.io.result.peek().litValue
  }

  it should "compute all multiply variants" in {
    test(new MulDiv) { m =>
      assert(runOp(m, MUL, 0xfffffff0L, 0x10) == 0xffffff00L)
      m.clock.step()
      assert(runOp(m, MULH, 0xffffffffL, 2) == 0xffffffffL)
      m.clock.step()
      assert(runOp(m, MULHU, 0xffffffffL, 2) == 1)
      m.clock.step()
      assert(runOp(m, MULHSU, 0xffffffffL, 2) == 0xffffffffL)
    }
  }

  it should "compute signed and unsigned divide/rem edge cases" in {
    test(new MulDiv) { m =>
      assert(runOp(m, DIV, 0xfffffff6L, 3) == 0xfffffffdL) // -10 / 3 = -3
      m.clock.step()
      assert(runOp(m, REM, 0xfffffff6L, 3) == 0xffffffffL) // -10 % 3 = -1
      m.clock.step()
      assert(runOp(m, DIVU, 10, 3) == 3)
      m.clock.step()
      assert(runOp(m, REMU, 10, 3) == 1)
      m.clock.step()
      assert(runOp(m, DIV, 123, 0) == 0xffffffffL)
      m.clock.step()
      assert(runOp(m, REM, 123, 0) == 123)
    }
  }
}
