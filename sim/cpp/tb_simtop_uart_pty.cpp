#include <verilated.h>
#include "VSimTop.h"

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>

#include <fcntl.h>
#include <pty.h>
#include <termios.h>
#include <unistd.h>

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

static void tick(VSimTop *dut) {
    dut->clock = 0;
    dut->eval();
    dut->clock = 1;
    dut->eval();
}

class PtyUart {
public:
    explicit PtyUart(int clocksPerBit_) : clocksPerBit(clocksPerBit_) {}

    bool openPty() {
        char slaveName[128] = {};
        int slaveFd = -1;
        termios tio {};
        cfmakeraw(&tio);
        if (openpty(&masterFd, &slaveFd, slaveName, &tio, nullptr) != 0) {
            std::fprintf(stderr, "ERROR: openpty failed: %s\n", std::strerror(errno));
            return false;
        }
        close(slaveFd);
        int flags = fcntl(masterFd, F_GETFL, 0);
        if (flags < 0 || fcntl(masterFd, F_SETFL, flags | O_NONBLOCK) < 0) {
            std::fprintf(stderr, "ERROR: failed to set PTY nonblocking: %s\n", std::strerror(errno));
            return false;
        }
        slavePath = slaveName;
        return true;
    }

    const std::string &path() const { return slavePath; }

    void closePty() {
        if (masterFd >= 0) {
            close(masterFd);
            masterFd = -1;
        }
    }

    void step(VSimTop *dut) {
        readHostBytes();
        driveRx(dut);
        sampleTx(dut);
    }

private:
    enum class TxState { Idle, Data, Stop };

    int masterFd = -1;
    int clocksPerBit = 1;
    std::string slavePath;
    std::deque<uint8_t> hostToDut;

    bool rxBusy = false;
    uint16_t rxFrame = 0x3ff;
    int rxBitsLeft = 0;
    int rxTimer = 0;

    TxState txState = TxState::Idle;
    int txTimer = 0;
    int txBit = 0;
    uint8_t txByte = 0;

    void readHostBytes() {
        uint8_t buf[256];
        while (true) {
            const ssize_t n = read(masterFd, buf, sizeof(buf));
            if (n > 0) {
                for (ssize_t i = 0; i < n; ++i) hostToDut.push_back(buf[i]);
                continue;
            }
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return;
            if (n < 0 && errno == EIO) return;
            if (n < 0) {
                std::fprintf(stderr, "ERROR: PTY read failed: %s\n", std::strerror(errno));
                std::exit(1);
            }
            return;
        }
    }

    void driveRx(VSimTop *dut) {
        if (!rxBusy) {
            dut->uartRx = 1;
            if (hostToDut.empty()) return;

            const uint8_t byte = hostToDut.front();
            hostToDut.pop_front();
            rxFrame = static_cast<uint16_t>((1u << 9) | (static_cast<uint16_t>(byte) << 1));
            rxBitsLeft = 10;
            rxTimer = clocksPerBit;
            rxBusy = true;
        }

        dut->uartRx = (rxFrame & 1u) != 0;
        rxTimer--;
        if (rxTimer <= 0) {
            rxFrame = static_cast<uint16_t>((rxFrame >> 1) | 0x200u);
            rxBitsLeft--;
            rxTimer = clocksPerBit;
            if (rxBitsLeft <= 0) {
                rxBusy = false;
                dut->uartRx = 1;
            }
        }
    }

    void writeHostByte(uint8_t byte) {
        const ssize_t n = write(masterFd, &byte, 1);
        if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EIO) {
            std::fprintf(stderr, "ERROR: PTY write failed: %s\n", std::strerror(errno));
            std::exit(1);
        }
    }

    void sampleTx(VSimTop *dut) {
        switch (txState) {
            case TxState::Idle:
                if (!dut->uartTx) {
                    txState = TxState::Data;
                    txTimer = clocksPerBit + clocksPerBit / 2;
                    txBit = 0;
                    txByte = 0;
                }
                break;
            case TxState::Data:
                if (--txTimer <= 0) {
                    if (dut->uartTx) txByte |= static_cast<uint8_t>(1u << txBit);
                    txBit++;
                    txTimer = clocksPerBit;
                    if (txBit >= 8) txState = TxState::Stop;
                }
                break;
            case TxState::Stop:
                if (--txTimer <= 0) {
                    if (dut->uartTx) writeHostByte(txByte);
                    txState = TxState::Idle;
                }
                break;
        }
    }
};

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);

    const uint64_t maxCycles = plusArgU64("+max-cycles=", argc, argv, 0);
    const uint64_t clockHz = plusArgU64("+clock-hz=", argc, argv, 60000000);
    const uint64_t baud = plusArgU64("+baud=", argc, argv, 115200);
    const bool dumpCommits = plusArgU64("+dump-commits=", argc, argv, 0) != 0;
    const int clocksPerBit = static_cast<int>((clockHz + baud / 2) / baud);

    auto dut = new VSimTop;
    dut->clock = 0;
    dut->reset = 1;
    dut->uartRx = 1;
    dut->eval();
    for (int i = 0; i < 8; ++i) tick(dut);
    dut->reset = 0;

    PtyUart uart(clocksPerBit > 0 ? clocksPerBit : 1);
    if (!uart.openPty()) {
        delete dut;
        return 1;
    }

    std::printf("UART PTY: %s\n", uart.path().c_str());
    std::printf("Shell example: picocom -b %llu %s\n",
        static_cast<unsigned long long>(baud), uart.path().c_str());
    std::fflush(stdout);

    uint64_t cycles = 0;
    bool exitSeen = false;
    while (!Verilated::gotFinish() && (maxCycles == 0 || cycles < maxCycles)) {
        uart.step(dut);
        tick(dut);
        cycles++;
        if (dumpCommits && dut->dbgCommitValid) {
            std::printf("commit: cycle=%llu pc=0x%08x rd=x%u data=0x%08x writes=%u\n",
                static_cast<unsigned long long>(cycles), dut->dbgCommitPc,
                dut->dbgCommitRd, dut->dbgCommitData, dut->dbgCommitWritesReg);
        }
        if (dut->exitValid && !exitSeen) {
            exitSeen = true;
            std::printf("mmio_exit: code=%u cycle=%llu\n",
                dut->exitCode, static_cast<unsigned long long>(cycles));
            std::fflush(stdout);
        }
    }

    if (maxCycles != 0 && cycles >= maxCycles) {
        std::printf("UART PTY simulation stopped after %llu cycles\n",
            static_cast<unsigned long long>(cycles));
    }

    uart.closePty();
    delete dut;
    return 0;
}
