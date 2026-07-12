OUTPUT_ARCH(riscv)
ENTRY(_start)

IMEM_ORIGIN = 0x00000000;
DMEM_ORIGIN = 0x10000000;
IMEM_LENGTH = 64K;
DMEM_LENGTH = 64K;

MEMORY
{
  imem (rx)  : ORIGIN = IMEM_ORIGIN, LENGTH = IMEM_LENGTH
  dmem (rwx) : ORIGIN = DMEM_ORIGIN, LENGTH = DMEM_LENGTH
}

SECTIONS
{
  .text : ALIGN(4)
  {
    KEEP(*(.text.init))
    *(.text .text.*)
    *(.rodata .rodata.*)
  } > imem

  .data : ALIGN(4)
  {
    *(.sdata .sdata.*)
    *(.data .data.*)
  } > dmem

  .bss (NOLOAD) : ALIGN(4)
  {
    *(.sbss .sbss.*)
    *(.bss .bss.*)
    *(COMMON)
  } > dmem
}
