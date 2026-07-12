package top

import chisel3._
import _root_.circt.stage.ChiselStage

import isa._
import isa.CoreConfig._
import mem._

/** Core, preload/UART loader, memories, and the simulation/board MMIO devices. */
class Top extends Module {
  val io = IO(new Bundle {
    val dbgCommit = Output(new RetireInfo)
    val dbgCommitValid = Output(Bool())
    val dbgCommitRd   = Output(UInt(LogNumLogical.W))
    val dbgCommitData = Output(UInt(XLen.W))
    val dbgCommitWritesReg = Output(Bool())
    val uartRx = Input(Bool())
    val uartTx = Output(Bool())
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(XLen.W))
    val loaderActive = Output(Bool())
    val loaderError = Output(Bool())
  })

  val uartClockHz = sys.env.get("BOARD_CLOCK_HZ").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(60000000)
  val uartBaud = sys.env.get("UART_BAUD").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(115200)
  val mtimeHz = sys.env.get("MTIME_HZ").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(10000000)
  val preloadBoot = sys.env.get("PRELOAD_BOOT").contains("1")
  val qemuVirtMmio = sys.env.get("QEMU_VIRT_MMIO").contains("1")
  require(!qemuVirtMmio || (mtimeHz > 0 && uartClockHz % mtimeHz == 0),
    s"BOARD_CLOCK_HZ ($uartClockHz) must be divisible by MTIME_HZ ($mtimeHz)")
  val mtimeDivider = if (qemuVirtMmio) uartClockHz / mtimeHz else 1
  val mmioDebug = sys.env.get("MMIO_DEBUG").contains("1")

  val loader = Module(new SerialLoader(uartClockHz, uartBaud, IMemDepth, DMemDepth))
  val preloadResetCycles = RegInit(Mux(preloadBoot.B, 15.U(4.W), 0.U(4.W)))
  when(preloadResetCycles =/= 0.U) {
    preloadResetCycles := preloadResetCycles - 1.U
  }
  val running = if (preloadBoot) preloadResetCycles === 0.U else loader.io.running

  val core = withReset(!running) { Module(new Core) }
  val imem = Module(new IMem(sys.env.get("IMEM_HEX").filter(_.nonEmpty)))
  val dmem = Module(new DMem(sys.env.get("DMEM_HEX").filter(_.nonEmpty)))
  val uartTx = Module(new UartTx(uartClockHz, uartBaud))
  val uartRx = withReset(!running) { Module(new UartRx(uartClockHz, uartBaud)) }
  val mmio = withReset(!running) {
    Module(new MachineMmio(qemuVirtMmio, mmioDebug, mtimeDivider = mtimeDivider))
  }

  loader.io.rx := io.uartRx
  uartRx.io.rx := io.uartRx
  mmio.io.uartRx <> uartRx.io.out
  uartTx.io.in <> mmio.io.uartTx
  mmio.io.uartFramingError := uartRx.io.framingError
  mmio.io.uartOverrun := uartRx.io.overrun

  imem.io.addr := core.io.imem.addr - IMemBase.U(PcWidth.W)
  imem.io.load.wen := !preloadBoot.B && loader.io.imem.wen
  imem.io.load.addr := loader.io.imem.addr
  imem.io.load.data := loader.io.imem.data
  core.io.imem.inst := imem.io.inst

  val ramStart = DMemBase.U(PcWidth.W)
  val ramEnd = (DMemBase + DMemDepth.toLong * 4L).U(PcWidth.W)
  val daddr = core.io.dmem.addr
  val dmemAccess = daddr >= ramStart && daddr < ramEnd
  val dmemRead = running && core.io.dmem.ren && dmemAccess
  val dmemWrite = running && core.io.dmem.wen && dmemAccess

  dmem.io.addr := daddr - DMemBase.U(PcWidth.W)
  dmem.io.wdata := core.io.dmem.wdata
  dmem.io.wmask := core.io.dmem.wmask
  dmem.io.wen := dmemWrite
  dmem.io.load.wen := !preloadBoot.B && loader.io.dmem.wen
  dmem.io.load.addr := loader.io.dmem.addr
  dmem.io.load.data := loader.io.dmem.data

  mmio.io.read.valid := running && core.io.dmem.ren && !dmemAccess
  mmio.io.read.bits := daddr
  mmio.io.write.valid := running && core.io.dmem.wen && !dmemAccess
  mmio.io.write.bits.addr := daddr
  mmio.io.write.bits.data := core.io.dmem.wdata
  mmio.io.write.bits.mask := core.io.dmem.wmask

  val readWasDmem = RegNext(dmemRead, false.B)
  core.io.dmem.rdata := Mux(readWasDmem, dmem.io.rdata, mmio.io.readData)
  core.io.softwareInterrupt := running && mmio.io.softwareInterrupt
  core.io.timerInterrupt := running && mmio.io.timerInterrupt
  core.io.externalInterrupt := running && mmio.io.externalInterrupt

  val loaderActive = !preloadBoot.B && loader.io.active
  io.uartTx := Mux(loaderActive, loader.io.tx, uartTx.io.tx)
  io.exitValid := mmio.io.exitValid
  io.exitCode := mmio.io.exitCode
  io.loaderActive := loaderActive
  io.loaderError := loader.io.error

  io.dbgCommit := core.io.dbgCommit
  io.dbgCommitValid := core.io.dbgCommitValid
  io.dbgCommitRd := core.io.dbgCommitRd
  io.dbgCommitData := core.io.dbgCommitData
  io.dbgCommitWritesReg := core.io.dbgCommitWritesReg
}

object TopMain extends App {
  ChiselStage.emitSystemVerilogFile(new Top, args = Array("--target-dir", "build"))
}
