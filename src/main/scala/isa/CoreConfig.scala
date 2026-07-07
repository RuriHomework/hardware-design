package isa

import chisel3._
import chisel3.util._

object CoreConfig {
  // ===== 机器宽度 =====
  val XLen = 32

  // ===== 寄存器堆 =====
  val NumLogicalRegs  = 32
  val LogNumLogical   = log2Ceil(NumLogicalRegs)    // 5

  // 物理寄存器堆：32 逻辑 + ROB 容量的物理寄存器，供 rename 重映射
  val NumPhysRegs     = 64
  val LogNumPhys      = log2Ceil(NumPhysRegs)       // 6

  // ===== ROB / 乱序窗口 =====
  val RobEntries      = 16
  val LogRobEntries   = log2Ceil(RobEntries)
  val RobIdWidth      = LogRobEntries

  // ===== Issue Queue =====
  val IssueEntries    = 8
  val LogIssueEntries = log2Ceil(IssueEntries)

  // ===== 分支预测 =====
  val BtbEntries      = 16
  val BhtEntries      = 64
  val RasEntries      = 4

  // ===== I/D 内存 =====
  val IMemDepth       = 4096      // 4K 条指令 = 16 KB
  val DMemDepth       = 4096      // 4K 字 = 16 KB

  // ===== 总线宽度（uop 中立即数、寄存器号携带位） =====
  val ImmWidth        = 32
  val PcWidth         = 32
}

import CoreConfig._

/** Uop 枚举：内部微指令，所有执行单元共享 */
object Uop extends ChiselEnum {
  // ---- ALU ----
  val ADD, SUB, SLT, SLTU = Value
  val XOR, OR, AND        = Value
  val SLL, SRL, SRA       = Value
  val LUI, AUIPC          = Value
  // ---- BRU：分支解析 ----
  val BEQ, BNE, BLT, BGE, BLTU, BGEU = Value
  val JAL, JALR                     = Value
  // ---- LSU ----
  val LW, LH, LHU, LB, LBU = Value
  val SW, SH, SB           = Value
  val FENCE                = Value
  // ---- M 扩展 ----
  val MUL, MULH, MULHSU, MULHU = Value
  val DIV, DIVU, REM, REMU     = Value
  // ---- 系统级 ----
  val ECALL, EBREAK = Value
  val NOP            = Value
}

/** 立即数类型，对应 RISC-V 6 种格式 */
object ImmType extends ChiselEnum {
  val R, I, S, B, U, J = Value
}

/** 访存宽度 */
object MemOp extends ChiselEnum {
  val BYTE, HALF, WORD = Value
}

/** 条件码复用帮助函数：Uop → 执行端口子类型 */
object UopKind {
  def isBranch(u: Uop.Type): Bool = {
    import Uop._
    u === BEQ || u === BNE || u === BLT || u === BGE ||
    u === BLTU || u === BGEU || u === JAL || u === JALR
  }
  def isJump(u: Uop.Type): Bool = u === Uop.JAL || u === Uop.JALR
  def isLoad(u: Uop.Type): Bool = {
    import Uop._
    u === LW || u === LH || u === LHU || u === LB || u === LBU
  }
  def isStore(u: Uop.Type): Bool = {
    import Uop._
    u === SW || u === SH || u === SB
  }
  def isMul(u: Uop.Type): Bool = {
    import Uop._
    u === MUL || u === MULH || u === MULHSU || u === MULHU
  }
  def isDiv(u: Uop.Type): Bool = {
    import Uop._
    u === DIV || u === DIVU || u === REM || u === REMU
  }
  def isMem(u: Uop.Type): Bool = isLoad(u) || isStore(u)
  def writesReg(u: Uop.Type): Bool = !isStore(u) && u =/= Uop.ECALL &&
    u =/= Uop.EBREAK && u =/= Uop.FENCE && u =/= Uop.NOP
}
