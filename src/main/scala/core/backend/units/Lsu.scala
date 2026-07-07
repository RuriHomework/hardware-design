package core.backend.units

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * LSU：访存单元，单周期地址计算 + 一拍 BRAM 访问。
 *
 * 流程：
 *   1. cmd 启动：算地址 = rs1 + imm
 *   2. 读：向 DMem 发地址，下一拍 rdata 回来，按 uop 做符号扩展
 *   3. 写：同周期把 wdata + wmask 发给 DMem
 *
 * 单槽位，busy 时 Issue 不能派发新指令。
 * 读延迟 1 → done 在启动后第 2 拍拉高。
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

  // 状态：1 拍发地址，1 拍等数据
  val sIdle :: sReadWait :: sDone :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val uopReg = RegInit(Uop.NOP)

  val addr = io.cmd.bits.a + io.cmd.bits.imm.asUInt

  // 字节使能
  val byteEn = WireDefault(0.U(4.W))
  val wdataAligned = WireDefault(0.U(XLen.W))
  switch(io.cmd.bits.uop) {
    is(SB) { byteEn := 1.U << io.cmd.bits.a(1, 0) }
    is(SH) {
      byteEn := Mux(io.cmd.bits.a(1), "b1100".U, "b0011".U)
    }
    is(SW) { byteEn := "b1111".U }
  }
  // 对齐写数据
  val shamt = io.cmd.bits.a(1, 0) << 3
  wdataAligned := io.cmd.bits.b << shamt

  io.dmem.addr  := 0.U
  io.dmem.wdata := wdataAligned
  io.dmem.wmask := byteEn
  io.dmem.wen   := false.B
  io.result := 0.U
  io.done   := false.B
  io.busy   := state =/= sIdle

  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
        uopReg := io.cmd.bits.uop
        when(UopKind.isStore(io.cmd.bits.uop)) {
          // store：当周期写
          io.dmem.addr  := addr
          io.dmem.wen   := true.B
          io.done := true.B
          // 单周期完成，回到 idle
        }.elsewhen(UopKind.isLoad(io.cmd.bits.uop)) {
          io.dmem.addr  := addr
          state := sReadWait
        }
      }
    }
    is(sReadWait) {
      // 本周期 rdata 来自 DMem，做扩展
      val rdata = io.dmem.rdata
      val off = RegNext(io.cmd.bits.a(1, 0))  // 注：cmd 在 idle 时有效，这里读 reg
      val byteOff = addr(1, 0)
      val extRes = WireDefault(0.U(XLen.W))
      switch(uopReg) {
        is(LB) {
          val b = rdata(7, 0)
          extRes := Cat(Fill(24, b(7)), b)
        }
        is(LBU) { extRes := rdata(7, 0) }
        is(LH) {
          val h = rdata(15, 0)
          extRes := Cat(Fill(16, h(15)), h)
        }
        is(LHU) { extRes := rdata(15, 0) }
        is(LW)  { extRes := rdata }
      }
      io.result := extRes
      io.done := true.B
      state := sIdle
    }
  }
}

object Lsu {
  def accepts(u: Uop.Type): Bool = UopKind.isMem(u) || u === FENCE
}
