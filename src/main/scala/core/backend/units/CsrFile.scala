package core.backend.units

import chisel3._
import chisel3.util._

import isa._
import isa.CoreConfig._
import isa.Uop._

class CsrFile extends Module {
  private val interruptDebug = sys.env.get("INTERRUPT_DEBUG").contains("1")
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

    val interruptLines = Input(new Bundle {
      val software = Bool()
      val timer = Bool()
      val external = Bool()
    })
    val interrupt = new Bundle {
      val fire = Input(Bool())
      val pc = Input(UInt(PcWidth.W))
      val pending = Output(Bool())
      val target = Output(UInt(PcWidth.W))
      val cause = Output(UInt(XLen.W))
    }
  })

  private val MstatusMie = 3
  private val MstatusMpie = 7
  private val MstatusMppLo = 11
  private val MstatusWritable = "h00001888".U(XLen.W)
  private val MieWritable = "h00000888".U(XLen.W)
  private val MisaRv32Im = "h40001100".U(XLen.W)

  val mstatus  = RegInit(0.U(XLen.W))
  val mie      = RegInit(0.U(XLen.W))
  val mtvec    = RegInit(0.U(XLen.W))
  val mscratch = RegInit(0.U(XLen.W))
  val mepc     = RegInit(0.U(XLen.W))
  val mcause   = RegInit(0.U(XLen.W))
  val mcycle   = RegInit(0.U(XLen.W))

  mcycle := mcycle + 1.U

  val mip = Cat(
    0.U(20.W), io.interruptLines.external,
    0.U(3.W), io.interruptLines.timer,
    0.U(3.W), io.interruptLines.software,
    0.U(3.W)
  )

  val old = WireDefault(0.U(XLen.W))
  switch(io.cmd.bits.addr) {
    is("h300".U) { old := mstatus }
    is("h301".U) { old := MisaRv32Im }
    is("h304".U) { old := mie }
    is("h305".U) { old := mtvec }
    is("h340".U) { old := mscratch }
    is("h341".U) { old := mepc }
    is("h342".U) { old := mcause }
    is("h344".U) { old := mip }
    is("hB00".U) { old := mcycle }
    is("hC00".U) { old := mcycle }
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
      is("h300".U) { mstatus := writeValue & MstatusWritable }
      is("h304".U) { mie := writeValue & MieWritable }
      is("h305".U) {
        val mode = Mux(writeValue(1, 0) === 1.U, 1.U(2.W), 0.U(2.W))
        mtvec := Cat(writeValue(31, 2), mode)
      }
      is("h340".U) { mscratch := writeValue }
      is("h341".U) { mepc := writeValue & "hfffffffc".U }
      is("h342".U) { mcause := writeValue }
      is("hB00".U) { mcycle := writeValue }
    }
  }

  val synchronousTrap = io.cmd.valid && (io.cmd.bits.uop === ECALL || io.cmd.bits.uop === EBREAK)
  val mret = io.cmd.valid && io.cmd.bits.uop === MRET
  val globallyEnabled = mstatus(MstatusMie)
  val externalPending = globallyEnabled && mie(11) && mip(11)
  val softwarePending = globallyEnabled && mie(3) && mip(3)
  val timerPending = globallyEnabled && mie(7) && mip(7)
  val interruptPending = externalPending || softwarePending || timerPending
  val interruptCode = Mux(externalPending, 11.U,
    Mux(softwarePending, 3.U, 7.U))
  val interruptCause = "h80000000".U | interruptCode
  val trapBase = mtvec & "hfffffffc".U
  val interruptTarget = Mux(mtvec(1, 0) === 1.U,
    trapBase + (interruptCode << 2), trapBase)

  if (interruptDebug) {
    val externalPrev = RegNext(io.interruptLines.external, false.B)
    when(io.interruptLines.external && !externalPrev) {
      printf(cf"csr external-line mstatus=0x${mstatus}%x mie=0x${mie}%x pending=${interruptPending}\n")
    }
    when(io.interrupt.fire) {
      printf(cf"csr interrupt-fire pc=0x${io.interrupt.pc}%x cause=0x${interruptCause}%x target=0x${interruptTarget}%x\n")
    }
  }

  def enterTrap(pc: UInt, trapCause: UInt): Unit = {
    mepc := pc & "hfffffffc".U
    mcause := trapCause
    mstatus := (mstatus & ~MstatusWritable) |
      (mstatus(MstatusMie).asUInt << MstatusMpie) |
      (3.U << MstatusMppLo)
  }

  when(synchronousTrap) {
    enterTrap(io.cmd.bits.pc, Mux(io.cmd.bits.uop === ECALL, 11.U, 3.U))
  }.elsewhen(io.interrupt.fire) {
    enterTrap(io.interrupt.pc, interruptCause)
  }.elsewhen(mret) {
    mstatus := (mstatus & ~MstatusWritable) |
      (mstatus(MstatusMpie).asUInt << MstatusMie) |
      (1.U << MstatusMpie)
  }

  io.result := old
  io.redirect := synchronousTrap || mret
  io.target := Mux(mret, mepc, trapBase)
  io.cause := Mux(synchronousTrap, RedirectCause.EXCEPTION,
    Mux(mret, RedirectCause.FLUSH, RedirectCause.NONE))
  io.interrupt.pending := interruptPending
  io.interrupt.target := interruptTarget
  io.interrupt.cause := interruptCause
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
    isCsr(u) || u === ECALL || u === EBREAK || u === MRET || u === WFI
  }
}
