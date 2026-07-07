package core.backend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * Rename / Map Table：逻辑→物理寄存器映射。
 *
 * 结构：32 项 RAM map table，每项存当前物理寄存器号 + ready 位。
 *
 * 查询：rs1, rs2 → 当前映射的 pdst + ready
 * 更新：dispatch 时，rd 分配新 pdst，旧 pdst 交给 ROB 记录（commit 时释放）
 *
 * 单发射简化：每周期 1 条指令，读 rs1/rs2 + 写 rd 同周期完成。
 */
class RenameTable extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(LogNumLogical.W))
    val rs2 = Input(UInt(LogNumLogical.W))
    val usesRs1 = Input(Bool())
    val usesRs2 = Input(Bool())
    val rs1Pdst = Output(UInt(LogNumPhys.W))
    val rs2Pdst = Output(UInt(LogNumPhys.W))
    val rs1Ready = Output(Bool())
    val rs2Ready = Output(Bool())

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
  })

  val mapTable = RegInit(VecInit(
    (0 until NumLogicalRegs).map(_.U(LogNumPhys.W))))
  val ready = RegInit(VecInit(Seq.fill(NumLogicalRegs)(true.B)))

  val rs1Pdst = mapTable(io.rs1)
  val rs2Pdst = mapTable(io.rs2)
  // x0 永远是 0 且 ready
  io.rs1Pdst  := Mux(io.rs1 === 0.U, 0.U, rs1Pdst)
  io.rs2Pdst  := Mux(io.rs2 === 0.U, 0.U, rs2Pdst)
  io.rs1Ready := Mux(io.rs1 === 0.U, true.B, ready(io.rs1))
  io.rs2Ready := Mux(io.rs2 === 0.U, true.B, ready(io.rs2))

  io.stalePdst := mapTable(io.rd)

  // 更新映射
  when(io.update && io.writesReg && io.rd =/= 0.U) {
    mapTable(io.rd) := io.newPdst
    ready(io.rd)    := false.B
  }

  // 回滚
  when(io.rollback.valid && io.rollback.bits.lrd =/= 0.U) {
    mapTable(io.rollback.bits.lrd) := io.rollback.bits.pdst
    ready(io.rollback.bits.lrd)    := true.B
  }
}
