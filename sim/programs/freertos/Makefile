LLVM_PREFIX ?= /opt/homebrew/opt/llvm/bin/
CLANG ?= $(LLVM_PREFIX)clang
OBJCOPY ?= $(LLVM_PREFIX)llvm-objcopy

TARGET ?= smoke
BUILD_DIR ?= build
KERNEL_TAG ?= V11.3.0
KERNEL_DIR ?= $(BUILD_DIR)/FreeRTOS-Kernel

RISCV_PORT := $(KERNEL_DIR)/portable/GCC/RISC-V
RISCV_CHIP := $(RISCV_PORT)/chip_specific_extensions/RISCV_MTIME_CLINT_no_extensions

CFLAGS := --target=riscv32-unknown-elf -march=rv32im -mabi=ilp32
CFLAGS += -ffreestanding -fno-builtin -nostdlib -nostartfiles -fuse-ld=lld
CFLAGS += -O2 -Wall -Wextra
CFLAGS += -I. -I$(KERNEL_DIR)/include -I$(RISCV_PORT) -I$(RISCV_CHIP)
LDFLAGS := -T linker.ld -Wl,--gc-sections -Wl,-Map,$(BUILD_DIR)/$(TARGET).map

APP_OBJS := $(BUILD_DIR)/start.o $(BUILD_DIR)/main.o
KERNEL_OBJS := \
	$(BUILD_DIR)/tasks.o \
	$(BUILD_DIR)/queue.o \
	$(BUILD_DIR)/list.o \
	$(BUILD_DIR)/port.o \
	$(BUILD_DIR)/portASM.o
OBJS := $(APP_OBJS) $(KERNEL_OBJS)

.PHONY: all clean kernel

all: $(BUILD_DIR)/$(TARGET).elf $(BUILD_DIR)/$(TARGET).text.bin $(BUILD_DIR)/$(TARGET).data.bin

$(BUILD_DIR):
	mkdir -p $@

kernel: $(KERNEL_DIR)

$(KERNEL_DIR): | $(BUILD_DIR)
	git clone --depth 1 --branch $(KERNEL_TAG) https://github.com/FreeRTOS/FreeRTOS-Kernel.git $@

$(BUILD_DIR)/start.o: start.S $(KERNEL_DIR) | $(BUILD_DIR)
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/main.o: main.c FreeRTOSConfig.h $(KERNEL_DIR) | $(BUILD_DIR)
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/tasks.o: $(KERNEL_DIR)/tasks.c FreeRTOSConfig.h
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/queue.o: $(KERNEL_DIR)/queue.c FreeRTOSConfig.h
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/list.o: $(KERNEL_DIR)/list.c FreeRTOSConfig.h
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/port.o: $(RISCV_PORT)/port.c FreeRTOSConfig.h
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/portASM.o: $(RISCV_PORT)/portASM.S FreeRTOSConfig.h
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/$(TARGET).elf: $(OBJS) linker.ld | $(BUILD_DIR)
	$(CLANG) $(CFLAGS) $(OBJS) $(LDFLAGS) -o $@

$(BUILD_DIR)/$(TARGET).text.bin: $(BUILD_DIR)/$(TARGET).elf
	$(OBJCOPY) -O binary --only-section=.text --only-section=.rodata $< $@

$(BUILD_DIR)/$(TARGET).data.bin: $(BUILD_DIR)/$(TARGET).elf
	$(OBJCOPY) -O binary --only-section=.data $< $@

clean:
	rm -rf $(BUILD_DIR)
