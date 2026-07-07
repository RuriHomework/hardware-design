package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * Rename / Map Table：逻辑→物理寄存器映射。
 *
 * 结构：32 项 map table，每项存当前物理寄存器号。
 * ready 位由独立的 PhysRegReady 模块维护（以 pdst 为键），
 * rmt 只负责映射，不负责 ready 跟踪。
 *
 * 查询：rs1, rs2 → 当前映射的 pdst
 * 更新：dispatch 时，rd 分配新 pdst，旧 pdst 交给 ROB 记录（commit 时释放）
 *
 * 单发射简化：每周期 1 条指令，读 rs1/rs2 + 写 rd 同周期完成。
 */
class RenameTable extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(LogNumLogical.W))
    val rs2 = Input(UInt(LogNumLogical.W))
    val rs1Pdst = Output(UInt(LogNumPhys.W))
    val rs2Pdst = Output(UInt(LogNumPhys.W))

    val rd     = Input(UInt(LogNumLogical.W))
    val writesReg = Input(Bool())
    val newPdst = Input(UInt(LogNumPhys.W))   // FreeList 分配的新物理寄存器
    val stalePdst = Output(UInt(LogNumPhys.W)) // 旧映射，给 ROB 记录
    val update = Input(Bool())                 // 本周期这条指令要写 rd

    // commit：恢复（mispred / exception 时回滚到旧映射）
    val rollback = Input(Valid(new Bundle {
      val lrd = UInt(LogNumLogical.W)
      val pdst = UInt(LogNumPhys.W)  // 恢复为这个映射
    }))

    val checkpoint = Output(Vec(NumLogicalRegs, UInt(LogNumPhys.W)))
    val restore = Input(Valid(Vec(NumLogicalRegs, UInt(LogNumPhys.W))))
  })

  val mapTable = RegInit(VecInit(
    (0 until NumLogicalRegs).map(_.U(LogNumPhys.W))))

  // x0 永远映射到 p0
  io.rs1Pdst := Mux(io.rs1 === 0.U, 0.U, mapTable(io.rs1))
  io.rs2Pdst := Mux(io.rs2 === 0.U, 0.U, mapTable(io.rs2))

  io.stalePdst := mapTable(io.rd)
  io.checkpoint := mapTable

  // 更新映射
  when(io.update && io.writesReg && io.rd =/= 0.U) {
    mapTable(io.rd) := io.newPdst
  }

  // 回滚
  when(io.rollback.valid && io.rollback.bits.lrd =/= 0.U) {
    mapTable(io.rollback.bits.lrd) := io.rollback.bits.pdst
  }

  when(io.restore.valid) {
    mapTable := io.restore.bits
    mapTable(0) := 0.U
  }
}
