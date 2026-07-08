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
  val OP_LOAD = 0x03
  val OP_STORE = 0x23

  def addi(rd: Int, rs1: Int, imm: Int): BigInt = {
    val i = imm & 0xFFF
    ((i << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_IMM)
  }
  def add(rd: Int, rs1: Int, rs2: Int): BigInt = {
    ((0 << 25) | (rs2 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_REG)
  }
  def lw(rd: Int, rs1: Int, imm: Int): BigInt = {
    val i = imm & 0xFFF
    ((i << 20) | (rs1 << 15) | (2 << 12) | (rd << 7) | OP_LOAD)
  }
  def sw(rs2: Int, rs1: Int, imm: Int): BigInt = {
    val i = imm & 0xFFF
    val immHi = (i >> 5) & 0x7F
    val immLo = i & 0x1F
    ((immHi << 25) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | (immLo << 7) | OP_STORE)
  }
  def lui(rd: Int, imm: Int): BigInt = {
    ((imm & 0xFFFFF) << 12) | (rd << 7) | OP_LUI
  }
  def jal(rd: Int, imm: Int): BigInt = {
    val i = imm & 0x1FFFFF
    val bit20 = (i >> 20) & 0x1
    val bits10_1 = (i >> 1) & 0x3FF
    val bit11 = (i >> 11) & 0x1
    val bits19_12 = (i >> 12) & 0xFF
    (bit20 << 31) | (bits19_12 << 12) | (bit11 << 20) |
      (bits10_1 << 21) | (rd << 7) | 0x6f
  }
  def csr(funct3: Int, rd: Int, csrAddr: Int, rs1OrZimm: Int): BigInt = {
    ((csrAddr & 0xFFF) << 20) | ((rs1OrZimm & 0x1F) << 15) |
      ((funct3 & 0x7) << 12) | (rd << 7) | 0x73
  }
  def ecall: BigInt = 0x00000073L
  def mret: BigInt = 0x30200073L
  val NOP_LIT: BigInt = 0x00000013L

  it should "execute ADDI then commit" in {
    test(new Core).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.timerInterrupt.poke(false.B)
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

  it should "execute store, load, and dependent ALU operations" in {
    test(new Core) { c =>
      c.io.timerInterrupt.poke(false.B)
      val prog = Array[BigInt](
        addi(1, 0, 0x100), // base
        addi(2, 0, 42),    // value
        sw(2, 1, 0),
        lw(3, 1, 0),
        addi(4, 3, 1)      // depends on loaded value
      )
      val dmem = scala.collection.mutable.Map[BigInt, BigInt]().withDefaultValue(0)
      var pendingReadAddr = BigInt(0)
      var commits = scala.collection.mutable.ListBuffer[(Int, BigInt)]()

      for (cycle <- 0 until 100) {
        val addr = c.io.imem.addr.peek().litValue
        val idx = (addr / 4).toInt
        val inst = if (idx >= 0 && idx < prog.length) prog(idx) else NOP_LIT
        c.io.imem.inst.poke(inst.U)
        c.io.dmem.rdata.poke(dmem(pendingReadAddr).U)

        if (c.io.dmem.wen.peek().litToBoolean) {
          val waddr = c.io.dmem.addr.peek().litValue & BigInt("fffffffc", 16)
          val wdata = c.io.dmem.wdata.peek().litValue
          dmem(waddr) = wdata
        } else {
          pendingReadAddr = c.io.dmem.addr.peek().litValue & BigInt("fffffffc", 16)
        }

        if (c.io.dbgCommitValid.peek().litToBoolean &&
            c.io.dbgCommitWritesReg.peek().litToBoolean) {
          commits += ((c.io.dbgCommitRd.peek().litValue.toInt,
                       c.io.dbgCommitData.peek().litValue))
        }

        c.clock.step(1)
      }

      assert(dmem(0x100) == 42, s"store should write 42, dmem: ${dmem}")
      assert(commits.find(_._1 == 3).exists(_._2 == 42),
        s"x3 should load 42, commits: ${commits}")
      assert(commits.find(_._1 == 4).exists(_._2 == 43),
        s"x4 should be 43, commits: ${commits}")
    }
  }

  it should "commit JAL with link data and redirect metadata" in {
    test(new Core) { c =>
      c.io.timerInterrupt.poke(false.B)
      val prog = Array[BigInt](
        jal(1, 8),        // x1 = pc + 4, target pc = 8
        addi(2, 0, 1),    // should be flushed after the JAL redirect
        addi(3, 0, 7)
      )

      c.io.dmem.rdata.poke(0.U)
      var sawJal = false
      var commits = scala.collection.mutable.ListBuffer[(Int, BigInt)]()

      for (_ <- 0 until 90) {
        val addr = c.io.imem.addr.peek().litValue
        val idx = (addr / 4).toInt
        val inst = if (idx >= 0 && idx < prog.length) prog(idx) else NOP_LIT
        c.io.imem.inst.poke(inst.U)

        if (c.io.dbgCommitValid.peek().litToBoolean) {
          val pc = c.io.dbgCommit.pc.peek().litValue
          if (pc == 0) {
            sawJal = true
            assert(c.io.dbgCommit.taken.peek().litToBoolean,
              "JAL commit should report taken=true")
            assert(c.io.dbgCommit.target.peek().litValue == 8,
              s"JAL target should be 8, got ${c.io.dbgCommit.target.peek().litValue}")
          }
          if (c.io.dbgCommitWritesReg.peek().litToBoolean) {
            commits += ((c.io.dbgCommitRd.peek().litValue.toInt,
                         c.io.dbgCommitData.peek().litValue))
          }
        }

        c.clock.step(1)
      }

      assert(sawJal, "JAL should commit")
      assert(commits.find(_._1 == 1).exists(_._2 == 4),
        s"x1 should receive link pc+4, commits: ${commits}")
      assert(!commits.exists(_._1 == 2),
        s"x2 should be flushed by the JAL redirect, commits: ${commits}")
      assert(commits.find(_._1 == 3).exists(_._2 == 7),
        s"x3 should execute at the JAL target, commits: ${commits}")
    }
  }

  it should "trap on ECALL and return with MRET" in {
    test(new Core) { c =>
      c.io.timerInterrupt.poke(false.B)
      val CSRRW = 1
      val CSRRS = 2
      val prog = Array[BigInt](
        addi(1, 0, 0x20),        // 0x00: trap vector
        csr(CSRRW, 0, 0x305, 1), // 0x04: csrw mtvec, x1
        ecall,                   // 0x08: trap to 0x20
        addi(3, 0, 7),           // 0x0c: resumed code
        NOP_LIT,                 // 0x10
        NOP_LIT,                 // 0x14
        NOP_LIT,                 // 0x18
        NOP_LIT,                 // 0x1c
        csr(CSRRS, 2, 0x341, 0), // 0x20: csrr x2, mepc
        addi(2, 2, 4),           // 0x24: skip ecall
        csr(CSRRW, 0, 0x341, 2), // 0x28: csrw mepc, x2
        mret                     // 0x2c
      )

      c.io.dmem.rdata.poke(0.U)
      var commits = scala.collection.mutable.ListBuffer[(Int, BigInt)]()

      for (_ <- 0 until 120) {
        val addr = c.io.imem.addr.peek().litValue
        val idx = (addr / 4).toInt
        val inst = if (idx >= 0 && idx < prog.length) prog(idx) else NOP_LIT
        c.io.imem.inst.poke(inst.U)

        if (c.io.dbgCommitValid.peek().litToBoolean &&
            c.io.dbgCommitWritesReg.peek().litToBoolean) {
          commits += ((c.io.dbgCommitRd.peek().litValue.toInt,
                       c.io.dbgCommitData.peek().litValue))
        }

        c.clock.step(1)
      }

      assert(commits.find(_._1 == 2).exists(_._2 == 8),
        s"x2 should observe mepc=8, commits: ${commits}")
      assert(commits.find(_._1 == 3).exists(_._2 == 7),
        s"x3 should execute after MRET, commits: ${commits}")
    }
  }

  it should "take a machine timer interrupt at an instruction boundary" in {
    test(new Core) { c =>
      val CSRRW = 1
      val CSRRS = 2
      val prog = Array[BigInt](
        addi(1, 0, 0x40),        // 0x00: timer vector
        csr(CSRRW, 0, 0x305, 1), // 0x04: mtvec = 0x40
        addi(1, 0, 0x80),        // 0x08: mie.MTIE
        csr(CSRRW, 0, 0x304, 1), // 0x0c
        addi(1, 0, 0x8),         // 0x10: mstatus.MIE
        csr(CSRRW, 0, 0x300, 1), // 0x14
        addi(5, 0, 1),           // 0x18: interrupted PC should be here or later
        addi(6, 0, 2),           // 0x1c
        jal(0, 0),                // 0x20: wait for interrupt
        NOP_LIT, NOP_LIT, NOP_LIT,
        NOP_LIT, NOP_LIT, NOP_LIT, NOP_LIT,
        csr(CSRRS, 10, 0x342, 0), // 0x40: mcause
        csr(CSRRS, 11, 0x341, 0), // 0x44: mepc
        addi(12, 0, 3)            // 0x48: marker
      )

      c.io.dmem.rdata.poke(0.U)
      c.io.timerInterrupt.poke(false.B)
      var commits = scala.collection.mutable.ListBuffer[(Int, BigInt)]()
      var pendingSeen = false
      var fireSeen = false

      for (cycle <- 0 until 120) {
        val addr = c.io.imem.addr.peek().litValue
        val idx = (addr / 4).toInt
        val inst = if (idx >= 0 && idx < prog.length) prog(idx) else NOP_LIT
        c.io.imem.inst.poke(inst.U)
        c.io.timerInterrupt.poke((cycle >= 18).B)
        pendingSeen ||= c.io.dbgTimerPending.peek().litToBoolean
        fireSeen ||= c.io.dbgInterruptFire.peek().litToBoolean

        if (c.io.dbgCommitValid.peek().litToBoolean &&
            c.io.dbgCommitWritesReg.peek().litToBoolean) {
          commits += ((c.io.dbgCommitRd.peek().litValue.toInt,
                       c.io.dbgCommitData.peek().litValue))
        }

        c.clock.step(1)
      }

      assert(pendingSeen, s"timer pending should become visible, commits: ${commits}")
      assert(fireSeen, s"timer interrupt should fire, commits: ${commits}")
      assert(commits.exists { case (rd, data) => rd == 10 && data == BigInt("80000007", 16) },
        s"x10 should observe timer interrupt mcause, commits: ${commits}")
      assert(commits.exists { case (rd, data) => rd == 11 && data >= 0x18 && data < 0x40 },
        s"x11 should observe interrupted mepc in mainline, commits: ${commits}")
      assert(commits.find(_._1 == 12).exists(_._2 == 3),
        s"x12 should execute timer handler marker, commits: ${commits}")
    }
  }
}
