package mem

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline

import isa.CoreConfig._

/**
 * 指令存储器：组合读、同步写。
 *
 * 当前 Fetch 按组合取指模型消费 inst；使用组合读可以让 Top/仿真路径
 * 与 Core testbench 的内存时序保持一致。
 * 由 Top 通过 loadMemoryFromFile 加载程序 hex。
 */
class IMem(initFile: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(PcWidth.W))
    val inst = Output(UInt(32.W))
    val load = new Bundle {
      val wen = Input(Bool())
      val addr = Input(UInt(log2Ceil(IMemDepth).W))
      val data = Input(UInt(32.W))
    }
  })

  val mem = Mem(IMemDepth, UInt(32.W))
  initFile.foreach(loadMemoryFromFileInline(mem, _))
  val wordAddr = (io.addr >> 2)(log2Ceil(IMemDepth) - 1, 0)
  io.inst := mem(wordAddr)

  when(io.load.wen) {
    mem.write(io.load.addr, io.load.data)
  }
}

/**
 * 数据存储器：单口同步读写 BRAM。
 *
 * 读：地址按字索引，输出延迟一拍
 * 写：按字节使能（wmask 4 位），同步写
 */
class DMem(initFile: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(PcWidth.W))
    val wdata = Input(UInt(XLen.W))
    val wmask = Input(UInt(4.W))   // 字节使能
    val wen  = Input(Bool())
    val rdata = Output(UInt(XLen.W))
    val load = new Bundle {
      val wen = Input(Bool())
      val addr = Input(UInt(log2Ceil(DMemDepth).W))
      val data = Input(UInt(XLen.W))
    }
  })

  val wordAddr = (io.addr >> 2)(log2Ceil(DMemDepth) - 1, 0)

  if (initFile.nonEmpty) {
    val mem = SyncReadMem(DMemDepth, Vec(4, UInt(8.W)))
    initFile.foreach(loadMemoryFromFileInline(mem, _))
    val rvec = mem.read(wordAddr)
    io.rdata := Cat(rvec(3), rvec(2), rvec(1), rvec(0))

    val writeVec = Wire(Vec(4, UInt(8.W)))
    val loadVec = Wire(Vec(4, UInt(8.W)))
    writeVec(0) := io.wdata(7, 0)
    writeVec(1) := io.wdata(15, 8)
    writeVec(2) := io.wdata(23, 16)
    writeVec(3) := io.wdata(31, 24)
    loadVec(0) := io.load.data(7, 0)
    loadVec(1) := io.load.data(15, 8)
    loadVec(2) := io.load.data(23, 16)
    loadVec(3) := io.load.data(31, 24)

    val writeEnable = io.load.wen || io.wen
    val writeAddr = Mux(io.load.wen, io.load.addr, wordAddr)
    val writeData = Mux(io.load.wen, loadVec, writeVec)
    val writeMask = Mux(io.load.wen, Fill(4, true.B), io.wmask).asBools
    when(writeEnable) {
      mem.write(writeAddr, writeData, writeMask)
    }
  } else {
    val mems = Seq.fill(4)(SyncReadMem(DMemDepth, UInt(8.W)))
    val rbytes = mems.map(_.read(wordAddr))
    io.rdata := Cat(rbytes.reverse)

    val writeBytes = Seq(
      io.wdata(7, 0),
      io.wdata(15, 8),
      io.wdata(23, 16),
      io.wdata(31, 24)
    )
    val loadBytes = Seq(
      io.load.data(7, 0),
      io.load.data(15, 8),
      io.load.data(23, 16),
      io.load.data(31, 24)
    )
    val writeAddr = Mux(io.load.wen, io.load.addr, wordAddr)

    for (i <- 0 until 4) {
      when(io.load.wen || (io.wen && io.wmask(i))) {
        mems(i).write(writeAddr, Mux(io.load.wen, loadBytes(i), writeBytes(i)))
      }
    }
  }
}
