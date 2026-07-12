package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import top.MachineMmio

class MachineMmioSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "MachineMmio"

  private def init(c: MachineMmio): Unit = {
    c.io.read.valid.poke(false.B)
    c.io.read.bits.poke(0.U)
    c.io.write.valid.poke(false.B)
    c.io.write.bits.addr.poke(0.U)
    c.io.write.bits.data.poke(0.U)
    c.io.write.bits.mask.poke(0.U)
    c.io.uartRx.valid.poke(false.B)
    c.io.uartRx.bits.poke(0.U)
    c.io.uartTx.ready.poke(true.B)
    c.io.uartFramingError.poke(false.B)
    c.io.uartOverrun.poke(false.B)
  }

  private def write(c: MachineMmio, addr: BigInt, data: BigInt, mask: Int = 0xf): Unit = {
    c.io.write.valid.poke(true.B)
    c.io.write.bits.addr.poke(addr.U)
    c.io.write.bits.data.poke(data.U)
    c.io.write.bits.mask.poke(mask.U)
    c.clock.step()
    c.io.write.valid.poke(false.B)
  }

  private def writeByte(c: MachineMmio, addr: BigInt, data: Int): Unit = {
    val offset = (addr & 3).toInt
    write(c, addr, BigInt(data) << (offset * 8), 1 << offset)
  }

  private def read(c: MachineMmio, addr: BigInt): BigInt = {
    c.io.read.valid.poke(true.B)
    c.io.read.bits.poke(addr.U)
    c.clock.step()
    c.io.read.valid.poke(false.B)
    c.io.readData.peek().litValue
  }

  it should "implement UART RX through PLIC claim and complete" in {
    test(new MachineMmio(enableQemuVirt = true)) { c =>
      init(c)
      val uart = BigInt("10000000", 16)
      val priority10 = BigInt("0c000028", 16)
      val enable0 = BigInt("0c002000", 16)
      val claim = BigInt("0c200004", 16)

      write(c, priority10, 1)
      write(c, enable0, 1 << 10)
      writeByte(c, uart + 1, 1) // IER.ERBFI

      c.io.uartRx.valid.poke(true.B)
      c.io.uartRx.bits.poke(0x68.U)
      c.clock.step()
      c.io.uartRx.valid.poke(false.B)
      c.clock.step()
      c.io.externalInterrupt.expect(true.B)

      read(c, claim).toInt shouldBe 10
      c.io.externalInterrupt.expect(false.B)
      (read(c, uart) & 0xff).toInt shouldBe 0x68
      write(c, claim, 10)
      c.clock.step()
      c.io.externalInterrupt.expect(false.B)
    }
  }

  it should "gate PLIC delivery with priority and threshold" in {
    test(new MachineMmio(enableQemuVirt = true)) { c =>
      init(c)
      val uart = BigInt("10000000", 16)
      write(c, BigInt("0c000028", 16), 2)
      write(c, BigInt("0c002000", 16), 1 << 10)
      write(c, BigInt("0c200000", 16), 2)
      writeByte(c, uart + 1, 1)
      c.io.uartRx.valid.poke(true.B)
      c.io.uartRx.bits.poke(0x41.U)
      c.clock.step()
      c.io.uartRx.valid.poke(false.B)
      c.clock.step()
      c.io.externalInterrupt.expect(false.B)
      write(c, BigInt("0c200000", 16), 1)
      c.io.externalInterrupt.expect(true.B)
    }
  }

  it should "provide CLINT software and timer interrupt levels" in {
    test(new MachineMmio(enableQemuVirt = true)) { c =>
      init(c)
      write(c, BigInt("02000000", 16), 1)
      c.io.softwareInterrupt.expect(true.B)
      write(c, BigInt("02000000", 16), 0)
      c.io.softwareInterrupt.expect(false.B)

      write(c, BigInt("02004004", 16), 0)
      write(c, BigInt("02004000", 16), 8)
      while (!c.io.timerInterrupt.peek().litToBoolean) {
        c.clock.step()
      }
      c.io.timerInterrupt.expect(true.B)
    }
  }

  it should "increment mtime at the configured timebase rate" in {
    test(new MachineMmio(enableQemuVirt = true, mtimeDivider = 6)) { c =>
      init(c)
      val mtime = BigInt("0200bff8", 16)
      val before = read(c, mtime)
      c.clock.step(11)
      val after = read(c, mtime)
      after - before shouldBe 2
    }
  }

  it should "reassert THRE after a full TX FIFO gains space" in {
    test(new MachineMmio(enableQemuVirt = true, uartFifoDepth = 16)) { c =>
      init(c)
      c.io.uartTx.ready.poke(false.B)
      val uart = BigInt("10000000", 16)
      val claim = BigInt("0c200004", 16)

      write(c, BigInt("0c000028", 16), 1)
      write(c, BigInt("0c002000", 16), 1 << 10)
      writeByte(c, uart + 1, 2) // IER.ETBEI

      c.clock.step(2)
      read(c, claim).toInt shouldBe 10
      read(c, uart + 2) // Reading IIR acknowledges the current THRE interrupt.
      write(c, claim, 10)

      for (i <- 0 until 16) {
        writeByte(c, uart, i)
      }
      c.clock.step(2)
      c.io.externalInterrupt.expect(false.B)

      c.io.uartTx.ready.poke(true.B)
      c.clock.step(4)
      c.io.externalInterrupt.expect(true.B)
      read(c, claim).toInt shouldBe 10
    }
  }
}
