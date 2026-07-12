package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._

/**
 * 自由列表：管理可用物理寄存器号。
 *
 * 结构：每个物理寄存器对应一个空闲位。
 * 初始时物理寄存器 32..63 都是空闲（0..31 对应逻辑寄存器初始映射）。
 *
 * 接口：
 *   - alloc：请求一个空闲 pdst（dispatch 时调用）
 *   - free：释放一个 pdst（commit 时，旧映射的物理寄存器回收）
 *
 * 单发射简化：每周期最多 1 alloc + 1 free。
 */
class FreeList extends Module {
  val io = IO(new Bundle {
    val allocReq  = Input(Bool())        // 需要分配（指令有 rd）
    val allocAvail= Output(Bool())       // 有空闲
    val allocPdst = Output(UInt(LogNumPhys.W))

    val freeReq   = Input(Bool())        // 释放
    val freePdst  = Input(UInt(LogNumPhys.W))

    val checkpoint = Output(new Bundle {
      val freeMask = UInt(NumPhysRegs.W)
    })
    val restore = Input(Valid(new Bundle {
      val freeMask = UInt(NumPhysRegs.W)
    }))
  })

  private val initialFreeMask = (((BigInt(1) << NumPhysRegs) - 1) ^
    ((BigInt(1) << NumLogicalRegs) - 1)).U(NumPhysRegs.W)
  val freeMask = RegInit(initialFreeMask)
  val canAlloc = freeMask.orR
  val allocPdst = PriorityEncoder(freeMask)

  io.allocAvail := canAlloc
  io.allocPdst := allocPdst
  io.checkpoint.freeMask := freeMask

  val doAlloc = io.allocReq && canAlloc
  val doFree = io.freeReq && io.freePdst =/= 0.U
  val allocBit = UIntToOH(allocPdst, NumPhysRegs)
  val freeBit = UIntToOH(io.freePdst, NumPhysRegs)
  val afterAlloc = Mux(doAlloc, freeMask & ~allocBit, freeMask)
  val nextFreeMask = Mux(doFree, afterAlloc | freeBit, afterAlloc)

  when(io.restore.valid) {
    freeMask := io.restore.bits.freeMask & ~1.U(NumPhysRegs.W)
  }.otherwise {
    freeMask := nextFreeMask
  }

  assert(!doFree || !freeMask(io.freePdst) || (doAlloc && allocPdst === io.freePdst),
    "physical register returned to FreeList twice")
}
