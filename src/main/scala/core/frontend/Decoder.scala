package isa

import chisel3._
import chisel3.util._

import CoreConfig._
import Uop._
import Instr._

/**
 * RV32IM 译码器：把 32 位指令翻译成 DecodedInstr。
 *
 * 译码是纯组合逻辑，无状态。
 * 输出 DecodedInstr 字段含义见 Contract.scala。
 *
 * 非法指令 → uop = NOP + illegal 标记，instr 字段保留原值供 trap 处理。
 */
class Decoder extends Module {
  val io = IO(new Bundle {
    val inst    = Input(UInt(32.W))
    val pc      = Input(UInt(PcWidth.W))
    val out     = Output(new DecodedInstr)
    val illegal = Output(Bool())
  })

  val inst = io.inst
  val op   = opcode(inst)
  val f3   = funct3(inst)
  val f7b5 = inst(30)            // funct7 第 5 位：区分 ADD/SUB、SRL/SRA

  val out = WireDefault(DecodedInstr.default)
  out.pc      := io.pc
  out.inst    := inst
  out.predPc  := io.pc + 4.U

  val illegal = WireDefault(false.B)

  // 默认操作数来源（R 型）
  out.rs1 := rs1(inst)
  out.rs2 := rs2(inst)
  out.rd  := rd(inst)
  out.zimm := rs1(inst)
  out.imm := 0.S

  // funct3 → uop 的查表辅助：分支/Load/Store/算术共用
  def branchUop(f3: UInt): Uop.Type = {
    val r = WireInit(Uop.NOP)
    switch(f3) {
      is("b000".U) { r := BEQ  }
      is("b001".U) { r := BNE  }
      is("b100".U) { r := BLT  }
      is("b101".U) { r := BGE  }
      is("b110".U) { r := BLTU }
      is("b111".U) { r := BGEU }
    }
    r
  }
  def loadUop(f3: UInt): Uop.Type = {
    val r = WireInit(Uop.NOP)
    switch(f3) {
      is("b000".U) { r := LB  }
      is("b001".U) { r := LH  }
      is("b010".U) { r := LW  }
      is("b100".U) { r := LBU }
      is("b101".U) { r := LHU }
    }
    r
  }
  def storeUop(f3: UInt): Uop.Type = {
    val r = WireInit(Uop.NOP)
    switch(f3) {
      is("b000".U) { r := SB }
      is("b001".U) { r := SH }
      is("b010".U) { r := SW }
    }
    r
  }
  def mExtUop(f3: UInt): Uop.Type = {
    val r = WireInit(Uop.NOP)
    switch(f3) {
      is("b000".U) { r := MUL    }
      is("b001".U) { r := MULH   }
      is("b010".U) { r := MULHSU }
      is("b011".U) { r := MULHU  }
      is("b100".U) { r := DIV    }
      is("b101".U) { r := DIVU   }
      is("b110".U) { r := REM    }
      is("b111".U) { r := REMU   }
    }
    r
  }
  def aluRegUop(f3: UInt, f7b5: Bool): Uop.Type = {
    val r = WireInit(Uop.NOP)
    switch(f3) {
      is("b000".U) { r := Mux(f7b5, SUB, ADD) }
      is("b001".U) { r := SLL  }
      is("b010".U) { r := SLT  }
      is("b011".U) { r := SLTU }
      is("b100".U) { r := XOR  }
      is("b101".U) { r := Mux(f7b5, SRA, SRL) }
      is("b110".U) { r := OR   }
      is("b111".U) { r := AND  }
    }
    r
  }
  def aluImmUop(f3: UInt, f7b5: Bool, funct7: UInt): Uop.Type = {
    val r = WireInit(Uop.NOP)
    switch(f3) {
      is("b000".U) { r := ADD  }   // ADDI
      is("b010".U) { r := SLT  }   // SLTI
      is("b011".U) { r := SLTU }   // SLTIU
      is("b100".U) { r := XOR  }   // XORI
      is("b110".U) { r := OR   }   // ORI
      is("b111".U) { r := AND  }   // ANDI
      is("b001".U) { r := SLL  }   // SLLI
      is("b101".U) { r := Mux(f7b5, SRA, SRL) }  // SRLI/SRAI
    }
    r
  }

  switch(op) {
    is(OP_LUI) {
      out.uop := LUI
      out.imm := immU(inst)
      out.writesReg := true.B
    }
    is(OP_AUIPC) {
      out.uop := AUIPC
      out.imm := immU(inst)
      out.writesReg := true.B
    }
    is(OP_JAL) {
      out.uop := JAL
      out.imm := immJ(inst)
      out.writesReg := true.B
      out.predTaken := true.B
    }
    is(OP_JALR) {
      out.uop := JALR
      out.imm := immI(inst)
      out.usesRs1 := true.B
      out.writesReg := true.B
      out.predTaken := true.B
    }
    is(OP_BRANCH) {
      out.imm := immB(inst)
      out.usesRs1 := true.B
      out.usesRs2 := true.B
      out.uop := branchUop(f3)
      val valid = f3 === "b000".U || f3 === "b001".U || f3 === "b100".U ||
                  f3 === "b101".U || f3 === "b110".U || f3 === "b111".U
      when(!valid) { illegal := true.B }
    }
    is(OP_LOAD) {
      out.imm := immI(inst)
      out.usesRs1 := true.B
      out.writesReg := true.B
      out.uop := loadUop(f3)
      val valid = f3 === "b000".U || f3 === "b001".U || f3 === "b010".U ||
                  f3 === "b100".U || f3 === "b101".U
      when(!valid) { illegal := true.B }
    }
    is(OP_STORE) {
      out.imm := immS(inst)
      out.usesRs1 := true.B
      out.usesRs2 := true.B
      out.uop := storeUop(f3)
      val valid = f3 === "b000".U || f3 === "b001".U || f3 === "b010".U
      when(!valid) { illegal := true.B }
    }
    is(OP_IMM) {
      out.imm := immI(inst)
      out.usesRs1 := true.B
      out.writesReg := true.B
      out.uop := aluImmUop(f3, f7b5, funct7(inst))
      // SLLI/SRLI/SRAI 要求 funct7[6:0] 为 0 或 0100000
      when((f3 === "b001".U || f3 === "b101".U) &&
          funct7(inst) =/= "b0000000".U && funct7(inst) =/= "b0100000".U) {
        illegal := true.B
      }
    }
    is(OP_REG) {
      out.usesRs1 := true.B
      out.usesRs2 := true.B
      out.writesReg := true.B
      out.uop := Mux(funct7(inst) === "b0000001".U,
                     mExtUop(f3),
                     aluRegUop(f3, f7b5))
    }
    is(OP_FENCE) {
      out.uop := FENCE
    }
    is(OP_SYSTEM) {
      out.uop := Uop.NOP
      when(inst === "h00000073".U) { out.uop := ECALL  }
      when(inst === "h00100073".U) { out.uop := EBREAK }
      when(inst === "h30200073".U) { out.uop := MRET   }
      when(inst === "h10500073".U) { out.uop := WFI    }
      when(f3 =/= 0.U) {
        out.imm := inst(31, 20).asSInt
        out.writesReg := true.B
        switch(f3) {
          is("b001".U) {
            out.uop := CSRRW
            out.usesRs1 := true.B
          }
          is("b010".U) {
            out.uop := CSRRS
            out.usesRs1 := true.B
          }
          is("b011".U) {
            out.uop := CSRRC
            out.usesRs1 := true.B
          }
          is("b101".U) { out.uop := CSRRWI }
          is("b110".U) { out.uop := CSRRSI }
          is("b111".U) { out.uop := CSRRCI }
        }
        when(f3 === "b100".U) { illegal := true.B }
      }
    }
  }

  // fallback：未知 opcode
  val knownOps = Seq(OP_LUI, OP_AUIPC, OP_JAL, OP_JALR, OP_BRANCH,
                     OP_LOAD, OP_STORE, OP_IMM, OP_REG, OP_FENCE, OP_SYSTEM)
  when(!knownOps.map(_ === op).reduce(_ || _)) {
    illegal := true.B
    out.uop := Uop.NOP
  }

  io.out := out
  io.illegal := illegal
}
