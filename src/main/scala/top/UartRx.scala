package top

import chisel3._
import chisel3.util._

class UartRx(clockHz: Int, baud: Int) extends Module {
  private val clocksPerBit = math.max(1, (clockHz + baud / 2) / baud)
  private val halfBit = math.max(1, clocksPerBit / 2)
  private val divWidth = math.max(1, log2Ceil(clocksPerBit))

  val io = IO(new Bundle {
    val rx = Input(Bool())
    val out = Decoupled(UInt(8.W))
    val framingError = Output(Bool())
    val overrun = Output(Bool())
  })

  val rxMeta = RegNext(io.rx, true.B)
  val rxSync = RegNext(rxMeta, true.B)

  val sIdle :: sStart :: sData :: sStop :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val timer = RegInit(0.U(divWidth.W))
  val bitIndex = RegInit(0.U(3.W))
  val shift = RegInit(0.U(8.W))
  val validReg = RegInit(false.B)
  val bitsReg = RegInit(0.U(8.W))
  val framingErrorReg = RegInit(false.B)
  val overrunReg = RegInit(false.B)

  io.out.valid := validReg
  io.out.bits := bitsReg
  io.framingError := framingErrorReg
  io.overrun := overrunReg

  when(io.out.fire) {
    validReg := false.B
  }

  switch(state) {
    is(sIdle) {
      when(!rxSync) {
        state := sStart
        timer := (halfBit - 1).U
      }
    }
    is(sStart) {
      when(timer === 0.U) {
        when(!rxSync) {
          state := sData
          timer := (clocksPerBit - 1).U
          bitIndex := 0.U
        }.otherwise {
          state := sIdle
        }
      }.otherwise {
        timer := timer - 1.U
      }
    }
    is(sData) {
      when(timer === 0.U) {
        val nextShift = Cat(rxSync, shift(7, 1))
        shift := nextShift
        timer := (clocksPerBit - 1).U
        when(bitIndex === 7.U) {
          bitsReg := nextShift
          state := sStop
        }.otherwise {
          bitIndex := bitIndex + 1.U
        }
      }.otherwise {
        timer := timer - 1.U
      }
    }
    is(sStop) {
      when(timer === 0.U) {
        when(rxSync) {
          when(validReg && !io.out.ready) {
            overrunReg := true.B
          }.otherwise {
            validReg := true.B
          }
        }.otherwise {
          framingErrorReg := true.B
        }
        state := sIdle
      }.otherwise {
        timer := timer - 1.U
      }
    }
  }
}
