package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa.Uop._
import core.backend.units._

class BruSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "BRU"

  it should "resolve conditional branches and mispredictions" in {
    test(new Bru) { b =>
      b.io.uop.poke(BEQ)
      b.io.a.poke(5.U)
      b.io.b.poke(5.U)
      b.io.pc.poke(0x100.U)
      b.io.imm.poke(16.S)
      b.io.predTaken.poke(false.B)
      b.io.predTarget.poke(0.U)

      b.io.ready.expect(true.B)
      b.io.taken.expect(true.B)
      b.io.target.expect(0x110.U)
      b.io.mispred.expect(true.B)

      b.io.predTaken.poke(true.B)
      b.io.predTarget.poke(0x110.U)
      b.io.mispred.expect(false.B)
    }
  }

  it should "compute JALR targets with bit zero cleared" in {
    test(new Bru) { b =>
      b.io.uop.poke(JALR)
      b.io.a.poke(0x101.U)
      b.io.b.poke(0.U)
      b.io.pc.poke(0.U)
      b.io.imm.poke(4.S)
      b.io.predTaken.poke(true.B)
      b.io.predTarget.poke(0x104.U)

      b.io.taken.expect(true.B)
      b.io.target.expect(0x104.U)
      b.io.mispred.expect(false.B)
    }
  }
}
