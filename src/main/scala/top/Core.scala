package top

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import core.frontend._
import core.backend._
import mem._

/**
 * Core：前端 + 后端 + IMem/DMem 的顶层封装。
 *
 * 对外暴露：
 *   - io_imem / io_dmem：内存接口（供 Top 挂载 BRAM 或 AXI）
 *   - io_commit_peek：观察 commit（用于测试 / ILA）
 *
 * 内部只做连线，无独立逻辑。
 */
class Core extends Module {
  val io = IO(new Bundle {
    val imem = new Bundle {
      val addr = Output(UInt(PcWidth.W))
      val inst = Input(UInt(32.W))
    }
    val dmem = new Bundle {
      val addr  = Output(UInt(PcWidth.W))
      val wdata = Output(UInt(XLen.W))
      val wmask = Output(UInt(4.W))
      val wen   = Output(Bool())
      val rdata = Input(UInt(XLen.W))
    }
    // 调试观察口
    val dbgCommit = Output(new RetireInfo)
    val dbgCommitValid = Output(Bool())
    val dbgCommitRd   = Output(UInt(LogNumLogical.W))
    val dbgCommitData = Output(UInt(XLen.W))
    val dbgCommitWritesReg = Output(Bool())
    // 内部状态（调试）
    val dbgRobCount = Output(UInt((log2Ceil(RobEntries) + 1).W))
    val dbgIssueCount = Output(UInt((log2Ceil(IssueEntries) + 1).W))
    val dbgDispatchReady = Output(Bool())
    val dbgPc = Output(UInt(PcWidth.W))
    val dbgInstReg = Output(UInt(32.W))
    val dbgDispatchValid = Output(Bool())
    val dbgEnqValid = Output(Bool())
    val dbgFreeAvail = Output(Bool())
    val dbgCdbValid = Output(Bool())
    val dbgCdbPdst = Output(UInt(LogNumPhys.W))
  })

  val bp    = Module(new BranchPredictor)
  val fetch = Module(new Fetch)
  val be    = Module(new Backend)

  // ===== 前端 =====
  fetch.io.redirect := be.io.redirect
  fetch.io.imem.inst := io.imem.inst
  io.imem.addr := fetch.io.imem.addr

  // BP 查询
  bp.io.query.pc := fetch.io.bpQuery.pc
  fetch.io.bpQuery.taken  := bp.io.query.taken
  fetch.io.bpQuery.target := bp.io.query.target

  // BP 更新（来自后端 retire）
  fetch.io.bpUpdate      := be.io.retire.bits
  fetch.io.bpUpdateValid := be.io.retire.valid
  bp.io.update      := fetch.io.bpUpdate
  bp.io.updateValid := fetch.io.bpUpdateValid
  bp.io.rasPush := fetch.io.bpRasPush
  bp.io.rasPop  := fetch.io.bpRasPop
  bp.io.rasData := fetch.io.bpRasData
  bp.io.queryIsCall  := fetch.io.bpQueryIsCall
  bp.io.queryIsRet   := fetch.io.bpQueryIsRet
  bp.io.queryTarget  := fetch.io.bpQueryTarget

  // ===== 后端 =====
  be.io.dispatch := fetch.io.dispatch
  fetch.io.dispatchReady := be.io.dispatchReady
  be.io.dmem.rdata := io.dmem.rdata
  io.dmem.addr  := be.io.dmem.addr
  io.dmem.wdata := be.io.dmem.wdata
  io.dmem.wmask := be.io.dmem.wmask
  io.dmem.wen   := be.io.dmem.wen

  io.dbgCommit       := be.io.retire.bits
  io.dbgCommitValid  := be.io.retire.valid
  io.dbgCommitRd     := be.io.dbgCommitRd
  io.dbgCommitData   := be.io.dbgCommitData
  io.dbgCommitWritesReg := be.io.dbgCommitWritesReg
  io.dbgRobCount := be.io.dbgRobCount
  io.dbgIssueCount := be.io.dbgIssueCount
  io.dbgDispatchReady := be.io.dispatchReady
  io.dbgPc := fetch.io.dbgPc
  io.dbgInstReg := fetch.io.dbgInstReg
  io.dbgDispatchValid := fetch.io.dispatch.valid
  io.dbgEnqValid := be.io.dbgEnqValid
  io.dbgFreeAvail := be.io.dbgFreeAvail
  io.dbgCdbValid := be.io.dbgCdbValid
  io.dbgCdbPdst := be.io.dbgCdbPdst
}
