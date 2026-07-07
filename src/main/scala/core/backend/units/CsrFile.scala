package core.backend.units

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

class CsrFile extends Module {
  val io = IO(new Bundle {
    val cmd = Input(Valid(new Bundle {
      val uop  = Uop()
      val pc   = UInt(PcWidth.W)
      val addr = UInt(12.W)
      val src  = UInt(XLen.W)
    }))
    val result   = Output(UInt(XLen.W))
    val redirect = Output(Bool())
    val target   = Output(UInt(PcWidth.W))
    val cause    = Output(RedirectCause())

    val timerInterrupt = Input(Bool())
    val interrupt = new Bundle {
      val fire = Input(Bool())
      val pc = Input(UInt(PcWidth.W))
      val pending = Output(Bool())
      val target = Output(UInt(PcWidth.W))
    }
  })

  val mstatus  = RegInit(0.U(XLen.W))
  val mie      = RegInit(0.U(XLen.W))
  val mtvec    = RegInit(0.U(XLen.W))
  val mscratch = RegInit(0.U(XLen.W))
  val mepc     = RegInit(0.U(XLen.W))
  val mcause   = RegInit(0.U(XLen.W))
  val mipReg   = RegInit(0.U(XLen.W))
  val mcycle   = RegInit(0.U(XLen.W))

  mcycle := mcycle + 1.U
  val mip = Mux(io.timerInterrupt, mipReg | (1.U << 7), mipReg & ~(1.U << 7).asUInt)

  val old = WireDefault(0.U(XLen.W))
  switch(io.cmd.bits.addr) {
    is("h300".U) { old := mstatus }
    is("h304".U) { old := mie }
    is("h305".U) { old := mtvec }
    is("h340".U) { old := mscratch }
    is("h341".U) { old := mepc }
    is("h342".U) { old := mcause }
    is("h344".U) { old := mip }
    is("hB00".U) { old := mcycle }
    is("hF14".U) { old := 0.U } // mhartid
  }

  val writeValue = WireDefault(old)
  switch(io.cmd.bits.uop) {
    is(CSRRW, CSRRWI) { writeValue := io.cmd.bits.src }
    is(CSRRS, CSRRSI) { writeValue := old | io.cmd.bits.src }
    is(CSRRC, CSRRCI) { writeValue := old & ~io.cmd.bits.src }
  }

  val isCsr = CsrFile.isCsr(io.cmd.bits.uop)
  val writeReq = io.cmd.valid && isCsr &&
    (io.cmd.bits.uop === CSRRW || io.cmd.bits.uop === CSRRWI || io.cmd.bits.src =/= 0.U)

  when(writeReq) {
    switch(io.cmd.bits.addr) {
      is("h300".U) { mstatus := writeValue }
      is("h304".U) { mie := writeValue }
      is("h305".U) { mtvec := writeValue }
      is("h340".U) { mscratch := writeValue }
      is("h341".U) { mepc := writeValue & "hFFFFFFFC".U }
      is("h342".U) { mcause := writeValue }
      is("h344".U) { mipReg := writeValue }
      is("hB00".U) { mcycle := writeValue }
    }
  }

  val trap = io.cmd.valid && (io.cmd.bits.uop === ECALL || io.cmd.bits.uop === EBREAK)
  val ret = io.cmd.valid && io.cmd.bits.uop === MRET
  val timerPending = mstatus(3) && mie(7) && mip(7)

  def enterTrap(pc: UInt, cause: UInt): Unit = {
    mepc := pc
    mcause := cause
    mstatus := (mstatus & ~(1.U << 3).asUInt & ~(1.U << 7).asUInt) |
      (mstatus(3).asUInt << 7)
  }

  when(trap) {
    enterTrap(io.cmd.bits.pc, Mux(io.cmd.bits.uop === ECALL, 11.U, 3.U))
  }.elsewhen(io.interrupt.fire) {
    enterTrap(io.interrupt.pc, "h80000007".U)
  }.elsewhen(ret) {
    mstatus := (mstatus | (1.U << 7).asUInt) |
      (mstatus(7).asUInt << 3)
  }

  io.result := old
  io.redirect := trap || ret
  io.target := Mux(ret, mepc, mtvec & "hFFFFFFFC".U)
  io.cause := Mux(trap, RedirectCause.EXCEPTION,
    Mux(ret, RedirectCause.FLUSH, RedirectCause.NONE))
  io.interrupt.pending := timerPending
  io.interrupt.target := mtvec & "hFFFFFFFC".U
}

object CsrFile {
  def isCsr(u: Uop.Type): Bool = {
    u === CSRRW || u === CSRRS || u === CSRRC ||
    u === CSRRWI || u === CSRRSI || u === CSRRCI
  }

  def isImm(u: Uop.Type): Bool = {
    u === CSRRWI || u === CSRRSI || u === CSRRCI
  }

  def accepts(u: Uop.Type): Bool = {
    isCsr(u) || u === ECALL || u === EBREAK || u === MRET
  }
}
