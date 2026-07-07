package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa._
import isa.Uop._
import core.frontend._

class BranchPredictorSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "BranchPredictor"

  it should "learn taken branch targets from retire updates" in {
    test(new BranchPredictor) { bp =>
      bp.io.query.pc.poke(0x100.U)
      bp.io.queryIsCall.poke(false.B)
      bp.io.queryIsRet.poke(false.B)
      bp.io.queryTarget.poke(0.U)
      bp.io.rasPush.poke(false.B)
      bp.io.rasPop.poke(false.B)
      bp.io.rasData.poke(0.U)
      bp.io.updateValid.poke(false.B)
      bp.clock.step()

      bp.io.updateValid.poke(true.B)
      bp.io.update.pc.poke(0x100.U)
      bp.io.update.uop.poke(BEQ)
      bp.io.update.taken.poke(true.B)
      bp.io.update.target.poke(0x140.U)
      bp.io.update.isCall.poke(false.B)
      bp.io.update.isRet.poke(false.B)
      bp.io.update.mispred.poke(true.B)
      bp.clock.step()
      bp.clock.step()

      bp.io.updateValid.poke(false.B)
      bp.io.query.pc.poke(0x100.U)
      bp.clock.step()

      bp.io.query.taken.expect(true.B)
      bp.io.query.target.expect(0x140.U)
    }
  }

  it should "use the speculative RAS for returns" in {
    test(new BranchPredictor) { bp =>
      bp.io.query.pc.poke(0.U)
      bp.io.queryIsCall.poke(false.B)
      bp.io.queryIsRet.poke(false.B)
      bp.io.queryTarget.poke(0.U)
      bp.io.updateValid.poke(false.B)
      bp.io.rasPush.poke(true.B)
      bp.io.rasPop.poke(false.B)
      bp.io.rasData.poke(0x404.U)
      bp.clock.step()

      bp.io.rasPush.poke(false.B)
      bp.io.queryIsRet.poke(true.B)
      bp.io.query.taken.expect(true.B)
      bp.io.query.target.expect(0x404.U)
    }
  }
}
