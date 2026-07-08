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

# Verilator 工作目录
OBJ_DIR   := $(CURDIR)/obj_dir
TB_SRC    ?= cpp/tb_core.cpp
PROG_DIR  ?= programs/baremetal
PROG_NAME ?= smoke
PROG_TEXT ?= $(PROG_DIR)/build/$(PROG_NAME).text.bin
PROG_DATA ?= $(PROG_DIR)/build/$(PROG_NAME).data.bin
MAX_CYCLES ?= 500000
SIM_ARGS ?=

# Verilator flags
VFLAGS := --top-module $(RTL_TOP)
VFLAGS += -I$(RTL_DIR)
VFLAGS += --trace              # 启用 VCD 波形 (始终生成，是否 dump 由 TB 控制)
VFLAGS += --trace-structs
VFLAGS += -Wall
# Chisel 生成的 RANDOMIZE 块会留未使用信号，抑制这类告警
VFLAGS += -Wno-UNUSEDSIGNAL
VFLAGS += -Wno-DECLFILENAME
VFLAGS += -CFLAGS -std=c++17

# 默认目标：编译并运行仿真
.PHONY: sim sim-program wave wave-program clean rtl program

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

program:
	@echo "==> Building bare-metal program"
	$(MAKE) -C $(PROG_DIR) TARGET=$(PROG_NAME)

# 生成 SystemVerilog
rtl:
	@echo "==> Elaborating Chisel -> SystemVerilog"
	cd $(PROJ_ROOT) && sbt "runMain Elaborate --top $(RTL_TOP)"

# Verilator 编译
$(OBJ_DIR)/V$(RTL_TOP): $(RTL_FILE) $(TB_SRC)
	@echo "==> Compiling with Verilator"
	verilator $(VFLAGS) --cc $(RTL_FILE) --exe $(TB_SRC) --build -Mdir $(OBJ_DIR)

clean:
	rm -rf $(OBJ_DIR)

clean-all: clean
	rm -f $(RTL_FILE)
