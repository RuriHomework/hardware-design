package core.backend.units

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * M 扩展执行单元：MUL / MULH / MULHSU / MULHU / DIV / DIVU / REM / REMU
 *
 * 资源权衡：
 *   - 乘法用单周期 DSP（XLen*XLen → 取低 64 位），7010 有 80 个 DSP 足够
 *   - 除法用迭代 SRT 简化版（32 周期非恢复余数法），节省面积
 *   - 对课程项目足够，后续可换成 4 周期 radix-4 SRT
 *
 * 接口：valid/cmd 启动一次运算，done 拉高时 result 有效。
 * 单槽位，busy 时 Issue 不能派发新指令进来。
 */
class MulDiv extends Module {
  val io = IO(new Bundle {
    val cmd   = Input(Valid(new Bundle {
      val uop = Uop()
      val a   = UInt(XLen.W)
      val b   = UInt(XLen.W)
    }))
    val result = Output(UInt(XLen.W))
    val done   = Output(Bool())
    val busy   = Output(Bool())
  })

  val busy = RegInit(false.B)
  val uop  = RegInit(Uop.NOP)
  val a    = RegInit(0.U(XLen.W))
  val b    = RegInit(0.U(XLen.W))

  // 乘法子结果
  val mulP = WireInit(0.U((2 * XLen).W))
  // MULHU: 无符号×无符号
  mulP := a * b
  // MULHSU: a 有符号, b 无符号
  val aSext = a.asSInt
  val mulHSU = (aSext * b.asSInt).asUInt  // b 视为无符号但参与 SInt 乘法需注意
  // MULH: 有符号×有符号
  val mulH = (aSext * b.asSInt).asUInt

  // 除法：非恢复余数法，32 周期
  val divCount = RegInit(0.U(6.W))
  val dividend = RegInit(0.U(XLen.W))
  val divisor  = RegInit(0.U(XLen.W))
  val quotient = RegInit(0.U(XLen.W))
  val remainder= RegInit(0.U(XLen.W))
  val negQ     = RegInit(false.B)
  val negR     = RegInit(false.B)
  val isDiv    = RegInit(false.B)  // 区分 div/rem 与 mul

  // 默认输出
  io.result := 0.U
  io.done   := false.B

  // 启动
  when(!busy && io.cmd.valid) {
    busy := true.B
    uop  := io.cmd.bits.uop
    a    := io.cmd.bits.a
    b    := io.cmd.bits.b
    when(UopKind.isMul(io.cmd.bits.uop)) {
      isDiv := false.B
    }.otherwise {
      isDiv := true.B
      // 除法预处理：取绝对值
      val aSigned = io.cmd.bits.uop === DIV || io.cmd.bits.uop === REM
      val bSigned = io.cmd.bits.uop === DIV || io.cmd.bits.uop === REM
      val aAbs = Mux(aSigned && io.cmd.bits.a(XLen - 1),
                     (~io.cmd.bits.a).asUInt + 1.U, io.cmd.bits.a)
      val bAbs = Mux(bSigned && io.cmd.bits.b(XLen - 1),
                     (~io.cmd.bits.b).asUInt + 1.U, io.cmd.bits.b)
      dividend  := aAbs
      divisor   := bAbs
      negQ      := aSigned && io.cmd.bits.a(XLen - 1) ^ (bSigned && io.cmd.bits.b(XLen - 1))
      negR      := aSigned && io.cmd.bits.a(XLen - 1)
      divCount  := 0.U
      quotient  := 0.U
      remainder := 0.U
    }
  }

  // 运行中
  when(busy) {
    when(!isDiv) {
      // 乘法：单周期完成
      io.done := true.B
      switch(uop) {
        is(MUL)    { io.result := mulP(XLen - 1, 0) }
        is(MULH)   { io.result := mulH(2 * XLen - 1, XLen) }
        is(MULHSU) { io.result := mulHSU(2 * XLen - 1, XLen) }
        is(MULHU)  { io.result := mulP(2 * XLen - 1, XLen) }
      }
      busy := false.B
    }.otherwise {
      // 除法迭代：每周期移一位
      when(divisor === 0.U) {
        // 除零：RISC-V 规定 -1 / 0 = -1，x % 0 = x
        io.done := true.B
        switch(uop) {
          is(DIV)  { io.result := "hFFFFFFFF".U }
          is(DIVU) { io.result := "hFFFFFFFF".U }
          is(REM)  { io.result := a }
          is(REMU) { io.result := a }
        }
        busy := false.B
      }.elsewhen(divCount === XLen.U) {
        // 完成
        io.done := true.B
        val qFinal = Mux(negQ, (~quotient).asUInt + 1.U, quotient)
        val rFinal = Mux(negR, (~remainder).asUInt + 1.U, remainder)
        // 溢出特例：INT_MIN / -1 → INT_MIN（商），余数 0
        val overflow = (a === "h80000000".U) && (b === "hFFFFFFFF".U)
        switch(uop) {
          is(DIV)  { io.result := Mux(overflow, "h80000000".U, qFinal) }
          is(DIVU) { io.result := quotient }
          is(REM)  { io.result := Mux(overflow, 0.U, rFinal) }
          is(REMU) { io.result := remainder }
        }
        busy := false.B
      }.otherwise {
        // 非恢复余数迭代：把 dividend 最高位移入 remainder，试减
        val newRem = Cat(remainder, dividend(XLen - 1))
        val sub = newRem - divisor
        when(sub(XLen) === 0.U) {
          remainder := sub(XLen - 1, 0)
          quotient  := Cat(quotient, 1.U(1.W))
        }.otherwise {
          remainder := newRem(XLen - 1, 0)
          quotient  := Cat(quotient, 0.U(1.W))
        }
        dividend := Cat(dividend(XLen - 2, 0), 0.U(1.W))
        divCount := divCount + 1.U
      }
    }
  }

  io.busy := busy
}

object MulDiv {
  def accepts(u: Uop.Type): Bool = UopKind.isMul(u) || UopKind.isDiv(u)
}
