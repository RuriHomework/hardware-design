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
    KEEP(*(.text.start))
    *(.text .text.*)
    *(.rodata .rodata.*)
  } > imem

  .data : ALIGN(4)
  {
    __global_pointer$ = . + 0x800;
    __data_start = .;
    *(.sdata .sdata.*)
    *(.data .data.*)
    __data_end = .;
  } > dmem

  .bss (NOLOAD) : ALIGN(4)
  {
    __bss_start = .;
    *(.sbss .sbss.*)
    *(.bss .bss.*)
    *(COMMON)
    __bss_end = .;
  } > dmem

  . = ALIGN(16);
  __heap_start = .;
  __stack_top = ORIGIN(dmem) + LENGTH(dmem);
}
