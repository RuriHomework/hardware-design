  .section .text.start, "ax", @progbits
  .globl _start
_start:
  .option push
  .option norelax
  la gp, __global_pointer$
  .option pop

  la sp, __stack_top

  la t0, freertos_risc_v_trap_handler
  csrw mtvec, t0

  la t0, __bss_start
  la t1, __bss_end
1:
  bgeu t0, t1, 2f
  sw zero, 0(t0)
  addi t0, t0, 4
  j 1b
2:
  call main
  j sim_exit

  .globl sim_exit
sim_exit:
  li t0, 0x20000000
  sw a0, 0(t0)
3:
  j 3b
