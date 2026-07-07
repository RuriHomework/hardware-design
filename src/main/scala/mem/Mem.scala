package mem

import chisel3._
import chisel3.util._

import isa.CoreConfig._

/**
 * 指令存储器：单口同步读 BRAM。
 *
 * 地址按字索引（addr >> 2），输出延迟一拍。
 * 由 Top 通过 loadMemoryFromFile 加载程序 hex。
 */
class IMem extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(PcWidth.W))
    val inst = Output(UInt(32.W))
  })

  val mem = SyncReadMem(IMemDepth, UInt(32.W))
  io.inst := mem.read(io.addr >> 2)
}

/**
 * 数据存储器：单口同步读写 BRAM。
 *
 * 读：地址按字索引，输出延迟一拍
 * 写：按字节使能（wmask 4 位），同步写
 */
class DMem extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(PcWidth.W))
    val wdata = Input(UInt(XLen.W))
    val wmask = Input(UInt(4.W))   // 字节使能
    val wen  = Input(Bool())
    val rdata = Output(UInt(XLen.W))
  })

  val mem = SyncReadMem(DMemDepth, Vec(4, UInt(8.W)))
  val rvec = mem.read(io.addr >> 2)
  io.rdata := Cat(rvec(3), rvec(2), rvec(1), rvec(0))

  when(io.wen) {
    val wvec = Wire(Vec(4, UInt(8.W)))
    wvec(0) := io.wdata(7, 0)
    wvec(1) := io.wdata(15, 8)
    wvec(2) := io.wdata(23, 16)
    wvec(3) := io.wdata(31, 24)
    mem.write(io.addr >> 2, wvec, io.wmask.asBools)
  }
}
