package core.frontend

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

/**
 * 分支预测子系统：BTB + BHT(2 位饱和) + RAS。
 *
 * 查询接口（Fetch 阶段每周期调用）：
 *   pc → predictedTaken, predictedTarget
 *
 * 更新接口（Commit 阶段反馈实际结果）：
 *   pc, uop, taken, target, isCall, isRet, retAddr
 *
 * 策略：
 *   - JAL：BTB 直接给目标（必然 taken）
 *   - JALR：RAS 预测返回地址（call/ret 识别）
 *   - 条件分支：BHT 2 位饱和计数器
 *   - 其他：顺序执行
 */
class BranchPredictor extends Module {
  val io = IO(new Bundle {
    // 查询
    val query = new Bundle {
      val pc     = Input(UInt(PcWidth.W))
      val taken  = Output(Bool())
      val target = Output(UInt(PcWidth.W))
    }
    // 更新（来自 commit）— valid 由外层包裹
    val updateValid = Input(Bool())
    val update = Input(new RetireInfo)
    // RAS 推/弹
    val rasPush = Input(Bool())
    val rasPop  = Input(Bool())
    val rasData = Input(UInt(PcWidth.W))
    // 预测阶段对 JAL/JALR 的标记（由 Fetch 根据指令给出）
    val queryIsCall = Input(Bool())
    val queryIsRet  = Input(Bool())
    val queryTarget = Input(UInt(PcWidth.W))  // JAL 静态目标（pc+imm），BTB miss 时用
  })

  class BtbEntry extends Bundle {
    val tag    = UInt((PcWidth - log2Ceil(BtbEntries) - 2).W)
    val target = UInt(PcWidth.W)
    val valid  = Bool()
  }

  // ---- BTB：直接映射，tag = PC 高位 ----
  val btb = RegInit(VecInit(Seq.fill(BtbEntries)(0.U.asTypeOf(new BtbEntry))))
  // BHT 用寄存器组合读以让预测在同一周期完成。
  val bht = RegInit(VecInit(Seq.fill(BhtEntries)(1.U(2.W))))
  val btbRead = btb(io.query.pc(log2Ceil(BtbEntries) + 1, 2))

  // ---- RAS ----
  val ras = RegInit(VecInit(Seq.fill(RasEntries)(0.U(PcWidth.W))))
  val rasPtr = RegInit(0.U(log2Ceil(RasEntries).W))

  // 查询逻辑
  val btbHit = btbRead.valid && btbRead.tag === io.query.pc(PcWidth - 1, log2Ceil(BtbEntries) + 2)
  val bhtIdx = io.query.pc(log2Ceil(BhtEntries) + 1, 2)
  val bhtVal = bht(bhtIdx)
  val bhtTaken = bhtVal(1)  // 2 位饱和：>= 2 视为 taken

  // RAS 预测返回地址
  val rasTop = ras(rasPtr - 1.U)

  // 综合。当前 Fetch 只能同周期提供 PC，指令类型来自后一拍译码，因此默认只用
  // 以 PC 索引的 BTB/BHT 预测；RAS 通道保留给独立测试和后续前端重构。
  val predTaken  = WireDefault(false.B)
  val predTarget = WireDefault(io.query.pc + 4.U)

  // 控制流统一使用 BHT 方向 + BTB 目标。JAL/JALR 在 commit 后也会训练为 taken。
  predTaken := bhtTaken && btbHit  // 简化：BTB hit 才用 BHT 结果
  predTarget := Mux(btbHit, btbRead.target, io.query.pc + 4.U)

  // ret：用 RAS
  when(io.queryIsRet) {
    predTaken  := rasPtr =/= 0.U
    predTarget := rasTop
  }
  // call：只有调用方明确说明当前 query PC 是 call 时才用静态目标。
  when(io.queryIsCall) {
    predTaken  := true.B
    predTarget := Mux(btbHit, btbRead.target, io.queryTarget)
  }

  io.query.taken  := predTaken
  io.query.target := predTarget

  // ---- 更新逻辑（commit 时） ----
  when(io.updateValid) {
    val updateIsControl = UopKind.isBranch(io.update.uop) || UopKind.isJump(io.update.uop)

    // BHT 更新：2 位饱和
    val idx = io.update.pc(log2Ceil(BhtEntries) + 1, 2)
    val old = bht(idx)
    val next = Mux(io.update.taken,
      Mux(old === 3.U, 3.U, old + 1.U),
      Mux(old === 0.U, 0.U, old - 1.U))
    when(updateIsControl) {
      bht(idx) := next
    }

    // BTB 更新：taken 的分支/JAL/JALR 写入目标
    when(updateIsControl && io.update.taken) {
      val entry = Wire(new BtbEntry)
      entry.tag    := io.update.pc(PcWidth - 1, log2Ceil(BtbEntries) + 2)
      entry.target := io.update.target
      entry.valid  := true.B
      btb(io.update.pc(log2Ceil(BtbEntries) + 1, 2)) := entry
    }
  }

  // ---- RAS 维护 ----
  // 预测阶段：call 压栈，ret 弹栈（投机）
  when(io.rasPush) {
    ras(rasPtr) := io.rasData
    rasPtr := Mux(rasPtr === (RasEntries - 1).U, 0.U, rasPtr + 1.U)
  }.elsewhen(io.rasPop && rasPtr =/= 0.U) {
    rasPtr := rasPtr - 1.U
  }
  // commit 阶段误预测修正 RAS：此处简化——mispred 时复位 rasPtr
  // 完整实现应 checkpoint RAS，留作扩展点
}
