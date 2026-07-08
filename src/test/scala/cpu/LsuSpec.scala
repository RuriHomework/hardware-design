package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa.Uop._
import core.backend.units._

class LsuSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "LSU"

  it should "align store data and masks by effective address" in {
    test(new Lsu) { l =>
      l.io.forward.valid.poke(false.B)
      l.io.forward.bits.poke(0.U)
      l.io.cmd.valid.poke(true.B)
      l.io.cmd.bits.uop.poke(SB)
      l.io.cmd.bits.a.poke(0x1000.U)
      l.io.cmd.bits.b.poke(0xaa.U)
      l.io.cmd.bits.imm.poke(3.S)

      l.io.done.expect(true.B)
      l.io.dmem.addr.expect(0x1003.U)
      l.io.dmem.wen.expect(true.B)
      l.io.dmem.wmask.expect("b1000".U)
      l.io.dmem.wdata.expect(0xaa000000L.U)

      l.io.cmd.bits.uop.poke(SH)
      l.io.cmd.bits.b.poke(0xbeef.U)
      l.io.cmd.bits.imm.poke(2.S)
      l.io.dmem.addr.expect(0x1002.U)
      l.io.dmem.wmask.expect("b1100".U)
      l.io.dmem.wdata.expect(0xbeef0000L.U)
    }
  }

  it should "sign and zero extend load data" in {
    test(new Lsu) { l =>
      l.io.forward.valid.poke(false.B)
      l.io.forward.bits.poke(0.U)
      l.io.cmd.valid.poke(true.B)
      l.io.cmd.bits.uop.poke(LB)
      l.io.cmd.bits.a.poke(0x1000.U)
      l.io.cmd.bits.b.poke(0.U)
      l.io.cmd.bits.imm.poke(1.S)
      l.io.dmem.rdata.poke(0x0000807f.U)
      l.clock.step()

      l.io.cmd.valid.poke(false.B)
      l.io.dmem.rdata.poke(0x0000807f.U)
      l.io.done.expect(true.B)
      l.io.result.expect(0xffffff80L.U)

      l.clock.step()
      l.io.cmd.valid.poke(true.B)
      l.io.cmd.bits.uop.poke(LHU)
      l.io.cmd.bits.a.poke(0x1000.U)
      l.io.cmd.bits.imm.poke(2.S)
      l.clock.step()

      l.io.cmd.valid.poke(false.B)
      l.io.dmem.rdata.poke(0x80010000L.U)
      l.io.done.expect(true.B)
      l.io.result.expect(0x00008001.U)
    }
  }

  it should "use forwarded store data for loads" in {
    test(new Lsu) { l =>
      l.io.cmd.valid.poke(true.B)
      l.io.cmd.bits.uop.poke(LB)
      l.io.cmd.bits.a.poke(0x1000.U)
      l.io.cmd.bits.b.poke(0.U)
      l.io.cmd.bits.imm.poke(3.S)
      l.io.forward.valid.poke(true.B)
      l.io.forward.bits.poke(0x7f332211.U)
      l.clock.step()

      l.io.cmd.valid.poke(false.B)
      l.io.forward.valid.poke(false.B)
      l.io.dmem.rdata.poke(0xffffffffL.U)
      l.io.done.expect(true.B)
      l.io.result.expect(0x7f.U)
    }
  }
}
