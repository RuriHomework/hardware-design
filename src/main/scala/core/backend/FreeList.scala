package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._

/**
 * 自由列表：管理可用物理寄存器号。
 *
 * 结构：环形队列 + 头尾指针。
 * 初始时物理寄存器 32..63 都是空闲（0..31 对应逻辑寄存器初始映射）。
 *
 * 接口：
 *   - alloc：请求一个空闲 pdst（dispatch 时调用）
 *   - free：释放一个 pdst（commit 时，旧映射的物理寄存器回收）
 *
 * 单发射简化：每周期最多 1 alloc + 1 free。
 */
class FreeList extends Module {
  // 空闲队列容量 = NumPhysRegs - NumLogicalRegs
  val FreeDepth = NumPhysRegs - NumLogicalRegs  // 32

  val io = IO(new Bundle {
    val allocReq  = Input(Bool())        // 需要分配（指令有 rd）
    val allocAvail= Output(Bool())       // 有空闲
    val allocPdst = Output(UInt(LogNumPhys.W))

    val freeReq   = Input(Bool())        // 释放
    val freePdst  = Input(UInt(LogNumPhys.W))

    val checkpoint = Output(new Bundle {
      val freeList = Vec(FreeDepth, UInt(LogNumPhys.W))
      val head = UInt(log2Ceil(FreeDepth).W)
      val tail = UInt(log2Ceil(FreeDepth).W)
      val count = UInt(log2Ceil(FreeDepth + 1).W)
    })
    val restore = Input(Valid(new Bundle {
      val freeList = Vec(FreeDepth, UInt(LogNumPhys.W))
      val head = UInt(log2Ceil(FreeDepth).W)
      val tail = UInt(log2Ceil(FreeDepth).W)
      val count = UInt(log2Ceil(FreeDepth + 1).W)
    }))
  })

  val freeList  = RegInit(VecInit(
    (NumLogicalRegs until NumPhysRegs).map(_.U(LogNumPhys.W))))
  val head = RegInit(0.U(log2Ceil(FreeDepth).W))
  val tail = RegInit(0.U(log2Ceil(FreeDepth).W))
  val count = RegInit(FreeDepth.U(log2Ceil(FreeDepth + 1).W))

  val canAlloc = count > 0.U
  val canFree  = count < FreeDepth.U

  io.allocAvail := canAlloc
  io.allocPdst  := freeList(head)
  io.checkpoint.freeList := freeList
  io.checkpoint.head := head
  io.checkpoint.tail := tail
  io.checkpoint.count := count

  // 同时 alloc + free
  val doAlloc = io.allocReq && canAlloc
  val doFree  = io.freeReq  && canFree

  when(io.restore.valid) {
    freeList := io.restore.bits.freeList
    head := io.restore.bits.head
    tail := io.restore.bits.tail
    count := io.restore.bits.count
  }.elsewhen(doAlloc && !doFree) {
    head  := Mux(head === (FreeDepth - 1).U, 0.U, head + 1.U)
    count := count - 1.U
  }.elsewhen(doFree && !doAlloc) {
    freeList(tail) := io.freePdst
    tail  := Mux(tail === (FreeDepth - 1).U, 0.U, tail + 1.U)
    count := count + 1.U
  }.elsewhen(doAlloc && doFree) {
    freeList(tail) := io.freePdst
    head := Mux(head === (FreeDepth - 1).U, 0.U, head + 1.U)
    tail := Mux(tail === (FreeDepth - 1).U, 0.U, tail + 1.U)
    // count 不变
  }
}
