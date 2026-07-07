LLVM_PREFIX ?= /opt/homebrew/opt/llvm/bin/
CLANG ?= $(LLVM_PREFIX)clang
OBJCOPY ?= $(LLVM_PREFIX)llvm-objcopy

TARGET ?= smoke
BUILD_DIR ?= build

CFLAGS := --target=riscv32-unknown-elf -march=rv32im -mabi=ilp32
CFLAGS += -ffreestanding -fno-builtin -nostdlib -nostartfiles -fuse-ld=lld
CFLAGS += -O2 -Wall -Wextra
LDFLAGS := -T linker.ld -Wl,--gc-sections -Wl,-Map,$(BUILD_DIR)/$(TARGET).map

SRCS := start.S main.c
OBJS := $(patsubst %.S,$(BUILD_DIR)/%.o,$(filter %.S,$(SRCS)))
OBJS += $(patsubst %.c,$(BUILD_DIR)/%.o,$(filter %.c,$(SRCS)))

.PHONY: all clean

all: $(BUILD_DIR)/$(TARGET).elf $(BUILD_DIR)/$(TARGET).text.bin $(BUILD_DIR)/$(TARGET).data.bin

$(BUILD_DIR):
	mkdir -p $@

$(BUILD_DIR)/%.o: %.S | $(BUILD_DIR)
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/%.o: %.c | $(BUILD_DIR)
	$(CLANG) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/$(TARGET).elf: $(OBJS) linker.ld | $(BUILD_DIR)
	$(CLANG) $(CFLAGS) $(OBJS) $(LDFLAGS) -o $@

$(BUILD_DIR)/$(TARGET).text.bin: $(BUILD_DIR)/$(TARGET).elf
	$(OBJCOPY) -O binary --only-section=.text --only-section=.rodata $< $@

$(BUILD_DIR)/$(TARGET).data.bin: $(BUILD_DIR)/$(TARGET).elf
	$(OBJCOPY) -O binary --only-section=.data $< $@

clean:
	rm -rf $(BUILD_DIR)
