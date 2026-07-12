# Verilator 仿真 Makefile
#
# 用法:
#   make sim          # 生成 Core Verilog + 编译 + 运行
#   make wave         # 同上，并生成 VCD 波形到 sim/simx.vcd
#   make sim RTL_TOP=Counter TB_SRC=cpp/tb_counter.cpp
#   make clean        # 清理仿真中间产物
#
# 前置:
#   - sbt, firtool 在 PATH (sbt run 会自动拉取 firtool)
#   - verilator 在 PATH (brew install verilator)

SHELL := bash

CHISEL_FIRTOOL_PATH ?= $(HOME)/.cache/llvm-firtool/1.62.1/bin
export CHISEL_FIRTOOL_PATH

# 项目根目录
PROJ_ROOT := $(abspath $(CURDIR)/..)

# 生成 Verilog 的输出目录
RTL_DIR   := $(PROJ_ROOT)/build
RTL_TOP   ?= Core
RTL_FILE  := $(RTL_DIR)/$(RTL_TOP).sv
RTL_CONFIG := $(RTL_DIR)/.$(RTL_TOP).elab-config
RTL_SOURCES := $(shell find $(PROJ_ROOT)/src/main/scala $(PROJ_ROOT)/project -type f \( -name '*.scala' -o -name '*.sbt' -o -name '*.properties' \)) $(PROJ_ROOT)/build.sbt

# Verilator 工作目录
OBJ_DIR   := $(CURDIR)/obj_dir
TB_SRC    ?= cpp/tb_core.cpp
PROG_DIR  ?= programs/baremetal
PROG_NAME ?= smoke
PROG_TEXT ?= $(PROG_DIR)/build/$(PROG_NAME).text.bin
PROG_DATA ?= $(PROG_DIR)/build/$(PROG_NAME).data.bin
MAX_CYCLES ?= 500000
UART_MAX_CYCLES ?= 0
SIM_ARGS ?=

export IMEM_HEX
export DMEM_HEX
export PRELOAD_BOOT
export BOARD_CLOCK_HZ
export UART_BAUD
export IMEM_WORDS
export DMEM_WORDS
export IMEM_BASE
export DMEM_BASE
export RESET_VECTOR
export QEMU_VIRT_MMIO
export MMIO_DEBUG
export INTERRUPT_DEBUG

# Verilator flags
VFLAGS := --top-module $(RTL_TOP)
VFLAGS += -I$(RTL_DIR)
VFLAGS += --trace              # 启用 VCD 波形 (始终生成，是否 dump 由 TB 控制)
VFLAGS += --trace-structs
VFLAGS += -Wall
# Chisel 生成的 RANDOMIZE 块会留未使用信号，抑制这类告警
VFLAGS += -Wno-UNUSEDSIGNAL
VFLAGS += -Wno-DECLFILENAME
VFLAGS += -Wno-PINCONNECTEMPTY
VFLAGS += -CFLAGS -std=c++17

# 默认目标：编译并运行仿真
.PHONY: sim sim-program wave wave-program uart-pty uart-pty-run preload-pty preload-pty-run riscv-tests clean clean-all rtl program FORCE

sim: rtl $(OBJ_DIR)/V$(RTL_TOP)
	@echo "==> Running simulation"
	$(OBJ_DIR)/V$(RTL_TOP)

sim-program: program rtl $(OBJ_DIR)/V$(RTL_TOP)
	@echo "==> Running program simulation"
	$(OBJ_DIR)/V$(RTL_TOP) +text=$(PROG_TEXT) +data=$(PROG_DATA) +max-cycles=$(MAX_CYCLES) $(SIM_ARGS)

# 带波形版本：用 ENABLE_TRACE=1 触发 TB 里的 VCD dump
wave: rtl $(OBJ_DIR)/V$(RTL_TOP)
	@echo "==> Running simulation with VCD trace"
	ENABLE_TRACE=1 $(OBJ_DIR)/V$(RTL_TOP)
	@echo "==> VCD waveform: $(CURDIR)/simx.vcd"
	@echo "   打开: gtkwave $(CURDIR)/simx.vcd"

wave-program: program rtl $(OBJ_DIR)/V$(RTL_TOP)
	@echo "==> Running program simulation with VCD trace"
	ENABLE_TRACE=1 $(OBJ_DIR)/V$(RTL_TOP) +text=$(PROG_TEXT) +data=$(PROG_DATA) +max-cycles=$(MAX_CYCLES) $(SIM_ARGS)
	@echo "==> VCD waveform: $(CURDIR)/simx.vcd"

uart-pty:
	@$(MAKE) RTL_TOP=BoardTop TB_SRC=cpp/tb_board_uart_pty.cpp SIM_ARGS="$(SIM_ARGS)" MAX_CYCLES="$(UART_MAX_CYCLES)" uart-pty-run

uart-pty-run: rtl $(OBJ_DIR)/V$(RTL_TOP)
	@echo "==> Running BoardTop UART PTY simulation"
	$(OBJ_DIR)/V$(RTL_TOP) +max-cycles=$(MAX_CYCLES) $(SIM_ARGS)

preload-pty:
	@test -n "$(PRELOAD_TEXT_HEX)" || (echo "ERROR: set PRELOAD_TEXT_HEX=<imem hex>" >&2; exit 1)
	@test -n "$(PRELOAD_DATA_HEX)" || (echo "ERROR: set PRELOAD_DATA_HEX=<dmem hex>" >&2; exit 1)
	@PRELOAD_BOOT=1 \
	  IMEM_HEX="$(abspath $(PRELOAD_TEXT_HEX))" \
	  DMEM_HEX="$(abspath $(PRELOAD_DATA_HEX))" \
	  $(MAKE) RTL_TOP=SimTop TB_SRC=cpp/tb_simtop_uart_pty.cpp SIM_ARGS="$(SIM_ARGS)" MAX_CYCLES="$(UART_MAX_CYCLES)" preload-pty-run

nuttx-pty:
	@test -n "$(NUTTX_HEX)" || (echo "ERROR: set NUTTX_HEX=<flat nuttx hex>" >&2; exit 1)
	@PRELOAD_TEXT_HEX="$(NUTTX_HEX)" \
	  PRELOAD_DATA_HEX="$(NUTTX_HEX)" \
	  IMEM_WORDS="$${IMEM_WORDS:-262144}" \
	  DMEM_WORDS="$${DMEM_WORDS:-262144}" \
	  IMEM_BASE="$${IMEM_BASE:-0x80000000}" \
	  DMEM_BASE="$${DMEM_BASE:-0x80000000}" \
	  RESET_VECTOR="$${RESET_VECTOR:-0x80000000}" \
	  QEMU_VIRT_MMIO=1 \
	  $(MAKE) preload-pty SIM_ARGS="$(SIM_ARGS)" UART_MAX_CYCLES="$(UART_MAX_CYCLES)"

preload-pty-run: rtl $(OBJ_DIR)/V$(RTL_TOP)
	@echo "==> Running preloaded BoardTop UART PTY simulation"
	$(OBJ_DIR)/V$(RTL_TOP) +max-cycles=$(MAX_CYCLES) $(SIM_ARGS)

riscv-tests: rtl $(OBJ_DIR)/V$(RTL_TOP)
	@set -euo pipefail; \
	tests="$$( $(MAKE) -s -C programs/riscv-tests list )"; \
	pass=0; fail=0; \
	for test in $$tests; do \
	  echo "==> $$test"; \
	  $(MAKE) -s -C programs/riscv-tests TARGET=$$test target; \
	  if $(OBJ_DIR)/V$(RTL_TOP) \
	    +text=programs/riscv-tests/build/$$test.text.bin \
	    +data=programs/riscv-tests/build/$$test.data.bin \
	    +max-cycles=$(MAX_CYCLES) $(SIM_ARGS); then \
	    pass=$$((pass + 1)); \
	  else \
	    fail=$$((fail + 1)); \
	    echo "FAIL: $$test"; \
	  fi; \
	done; \
	echo "==> riscv-tests summary: pass=$$pass fail=$$fail"; \
	test $$fail -eq 0

program:
	@echo "==> Building bare-metal program"
	$(MAKE) -C $(PROG_DIR) TARGET=$(PROG_NAME)

# 仅在展开参数实际变化时更新时间戳，避免每次启动都重新生成 RTL。
$(RTL_CONFIG): FORCE
	@mkdir -p $(RTL_DIR)
	@tmp="$@.tmp"; \
	{ \
	  printf '%s\n' "RTL_TOP=$(RTL_TOP)"; \
	  printf '%s\n' "IMEM_HEX=$(IMEM_HEX)"; \
	  printf '%s\n' "DMEM_HEX=$(DMEM_HEX)"; \
	  printf '%s\n' "PRELOAD_BOOT=$(PRELOAD_BOOT)"; \
	  printf '%s\n' "BOARD_CLOCK_HZ=$(BOARD_CLOCK_HZ)"; \
	  printf '%s\n' "UART_BAUD=$(UART_BAUD)"; \
	  printf '%s\n' "IMEM_WORDS=$(IMEM_WORDS)"; \
	  printf '%s\n' "DMEM_WORDS=$(DMEM_WORDS)"; \
	  printf '%s\n' "IMEM_BASE=$(IMEM_BASE)"; \
	  printf '%s\n' "DMEM_BASE=$(DMEM_BASE)"; \
	  printf '%s\n' "RESET_VECTOR=$(RESET_VECTOR)"; \
	  printf '%s\n' "QEMU_VIRT_MMIO=$(QEMU_VIRT_MMIO)"; \
	  printf '%s\n' "MMIO_DEBUG=$(MMIO_DEBUG)"; \
	  printf '%s\n' "INTERRUPT_DEBUG=$(INTERRUPT_DEBUG)"; \
	} > "$$tmp"; \
	if test -r "$@" && cmp -s "$$tmp" "$@"; then \
	  rm -f "$$tmp"; \
	else \
	  mv -f "$$tmp" "$@"; \
	fi

# 生成 SystemVerilog
rtl: $(RTL_FILE)

$(RTL_FILE): $(RTL_SOURCES) $(RTL_CONFIG) Makefile
	@echo "==> Elaborating Chisel -> SystemVerilog"
	cd $(PROJ_ROOT) && sbt "runMain Elaborate --top $(RTL_TOP)"

# Verilator 编译
$(OBJ_DIR)/V$(RTL_TOP): $(RTL_FILE) $(TB_SRC) Makefile
	@echo "==> Compiling with Verilator"
	verilator $(VFLAGS) --cc $(RTL_FILE) --exe $(TB_SRC) --build -Mdir $(OBJ_DIR)

clean:
	rm -rf $(OBJ_DIR)

clean-all: clean
	rm -f $(RTL_FILE) $(RTL_CONFIG)

FORCE:
