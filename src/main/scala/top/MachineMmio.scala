package top

import chisel3._
import chisel3.util._

import isa.CoreConfig._

/** One-cycle MMIO model for the board-local devices and QEMU virt machine devices. */
class MachineMmio(
  enableQemuVirt: Boolean,
  debug: Boolean = false,
  uartFifoDepth: Int = 16,
  mtimeDivider: Int = 1
) extends Module {
  require(uartFifoDepth > 0)
  require(mtimeDivider > 0)
  val io = IO(new Bundle {
    val read = Input(Valid(UInt(PcWidth.W)))
    val readData = Output(UInt(XLen.W))
    val write = Input(Valid(new Bundle {
      val addr = UInt(PcWidth.W)
      val data = UInt(XLen.W)
      val mask = UInt(4.W)
    }))
    val uartRx = Flipped(Decoupled(UInt(8.W)))
    val uartTx = Decoupled(UInt(8.W))
    val uartFramingError = Input(Bool())
    val uartOverrun = Input(Bool())
    val softwareInterrupt = Output(Bool())
    val timerInterrupt = Output(Bool())
    val externalInterrupt = Output(Bool())
    val exitValid = Output(Bool())
    val exitCode = Output(UInt(XLen.W))
  })

  private val localExit = "h20000000".U(PcWidth.W)
  private val localUartTx = "h20000004".U(PcWidth.W)
  private val localMtimeLo = "h20000008".U(PcWidth.W)
  private val localMtimeHi = "h2000000c".U(PcWidth.W)
  private val localMtimecmpLo = "h20000010".U(PcWidth.W)
  private val localMtimecmpHi = "h20000014".U(PcWidth.W)
  private val localUartStatus = "h20000018".U(PcWidth.W)
  private val localUartRx = "h2000001c".U(PcWidth.W)

  private val uartBase = "h10000000".U(PcWidth.W)
  private val clintMsip = "h02000000".U(PcWidth.W)
  private val clintMtimecmp = "h02004000".U(PcWidth.W)
  private val clintMtime = "h0200bff8".U(PcWidth.W)
  private val plicBase = "h0c000000".U(PcWidth.W)
  private val plicPriority10 = "h0c000028".U(PcWidth.W)
  private val plicPending0 = "h0c001000".U(PcWidth.W)
  private val plicEnable0 = "h0c002000".U(PcWidth.W)
  private val plicThreshold = "h0c200000".U(PcWidth.W)
  private val plicClaim = "h0c200004".U(PcWidth.W)
  private val uartSourceId = 10.U(32.W)

  def maskedWrite(old: UInt, data: UInt, mask: UInt): UInt = {
    Cat((0 until 4).reverse.map { i =>
      Mux(mask(i), data(8 * i + 7, 8 * i), old(8 * i + 7, 8 * i))
    })
  }

  def addressedByte(data: UInt, addr: UInt): UInt = {
    MuxLookup(addr(1, 0), data(7, 0))(Seq(
      1.U -> data(15, 8), 2.U -> data(23, 16), 3.U -> data(31, 24)))
  }

  def alignByte(data: UInt, addr: UInt): UInt = data.pad(32) << (addr(1, 0) << 3)

  val mtime = RegInit(0.U(64.W))
  val mtimePrescaler = if (mtimeDivider > 1) Some(RegInit(0.U(log2Ceil(mtimeDivider).W))) else None
  val mtimecmp = RegInit("hffffffffffffffff".U(64.W))
  val msip = RegInit(false.B)
  val exitValid = RegInit(false.B)
  val exitCode = RegInit(0.U(XLen.W))

  val uartRxQueue = Module(new Queue(UInt(8.W), uartFifoDepth))
  val uartTxQueue = Module(new Queue(UInt(8.W), uartFifoDepth))
  uartRxQueue.io.enq <> io.uartRx
  io.uartTx <> uartTxQueue.io.deq

  val uartIer = RegInit(0.U(4.W))
  val uartLcr = RegInit(0.U(8.W))
  val uartMcr = RegInit(0.U(8.W))
  val uartScr = RegInit(0.U(8.W))
  val uartDll = RegInit(1.U(8.W))
  val uartDlm = RegInit(0.U(8.W))
  val uartTxIrqLatch = RegInit(false.B)
  val txWriteLastCycle = RegNext(uartTxQueue.io.enq.fire, false.B)
  val txReadyPrev = RegNext(uartTxQueue.io.enq.ready, true.B)
  val txReadyBecameAvailable = !txReadyPrev && uartTxQueue.io.enq.ready

  val plicPriority = RegInit(0.U(3.W))
  val plicEnable = RegInit(false.B)
  val plicThresholdReg = RegInit(0.U(3.W))
  val plicPending = RegInit(false.B)
  val plicInService = RegInit(false.B)

  val qemu = enableQemuVirt.B
  val dlab = uartLcr(7)
  val uartRxPending = uartRxQueue.io.deq.valid && uartIer(0)
  val uartTxPending = uartTxIrqLatch && uartIer(1)
  val uartInterrupt = uartRxPending || uartTxPending
  val plicEligible = plicPending && !plicInService && plicEnable &&
    plicPriority =/= 0.U && plicPriority > plicThresholdReg
  val claimRead = qemu && io.read.valid && io.read.bits === plicClaim
  val claimValue = Mux(plicEligible, uartSourceId, 0.U)
  val completeWrite = qemu && io.write.valid && io.write.bits.addr === plicClaim &&
    io.write.bits.data === uartSourceId

  when(!plicInService && uartInterrupt) {
    plicPending := true.B
  }
  when(claimRead && plicEligible) {
    plicPending := false.B
    plicInService := true.B
  }
  when(completeWrite) {
    plicInService := false.B
  }

  val readUartRbr = qemu && io.read.valid && io.read.bits === uartBase && !dlab
  val readLocalUart = io.read.valid && io.read.bits === localUartRx
  uartRxQueue.io.deq.ready := readUartRbr || readLocalUart

  val writeAddr = io.write.bits.addr
  val writeByte = addressedByte(io.write.bits.data, writeAddr)
  val writeUartThr = qemu && io.write.valid && writeAddr === uartBase && !dlab
  val writeLocalUart = io.write.valid && writeAddr === localUartTx
  uartTxQueue.io.enq.valid := writeUartThr || writeLocalUart
  uartTxQueue.io.enq.bits := writeByte

  val uartIir = Mux(uartRxPending, "h04".U(8.W),
    Mux(uartTxPending, "h02".U(8.W), "h01".U(8.W)))
  val readUartIir = qemu && io.read.valid && io.read.bits === uartBase + 2.U
  when(writeUartThr) {
    uartTxIrqLatch := false.B
  }.elsewhen(readUartIir && uartTxPending && !uartRxPending) {
    uartTxIrqLatch := false.B
  }.elsewhen((txWriteLastCycle && uartTxQueue.io.enq.ready) || txReadyBecameAvailable) {
    uartTxIrqLatch := true.B
  }

  if (debug) {
    val externalInterruptPrev = RegNext(plicEligible, false.B)
    when(uartRxQueue.io.enq.fire) {
      printf(cf"mmio uart-rx enqueue data=0x${uartRxQueue.io.enq.bits}%x\n")
    }
    when(plicEligible && !externalInterruptPrev) {
      printf(cf"mmio plic-meip priority=${plicPriority} threshold=${plicThresholdReg}\n")
    }
    when(claimRead) {
      printf(cf"mmio plic-claim id=${claimValue}\n")
    }
    when(completeWrite) {
      printf(cf"mmio plic-complete id=${io.write.bits.data}\n")
    }
    when(readUartRbr) {
      printf(cf"mmio uart-rbr data=0x${uartRxQueue.io.deq.bits}%x\n")
    }
    when(io.write.valid && io.write.bits.addr === plicPriority10) {
      printf(cf"mmio plic-priority10=${io.write.bits.data}%d\n")
    }
    when(io.write.valid && io.write.bits.addr === plicEnable0) {
      printf(cf"mmio plic-enable0=0x${io.write.bits.data}%x\n")
    }
    when(io.write.valid && io.write.bits.addr === plicThreshold) {
      printf(cf"mmio plic-threshold=${io.write.bits.data}%d\n")
    }
    when(io.write.valid && io.write.bits.addr === uartBase + 1.U && !dlab) {
      printf(cf"mmio uart-ier=0x${writeByte}%x\n")
    }
  }

  val readData = RegInit(0.U(XLen.W))
  when(io.read.valid) {
    readData := 0.U
    switch(io.read.bits) {
      is(localMtimeLo) { readData := mtime(31, 0) }
      is(localMtimeHi) { readData := mtime(63, 32) }
      is(localMtimecmpLo) { readData := mtimecmp(31, 0) }
      is(localMtimecmpHi) { readData := mtimecmp(63, 32) }
      is(localUartStatus) {
        readData := Cat(0.U(27.W), io.uartOverrun, io.uartFramingError,
          uartRxQueue.io.deq.valid, !uartTxQueue.io.enq.ready, uartTxQueue.io.enq.ready)
      }
      is(localUartRx) { readData := uartRxQueue.io.deq.bits }
    }
    if (enableQemuVirt) {
      when(io.read.bits === clintMsip) { readData := msip }
      when(io.read.bits === clintMtimecmp) { readData := mtimecmp(31, 0) }
      when(io.read.bits === clintMtimecmp + 4.U) { readData := mtimecmp(63, 32) }
      when(io.read.bits === clintMtime) { readData := mtime(31, 0) }
      when(io.read.bits === clintMtime + 4.U) { readData := mtime(63, 32) }
      when(io.read.bits === plicPriority10) { readData := plicPriority }
      when(io.read.bits === plicPending0) { readData := plicPending.asUInt << 10 }
      when(io.read.bits === plicEnable0) { readData := plicEnable.asUInt << 10 }
      when(io.read.bits === plicThreshold) { readData := plicThresholdReg }
      when(io.read.bits === plicClaim) { readData := claimValue }
      when(io.read.bits >= uartBase && io.read.bits < uartBase + 8.U) {
        val uartByte = WireDefault(0.U(8.W))
        switch(io.read.bits(2, 0)) {
          is(0.U) { uartByte := Mux(dlab, uartDll, uartRxQueue.io.deq.bits) }
          is(1.U) { uartByte := Mux(dlab, uartDlm, uartIer) }
          is(2.U) { uartByte := uartIir }
          is(3.U) { uartByte := uartLcr }
          is(4.U) { uartByte := uartMcr }
          is(5.U) {
            uartByte := Cat(0.U(1.W), !uartTxQueue.io.deq.valid && io.uartTx.ready,
              uartTxQueue.io.enq.ready, 0.U(4.W), uartRxQueue.io.deq.valid)
          }
          is(6.U) { uartByte := 0.U }
          is(7.U) { uartByte := uartScr }
        }
        readData := alignByte(uartByte, io.read.bits)
      }
    }
  }
  io.readData := readData

  val mtimeTick = mtimePrescaler match {
    case Some(counter) => counter === (mtimeDivider - 1).U
    case None => true.B
  }
  mtimePrescaler.foreach { counter =>
    counter := Mux(mtimeTick, 0.U, counter + 1.U)
  }
  val nextMtime = WireDefault(Mux(mtimeTick, mtime + 1.U, mtime))
  val nextMtimecmp = WireDefault(mtimecmp)
  when(io.write.valid) {
    when(writeAddr === localExit) {
      exitValid := true.B
      exitCode := io.write.bits.data
    }.elsewhen(writeAddr === localMtimeLo || (qemu && writeAddr === clintMtime)) {
      nextMtime := Cat(mtime(63, 32), maskedWrite(mtime(31, 0), io.write.bits.data, io.write.bits.mask))
    }.elsewhen(writeAddr === localMtimeHi || (qemu && writeAddr === clintMtime + 4.U)) {
      nextMtime := Cat(maskedWrite(mtime(63, 32), io.write.bits.data, io.write.bits.mask), mtime(31, 0))
    }.elsewhen(writeAddr === localMtimecmpLo || (qemu && writeAddr === clintMtimecmp)) {
      nextMtimecmp := Cat(mtimecmp(63, 32), maskedWrite(mtimecmp(31, 0), io.write.bits.data, io.write.bits.mask))
    }.elsewhen(writeAddr === localMtimecmpHi || (qemu && writeAddr === clintMtimecmp + 4.U)) {
      nextMtimecmp := Cat(maskedWrite(mtimecmp(63, 32), io.write.bits.data, io.write.bits.mask), mtimecmp(31, 0))
    }.elsewhen(qemu && writeAddr === clintMsip) {
      msip := io.write.bits.data(0)
    }.elsewhen(qemu && writeAddr === plicPriority10) {
      plicPriority := io.write.bits.data(2, 0)
    }.elsewhen(qemu && writeAddr === plicEnable0) {
      plicEnable := io.write.bits.data(10)
    }.elsewhen(qemu && writeAddr === plicThreshold) {
      plicThresholdReg := io.write.bits.data(2, 0)
    }.elsewhen(qemu && writeAddr === uartBase && dlab) {
      uartDll := writeByte
    }.elsewhen(qemu && writeAddr === uartBase + 1.U) {
      when(dlab) {
        uartDlm := writeByte
      }.otherwise {
        when(!uartIer(1) && writeByte(1) && uartTxQueue.io.enq.ready) {
          uartTxIrqLatch := true.B
        }
        uartIer := writeByte(3, 0)
      }
    }.elsewhen(qemu && writeAddr === uartBase + 3.U) {
      uartLcr := writeByte
    }.elsewhen(qemu && writeAddr === uartBase + 4.U) {
      uartMcr := writeByte
    }.elsewhen(qemu && writeAddr === uartBase + 7.U) {
      uartScr := writeByte
    }
  }
  mtime := nextMtime
  mtimecmp := nextMtimecmp

  io.softwareInterrupt := qemu && msip
  io.timerInterrupt := mtime >= mtimecmp
  io.externalInterrupt := qemu && plicEligible
  io.exitValid := exitValid
  io.exitCode := exitCode
}
