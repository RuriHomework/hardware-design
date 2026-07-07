  .section .text.start, "ax", @progbits
  .globl _start
_start:
  la sp, __stack_top
  la t0, __bss_start
  la t1, __bss_end
1:
  bgeu t0, t1, 2f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 1b
2:
  call main
  li t0, 0x20000000
  sw a0, 0(t0)
3:
  j 3b

  .globl trap_vector
trap_vector:
  la t0, ticks
  lw t1, 0(t0)
  addi t1, t1, 1
  sw t1, 0(t0)

  li t0, 0x20000010
  li t1, -1
  sw t1, 0(t0)
  sw t1, 4(t0)
  mret
