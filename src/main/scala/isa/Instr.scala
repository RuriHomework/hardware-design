package isa

import chisel3._
import chisel3.util._

import CoreConfig._
import Uop._

/**
 * RV32IM 指令编码——按 RISC-V 规范固定布局。
 * 仅用于 Decoder 查询 opcode/字段切分，不引入运行期开销。
 */
object Instr {

  // ===== opcode (inst[6:0]) =====
  val OP_LUI    = "b0110111".U(7.W)
  val OP_AUIPC  = "b0010111".U(7.W)
  val OP_JAL    = "b1101111".U(7.W)
  val OP_JALR   = "b1100111".U(7.W)
  val OP_BRANCH = "b1100011".U(7.W)
  val OP_LOAD   = "b0000011".U(7.W)
  val OP_STORE  = "b0100011".U(7.W)
  val OP_IMM    = "b0010011".U(7.W)
  val OP_REG    = "b0110011".U(7.W)
  val OP_FENCE  = "b0001111".U(7.W)
  val OP_SYSTEM = "b1110011".U(7.W)

  // 字段切片
  def opcode(i: UInt): UInt = i(6, 0)
  def rd     (i: UInt): UInt = i(11, 7)
  def funct3 (i: UInt): UInt = i(14, 12)
  def rs1    (i: UInt): UInt = i(19, 15)
  def rs2    (i: UInt): UInt = i(24, 20)
  def funct7 (i: UInt): UInt = i(31, 25)

  // ===== 立即数生成（按规范拼接） =====
  def immI(i: UInt): SInt = {
    val sign = i(31)
    val bits = Cat(sign, i(31, 20))
    bits.asSInt
  }
  def immS(i: UInt): SInt = {
    val sign = i(31)
    val bits = Cat(sign, i(31, 25), i(11, 7))
    bits.asSInt
  }
  def immB(i: UInt): SInt = {
    val sign = i(31)
    val bits = Cat(sign, i(7), i(30, 25), i(11, 8), 0.U(1.W))
    bits.asSInt
  }
  def immU(i: UInt): SInt = Cat(i(31, 12), 0.U(12.W)).asSInt
  def immJ(i: UInt): SInt = {
    val sign = i(31)
    val bits = Cat(sign, i(19, 12), i(20), i(30, 21), 0.U(1.W))
    bits.asSInt
  }

  // ===== 一条 32 位 NOP (addi x0,x0,0) =====
  val NOP = "h00000013".U(32.W)
}

/** 非法/未知指令异常（目前只做单发，记号位，后续可扩展 trap） */
class IllegalInstrException extends Bundle {
  val pc   = UInt(PcWidth.W)
  val inst = UInt(32.W)
}
