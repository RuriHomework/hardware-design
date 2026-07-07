package core.backend.units

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * LSU：访存单元。
 *
 * 流程（读延迟 1 拍）：
 *   周期 N (sReq)：算地址 = rs1 + imm，发地址给 DMem，寄存 uop/byteOff
 *   周期 N+1 (sResp)：DMem rdata 回来，按 uop + byteOff 做符号扩展，done
 * 写：周期 N 单周期完成，done 即拉高
 *
 * 单槽位，busy 时 Issue 不能派发新指令。
 */
class Lsu extends Module {
  val io = IO(new Bundle {
    val cmd = Input(Valid(new Bundle {
      val uop = Uop()
      val a   = UInt(XLen.W)   // rs1
      val b   = UInt(XLen.W)   // rs2 (store data)
      val imm = SInt(ImmWidth.W)
    }))

    val dmem = new Bundle {
      val addr  = Output(UInt(PcWidth.W))
      val wdata = Output(UInt(XLen.W))
      val wmask = Output(UInt(4.W))
      val wen   = Output(Bool())
      val rdata = Input(UInt(XLen.W))
    }

    val result = Output(UInt(XLen.W))
    val done   = Output(Bool())
    val busy   = Output(Bool())
  })

  val sIdle :: sResp :: Nil = Enum(2)
  val state    = RegInit(sIdle)
  val uopReg   = RegInit(Uop.NOP)
  val byteOffReg = RegInit(0.U(2.W))

  // 地址计算
  val addr = io.cmd.bits.a + io.cmd.bits.imm.asUInt
  val byteOff = addr(1, 0)

  // 写数据对齐 + 字节使能（针对当前 cmd）
  val shamt = byteOff << 3
  val wdataAligned = io.cmd.bits.b << shamt
  val wmask = WireDefault(0.U(4.W))
  switch(io.cmd.bits.uop) {
    is(SB) { wmask := 1.U << byteOff }
    is(SH) { wmask := Mux(byteOff(1), "b1100".U, "b0011".U) }
    is(SW) { wmask := "b1111".U }
  }

  // 默认输出
  io.dmem.addr  := 0.U
  io.dmem.wdata := wdataAligned
  io.dmem.wmask := wmask
  io.dmem.wen   := false.B
  io.result := 0.U
  io.done   := false.B
  io.busy   := state =/= sIdle

  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
        uopReg := io.cmd.bits.uop
        when(UopKind.isStore(io.cmd.bits.uop)) {
          // store：当周期写，单周期完成
          io.dmem.addr := addr
          io.dmem.wen  := true.B
          io.done := true.B
        }.elsewhen(UopKind.isLoad(io.cmd.bits.uop)) {
          // load：发地址，下拍收数据
          io.dmem.addr  := addr
          byteOffReg    := byteOff
          state := sResp
        }
      }
    }
    is(sResp) {
      // DMem rdata 已到，按 byteOff 做扩展
      val rdata = io.dmem.rdata
      val off = byteOffReg
      val byteSel = off(1, 0)
      val byteVal = (rdata >> (byteSel << 3))(7, 0)
      val halfSel = off(1)
      val halfVal = (rdata >> (halfSel << 4))(15, 0)

      switch(uopReg) {
        is(LB)  { io.result := Cat(Fill(24, byteVal(7)),  byteVal) }
        is(LBU) { io.result := byteVal }
        is(LH)  { io.result := Cat(Fill(16, halfVal(15)), halfVal) }
        is(LHU) { io.result := halfVal }
        is(LW)  { io.result := rdata }
      }
      io.done := true.B
      state := sIdle
    }
  }
}

object Lsu {
  def accepts(u: Uop.Type): Bool = UopKind.isMem(u) || u === FENCE
}
