#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VCore.h"

#include <array>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

static constexpr uint32_t OP_IMM = 0x13;
static constexpr uint32_t OP_REG = 0x33;
static constexpr uint32_t NOP    = 0x00000013;

static constexpr uint32_t IMEM_BASE = 0x00000000;
static constexpr uint32_t DMEM_BASE = 0x10000000;
static constexpr uint32_t MMIO_EXIT = 0x20000000;
static constexpr uint32_t MMIO_MTIME_LO = 0x20000008;
static constexpr uint32_t MMIO_MTIME_HI = 0x2000000c;
static constexpr uint32_t MMIO_MTIMECMP_LO = 0x20000010;
static constexpr uint32_t MMIO_MTIMECMP_HI = 0x20000014;
static constexpr size_t IMEM_SIZE = 64 * 1024;
static constexpr size_t DMEM_SIZE = 64 * 1024;

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

static uint32_t maskedWordWrite(uint32_t oldValue, uint32_t data, uint8_t mask) {
    uint32_t next = oldValue;
    for (int i = 0; i < 4; ++i) {
        if (mask & (1u << i)) {
            const uint32_t byteMask = 0xffu << (8 * i);
            next = (next & ~byteMask) | (data & byteMask);
        }
    }
    return next;
}

struct PerfStats {
    uint64_t cycles = 0;
    uint64_t commits = 0;
    uint64_t commitWrites = 0;
    uint64_t controlCommits = 0;
    uint64_t mispredicts = 0;
    uint64_t mretCommits = 0;
    uint64_t interruptEntries = 0;
    uint64_t cdbValidCycles = 0;

    uint64_t dispatchValidCycles = 0;
    uint64_t frontendBubbleCycles = 0;
    uint64_t dispatchStallCycles = 0;
    uint64_t stallInterrupt = 0;
    uint64_t stallSystem = 0;
    uint64_t stallBranchCheckpoint = 0;
    uint64_t stallStoreBehindBranch = 0;
    uint64_t stallRobFull = 0;
    uint64_t stallIssueFull = 0;
    uint64_t stallFreeList = 0;
    uint64_t stallOther = 0;

    uint64_t issueValidCycles = 0;
    uint64_t issueFireCycles = 0;
    uint64_t issueBlockedCycles = 0;
    uint64_t issueMemoryOrderBlocked = 0;
    uint64_t issueBlockedLsuBusy = 0;
    uint64_t issueBlockedMduBusy = 0;
    uint64_t issueBlockedWbBusy = 0;
    uint64_t issueBlockedOther = 0;
    uint64_t issuedLoads = 0;
    uint64_t issuedStores = 0;
    uint64_t issuedBranches = 0;
    uint64_t issuedMdu = 0;

    uint64_t lsuBusyCycles = 0;
    uint64_t mduBusyCycles = 0;
    uint64_t wbBusyCycles = 0;
    uint64_t robOccupancySum = 0;
    uint64_t issueOccupancySum = 0;
    uint64_t robOccupancyMax = 0;
    uint64_t issueOccupancyMax = 0;
    uint64_t dmemStores = 0;
    uint64_t mmioStores = 0;

    std::unordered_map<uint32_t, uint64_t> mispredictByPc;

    static double pct(uint64_t value, uint64_t total) {
        return total == 0 ? 0.0 : (100.0 * static_cast<double>(value)) / static_cast<double>(total);
    }

    void print() const {
        const double ipc = cycles == 0 ? 0.0 : static_cast<double>(commits) / static_cast<double>(cycles);
        std::printf("perf: cycles=%llu commits=%llu ipc=%.3f cpi=%.3f\n",
            static_cast<unsigned long long>(cycles),
            static_cast<unsigned long long>(commits),
            ipc,
            commits == 0 ? 0.0 : static_cast<double>(cycles) / static_cast<double>(commits));
        std::printf("perf: commit writes=%llu control=%llu mispredicts=%llu mret=%llu interrupts=%llu cdb=%llu\n",
            static_cast<unsigned long long>(commitWrites),
            static_cast<unsigned long long>(controlCommits),
            static_cast<unsigned long long>(mispredicts),
            static_cast<unsigned long long>(mretCommits),
            static_cast<unsigned long long>(interruptEntries),
            static_cast<unsigned long long>(cdbValidCycles));
        std::printf("perf: dispatch valid=%llu stalled=%llu (%.1f%% of valid)\n",
            static_cast<unsigned long long>(dispatchValidCycles),
            static_cast<unsigned long long>(dispatchStallCycles),
            pct(dispatchStallCycles, dispatchValidCycles));
        std::printf("perf: frontend bubbles=%llu (%.1f%% of cycles)\n",
            static_cast<unsigned long long>(frontendBubbleCycles),
            pct(frontendBubbleCycles, cycles));
        std::printf("perf: dispatch stalls interrupt=%llu system=%llu branch_checkpoint=%llu store_after_branch=%llu rob_full=%llu issue_full=%llu freelist=%llu other=%llu\n",
            static_cast<unsigned long long>(stallInterrupt),
            static_cast<unsigned long long>(stallSystem),
            static_cast<unsigned long long>(stallBranchCheckpoint),
            static_cast<unsigned long long>(stallStoreBehindBranch),
            static_cast<unsigned long long>(stallRobFull),
            static_cast<unsigned long long>(stallIssueFull),
            static_cast<unsigned long long>(stallFreeList),
            static_cast<unsigned long long>(stallOther));
        std::printf("perf: issue valid=%llu fire=%llu blocked=%llu mem_order=%llu lsu_busy=%llu mdu_busy=%llu wb_busy=%llu other=%llu\n",
            static_cast<unsigned long long>(issueValidCycles),
            static_cast<unsigned long long>(issueFireCycles),
            static_cast<unsigned long long>(issueBlockedCycles),
            static_cast<unsigned long long>(issueMemoryOrderBlocked),
            static_cast<unsigned long long>(issueBlockedLsuBusy),
            static_cast<unsigned long long>(issueBlockedMduBusy),
            static_cast<unsigned long long>(issueBlockedWbBusy),
            static_cast<unsigned long long>(issueBlockedOther));
        std::printf("perf: issued loads=%llu stores=%llu branches=%llu mdu=%llu\n",
            static_cast<unsigned long long>(issuedLoads),
            static_cast<unsigned long long>(issuedStores),
            static_cast<unsigned long long>(issuedBranches),
            static_cast<unsigned long long>(issuedMdu));
        std::printf("perf: busy lsu=%llu mdu=%llu wb=%llu dmem_stores=%llu mmio_stores=%llu\n",
            static_cast<unsigned long long>(lsuBusyCycles),
            static_cast<unsigned long long>(mduBusyCycles),
            static_cast<unsigned long long>(wbBusyCycles),
            static_cast<unsigned long long>(dmemStores),
            static_cast<unsigned long long>(mmioStores));
        std::printf("perf: occupancy rob_avg=%.2f rob_max=%llu issue_avg=%.2f issue_max=%llu\n",
            cycles == 0 ? 0.0 : static_cast<double>(robOccupancySum) / static_cast<double>(cycles),
            static_cast<unsigned long long>(robOccupancyMax),
            cycles == 0 ? 0.0 : static_cast<double>(issueOccupancySum) / static_cast<double>(cycles),
            static_cast<unsigned long long>(issueOccupancyMax));

        std::vector<std::pair<uint32_t, uint64_t>> pcs(mispredictByPc.begin(), mispredictByPc.end());
        std::sort(pcs.begin(), pcs.end(), [](const auto &a, const auto &b) {
            if (a.second != b.second) return a.second > b.second;
            return a.first < b.first;
        });
        const size_t limit = std::min<size_t>(10, pcs.size());
        for (size_t i = 0; i < limit; ++i) {
            std::printf("perf: top_mispredict[%zu] pc=0x%08x count=%llu\n",
                i,
                pcs[i].first,
                static_cast<unsigned long long>(pcs[i].second));
        }
    }
};

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
    bool perf = plusArgU64("+perf=", argc, argv, 0) != 0;
    if (const char *e = getenv("PERF"))
        if (std::string(e) == "1") perf = true;

    auto dut = std::make_unique<VCore>();
    VerilatedVcdC *tfp = nullptr;
    if (trace) {
        tfp = new VerilatedVcdC;
        dut->trace(tfp, 99);
        tfp->open("simx.vcd");
    }

    vluint64_t time = 0;
    uint32_t pendingDmemRead = DMEM_BASE;
    uint64_t mtime = 0;
    uint64_t mtimecmp = UINT64_MAX;

    dut->reset = 1;
    dut->io_imem_inst = NOP;
    dut->io_dmem_rdata = 0;
    dut->io_timerInterrupt = 0;
    tick(dut.get(), tfp, time);
    dut->reset = 0;

    bool saw_x1 = false, saw_x2 = false, saw_x3 = false;
    bool halted = false;
    uint32_t exitCode = 0xffffffffu;
    PerfStats perfStats;

    for (uint64_t cycle = 0; cycle < maxCycles && !halted; ++cycle) {
        const auto loadMmio32 = [&](uint32_t addr) -> uint32_t {
            switch (addr) {
                case MMIO_MTIME_LO: return static_cast<uint32_t>(mtime);
                case MMIO_MTIME_HI: return static_cast<uint32_t>(mtime >> 32);
                case MMIO_MTIMECMP_LO: return static_cast<uint32_t>(mtimecmp);
                case MMIO_MTIMECMP_HI: return static_cast<uint32_t>(mtimecmp >> 32);
                default: return 0;
            }
        };

        dut->io_imem_inst = imem.load32(dut->io_imem_addr);
        dut->io_dmem_rdata = dmem.contains(pendingDmemRead)
            ? dmem.load32(pendingDmemRead)
            : (imem.contains(pendingDmemRead) ? imem.load32(pendingDmemRead) : loadMmio32(pendingDmemRead));
        dut->io_timerInterrupt = mtime >= mtimecmp;
        dut->eval();
        perfStats.cycles = cycle + 1;

        if (perf) {
            if (dut->io_dbgCommitValid) {
                perfStats.commits++;
                perfStats.commitWrites += dut->io_dbgCommitWritesReg ? 1 : 0;
                perfStats.controlCommits += dut->io_dbgCommitIsControl ? 1 : 0;
                perfStats.mretCommits += dut->io_dbgCommitIsMret ? 1 : 0;
                if (dut->io_dbgCommit_mispred) {
                    perfStats.mispredicts++;
                    perfStats.mispredictByPc[dut->io_dbgCommit_pc]++;
                }
            }
            perfStats.interruptEntries += dut->io_dbgInterruptFire ? 1 : 0;
            perfStats.cdbValidCycles += dut->io_dbgCdbValid ? 1 : 0;

            if (dut->io_dbgDispatchValid) {
                perfStats.dispatchValidCycles++;
                if (!dut->io_dbgDispatchReady) {
                    perfStats.dispatchStallCycles++;
                    if (dut->io_dbgDispatchBlockedInterrupt) perfStats.stallInterrupt++;
                    else if (dut->io_dbgDispatchBlockedSystem) perfStats.stallSystem++;
                    else if (dut->io_dbgDispatchBlockedBranchCheckpoint) perfStats.stallBranchCheckpoint++;
                    else if (dut->io_dbgDispatchBlockedStoreBehindBranch) perfStats.stallStoreBehindBranch++;
                    else if (dut->io_dbgDispatchBlockedRobFull) perfStats.stallRobFull++;
                    else if (dut->io_dbgDispatchBlockedIssueFull) perfStats.stallIssueFull++;
                    else if (dut->io_dbgDispatchBlockedFreeList) perfStats.stallFreeList++;
                    else perfStats.stallOther++;
                }
            } else {
                perfStats.frontendBubbleCycles++;
            }

            if (dut->io_dbgIssueMemoryOrderBlocked) perfStats.issueMemoryOrderBlocked++;
            if (dut->io_dbgIssueDeqValid) {
                perfStats.issueValidCycles++;
                if (dut->io_dbgIssueDeqReady) {
                    perfStats.issueFireCycles++;
                    perfStats.issuedLoads += dut->io_dbgIssueIsLoad ? 1 : 0;
                    perfStats.issuedStores += dut->io_dbgIssueIsStore ? 1 : 0;
                    perfStats.issuedBranches += dut->io_dbgIssueIsBranch ? 1 : 0;
                    perfStats.issuedMdu += dut->io_dbgIssueIsMdu ? 1 : 0;
                } else {
                    perfStats.issueBlockedCycles++;
                    if (dut->io_dbgLsuBusy) perfStats.issueBlockedLsuBusy++;
                    else if (dut->io_dbgMduBusy) perfStats.issueBlockedMduBusy++;
                    else if (dut->io_dbgWbBusy) perfStats.issueBlockedWbBusy++;
                    else perfStats.issueBlockedOther++;
                }
            }

            perfStats.lsuBusyCycles += dut->io_dbgLsuBusy ? 1 : 0;
            perfStats.mduBusyCycles += dut->io_dbgMduBusy ? 1 : 0;
            perfStats.wbBusyCycles += dut->io_dbgWbBusy ? 1 : 0;
            perfStats.robOccupancySum += dut->io_dbgRobCount;
            perfStats.issueOccupancySum += dut->io_dbgIssueCount;
            perfStats.robOccupancyMax = std::max<uint64_t>(perfStats.robOccupancyMax, dut->io_dbgRobCount);
            perfStats.issueOccupancyMax = std::max<uint64_t>(perfStats.issueOccupancyMax, dut->io_dbgIssueCount);
        }

        const uint32_t daddr = dut->io_dmem_addr;
        if (dut->io_dmem_wen) {
            if (perf) {
                if (dmem.contains(daddr)) perfStats.dmemStores++;
                else if (daddr >= MMIO_EXIT && daddr <= MMIO_MTIMECMP_HI) perfStats.mmioStores++;
            }
            if (dumpCommits) {
                std::printf("store: cycle=%llu addr=0x%08x data=0x%08x mask=0x%x\n",
                    static_cast<unsigned long long>(cycle), daddr, dut->io_dmem_wdata, dut->io_dmem_wmask);
            }
            if (daddr == MMIO_EXIT) {
                exitCode = dut->io_dmem_wdata;
                halted = true;
                std::printf("mmio_exit: code=%u cycle=%llu\n",
                    exitCode, static_cast<unsigned long long>(cycle));
            } else if (daddr == MMIO_MTIME_LO || daddr == MMIO_MTIME_HI ||
                       daddr == MMIO_MTIMECMP_LO || daddr == MMIO_MTIMECMP_HI) {
                uint32_t lo = static_cast<uint32_t>(daddr < MMIO_MTIMECMP_LO ? mtime : mtimecmp);
                uint32_t hi = static_cast<uint32_t>((daddr < MMIO_MTIMECMP_LO ? mtime : mtimecmp) >> 32);
                if (daddr == MMIO_MTIME_LO || daddr == MMIO_MTIMECMP_LO) {
                    lo = maskedWordWrite(lo, dut->io_dmem_wdata, dut->io_dmem_wmask);
                } else {
                    hi = maskedWordWrite(hi, dut->io_dmem_wdata, dut->io_dmem_wmask);
                }
                const uint64_t next = (static_cast<uint64_t>(hi) << 32) | lo;
                if (daddr < MMIO_MTIMECMP_LO) {
                    mtime = next;
                } else {
                    mtimecmp = next;
                }
            } else if (dmem.contains(daddr)) {
                dmem.storeMasked32(daddr, dut->io_dmem_wdata, dut->io_dmem_wmask);
            } else if (imem.contains(daddr)) {
                std::printf("ERROR: store to read-only IMem addr=0x%08x data=0x%08x mask=0x%x\n",
                    daddr, dut->io_dmem_wdata, dut->io_dmem_wmask);
                exitCode = 1;
                halted = true;
            } else {
                std::printf("ERROR: store outside DMem addr=0x%08x data=0x%08x mask=0x%x\n",
                    daddr, dut->io_dmem_wdata, dut->io_dmem_wmask);
                exitCode = 1;
                halted = true;
            }
        } else if (dmem.contains(daddr)) {
            pendingDmemRead = daddr;
        } else {
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
        mtime++;
    }

    if (tfp) tfp->close();
    if (perf) perfStats.print();

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
