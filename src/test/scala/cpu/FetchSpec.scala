package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa.Instr
import core.frontend._

class FetchSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Fetch"

  it should "advance PC, stall on backpressure, and apply redirects" in {
    test(new Fetch) { f =>
      f.io.bpQuery.taken.poke(false.B)
      f.io.bpQuery.target.poke(0.U)
      f.io.bpUpdateValid.poke(false.B)
      f.io.redirect.valid.poke(false.B)
      f.io.redirect.bits.target.poke(0.U)
      f.io.redirect.bits.robIdx.poke(0.U)
      f.io.redirect.bits.cause.poke(isa.RedirectCause.NONE)
      f.io.dispatchReady.poke(true.B)
      f.io.imem.inst.poke(Instr.NOP)

      f.io.imem.addr.expect(0.U)
      f.clock.step()
      f.io.imem.addr.expect(4.U)

      f.io.imem.inst.poke("h02a00093".U) // addi x1, x0, 42
      f.clock.step()
      f.io.dispatch.valid.expect(true.B)
      f.io.dispatch.instr.pc.expect(4.U)
      f.io.imem.addr.expect(8.U)

      f.io.dispatchReady.poke(false.B)
      f.clock.step()
      f.io.imem.addr.expect(8.U)

      f.io.dispatchReady.poke(true.B)
      f.io.redirect.valid.poke(true.B)
      f.io.redirect.bits.target.poke(0x80.U)
      f.clock.step()
      f.io.imem.addr.expect(0x80.U)
      f.io.dispatch.valid.expect(false.B)
    }
  }
}
