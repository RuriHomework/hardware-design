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
    val uartRx = Input(Bool())
    val uartTx = Output(Bool())
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(XLen.W))
    val loaderActive = Output(Bool())
    val loaderError = Output(Bool())
  })

  val uartClockHz = sys.env.get("BOARD_CLOCK_HZ").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(60000000)
  val uartBaud = sys.env.get("UART_BAUD").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(115200)
  val preloadBoot = sys.env.get("PRELOAD_BOOT").contains("1")
  val loader = Module(new SerialLoader(uartClockHz, uartBaud, IMemDepth, DMemDepth))
  val preloadResetCycles = RegInit(Mux(preloadBoot.B, 15.U(4.W), 0.U(4.W)))
  when(preloadResetCycles =/= 0.U) {
    preloadResetCycles := preloadResetCycles - 1.U
  }
  val running = if (preloadBoot) preloadResetCycles === 0.U else loader.io.running
  val core = withReset(!running) { Module(new Core) }
  val imem = Module(new IMem(sys.env.get("IMEM_HEX").filter(_.nonEmpty)))
  val dmem = Module(new DMem(sys.env.get("DMEM_HEX").filter(_.nonEmpty)))
  val uart = Module(new UartTx(uartClockHz, uartBaud))
  val uartTxQueue = Module(new Queue(UInt(8.W), 16))
  val uartRx = withReset(!loader.io.running) { Module(new UartRx(uartClockHz, uartBaud)) }

  val mmioExit = "h20000000".U(PcWidth.W)
  val mmioUartTx = "h20000004".U(PcWidth.W)
  val mmioMtimeLo = "h20000008".U(PcWidth.W)
  val mmioMtimeHi = "h2000000c".U(PcWidth.W)
  val mmioMtimecmpLo = "h20000010".U(PcWidth.W)
  val mmioMtimecmpHi = "h20000014".U(PcWidth.W)
  val mmioUartStatus = "h20000018".U(PcWidth.W)
  val mmioUartRxData = "h2000001c".U(PcWidth.W)

  def maskWrite32(old: UInt, data: UInt, mask: UInt): UInt = {
    Cat((0 until 4).reverse.map { i =>
      Mux(mask(i), data(8 * i + 7, 8 * i), old(8 * i + 7, 8 * i))
    })
  }

  val daddr = core.io.dmem.addr
  val dwrite = core.io.dmem.wen && running
  val dmemAccess = daddr(31, 16) === "h1000".U
  val mmioWrite = dwrite && !dmemAccess
  val pendingReadAddr = RegInit(0.U(PcWidth.W))
  val mtime = RegInit(0.U(64.W))
  val mtimecmp = RegInit("hffffffffffffffff".U(64.W))
  val exitValid = RegInit(false.B)
  val exitCode = RegInit(0.U(XLen.W))

  loader.io.rx := io.uartRx
  uartRx.io.rx := io.uartRx

  core.io.timerInterrupt := running && mtime >= mtimecmp

  // IMem 连线
  imem.io.addr := core.io.imem.addr
  imem.io.load.wen := !preloadBoot.B && loader.io.imem.wen
  imem.io.load.addr := loader.io.imem.addr
  imem.io.load.data := loader.io.imem.data
  core.io.imem.inst := imem.io.inst

  // DMem 连线
  dmem.io.addr  := core.io.dmem.addr
  dmem.io.wdata := core.io.dmem.wdata
  dmem.io.wmask := core.io.dmem.wmask
  dmem.io.wen   := running && core.io.dmem.wen && dmemAccess
  dmem.io.load.wen := !preloadBoot.B && loader.io.dmem.wen
  dmem.io.load.addr := loader.io.dmem.addr
  dmem.io.load.data := loader.io.dmem.data

  when(!running) {
    pendingReadAddr := 0.U
  }.elsewhen(!dwrite) {
    pendingReadAddr := daddr
  }

  val uartRxValid = RegInit(false.B)
  val uartRxData = RegInit(0.U(8.W))
  val uartRxReadPending = RegNext(running && !dwrite && daddr === mmioUartRxData, false.B)
  uartRx.io.out.ready := running && (!uartRxValid || uartRxReadPending)

  when(!running) {
    uartRxValid := false.B
    uartRxData := 0.U
  }.elsewhen(uartRx.io.out.fire) {
    uartRxValid := true.B
    uartRxData := uartRx.io.out.bits
  }.elsewhen(uartRxReadPending) {
    uartRxValid := false.B
  }

  val mmioReadData = WireDefault(0.U(XLen.W))
  switch(pendingReadAddr) {
    is(mmioMtimeLo) { mmioReadData := mtime(31, 0) }
    is(mmioMtimeHi) { mmioReadData := mtime(63, 32) }
    is(mmioMtimecmpLo) { mmioReadData := mtimecmp(31, 0) }
    is(mmioMtimecmpHi) { mmioReadData := mtimecmp(63, 32) }
    is(mmioUartStatus) {
      mmioReadData := Cat(0.U(27.W), uartRx.io.overrun, uartRx.io.framingError, uartRxValid, uart.io.busy, uartTxQueue.io.enq.ready)
    }
    is(mmioUartRxData) { mmioReadData := uartRxData }
  }
  val pendingDmemAccess = pendingReadAddr(31, 16) === "h1000".U
  core.io.dmem.rdata := Mux(pendingDmemAccess, dmem.io.rdata, mmioReadData)

  uartTxQueue.io.enq.valid := running && mmioWrite && daddr === mmioUartTx
  uartTxQueue.io.enq.bits := MuxLookup(daddr(1, 0), core.io.dmem.wdata(7, 0))(Seq(
    1.U -> core.io.dmem.wdata(15, 8),
    2.U -> core.io.dmem.wdata(23, 16),
    3.U -> core.io.dmem.wdata(31, 24)
  ))
  uart.io.in <> uartTxQueue.io.deq

  val nextMtime = WireDefault(mtime + 1.U)
  val nextMtimecmp = WireDefault(mtimecmp)
  when(!running) {
    exitValid := false.B
    exitCode := 0.U
    mtime := 0.U
    mtimecmp := "hffffffffffffffff".U
  }.otherwise {
    when(mmioWrite) {
      when(daddr === mmioExit) {
        exitValid := true.B
        exitCode := core.io.dmem.wdata
      }.elsewhen(daddr === mmioMtimeLo) {
        nextMtime := Cat(mtime(63, 32), maskWrite32(mtime(31, 0), core.io.dmem.wdata, core.io.dmem.wmask))
      }.elsewhen(daddr === mmioMtimeHi) {
        nextMtime := Cat(maskWrite32(mtime(63, 32), core.io.dmem.wdata, core.io.dmem.wmask), mtime(31, 0))
      }.elsewhen(daddr === mmioMtimecmpLo) {
        nextMtimecmp := Cat(mtimecmp(63, 32), maskWrite32(mtimecmp(31, 0), core.io.dmem.wdata, core.io.dmem.wmask))
      }.elsewhen(daddr === mmioMtimecmpHi) {
        nextMtimecmp := Cat(maskWrite32(mtimecmp(63, 32), core.io.dmem.wdata, core.io.dmem.wmask), mtimecmp(31, 0))
      }
    }
    mtime := nextMtime
    mtimecmp := nextMtimecmp
  }

  val loaderActive = !preloadBoot.B && loader.io.active
  io.uartTx := Mux(loaderActive, loader.io.tx, uart.io.tx)
  io.exitValid := exitValid
  io.exitCode := exitCode
  io.loaderActive := loaderActive
  io.loaderError := loader.io.error

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
