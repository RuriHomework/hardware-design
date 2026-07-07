package cpu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import top._

/**
 * Core 集成测试：直接喂指令流，检查 commit 行为。
 *
 * 不实例化 IMem/DMem——通过 io.imem.inst 喂指令，
 * DMem rdata 喂 0（store 不检查，load 测试单独做）。
 *
 * 目标：验证前端→后端→commit 数据流贯通，ALU 算术正确。
 */
class CoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Core"

  // RISC-V 指令编码辅助（纯 Scala 位运算，返回 BigInt，poke 时用）
  val OP_IMM  = 0x13
  val OP_REG  = 0x33
  val OP_LUI  = 0x37

  def addi(rd: Int, rs1: Int, imm: Int): BigInt = {
    val i = imm & 0xFFF
    ((i << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_IMM)
  }
  def add(rd: Int, rs1: Int, rs2: Int): BigInt = {
    ((0 << 25) | (rs2 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_REG)
  }
  def lui(rd: Int, imm: Int): BigInt = {
    ((imm & 0xFFFFF) << 12) | (rd << 7) | OP_LUI
  }
  val NOP_LIT: BigInt = 0x00000013L

  it should "execute ADDI then commit" in {
    test(new Core).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      // 程序：x1=42, x2=52, x3=94
      val prog = Array[BigInt](
        addi(1, 0, 42),   // x1 = 42
        addi(2, 1, 10),   // x2 = x1 + 10 = 52
        add(3, 1, 2)      // x3 = x1 + x2 = 94
      )

      // DMem 读返回 0
      c.io.dmem.rdata.poke(0.U)

      // 跟踪 commit：记录每条指令 commit 时的 (rd, data)
      var commits = scala.collection.mutable.ListBuffer[(Int, BigInt)]()

      // 喂指令 + 跑足够多拍
      for (cycle <- 0 until 60) {
        val addr = c.io.imem.addr.peek().litValue
        val idx = (addr / 4).toInt
        val inst = if (idx >= 0 && idx < prog.length) prog(idx) else NOP_LIT
        c.io.imem.inst.poke(inst.U)

        val commitValid = c.io.dbgCommitValid.peek().litToBoolean
        val commitWrites = c.io.dbgCommitWritesReg.peek().litToBoolean
        // 检查上一拍 commit（在 step 前）
        if (commitValid && commitWrites) {
          val rd = c.io.dbgCommitRd.peek().litValue.toInt
          val data = c.io.dbgCommitData.peek().litValue
          commits += ((rd, data))
        }

        c.clock.step(1)
      }

      // 应该有 3 条写寄存器的 commit
      assert(commits.length >= 3, s"Expected >=3 commits, got ${commits.length}")
      // x1 = 42
      assert(commits.find(_._1 == 1).exists(_._2 == 42),
        s"x1 should be 42, commits: ${commits}")
      // x2 = 52
      assert(commits.find(_._1 == 2).exists(_._2 == 52),
        s"x2 should be 52, commits: ${commits}")
      // x3 = 94
      assert(commits.find(_._1 == 3).exists(_._2 == 94),
        s"x3 should be 94, commits: ${commits}")
    }
  }
}
