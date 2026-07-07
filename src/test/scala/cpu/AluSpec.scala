package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa._
import isa.CoreConfig._
import isa.Uop._
import core.backend.units._

class AluSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "ALU"

  it should "add two numbers" in {
    test(new Alu) { a =>
      a.io.uop.poke(ADD)
      a.io.a.poke(10.U)
      a.io.b.poke(32.U)
      a.io.ready.expect(true.B)
      a.io.out.expect(42.U)
    }
  }

  it should "subtract" in {
    test(new Alu) { a =>
      a.io.uop.poke(SUB)
      a.io.a.poke(50.U)
      a.io.b.poke(8.U)
      a.io.out.expect(42.U)
    }
  }

  it should "compare signed (SLT)" in {
    test(new Alu) { a =>
      a.io.uop.poke(SLT)
      // -1 < 1 → 1
      a.io.a.poke("hFFFFFFFF".U)
      a.io.b.poke(1.U)
      a.io.out.expect(1.U)
    }
  }

  it should "shift left logical" in {
    test(new Alu) { a =>
      a.io.uop.poke(SLL)
      a.io.a.poke(1.U)
      a.io.b.poke(4.U)
      a.io.out.expect(16.U)
    }
  }

  it should "shift right arithmetic" in {
    test(new Alu) { a =>
      a.io.uop.poke(SRA)
      a.io.a.poke("h80000000".U)
      a.io.b.poke(4.U)
      a.io.out.expect("hf8000000".U)
    }
  }

  it should "XOR" in {
    test(new Alu) { a =>
      a.io.uop.poke(XOR)
      a.io.a.poke("hFF00FF00".U)
      a.io.b.poke("h0F0F0F0F".U)
      a.io.out.expect("hF00FF00F".U)
    }
  }

  it should "LUI passes immediate" in {
    test(new Alu) { a =>
      a.io.uop.poke(LUI)
      a.io.b.poke("hABCD0000".U)
      a.io.out.expect("hABCD0000".U)
    }
  }

  it should "not accept branch uop" in {
    test(new Alu) { a =>
      a.io.uop.poke(BEQ)
      a.io.ready.expect(false.B)
    }
  }
}
