#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VCore.h"

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>

static constexpr uint32_t OP_IMM = 0x13;
static constexpr uint32_t OP_REG = 0x33;
static constexpr uint32_t NOP    = 0x00000013;

static uint32_t addi(int rd, int rs1, int imm) {
    return ((imm & 0xfff) << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_IMM;
}

static uint32_t add(int rd, int rs1, int rs2) {
    return (0 << 25) | (rs2 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_REG;
}

static void eval_dump(VCore *dut, VerilatedVcdC *tfp, vluint64_t &time) {
    dut->eval();
    if (tfp) tfp->dump(time);
}

static void tick(VCore *dut, VerilatedVcdC *tfp, vluint64_t &time) {
    dut->clock = 0;
    eval_dump(dut, tfp, time);
    time++;
    dut->clock = 1;
    eval_dump(dut, tfp, time);
    time++;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);

    bool trace = false;
    for (int i = 1; i < argc; ++i)
        if (std::string(argv[i]) == "-v") trace = true;
    if (const char *e = getenv("ENABLE_TRACE"))
        if (std::string(e) == "1") trace = true;
    Verilated::traceEverOn(trace);

    auto dut = std::make_unique<VCore>();
    VerilatedVcdC *tfp = nullptr;
    if (trace) {
        tfp = new VerilatedVcdC;
        dut->trace(tfp, 99);
        tfp->open("simx.vcd");
    }

    vluint64_t time = 0;
    const std::array<uint32_t, 3> program = {
        addi(1, 0, 42), // x1 = 42
        addi(2, 1, 10), // x2 = 52
        add(3, 1, 2),   // x3 = 94
    };

    dut->reset = 1;
    dut->io_dmem_rdata = 0;
    dut->io_imem_inst = NOP;
    tick(dut.get(), tfp, time);
    dut->reset = 0;

    bool saw_x1 = false, saw_x2 = false, saw_x3 = false;
    for (int cycle = 0; cycle < 80; ++cycle) {
        const uint32_t addr = dut->io_imem_addr;
        const uint32_t idx = addr >> 2;
        dut->io_imem_inst = idx < program.size() ? program[idx] : NOP;
        dut->io_dmem_rdata = 0;

        if (dut->io_dbgCommitValid && dut->io_dbgCommitWritesReg) {
            const uint32_t rd = dut->io_dbgCommitRd;
            const uint32_t data = dut->io_dbgCommitData;
            std::printf("commit: x%u = %u\n", rd, data);
            saw_x1 |= rd == 1 && data == 42;
            saw_x2 |= rd == 2 && data == 52;
            saw_x3 |= rd == 3 && data == 94;
        }

        tick(dut.get(), tfp, time);
    }

    if (tfp) tfp->close();

    if (!saw_x1 || !saw_x2 || !saw_x3) {
        std::printf("FAIL: expected commits x1=42, x2=52, x3=94\n");
        return 1;
    }
    std::printf("PASS: Core executed dependent ALU program\n");
    return 0;
}
