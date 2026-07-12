package core.backend

import chisel3._
import chisel3.util._

import isa.CoreConfig._

class StoreBufferEntry extends Bundle {
  val robIdx = UInt(RobIdWidth.W)
  val addr   = UInt(PcWidth.W)
  val wdata  = UInt(XLen.W)
  val wmask  = UInt(4.W)
  val branchMask = UInt(BranchCheckpointEntries.W)
}

class StoreBuffer extends Module {
  val io = IO(new Bundle {
    val robHead = Input(UInt(RobIdWidth.W))

    val enq = Input(Valid(new StoreBufferEntry))
    val enqReady = Output(Bool())

    val commitRobIdx = Input(UInt(RobIdWidth.W))
    val commitReady = Output(Bool())
    val commitFire = Input(Bool())
    val drainFire = Input(Bool())
    val drainValid = Output(Bool())
    val drain = Output(new Bundle {
      val addr = UInt(PcWidth.W)
      val wdata = UInt(XLen.W)
      val wmask = UInt(4.W)
      val wen = Bool()
    })

    val load = Input(Valid(new Bundle {
      val robIdx = UInt(RobIdWidth.W)
      val addr = UInt(PcWidth.W)
      val mask = UInt(4.W)
    }))
    val loadWait = Output(Bool())
    val loadForward = Output(Valid(UInt(XLen.W)))

    val flush = Input(Bool())
    val flushBranchMask = Input(Valid(UInt(BranchCheckpointEntries.W)))
    val clearBranchMask = Input(Valid(UInt(BranchCheckpointEntries.W)))

    val dbgCount = Output(UInt((LogStoreBufferEntries + 1).W))
    val dbgFull = Output(Bool())
    val empty = Output(Bool())
  })

  class Entry extends Bundle {
    val valid = Bool()
    val committed = Bool()
    val bits = new StoreBufferEntry
    val age = UInt(32.W)
  }

  val entries = RegInit(VecInit(Seq.fill(StoreBufferEntries)(0.U.asTypeOf(new Entry))))
  val nextAge = RegInit(0.U(32.W))

  val commitMatches = entries.map(e => e.valid && !e.committed && e.bits.robIdx === io.commitRobIdx)
  val commitHit = commitMatches.reduce(_ || _)
  val commitIdx = PriorityEncoder(commitMatches)

  io.commitReady := commitHit

  val drainCandidates = entries.map(e => e.valid && e.committed)
  val oldestDrainMask = (0 until StoreBufferEntries).map { i =>
    val hasOlderCandidate = (0 until StoreBufferEntries).map { j =>
      drainCandidates(j) && entries(j).age < entries(i).age
    }.reduce(_ || _)
    drainCandidates(i) && !hasOlderCandidate
  }
  val drainHit = drainCandidates.reduce(_ || _)
  val drainIdx = PriorityEncoder(oldestDrainMask)

  io.drainValid := drainHit
  io.drain.addr := Mux(drainHit, entries(drainIdx).bits.addr, 0.U)
  io.drain.wdata := Mux(drainHit, entries(drainIdx).bits.wdata, 0.U)
  io.drain.wmask := Mux(drainHit, entries(drainIdx).bits.wmask, 0.U)
  io.drain.wen := io.drainFire && drainHit

  val freeMask = entries.map(e => !e.valid)
  val hasFree = freeMask.reduce(_ || _)
  val enqIdx = PriorityEncoder(freeMask)
  io.enqReady := hasFree

  val loadWord = io.load.bits.addr(PcWidth - 1, 2)
  val loadDist = io.load.bits.robIdx - io.robHead
  val olderSameWord = entries.map { e =>
    val storeDist = e.bits.robIdx - io.robHead
    e.valid && (e.committed || storeDist < loadDist) &&
      e.bits.addr(PcWidth - 1, 2) === loadWord
  }
  val olderPartialSameWord = entries.zip(olderSameWord).map { case (e, same) =>
    val overlap = (e.bits.wmask & io.load.bits.mask).orR
    val coversLoad = (e.bits.wmask & io.load.bits.mask) === io.load.bits.mask
    same && overlap && !coversLoad
  }
  val forwardCandidates = entries.zip(olderSameWord).map { case (e, same) =>
    same && ((e.bits.wmask & io.load.bits.mask) === io.load.bits.mask)
  }
  val youngestForwardMask = (0 until StoreBufferEntries).map { i =>
    val hasYoungerCandidate = (0 until StoreBufferEntries).map { j =>
      forwardCandidates(j) && entries(j).age > entries(i).age
    }.reduce(_ || _)
    forwardCandidates(i) && !hasYoungerCandidate
  }
  val hasForward = forwardCandidates.reduce(_ || _)
  val forwardIdx = PriorityEncoder(youngestForwardMask)

  io.loadWait := io.load.valid && olderPartialSameWord.reduce(_ || _)
  io.loadForward.valid := io.load.valid && hasForward && !io.loadWait
  io.loadForward.bits := Mux(hasForward, entries(forwardIdx).bits.wdata, 0.U)

  val doEnq = io.enq.valid && io.enqReady
  val doCommit = io.commitFire && commitHit
  val doDrain = io.drainFire && drainHit
  val validCount = PopCount(entries.map(_.valid))
  val branchKill = entries.map(e =>
    io.flushBranchMask.valid && !e.committed && (e.bits.branchMask & io.flushBranchMask.bits).orR)
  val branchKillVec = VecInit(branchKill)

  when(io.flush) {
    for (e <- entries) {
      when(!e.committed) {
        e.valid := false.B
      }
    }
  }.otherwise {
    for ((e, i) <- entries.zipWithIndex) {
      when(branchKill(i) || (doDrain && drainIdx === i.U)) {
        e.valid := false.B
        e.committed := false.B
      }
    }
  }
  when(io.clearBranchMask.valid && !io.flush && !io.flushBranchMask.valid) {
    for (e <- entries) {
      e.bits.branchMask := e.bits.branchMask & ~io.clearBranchMask.bits
    }
  }
  when(doCommit && !io.flush && !branchKillVec(commitIdx)) {
    entries(commitIdx).committed := true.B
  }
  when(doEnq && !io.flush && !io.flushBranchMask.valid) {
    entries(enqIdx).valid := true.B
    entries(enqIdx).committed := false.B
    entries(enqIdx).bits := io.enq.bits
    entries(enqIdx).age := nextAge
    nextAge := nextAge + 1.U
  }

  io.dbgCount := validCount
  io.dbgFull := !hasFree
  io.empty := validCount === 0.U
}
