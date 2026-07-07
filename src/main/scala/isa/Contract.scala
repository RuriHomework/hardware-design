package isa

import chisel3._
import chisel3.util._

import CoreConfig._
import Uop._

/**
 * 前后端契约 Bundle 集合。
 *
 * 设计原则：
 *   1. 前端只产 uop，后端只吃 uop；前后端不互相依赖实现细节
 *   2. 所有重定向/异常都从后端发往前端，前端不自行决定路径
 *   3. 顺序后端与乱序后端实现同一接口，前端可无感切换
 */

/** 解码后的微指令——前端→后端的主数据流 */
class DecodedInstr extends Bundle {
  // ---- 来源信息 ----
  val pc      = UInt(PcWidth.W)
  val inst    = UInt(32.W)         // 原始指令，便于追溯 / waveform 调试
  val predPc  = UInt(PcWidth.W)    // 预测的下一 PC（前端 BP 给出）

  // ---- 操作码与操作数 ----
  val uop    = Uop()
  val rs1    = UInt(LogNumLogical.W)
  val rs2    = UInt(LogNumLogical.W)
  val rd     = UInt(LogNumLogical.W)
  val imm    = SInt(ImmWidth.W)
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val writesReg = Bool()

  // ---- 预测信息（执行时验证） ----
  val predTaken = Bool()           // BP 认为这条分支要跳
  val predTarget = UInt(PcWidth.W) // BP 预测的目标
}

object DecodedInstr {
  def default: DecodedInstr = {
    val d = Wire(new DecodedInstr)
    d.pc        := 0.U
    d.inst      := Instr.NOP
    d.predPc    := 0.U
    d.uop       := Uop.NOP
    d.rs1       := 0.U
    d.rs2       := 0.U
    d.rd        := 0.U
    d.imm       := 0.S
    d.usesRs1   := false.B
    d.usesRs2   := false.B
    d.writesReg := false.B
    d.predTaken  := false.B
    d.predTarget := 0.U
    d
  }
}

/** 前端→后端的提交反馈：指令实际行为，用于更新 BP/ROB。
 *  valid 由外层 Valid() 包裹提供，本 Bundle 不含 valid 字段。 */
class CommitInfo extends Bundle {
  val pc        = UInt(PcWidth.W)
  val uop       = Uop()
  val taken     = Bool()        // 实际是否跳转（分支/跳转用）
  val target    = UInt(PcWidth.W) // 实际跳转目标
  val mispred   = Bool()        // 是否误预测
  val illegal   = Bool()        // 非法指令
  // RAS 维护：call / ret 语义标记
  val isCall    = Bool()
  val isReturn  = Bool()
  val retAddr   = UInt(PcWidth.W) // call 的返回地址，压栈用
}

/**
 * 重定向通道：后端→前端。
 * valid 由外层 Valid() 包裹提供，本 Bundle 不含 valid 字段。
 */
class Redirect extends Bundle {
  val target = UInt(PcWidth.W)  // 下一条取指 PC
  val robIdx = UInt(RobIdWidth.W) // 触发重定向的 ROB 槽（用于后端内部清理）
  val cause  = RedirectCause()
}

object RedirectCause extends ChiselEnum {
  val NONE, MISPRED, EXCEPTION, FLUSH = Value
}

/** 分配槽：后端→前端，告知能否接收新指令 */
class DispatchAck extends Bundle {
  val ready    = Bool()           // 后端能接收
  val robIdx   = UInt(RobIdWidth.W) // 分配到的 ROB 槽号
  // rename 表给出的物理寄存器映射（后端反馈给前端的元数据，可空）
  val pdst     = UInt(LogNumPhys.W)
  val stalePdst = UInt(LogNumPhys.W) // 旧映射，commit 时释放
}

/** 前端→后端的单发射请求包 */
class DispatchReq extends Bundle {
  val instr = new DecodedInstr
  val valid = Bool()
}

/** commit→前端的 retire 反馈，更新 BTB/RAS 等历史。
 *  valid 由外层 Valid() 包裹提供，本 Bundle 不含 valid 字段。 */
class RetireInfo extends Bundle {
  val pc      = UInt(PcWidth.W)
  val uop     = Uop()
  val taken   = Bool()
  val target  = UInt(PcWidth.W)
  val isCall  = Bool()
  val isRet   = Bool()
  val mispred = Bool()
}

/**
 * 物理寄存器读端口描述：Issue 阶段向 PRF 请求操作数。
 * 一个 uop 最多两个源操作数。
 */
class PrfReadReq extends Bundle {
  val prdIdx = UInt(LogNumPhys.W)
}

class PrfReadResp extends Bundle {
  val data = UInt(XLen.W)
}

/** CDB（Common Data Bus）：所有执行单元结果广播。
 *  valid 由外层 Valid() 包裹提供，本 Bundle 不含 valid 字段。 */
class CdbEntry extends Bundle {
  val robIdx   = UInt(RobIdWidth.W)
  val pdst     = UInt(LogNumPhys.W)
  val data     = UInt(XLen.W)
  val exception = Bool()
}

/** 单发射后端→commit 的执行完成包。
 *  valid 由外层 Valid() 包裹提供，本 Bundle 不含 valid 字段。
 *  taken/target 仅对分支/跳转 uop 有意义，其他 uop 填 false.B/0.U。 */
class WritebackBundle extends Bundle {
  val robIdx   = UInt(RobIdWidth.W)
  val pdst     = UInt(LogNumPhys.W)
  val data     = UInt(XLen.W)
  val cause    = RedirectCause()
  val taken    = Bool()         // 分支/跳转实际是否跳
  val target   = UInt(PcWidth.W) // 实际跳转目标
}
