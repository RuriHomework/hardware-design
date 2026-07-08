package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa._
import isa.Uop._
import core.backend._

class BackendStructuresSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "backend structures"

  private def initRob(r: Rob): Unit = {
    r.io.commitStoreReady.poke(true.B)
    r.io.clearBranchMask.valid.poke(false.B)
    r.io.clearBranchMask.bits.poke(0.U)
    r.io.flushBranchMask.valid.poke(false.B)
    r.io.flushBranchMask.bits.mask.poke(0.U)
    r.io.flushBranchMask.bits.robIdx.poke(0.U)
    r.io.flushAll.poke(false.B)
    r.io.enq.valid.poke(false.B)
    r.io.enq.bits.uop.poke(ADD)
    r.io.enq.bits.pc.poke(0.U)
    r.io.enq.bits.rd.poke(0.U)
    r.io.enq.bits.pdst.poke(0.U)
    r.io.enq.bits.stalePdst.poke(0.U)
    r.io.enq.bits.writesReg.poke(false.B)
    r.io.enq.bits.predTaken.poke(false.B)
    r.io.enq.bits.predTarget.poke(0.U)
    r.io.enq.bits.branchMask.poke(0.U)
    r.io.wb.valid.poke(false.B)
    r.io.wb.bits.robIdx.poke(0.U)
    r.io.wb.bits.pdst.poke(0.U)
    r.io.wb.bits.data.poke(0.U)
    r.io.wb.bits.cause.poke(RedirectCause.NONE)
    r.io.wb.bits.taken.poke(false.B)
    r.io.wb.bits.target.poke(0.U)
    r.io.wb.bits.redirectHandled.poke(false.B)
  }

  private def pokeRobEnq(
      r: Rob,
      valid: Boolean,
      uop: Uop.Type = ADD,
      pc: BigInt = 0,
      rd: Int = 0,
      pdst: Int = 0,
      stalePdst: Int = 0,
      writesReg: Boolean = false,
      predTaken: Boolean = false,
      predTarget: BigInt = 0,
      branchMask: Int = 0
  ): Unit = {
    r.io.enq.valid.poke(valid.B)
    r.io.enq.bits.uop.poke(uop)
    r.io.enq.bits.pc.poke(pc.U)
    r.io.enq.bits.rd.poke(rd.U)
    r.io.enq.bits.pdst.poke(pdst.U)
    r.io.enq.bits.stalePdst.poke(stalePdst.U)
    r.io.enq.bits.writesReg.poke(writesReg.B)
    r.io.enq.bits.predTaken.poke(predTaken.B)
    r.io.enq.bits.predTarget.poke(predTarget.U)
    r.io.enq.bits.branchMask.poke(branchMask.U)
  }

  private def pokeRobWb(
      r: Rob,
      valid: Boolean,
      robIdx: Int = 0,
      pdst: Int = 0,
      cause: RedirectCause.Type = RedirectCause.NONE,
      taken: Boolean = false,
      target: BigInt = 0,
      redirectHandled: Boolean = false
  ): Unit = {
    r.io.wb.valid.poke(valid.B)
    r.io.wb.bits.robIdx.poke(robIdx.U)
    r.io.wb.bits.pdst.poke(pdst.U)
    r.io.wb.bits.data.poke(0.U)
    r.io.wb.bits.cause.poke(cause)
    r.io.wb.bits.taken.poke(taken.B)
    r.io.wb.bits.target.poke(target.U)
    r.io.wb.bits.redirectHandled.poke(redirectHandled.B)
  }

  it should "write and read the physical register file" in {
    test(new PhysRegFile) { p =>
      p.io.rs1.poke(10.U)
      p.io.rs2.poke(0.U)
      p.io.dbgRaddr.poke(10.U)
      p.io.wen.poke(true.B)
      p.io.waddr.poke(10.U)
      p.io.wdata.poke(0x12345678L.U)
      p.clock.step()

      p.io.wen.poke(false.B)
      p.io.rs1Data.expect(0x12345678L.U)
      p.io.dbgRdata.expect(0x12345678L.U)
    }
  }

  it should "rename registers and roll mappings back" in {
    test(new RenameTable) { r =>
      r.io.rs1.poke(5.U)
      r.io.rs2.poke(0.U)
      r.io.rd.poke(5.U)
      r.io.writesReg.poke(true.B)
      r.io.newPdst.poke(40.U)
      r.io.update.poke(true.B)
      r.io.rollback.valid.poke(false.B)
      r.io.restore.valid.poke(false.B)
      r.io.stalePdst.expect(5.U)
      r.clock.step()

      r.io.update.poke(false.B)
      r.io.rs1Pdst.expect(40.U)

      r.io.rollback.valid.poke(true.B)
      r.io.rollback.bits.lrd.poke(5.U)
      r.io.rollback.bits.pdst.poke(5.U)
      r.clock.step()
      r.io.rollback.valid.poke(false.B)
      r.io.rs1Pdst.expect(5.U)
    }
  }

  it should "allocate and recycle physical registers" in {
    test(new FreeList) { f =>
      f.io.freeReq.poke(false.B)
      f.io.allocReq.poke(false.B)
      f.io.restore.valid.poke(false.B)
      f.io.allocAvail.expect(true.B)
      f.io.allocPdst.expect(32.U)

      f.io.allocReq.poke(true.B)
      f.clock.step()
      f.io.allocPdst.expect(33.U)

      f.io.allocReq.poke(false.B)
      f.io.freeReq.poke(true.B)
      f.io.freePdst.poke(32.U)
      f.clock.step()

      f.io.freeReq.poke(false.B)
      for (_ <- 0 until 31) {
        f.io.allocReq.poke(true.B)
        f.clock.step()
      }
      f.io.allocPdst.expect(32.U)
    }
  }

  it should "wake operands from CDB and honor deqReady" in {
    test(new IssueQueue) { q =>
      q.io.flush.valid.poke(false.B)
      q.io.clearBranchMask.valid.poke(false.B)
      q.io.flushBranchMask.valid.poke(false.B)
      q.io.deqReady.poke(false.B)
      q.io.cdb.valid.poke(false.B)

      q.io.enq.valid.poke(true.B)
      q.io.enq.bits.uop.poke(ADD)
      q.io.enq.bits.pc.poke(0x100.U)
      q.io.enq.bits.rd.poke(1.U)
      q.io.enq.bits.pdst.poke(40.U)
      q.io.enq.bits.prs1.poke(10.U)
      q.io.enq.bits.prs2.poke(11.U)
      q.io.enq.bits.rs1Ready.poke(false.B)
      q.io.enq.bits.rs2Ready.poke(true.B)
      q.io.enq.bits.imm.poke(0.S)
      q.io.enq.bits.usesRs1.poke(true.B)
      q.io.enq.bits.usesRs2.poke(true.B)
      q.io.enq.bits.predTaken.poke(false.B)
      q.io.enq.bits.predTarget.poke(0.U)
      q.io.enq.bits.branchMask.poke(0.U)
      q.io.enq.bits.robIdx.poke(3.U)
      q.clock.step()

      q.io.enq.valid.poke(false.B)
      q.io.deq.valid.expect(false.B)

      q.io.cdb.valid.poke(true.B)
      q.io.cdb.bits.pdst.poke(10.U)
      q.io.cdb.bits.data.poke(0.U)
      q.io.cdb.bits.robIdx.poke(0.U)
      q.io.cdb.bits.exception.poke(false.B)
      q.clock.step()

      q.io.cdb.valid.poke(false.B)
      q.io.deq.valid.expect(true.B)
      q.io.deq.bits.robIdx.expect(3.U)
      q.io.dbgCount.expect(1.U)

      q.io.deqReady.poke(true.B)
      q.clock.step()
      q.io.dbgCount.expect(0.U)
    }
  }

  it should "commit completed ROB entries and raise redirects" in {
    test(new Rob) { r =>
      initRob(r)
      r.io.enq.valid.poke(true.B)
      r.io.enq.bits.uop.poke(ADD)
      r.io.enq.bits.pc.poke(0x100.U)
      r.io.enq.bits.rd.poke(1.U)
      r.io.enq.bits.pdst.poke(40.U)
      r.io.enq.bits.stalePdst.poke(1.U)
      r.io.enq.bits.writesReg.poke(true.B)
      r.io.enq.bits.predTaken.poke(false.B)
      r.io.enq.bits.predTarget.poke(0.U)
      r.io.enq.bits.branchMask.poke(0.U)
      r.io.wb.valid.poke(false.B)
      r.io.flushAll.poke(false.B)
      r.clock.step()

      r.io.enq.valid.poke(false.B)
      r.io.wb.valid.poke(true.B)
      r.io.wb.bits.robIdx.poke(0.U)
      r.io.wb.bits.pdst.poke(40.U)
      r.io.wb.bits.data.poke(42.U)
      r.io.wb.bits.cause.poke(RedirectCause.NONE)
      r.io.wb.bits.taken.poke(false.B)
      r.io.wb.bits.target.poke(0.U)
      r.io.wb.bits.redirectHandled.poke(false.B)
      r.clock.step()

      r.io.wb.valid.poke(false.B)
      r.io.commit.valid.expect(true.B)
      r.io.commit.bits.rd.expect(1.U)
      r.io.freeReq.expect(true.B)
      r.io.freePdst.expect(1.U)
      r.clock.step()

      r.io.enq.valid.poke(true.B)
      r.io.enq.bits.uop.poke(BEQ)
      r.io.enq.bits.pc.poke(0x200.U)
      r.io.enq.bits.rd.poke(0.U)
      r.io.enq.bits.pdst.poke(0.U)
      r.io.enq.bits.stalePdst.poke(0.U)
      r.io.enq.bits.writesReg.poke(false.B)
      r.io.enq.bits.predTaken.poke(false.B)
      r.io.enq.bits.predTarget.poke(0.U)
      r.io.enq.bits.branchMask.poke(0.U)
      r.clock.step()

      r.io.enq.valid.poke(false.B)
      r.io.wb.valid.poke(true.B)
      r.io.wb.bits.robIdx.poke(1.U)
      r.io.wb.bits.pdst.poke(0.U)
      r.io.wb.bits.data.poke(0.U)
      r.io.wb.bits.cause.poke(RedirectCause.MISPRED)
      r.io.wb.bits.taken.poke(true.B)
      r.io.wb.bits.target.poke(0x240.U)
      r.io.wb.bits.redirectHandled.poke(false.B)
      r.clock.step()

      r.io.wb.valid.poke(false.B)
      r.io.commit.valid.expect(true.B)
      r.io.redirect.valid.expect(true.B)
      r.io.redirect.bits.target.expect(0x240.U)
      r.io.flushIssue.expect(true.B)
    }
  }

  it should "keep ROB occupancy coherent across same-cycle commit and enqueue" in {
    test(new Rob) { r =>
      initRob(r)

      pokeRobEnq(r, valid = true, uop = ADD, pc = 0x100, rd = 1, pdst = 40,
        stalePdst = 1, writesReg = true)
      r.clock.step()

      pokeRobEnq(r, valid = false)
      pokeRobWb(r, valid = true, robIdx = 0, pdst = 40)
      r.clock.step()

      pokeRobWb(r, valid = false)
      r.io.commit.valid.expect(true.B)
      r.io.commit.bits.pc.expect(0x100.U)
      pokeRobEnq(r, valid = true, uop = ADD, pc = 0x104, rd = 2, pdst = 41,
        stalePdst = 2, writesReg = true)
      r.clock.step()

      pokeRobEnq(r, valid = false)
      r.io.dbgCount.expect(1.U)
      r.io.dbgHead.expect(1.U)
      r.io.dbgTail.expect(2.U)
      r.io.commit.valid.expect(false.B)
    }
  }

  it should "flush younger ROB entries while committing an older head" in {
    test(new Rob) { r =>
      initRob(r)

      pokeRobEnq(r, valid = true, uop = ADD, pc = 0x100, rd = 1, pdst = 40,
        stalePdst = 1, writesReg = true)
      r.clock.step()
      pokeRobEnq(r, valid = true, uop = BEQ, pc = 0x104)
      r.clock.step()
      pokeRobEnq(r, valid = true, uop = ADD, pc = 0x108, rd = 2, pdst = 41,
        stalePdst = 2, writesReg = true, branchMask = 1)
      r.clock.step()
      pokeRobEnq(r, valid = true, uop = ADD, pc = 0x10c, rd = 3, pdst = 42,
        stalePdst = 3, writesReg = true, branchMask = 1)
      r.clock.step()

      pokeRobEnq(r, valid = false)
      pokeRobWb(r, valid = true, robIdx = 0, pdst = 40)
      r.clock.step()

      r.io.commit.valid.expect(true.B)
      r.io.commit.bits.pc.expect(0x100.U)
      pokeRobWb(r, valid = true, robIdx = 1, cause = RedirectCause.MISPRED,
        taken = true, target = 0x200, redirectHandled = true)
      r.io.flushBranchMask.valid.poke(true.B)
      r.io.flushBranchMask.bits.mask.poke(1.U)
      r.io.flushBranchMask.bits.robIdx.poke(1.U)
      r.clock.step()

      r.io.flushBranchMask.valid.poke(false.B)
      pokeRobWb(r, valid = false)
      r.io.dbgCount.expect(1.U)
      r.io.dbgHead.expect(1.U)
      r.io.dbgTail.expect(2.U)
      r.io.commit.valid.expect(true.B)
      r.io.commit.bits.pc.expect(0x104.U)
      r.io.redirect.valid.expect(false.B)
      r.clock.step()

      r.io.dbgCount.expect(0.U)
      r.io.empty.expect(true.B)
    }
  }

  it should "ignore writeback to ROB entries killed by branch recovery" in {
    test(new Rob) { r =>
      initRob(r)

      pokeRobEnq(r, valid = true, uop = BEQ, pc = 0x200)
      r.clock.step()
      pokeRobEnq(r, valid = true, uop = ADD, pc = 0x204, rd = 1, pdst = 40,
        stalePdst = 1, writesReg = true, branchMask = 1)
      r.clock.step()

      pokeRobEnq(r, valid = false)
      pokeRobWb(r, valid = true, robIdx = 1, pdst = 40)
      r.io.flushBranchMask.valid.poke(true.B)
      r.io.flushBranchMask.bits.mask.poke(1.U)
      r.io.flushBranchMask.bits.robIdx.poke(0.U)
      r.clock.step()

      r.io.flushBranchMask.valid.poke(false.B)
      pokeRobWb(r, valid = true, robIdx = 0, cause = RedirectCause.MISPRED,
        taken = true, target = 0x300, redirectHandled = true)
      r.clock.step()

      pokeRobWb(r, valid = false)
      r.io.dbgCount.expect(1.U)
      r.io.commit.valid.expect(true.B)
      r.io.commit.bits.pc.expect(0x200.U)
      r.io.redirect.valid.expect(false.B)
      r.clock.step()
      r.io.dbgCount.expect(0.U)
    }
  }

  it should "buffer stores until commit and forward full-word stores" in {
    test(new StoreBuffer) { s =>
      s.io.robHead.poke(0.U)
      s.io.flush.poke(false.B)
      s.io.clearBranchMask.valid.poke(false.B)
      s.io.flushBranchMask.valid.poke(false.B)
      s.io.commitFire.poke(false.B)
      s.io.drainFire.poke(false.B)
      s.io.commitRobIdx.poke(0.U)
      s.io.load.valid.poke(false.B)
      s.io.enq.valid.poke(true.B)
      s.io.enq.bits.robIdx.poke(2.U)
      s.io.enq.bits.addr.poke(0x100.U)
      s.io.enq.bits.wdata.poke(0x12345678.U)
      s.io.enq.bits.wmask.poke("b1111".U)
      s.io.enq.bits.branchMask.poke(0.U)
      s.io.enqReady.expect(true.B)
      s.clock.step()

      s.io.enq.valid.poke(false.B)
      s.io.commitRobIdx.poke(2.U)
      s.io.commitReady.expect(true.B)
      s.io.drainValid.expect(false.B)
      s.io.drain.wen.expect(false.B)

      s.io.load.valid.poke(true.B)
      s.io.load.bits.robIdx.poke(4.U)
      s.io.load.bits.addr.poke(0x100.U)
      s.io.loadWait.expect(false.B)
      s.io.loadForward.valid.expect(true.B)
      s.io.loadForward.bits.expect(0x12345678.U)

      s.io.load.valid.poke(false.B)
      s.io.commitFire.poke(true.B)
      s.clock.step()

      s.io.commitFire.poke(false.B)
      s.io.drainValid.expect(true.B)
      s.io.drainFire.poke(true.B)
      s.io.drain.wen.expect(true.B)
      s.io.drain.addr.expect(0x100.U)
      s.io.drain.wdata.expect(0x12345678.U)
      s.clock.step()

      s.io.drainFire.poke(false.B)
      s.io.commitReady.expect(false.B)
      s.io.dbgCount.expect(0.U)
    }
  }

  it should "wait loads behind older partial stores and flush buffered stores" in {
    test(new StoreBuffer) { s =>
      s.io.robHead.poke(0.U)
      s.io.flush.poke(false.B)
      s.io.clearBranchMask.valid.poke(false.B)
      s.io.flushBranchMask.valid.poke(false.B)
      s.io.commitFire.poke(false.B)
      s.io.drainFire.poke(false.B)
      s.io.commitRobIdx.poke(0.U)
      s.io.load.valid.poke(false.B)
      s.io.enq.valid.poke(true.B)
      s.io.enq.bits.robIdx.poke(1.U)
      s.io.enq.bits.addr.poke(0x104.U)
      s.io.enq.bits.wdata.poke(0xaa.U)
      s.io.enq.bits.wmask.poke("b0001".U)
      s.io.enq.bits.branchMask.poke(0.U)
      s.clock.step()

      s.io.enq.valid.poke(false.B)
      s.io.load.valid.poke(true.B)
      s.io.load.bits.robIdx.poke(3.U)
      s.io.load.bits.addr.poke(0x104.U)
      s.io.loadWait.expect(true.B)
      s.io.loadForward.valid.expect(false.B)

      s.io.flush.poke(true.B)
      s.clock.step()
      s.io.flush.poke(false.B)
      s.io.loadWait.expect(false.B)
      s.io.dbgCount.expect(0.U)
    }
  }

  it should "mark committed stores and drain them in store age order" in {
    test(new StoreBuffer) { s =>
      s.io.robHead.poke(0.U)
      s.io.flush.poke(false.B)
      s.io.clearBranchMask.valid.poke(false.B)
      s.io.flushBranchMask.valid.poke(false.B)
      s.io.commitFire.poke(false.B)
      s.io.drainFire.poke(false.B)
      s.io.commitRobIdx.poke(0.U)
      s.io.load.valid.poke(false.B)

      s.io.enq.valid.poke(true.B)
      s.io.enq.bits.robIdx.poke(1.U)
      s.io.enq.bits.addr.poke(0x100.U)
      s.io.enq.bits.wdata.poke(0x11111111.U)
      s.io.enq.bits.wmask.poke("b1111".U)
      s.io.enq.bits.branchMask.poke(0.U)
      s.clock.step()

      s.io.enq.bits.robIdx.poke(2.U)
      s.io.enq.bits.addr.poke(0x104.U)
      s.io.enq.bits.wdata.poke(0x22222222.U)
      s.io.enq.bits.wmask.poke("b1111".U)
      s.io.enq.bits.branchMask.poke(0.U)
      s.clock.step()

      s.io.enq.valid.poke(false.B)
      s.io.commitFire.poke(true.B)
      s.io.commitRobIdx.poke(1.U)
      s.clock.step()

      s.io.commitRobIdx.poke(2.U)
      s.clock.step()

      s.io.commitFire.poke(false.B)
      s.io.drainValid.expect(true.B)
      s.io.drain.addr.expect(0x100.U)
      s.io.drain.wdata.expect(0x11111111.U)
      s.io.drainFire.poke(true.B)
      s.clock.step()

      s.io.drain.addr.expect(0x104.U)
      s.io.drain.wdata.expect(0x22222222.U)
      s.clock.step()

      s.io.drainFire.poke(false.B)
      s.io.drainValid.expect(false.B)
      s.io.dbgCount.expect(0.U)
    }
  }

  it should "preserve committed stores and discard speculative stores on flush" in {
    test(new StoreBuffer) { s =>
      s.io.robHead.poke(0.U)
      s.io.flush.poke(false.B)
      s.io.clearBranchMask.valid.poke(false.B)
      s.io.flushBranchMask.valid.poke(false.B)
      s.io.commitFire.poke(false.B)
      s.io.drainFire.poke(false.B)
      s.io.commitRobIdx.poke(0.U)
      s.io.load.valid.poke(false.B)

      s.io.enq.valid.poke(true.B)
      s.io.enq.bits.robIdx.poke(1.U)
      s.io.enq.bits.addr.poke(0x200.U)
      s.io.enq.bits.wdata.poke(0xaaaaaaaaL.U)
      s.io.enq.bits.wmask.poke("b1111".U)
      s.io.enq.bits.branchMask.poke(0.U)
      s.clock.step()

      s.io.enq.bits.robIdx.poke(2.U)
      s.io.enq.bits.addr.poke(0x204.U)
      s.io.enq.bits.wdata.poke(0xbbbbbbbbL.U)
      s.io.enq.bits.wmask.poke("b1111".U)
      s.io.enq.bits.branchMask.poke(0.U)
      s.clock.step()

      s.io.enq.valid.poke(false.B)
      s.io.commitRobIdx.poke(1.U)
      s.io.commitFire.poke(true.B)
      s.clock.step()

      s.io.commitFire.poke(false.B)
      s.io.flush.poke(true.B)
      s.clock.step()

      s.io.flush.poke(false.B)
      s.io.dbgCount.expect(1.U)
      s.io.drainValid.expect(true.B)
      s.io.drain.addr.expect(0x200.U)
      s.io.drainFire.poke(true.B)
      s.clock.step()

      s.io.drainFire.poke(false.B)
      s.io.drainValid.expect(false.B)
      s.io.dbgCount.expect(0.U)
    }
  }
}
