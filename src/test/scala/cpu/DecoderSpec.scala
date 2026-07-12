package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa._
import isa.CoreConfig._
import isa.Uop._
import isa.Instr._

class DecoderSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Decoder"

  it should "decode WFI as a system hint" in {
    test(new Decoder) { c =>
      c.io.inst.poke("h10500073".U)
      c.io.pc.poke(0.U)
      c.io.out.uop.expect(WFI)
      c.io.out.writesReg.expect(false.B)
    }
  }

  it should "decode ADDI x1, x0, 42" in {
    test(new Decoder) { d =>
      // ADDI x1, x0, 42 = 0x02a00093
      d.io.inst.poke("h02a00093".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(ADD)
      d.io.out.rd.expect(1.U)
      d.io.out.rs1.expect(0.U)
      d.io.out.usesRs1.expect(true.B)
      d.io.out.usesRs2.expect(false.B)
      d.io.out.writesReg.expect(true.B)
      d.io.out.imm.expect(42.S)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode SUB x2, x3, x4" in {
    test(new Decoder) { d =>
      // SUB x2, x3, x4 = 0x40418133
      d.io.inst.poke("h40418133".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(SUB)
      d.io.out.rd.expect(2.U)
      d.io.out.rs1.expect(3.U)
      d.io.out.rs2.expect(4.U)
      d.io.out.usesRs1.expect(true.B)
      d.io.out.usesRs2.expect(true.B)
      d.io.out.writesReg.expect(true.B)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode LW x1, 8(x2)" in {
    test(new Decoder) { d =>
      // LW x1, 8(x2) = 0x00812083
      d.io.inst.poke("h00812083".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(LW)
      d.io.out.rd.expect(1.U)
      d.io.out.rs1.expect(2.U)
      d.io.out.usesRs2.expect(false.B)
      d.io.out.writesReg.expect(true.B)
      d.io.out.imm.expect(8.S)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode SW x3, 12(x1)" in {
    test(new Decoder) { d =>
      // SW x3, 12(x1) = 0x0030a623
      d.io.inst.poke("h0030a623".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(SW)
      d.io.out.rs1.expect(1.U)
      d.io.out.rs2.expect(3.U)
      d.io.out.writesReg.expect(false.B)
      d.io.out.imm.expect(12.S)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode BEQ x1, x2, 16" in {
    test(new Decoder) { d =>
      // BEQ x1, x2, 16 = 0x00208663
      d.io.inst.poke("h00208663".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(BEQ)
      d.io.out.rs1.expect(1.U)
      d.io.out.rs2.expect(2.U)
      d.io.out.writesReg.expect(false.B)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode JAL x1, 32" in {
    test(new Decoder) { d =>
      // JAL x1, 32 = 0x020000ef
      d.io.inst.poke("h020000ef".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(JAL)
      d.io.out.rd.expect(1.U)
      d.io.out.writesReg.expect(true.B)
      d.io.out.predTaken.expect(true.B)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode MUL x1, x2, x3" in {
    test(new Decoder) { d =>
      // MUL x1, x2, x3 = 0x023100b3
      d.io.inst.poke("h023100b3".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(MUL)
      d.io.out.rd.expect(1.U)
      d.io.out.rs1.expect(2.U)
      d.io.out.rs2.expect(3.U)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode CSRRS x1, mstatus, x2" in {
    test(new Decoder) { d =>
      // CSRRS x1, mstatus, x2
      d.io.inst.poke("h300120f3".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(CSRRS)
      d.io.out.rd.expect(1.U)
      d.io.out.rs1.expect(2.U)
      d.io.out.usesRs1.expect(true.B)
      d.io.out.writesReg.expect(true.B)
      d.io.out.imm.expect(0x300.S)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode CSR immediate and MRET" in {
    test(new Decoder) { d =>
      // CSRRSI x3, mie, 8
      d.io.inst.poke("h304461f3".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(CSRRSI)
      d.io.out.rd.expect(3.U)
      d.io.out.zimm.expect(8.U)
      d.io.out.usesRs1.expect(false.B)
      d.io.out.imm.expect(0x304.S)
      d.io.illegal.expect(false.B)

      d.io.inst.poke("h30200073".U)
      d.io.out.uop.expect(MRET)
      d.io.illegal.expect(false.B)
    }
  }

  it should "decode LUI x1, 0x10000" in {
    test(new Decoder) { d =>
      // LUI x1, 0x10000 = 0x000100b7
      d.io.inst.poke("h000100b7".U)
      d.io.pc.poke(0.U)
      d.io.out.uop.expect(LUI)
      d.io.out.rd.expect(1.U)
      d.io.out.usesRs1.expect(false.B)
      d.io.out.usesRs2.expect(false.B)
      d.io.out.writesReg.expect(true.B)
      d.io.illegal.expect(false.B)
    }
  }

  it should "flag illegal instruction" in {
    test(new Decoder) { d =>
      // opcode = 0 (非法)
      d.io.inst.poke("h00000000".U)
      d.io.pc.poke(0.U)
      d.io.illegal.expect(true.B)
    }
  }
}
