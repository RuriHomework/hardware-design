package top

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import isa._
import isa.CoreConfig._
import mem._

/**
 * Top：Core + IMem + DMem 顶层封装。
 *
 * 仿真用：通过 loadMemoryFromFile 加载程序 hex。
 * 综合时：IMem/DMem 映射为 BRAM，由 Vivado 自动推断。
 *
 * 对外：仅暴露时钟/复位 + 调试观察口。
 * 上板时，在更外层把 IMem/DMem 换成 AXI Slave 即可由 PS 写入程序。
 */
class Top extends Module {
  val io = IO(new Bundle {
    val dbgCommit = Output(new RetireInfo)
    val dbgCommitValid = Output(Bool())
    val dbgCommitRd   = Output(UInt(LogNumLogical.W))
    val dbgCommitData = Output(UInt(XLen.W))
    val dbgCommitWritesReg = Output(Bool())
  })

  val core = Module(new Core)
  val imem = Module(new IMem)
  val dmem = Module(new DMem)

  // IMem 连线
  imem.io.addr := core.io.imem.addr
  core.io.imem.inst := imem.io.inst

  // DMem 连线
  dmem.io.addr  := core.io.dmem.addr
  dmem.io.wdata := core.io.dmem.wdata
  dmem.io.wmask := core.io.dmem.wmask
  dmem.io.wen   := core.io.dmem.wen
  core.io.dmem.rdata := dmem.io.rdata

  // 调试
  io.dbgCommit       := core.io.dbgCommit
  io.dbgCommitValid  := core.io.dbgCommitValid
  io.dbgCommitRd     := core.io.dbgCommitRd
  io.dbgCommitData   := core.io.dbgCommitData
  io.dbgCommitWritesReg := core.io.dbgCommitWritesReg
}

object TopMain extends App {
  ChiselStage.emitSystemVerilogFile(
    new Top,
    args = Array("--target-dir", "build")
  )
}
