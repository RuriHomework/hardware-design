package top

import chisel3._
import chisel3.util._

class SerialLoader(clockHz: Int, baud: Int, imemDepth: Int, dmemDepth: Int) extends Module {
  private val imemAddrWidth = log2Ceil(imemDepth)
  private val dmemAddrWidth = log2Ceil(dmemDepth)
  private val maxDepth = math.max(imemDepth, dmemDepth)
  private val clearWidth = log2Ceil(maxDepth + 1)

  val io = IO(new Bundle {
    val rx = Input(Bool())
    val tx = Output(Bool())

    val imem = new Bundle {
      val wen = Output(Bool())
      val addr = Output(UInt(imemAddrWidth.W))
      val data = Output(UInt(32.W))
    }
    val dmem = new Bundle {
      val wen = Output(Bool())
      val addr = Output(UInt(dmemAddrWidth.W))
      val data = Output(UInt(32.W))
    }

    val running = Output(Bool())
    val active = Output(Bool())
    val error = Output(Bool())
  })

  val rx = Module(new UartRx(clockHz, baud))
  val tx = Module(new UartTx(clockHz, baud))

  val sCommand :: sLength :: sData :: sClear :: Nil = Enum(4)
  val state = RegInit(sCommand)
  val running = RegInit(false.B)
  val targetDmem = RegInit(false.B)
  val lengthByte = RegInit(0.U(2.W))
  val dataByte = RegInit(0.U(2.W))
  val wordCount = RegInit(0.U(32.W))
  val wordIndex = RegInit(0.U(32.W))
  val wordShift = RegInit(0.U(32.W))
  val overflow = RegInit(false.B)
  val clearIndex = RegInit(0.U(clearWidth.W))
  val ackValid = RegInit(false.B)
  val ackByte = RegInit(0.U(8.W))
  val bootAfterAck = RegInit(false.B)
  val stickyError = RegInit(false.B)

  def byteToWord(byte: UInt, index: UInt): UInt = {
    MuxLookup(index, byte.pad(32))(Seq(
      1.U -> (byte.pad(32) << 8),
      2.U -> (byte.pad(32) << 16),
      3.U -> (byte.pad(32) << 24)
    ))
  }

  def sendAck(byte: UInt, boot: Bool): Unit = {
    ackByte := byte
    ackValid := true.B
    bootAfterAck := boot
  }

  rx.io.rx := io.rx
  rx.io.out.ready := state =/= sClear && !ackValid && !bootAfterAck

  tx.io.in.valid := ackValid
  tx.io.in.bits := ackByte

  io.tx := tx.io.tx
  io.running := running
  io.active := !running || ackValid || tx.io.busy || bootAfterAck
  io.error := stickyError || rx.io.framingError || rx.io.overrun

  io.imem.wen := false.B
  io.imem.addr := wordIndex(imemAddrWidth - 1, 0)
  io.imem.data := wordShift
  io.dmem.wen := false.B
  io.dmem.addr := wordIndex(dmemAddrWidth - 1, 0)
  io.dmem.data := wordShift

  when(ackValid && tx.io.in.fire) {
    ackValid := false.B
  }

  when(bootAfterAck && !ackValid && !tx.io.busy) {
    running := true.B
    bootAfterAck := false.B
  }

  when(state === sClear) {
    io.imem.wen := clearIndex < imemDepth.U
    io.imem.addr := clearIndex(imemAddrWidth - 1, 0)
    io.imem.data := 0.U
    io.dmem.wen := clearIndex < dmemDepth.U
    io.dmem.addr := clearIndex(dmemAddrWidth - 1, 0)
    io.dmem.data := 0.U
    when(clearIndex === (maxDepth - 1).U) {
      clearIndex := 0.U
      state := sCommand
      sendAck("h4b".U, false.B)
    }.otherwise {
      clearIndex := clearIndex + 1.U
    }
  }

  when(rx.io.out.fire) {
    val byte = rx.io.out.bits
    switch(state) {
      is(sCommand) {
        switch(byte) {
          is("h52".U) { // R: reset/halt CPU
            running := false.B
            stickyError := false.B
            sendAck("h4b".U, false.B)
          }
          is("h43".U) { // C: clear IMEM and DMEM
            running := false.B
            stickyError := false.B
            clearIndex := 0.U
            state := sClear
          }
          is("h49".U) { // I: load instruction memory
            running := false.B
            targetDmem := false.B
            lengthByte := 0.U
            wordCount := 0.U
            overflow := false.B
            state := sLength
          }
          is("h44".U) { // D: load data memory
            running := false.B
            targetDmem := true.B
            lengthByte := 0.U
            wordCount := 0.U
            overflow := false.B
            state := sLength
          }
          is("h42".U) { // B: boot CPU after ACK finishes
            sendAck("h4b".U, true.B)
          }
          is("h53".U) { // S: status
            sendAck(Mux(running, "h52".U, "h4c".U), false.B)
          }
          is("h3f".U) { // ?: protocol marker
            sendAck("h4b".U, false.B)
          }
        }
      }
      is(sLength) {
        val nextCount = wordCount | byteToWord(byte, lengthByte)
        wordCount := nextCount
        when(lengthByte === 3.U) {
          val tooLarge = Mux(targetDmem, nextCount > dmemDepth.U, nextCount > imemDepth.U)
          overflow := tooLarge
          wordIndex := 0.U
          dataByte := 0.U
          wordShift := 0.U
          when(nextCount === 0.U) {
            state := sCommand
            stickyError := stickyError || tooLarge
            sendAck(Mux(tooLarge, "h45".U, "h4b".U), false.B)
          }.otherwise {
            state := sData
          }
        }.otherwise {
          lengthByte := lengthByte + 1.U
        }
      }
      is(sData) {
        val nextWord = wordShift | byteToWord(byte, dataByte)
        wordShift := nextWord
        when(dataByte === 3.U) {
          when(!overflow) {
            when(targetDmem) {
              io.dmem.wen := true.B
              io.dmem.addr := wordIndex(dmemAddrWidth - 1, 0)
              io.dmem.data := nextWord
            }.otherwise {
              io.imem.wen := true.B
              io.imem.addr := wordIndex(imemAddrWidth - 1, 0)
              io.imem.data := nextWord
            }
          }
          when(wordIndex + 1.U === wordCount) {
            wordIndex := 0.U
            dataByte := 0.U
            wordShift := 0.U
            state := sCommand
            stickyError := stickyError || overflow
            sendAck(Mux(overflow, "h45".U, "h4b".U), false.B)
          }.otherwise {
            wordIndex := wordIndex + 1.U
            dataByte := 0.U
            wordShift := 0.U
          }
        }.otherwise {
          dataByte := dataByte + 1.U
        }
      }
    }
  }
}
