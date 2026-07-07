package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import isa._
import isa.Uop._
import core.backend._

class BackendStructuresSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "backend structures"

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
      r.io.enq.valid.poke(true.B)
      r.io.enq.bits.uop.poke(ADD)
      r.io.enq.bits.pc.poke(0x100.U)
      r.io.enq.bits.rd.poke(1.U)
      r.io.enq.bits.pdst.poke(40.U)
      r.io.enq.bits.stalePdst.poke(1.U)
      r.io.enq.bits.writesReg.poke(true.B)
      r.io.enq.bits.predTaken.poke(false.B)
      r.io.enq.bits.predTarget.poke(0.U)
      r.io.wb.valid.poke(false.B)
      r.clock.step()

      r.io.enq.valid.poke(false.B)
      r.io.wb.valid.poke(true.B)
      r.io.wb.bits.robIdx.poke(0.U)
      r.io.wb.bits.pdst.poke(40.U)
      r.io.wb.bits.data.poke(42.U)
      r.io.wb.bits.cause.poke(RedirectCause.NONE)
      r.io.wb.bits.taken.poke(false.B)
      r.io.wb.bits.target.poke(0.U)
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
      r.clock.step()

      r.io.enq.valid.poke(false.B)
      r.io.wb.valid.poke(true.B)
      r.io.wb.bits.robIdx.poke(1.U)
      r.io.wb.bits.pdst.poke(0.U)
      r.io.wb.bits.data.poke(0.U)
      r.io.wb.bits.cause.poke(RedirectCause.MISPRED)
      r.io.wb.bits.taken.poke(true.B)
      r.io.wb.bits.target.poke(0x240.U)
      r.clock.step()

      r.io.wb.valid.poke(false.B)
      r.io.commit.valid.expect(true.B)
      r.io.redirect.valid.expect(true.B)
      r.io.redirect.bits.target.expect(0x240.U)
      r.io.flushIssue.expect(true.B)
    }
  }
}
