package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa._
import isa.Uop._
import core.backend.units._

class CsrFileSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "CsrFile"

  it should "read and write machine CSRs" in {
    test(new CsrFile) { c =>
      c.io.cmd.valid.poke(true.B)
      c.io.cmd.bits.uop.poke(CSRRW)
      c.io.cmd.bits.pc.poke(0.U)
      c.io.cmd.bits.addr.poke("h305".U) // mtvec
      c.io.cmd.bits.src.poke(0x80.U)
      c.io.result.expect(0.U)
      c.clock.step()

      c.io.cmd.bits.uop.poke(CSRRS)
      c.io.cmd.bits.addr.poke("h305".U)
      c.io.cmd.bits.src.poke(0.U)
      c.io.result.expect(0x80.U)
      c.clock.step()

      c.io.cmd.bits.uop.poke(CSRRSI)
      c.io.cmd.bits.addr.poke("h300".U) // mstatus
      c.io.cmd.bits.src.poke(0x8.U)
      c.io.result.expect(0.U)
      c.clock.step()

      c.io.cmd.bits.uop.poke(CSRRCI)
      c.io.cmd.bits.addr.poke("h300".U)
      c.io.cmd.bits.src.poke(0x8.U)
      c.io.result.expect(0x8.U)
    }
  }

  it should "redirect ECALL to mtvec and MRET to mepc" in {
    test(new CsrFile) { c =>
      c.io.cmd.valid.poke(true.B)
      c.io.cmd.bits.uop.poke(CSRRW)
      c.io.cmd.bits.pc.poke(0.U)
      c.io.cmd.bits.addr.poke("h305".U)
      c.io.cmd.bits.src.poke(0x100.U)
      c.clock.step()

      c.io.cmd.bits.uop.poke(ECALL)
      c.io.cmd.bits.pc.poke(0x40.U)
      c.io.cmd.bits.addr.poke(0.U)
      c.io.cmd.bits.src.poke(0.U)
      c.io.redirect.expect(true.B)
      c.io.target.expect(0x100.U)
      c.io.cause.expect(RedirectCause.EXCEPTION)
      c.clock.step()

      c.io.cmd.bits.uop.poke(MRET)
      c.io.redirect.expect(true.B)
      c.io.target.expect(0x40.U)
      c.io.cause.expect(RedirectCause.FLUSH)
    }
  }
}
