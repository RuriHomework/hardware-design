#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VCore.h"

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

static constexpr uint32_t OP_IMM = 0x13;
static constexpr uint32_t OP_REG = 0x33;
static constexpr uint32_t NOP    = 0x00000013;

static constexpr uint32_t IMEM_BASE = 0x00000000;
static constexpr uint32_t DMEM_BASE = 0x10000000;
static constexpr uint32_t MMIO_EXIT = 0x20000000;
static constexpr size_t IMEM_SIZE = 16 * 1024;
static constexpr size_t DMEM_SIZE = 16 * 1024;

static uint32_t addi(int rd, int rs1, int imm) {
    return ((imm & 0xfff) << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_IMM;
}

static uint32_t add(int rd, int rs1, int rs2) {
    return (0 << 25) | (rs2 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | OP_REG;
}

struct Memory {
    uint32_t base;
    std::vector<uint8_t> bytes;

    Memory(uint32_t base_, size_t size) : base(base_), bytes(size, 0) {}

    bool contains(uint32_t addr) const {
        const uint64_t off = static_cast<uint64_t>(addr) - base;
        return addr >= base && off < bytes.size();
    }

    bool loadBinary(const std::string &path, uint32_t loadBase) {
        std::ifstream in(path, std::ios::binary);
        if (!in) {
            std::fprintf(stderr, "ERROR: cannot open %s\n", path.c_str());
            return false;
        }
        const uint64_t off = static_cast<uint64_t>(loadBase) - base;
        if (loadBase < base || off >= bytes.size()) {
            std::fprintf(stderr, "ERROR: load address 0x%08x outside memory\n", loadBase);
            return false;
        }
        in.read(reinterpret_cast<char *>(bytes.data() + off), bytes.size() - off);
        if (in.bad()) {
            std::fprintf(stderr, "ERROR: failed while reading %s\n", path.c_str());
            return false;
        }
        return true;
    }

    uint32_t load32(uint32_t addr) const {
        if (!contains(addr)) return 0;
        const uint32_t off = addr - base;
        uint32_t value = 0;
        for (int i = 0; i < 4; ++i) {
            if (off + static_cast<uint32_t>(i) < bytes.size()) {
                value |= static_cast<uint32_t>(bytes[off + i]) << (8 * i);
            }
        }
        return value;
    }

    void storeMasked32(uint32_t addr, uint32_t data, uint8_t mask) {
        if (!contains(addr)) return;
        const uint32_t wordAddr = addr & ~0x3u;
        const uint32_t off = wordAddr - base;
        for (int i = 0; i < 4; ++i) {
            if ((mask & (1u << i)) && off + static_cast<uint32_t>(i) < bytes.size()) {
                bytes[off + i] = static_cast<uint8_t>((data >> (8 * i)) & 0xffu);
            }
        }
    }
};

static void storeWordToBytes(std::vector<uint8_t> &bytes, uint32_t addr, uint32_t base, uint32_t word) {
    const uint32_t off = addr - base;
    for (int i = 0; i < 4; ++i) {
        bytes[off + i] = static_cast<uint8_t>((word >> (8 * i)) & 0xffu);
    }
}

static void loadDefaultProgram(Memory &imem) {
    const std::array<uint32_t, 3> program = {
        addi(1, 0, 42), // x1 = 42
        addi(2, 1, 10), // x2 = 52
        add(3, 1, 2),   // x3 = 94
    };
    for (size_t i = 0; i < program.size(); ++i) {
        storeWordToBytes(imem.bytes, IMEM_BASE + static_cast<uint32_t>(4 * i), IMEM_BASE, program[i]);
    }
}

static std::string plusArg(const char *prefix, int argc, char **argv) {
    const std::string p(prefix);
    for (int i = 1; i < argc; ++i) {
        const std::string arg(argv[i]);
        if (arg.rfind(p, 0) == 0) return arg.substr(p.size());
    }
    return "";
}

static uint64_t plusArgU64(const char *prefix, int argc, char **argv, uint64_t fallback) {
    const std::string value = plusArg(prefix, argc, argv);
    if (value.empty()) return fallback;
    return std::strtoull(value.c_str(), nullptr, 0);
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

    Memory imem(IMEM_BASE, IMEM_SIZE);
    Memory dmem(DMEM_BASE, DMEM_SIZE);

    const std::string textPath = plusArg("+text=", argc, argv);
    const std::string dataPath = plusArg("+data=", argc, argv);
    if (textPath.empty()) {
        loadDefaultProgram(imem);
    } else if (!imem.loadBinary(textPath, IMEM_BASE)) {
        return 1;
    }
    if (!dataPath.empty() && !dmem.loadBinary(dataPath, DMEM_BASE)) {
        return 1;
    }

    const uint64_t maxCycles = plusArgU64("+max-cycles=", argc, argv, textPath.empty() ? 80 : 200000);
    const bool dumpCommits = plusArgU64("+dump-commits=", argc, argv, 0) != 0;

    auto dut = std::make_unique<VCore>();
    VerilatedVcdC *tfp = nullptr;
    if (trace) {
        tfp = new VerilatedVcdC;
        dut->trace(tfp, 99);
        tfp->open("simx.vcd");
    }

    vluint64_t time = 0;
    uint32_t pendingDmemRead = DMEM_BASE;

    dut->reset = 1;
    dut->io_imem_inst = NOP;
    dut->io_dmem_rdata = 0;
    tick(dut.get(), tfp, time);
    dut->reset = 0;

    bool saw_x1 = false, saw_x2 = false, saw_x3 = false;
    bool halted = false;
    uint32_t exitCode = 0xffffffffu;

    for (uint64_t cycle = 0; cycle < maxCycles && !halted; ++cycle) {
        dut->io_imem_inst = imem.load32(dut->io_imem_addr);
        dut->io_dmem_rdata = dmem.load32(pendingDmemRead);
        dut->eval();

        const uint32_t daddr = dut->io_dmem_addr;
        if (dut->io_dmem_wen) {
            if (dumpCommits) {
                std::printf("store: cycle=%llu addr=0x%08x data=0x%08x mask=0x%x\n",
                    static_cast<unsigned long long>(cycle), daddr, dut->io_dmem_wdata, dut->io_dmem_wmask);
            }
            if (daddr == MMIO_EXIT) {
                exitCode = dut->io_dmem_wdata;
                halted = true;
                std::printf("mmio_exit: code=%u cycle=%llu\n",
                    exitCode, static_cast<unsigned long long>(cycle));
            } else if (dmem.contains(daddr)) {
                dmem.storeMasked32(daddr, dut->io_dmem_wdata, dut->io_dmem_wmask);
            } else {
                std::printf("ERROR: store outside DMem addr=0x%08x data=0x%08x mask=0x%x\n",
                    daddr, dut->io_dmem_wdata, dut->io_dmem_wmask);
                exitCode = 1;
                halted = true;
            }
        } else if (dmem.contains(daddr)) {
            pendingDmemRead = daddr;
        }

        if (dumpCommits && dut->io_dbgCommitValid) {
            std::printf("commit: cycle=%llu pc=0x%08x uop=%u rd=x%u data=0x%08x writes=%u taken=%u target=0x%08x mispred=%u\n",
                static_cast<unsigned long long>(cycle),
                dut->io_dbgCommit_pc,
                dut->io_dbgCommit_uop,
                dut->io_dbgCommitRd,
                dut->io_dbgCommitData,
                dut->io_dbgCommitWritesReg,
                dut->io_dbgCommit_taken,
                dut->io_dbgCommit_target,
                dut->io_dbgCommit_mispred);
        }

        if (textPath.empty() && dut->io_dbgCommitValid && dut->io_dbgCommitWritesReg) {
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

    if (textPath.empty()) {
        if (!saw_x1 || !saw_x2 || !saw_x3) {
            std::printf("FAIL: expected commits x1=42, x2=52, x3=94\n");
            return 1;
        }
        std::printf("PASS: Core executed dependent ALU program\n");
        return 0;
    }

    if (!halted) {
        std::printf("FAIL: simulation timed out after %llu cycles\n",
            static_cast<unsigned long long>(maxCycles));
        return 1;
    }
    if (exitCode != 0) {
        std::printf("FAIL: program exited with code %u\n", exitCode);
        return 1;
    }
    std::printf("PASS: program exited successfully\n");
    return 0;
}
