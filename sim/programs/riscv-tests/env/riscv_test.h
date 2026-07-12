// Minimal riscv-tests environment for this core's Verilator testbench.
//
// The upstream p environment expects a tohost region and starts at 0x80000000.
// This core starts at 0x00000000, places data at 0x10000000, and exits by
// storing a code to MMIO address 0x20000000.

#ifndef _SIM_RISCV_TEST_H
#define _SIM_RISCV_TEST_H

#define RVTEST_RV64U .macro init; .endm
#define RVTEST_RV32U .macro init; .endm
#define RVTEST_RV64M .macro init; .endm
#define RVTEST_RV32M .macro init; .endm

#define CHECK_XLEN

#define INIT_XREG                                                       \
  li x1, 0;                                                             \
  li x2, 0;                                                             \
  li x3, 0;                                                             \
  li x4, 0;                                                             \
  li x5, 0;                                                             \
  li x6, 0;                                                             \
  li x7, 0;                                                             \
  li x8, 0;                                                             \
  li x9, 0;                                                             \
  li x10, 0;                                                            \
  li x11, 0;                                                            \
  li x12, 0;                                                            \
  li x13, 0;                                                            \
  li x14, 0;                                                            \
  li x15, 0;                                                            \
  li x16, 0;                                                            \
  li x17, 0;                                                            \
  li x18, 0;                                                            \
  li x19, 0;                                                            \
  li x20, 0;                                                            \
  li x21, 0;                                                            \
  li x22, 0;                                                            \
  li x23, 0;                                                            \
  li x24, 0;                                                            \
  li x25, 0;                                                            \
  li x26, 0;                                                            \
  li x27, 0;                                                            \
  li x28, 0;                                                            \
  li x29, 0;                                                            \
  li x30, 0;                                                            \
  li x31, 0;

#define TESTNUM gp
#define MMIO_EXIT 0x20000000

#define RVTEST_CODE_BEGIN                                               \
        .section .text.init;                                            \
        .align  6;                                                      \
        .globl _start;                                                  \
_start:                                                                 \
        INIT_XREG;                                                      \
        li TESTNUM, 0;                                                  \
        CHECK_XLEN;                                                     \
        init;

#define RVTEST_CODE_END                                                 \
1:      j 1b

#define RVTEST_PASS                                                     \
        fence;                                                          \
        li t0, MMIO_EXIT;                                               \
        sw zero, 0(t0);                                                 \
1:      j 1b

#define RVTEST_FAIL                                                     \
        fence;                                                          \
1:      beqz TESTNUM, 1b;                                               \
        sll TESTNUM, TESTNUM, 1;                                        \
        or TESTNUM, TESTNUM, 1;                                         \
        li t0, MMIO_EXIT;                                               \
        sw TESTNUM, 0(t0);                                              \
2:      j 2b

#define EXTRA_DATA

#define RVTEST_DATA_BEGIN                                               \
        EXTRA_DATA                                                      \
        .align 4;                                                       \
        .global begin_signature;                                        \
begin_signature:

#define RVTEST_DATA_END                                                 \
        .align 4;                                                       \
        .global end_signature;                                          \
end_signature:

#endif
