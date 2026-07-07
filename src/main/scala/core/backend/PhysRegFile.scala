package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._

/**
 * 物理寄存器堆（PRF）：64 个 32 位寄存器。
 *
 * 端口：
 *   - 2 个组合读口（rs1, rs2 供 Issue 用）
 *   - 1 个写口（CDB / commit 写回）
 *   - 写优先于读（write-first），保证同拍 bypass
 *
 * 注意：PRF 不负责 forward/bypass——那由 IssueQueue 监听 CDB 实现。
 * 这里只做基础寄存器堆读写。
 */
class PhysRegFile extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(LogNumPhys.W))
    val rs2 = Input(UInt(LogNumPhys.W))
    val rs1Data = Output(UInt(XLen.W))
    val rs2Data = Output(UInt(XLen.W))

    val wen   = Input(Bool())
    val waddr = Input(UInt(LogNumPhys.W))
    val wdata = Input(UInt(XLen.W))
  })

  val regs = RegInit(VecInit(Seq.fill(NumPhysRegs)(0.U(XLen.W))))

  // 读优先于写（read-first）：避免组合环。
  // bypass 由 IssueQueue 监听 CDB 实现，PRF 不做 write-first。
  io.rs1Data := regs(io.rs1)
  io.rs2Data := regs(io.rs2)

  when(io.wen) { regs(io.waddr) := io.wdata }
}

/**
 * 物理寄存器号是否有效（用于 scoreboard，标记值已就绪）。
 * 本模块用单独的 ready array 而非 PRF 内嵌，便于 CDB 多源更新。
 */
class PhysRegReady extends Module {
  val io = IO(new Bundle {
    val query  = Input(UInt(LogNumPhys.W))
    val ready  = Output(Bool())

    // 写回时置 ready
    val setReady = Input(Valid(UInt(LogNumPhys.W)))
    // dispatch 时清 ready（新分配的物理寄存器待写）
    val clearReady = Input(Valid(UInt(LogNumPhys.W)))
  })

  val ready = RegInit(VecInit(Seq.fill(NumPhysRegs)(true.B)))

  // 默认查询
  io.ready := ready(io.query)

  // 写回置位
  when(io.setReady.valid)  { ready(io.setReady.bits)  := true.B  }
  when(io.clearReady.valid){ ready(io.clearReady.bits):= false.B }
}
