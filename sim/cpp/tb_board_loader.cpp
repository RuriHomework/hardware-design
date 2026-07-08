#include <verilated.h>
#include "VBoardTop.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

static constexpr int CLOCKS_PER_BIT = 521; // 60 MHz / 115200 baud, rounded
static constexpr int MAX_WAIT_CYCLES = 2000000;

static vluint64_t sim_time = 0;

static void tick(VBoardTop *dut) {
    dut->clock = 0;
    dut->eval();
    sim_time++;
    dut->clock = 1;
    dut->eval();
    sim_time++;
}

static void tickMany(VBoardTop *dut, int cycles) {
    for (int i = 0; i < cycles; ++i) tick(dut);
}

static void uartSendByte(VBoardTop *dut, uint8_t byte) {
    dut->uartRx = 0;
    tickMany(dut, CLOCKS_PER_BIT);
    for (int bit = 0; bit < 8; ++bit) {
        dut->uartRx = (byte >> bit) & 1;
        tickMany(dut, CLOCKS_PER_BIT);
    }
    dut->uartRx = 1;
    tickMany(dut, CLOCKS_PER_BIT);
}

static bool uartRecvByte(VBoardTop *dut, uint8_t &byte, int maxWaitCycles = MAX_WAIT_CYCLES) {
    int waited = 0;
    while (dut->uartTx && waited < maxWaitCycles) {
        tick(dut);
        waited++;
    }
    if (waited >= maxWaitCycles) return false;

    tickMany(dut, CLOCKS_PER_BIT + CLOCKS_PER_BIT / 2);
    uint8_t value = 0;
    for (int bit = 0; bit < 8; ++bit) {
        if (dut->uartTx) value |= static_cast<uint8_t>(1u << bit);
        tickMany(dut, CLOCKS_PER_BIT);
    }
    byte = value;
    tickMany(dut, CLOCKS_PER_BIT);
    return true;
}

static void sendU32(std::vector<uint8_t> &bytes, uint32_t value) {
    bytes.push_back(static_cast<uint8_t>(value));
    bytes.push_back(static_cast<uint8_t>(value >> 8));
    bytes.push_back(static_cast<uint8_t>(value >> 16));
    bytes.push_back(static_cast<uint8_t>(value >> 24));
}

static void sendCommand(VBoardTop *dut, uint8_t command, const std::vector<uint8_t> &payload = {}) {
    uartSendByte(dut, command);
    for (uint8_t byte : payload) uartSendByte(dut, byte);

    uint8_t ack = 0;
    if (!uartRecvByte(dut, ack)) {
        std::fprintf(stderr, "ERROR: timeout waiting for ACK for command %c\n", command);
        std::exit(1);
    }
    if (ack != 'K') {
        std::fprintf(stderr, "ERROR: command %c returned 0x%02x\n", command, ack);
        std::exit(1);
    }
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    auto dut = new VBoardTop;
    dut->clock = 0;
    dut->uartRx = 1;
    dut->eval();
    tickMany(dut, 1000);

    sendCommand(dut, 'R');
    sendCommand(dut, 'C');

    std::vector<uint32_t> program(1024, 0x00000013); // nop delay after boot ACK
    const std::vector<uint32_t> tail = {
        0x200002b7, // lui t0, 0x20000
        0x05a00313, // addi t1, zero, 'Z'
        0x0062a223, // sw t1, 4(t0)
        0x0002a023, // sw zero, 0(t0)
        0x0000006f, // jal zero, 0
    };
    program.insert(program.end(), tail.begin(), tail.end());

    std::vector<uint8_t> imemPayload;
    sendU32(imemPayload, static_cast<uint32_t>(program.size()));
    for (uint32_t word : program) sendU32(imemPayload, word);
    sendCommand(dut, 'I', imemPayload);

    std::vector<uint8_t> dmemPayload;
    sendU32(dmemPayload, 0);
    sendCommand(dut, 'D', dmemPayload);
    sendCommand(dut, 'B');

    std::vector<uint8_t> seen;
    bool gotExpected = false;
    for (int i = 0; i < 8; ++i) {
        uint8_t out = 0;
        if (!uartRecvByte(dut, out, 10000000)) break;
        seen.push_back(out);
        if (out == 'Z') {
            gotExpected = true;
            break;
        }
    }
    if (!gotExpected) {
        std::fprintf(stderr, "ERROR: expected program output 'Z', saw:");
        for (uint8_t byte : seen) std::fprintf(stderr, " 0x%02x", byte);
        std::fprintf(stderr, "\n");
        delete dut;
        return 1;
    }

    std::printf("PASS: UART loader booted program and received 'Z'\n");
    delete dut;
    return 0;
}
