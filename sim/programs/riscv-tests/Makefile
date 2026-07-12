UNWRAPPED_CLANG := $(firstword $(wildcard /nix/store/*-clang-[0-9]*/bin/clang))
LLVM_OBJCOPY := $(firstword $(wildcard /nix/store/*-llvm-*/bin/llvm-objcopy) $(wildcard /nix/store/*-circt-llvm-*/bin/llvm-objcopy))
LLD := $(firstword $(wildcard /nix/store/*-lld-*/bin/ld.lld) $(wildcard /nix/store/*-llvm-binutils-wrapper-*/bin/ld.lld))
ifeq ($(origin CLANG),undefined)
CLANG := $(if $(UNWRAPPED_CLANG),$(UNWRAPPED_CLANG),clang)
endif
ifneq ($(origin OBJCOPY),command line)
OBJCOPY := $(if $(LLVM_OBJCOPY),$(LLVM_OBJCOPY),objcopy)
endif

RISCV_TESTS ?= ../../../third_party/riscv-tests
BUILD_DIR ?= build
TARGET ?= rv32ui-p-simple

ISA_DIR := $(RISCV_TESTS)/isa
ENV_DIR := env
MACRO_DIR := $(ISA_DIR)/macros/scalar

CFLAGS := --target=riscv32-unknown-elf -march=rv32im -mabi=ilp32
CFLAGS += -x assembler-with-cpp -ffreestanding -nostdlib -nostartfiles
CFLAGS += $(if $(LLD),-fuse-ld=$(LLD),-fuse-ld=lld)
CFLAGS += -I$(ENV_DIR) -I$(MACRO_DIR)
LDFLAGS := -T linker.ld -Wl,--gc-sections -Wl,-Map,$(BUILD_DIR)/$(TARGET).map

RV32UI_TESTS := \
  simple add addi and andi auipc beq bge bgeu blt bltu bne fence_i \
  jal jalr lb lbu lh lhu lw ld_st lui or ori sb sh sw st_ld \
  sll slli slt slti sltiu sltu sra srai srl srli sub xor xori

RV32UI_MISALIGNED_TESTS := ma_data
RV32UM_TESTS := div divu mul mulh mulhsu mulhu rem remu

TESTS := $(addprefix rv32ui-p-,$(RV32UI_TESTS)) $(addprefix rv32um-p-,$(RV32UM_TESTS))
ifeq ($(MISALIGNED),1)
TESTS += $(addprefix rv32ui-p-,$(RV32UI_MISALIGNED_TESTS))
endif

.PHONY: target all clean list

target: $(BUILD_DIR)/$(TARGET).text.bin $(BUILD_DIR)/$(TARGET).data.bin

all: $(addprefix $(BUILD_DIR)/,$(addsuffix .text.bin,$(TESTS))) \
     $(addprefix $(BUILD_DIR)/,$(addsuffix .data.bin,$(TESTS)))

list:
	@printf '%s\n' $(TESTS)

$(BUILD_DIR):
	mkdir -p $@

define TEST_template
$(BUILD_DIR)/rv32ui-p-$(1).elf: $(ISA_DIR)/rv32ui/$(1).S linker.ld env/riscv_test.h | $(BUILD_DIR)
	$$(CLANG) $$(CFLAGS) $$< $$(LDFLAGS) -o $$@
$(BUILD_DIR)/rv32um-p-$(1).elf: $(ISA_DIR)/rv32um/$(1).S linker.ld env/riscv_test.h | $(BUILD_DIR)
	$$(CLANG) $$(CFLAGS) $$< $$(LDFLAGS) -o $$@
endef

$(foreach t,$(RV32UI_TESTS) $(RV32UI_MISALIGNED_TESTS),$(eval $(call TEST_template,$(t))))
$(foreach t,$(RV32UM_TESTS),$(eval $(call TEST_template,$(t))))

$(BUILD_DIR)/%.text.bin: $(BUILD_DIR)/%.elf
	$(OBJCOPY) -O binary --only-section=.text $< $@

$(BUILD_DIR)/%.data.bin: $(BUILD_DIR)/%.elf
	$(OBJCOPY) -O binary --only-section=.data $< $@

clean:
	rm -rf $(BUILD_DIR)
