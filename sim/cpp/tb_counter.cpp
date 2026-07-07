// Verilator C++ testbench for Counter
// 验证 Counter.sv：复位→使能计数→禁用保持
#include <verilated.h>
#include "VCounter.h"
#include <verilated_vcd_c.h>

#include <cstdio>
#include <memory>

// 单个时钟周期：clk 先低再高，模拟一个完整上升沿
static void tick(VCounter *dut, vluint64_t &time, VerilatedVcdC *tfp) {
    dut->clock = 0;
    dut->eval();
    if (tfp) tfp->dump(time);
    time++;
    dut->clock = 1;
    dut->eval();
    if (tfp) tfp->dump(time);
    time++;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);

    // 命令行 -v 或环境变量 ENABLE_TRACE=1 开启波形
    bool trace = false;
    for (int i = 1; i < argc; ++i)
        if (std::string(argv[i]) == "-v") trace = true;
    if (const char *e = getenv("ENABLE_TRACE"))
        if (std::string(e) == "1") trace = true;
    Verilated::traceEverOn(trace);

    auto dut = std::make_unique<VCounter>();
    VerilatedVcdC *tfp = nullptr;
    if (trace) {
        tfp = new VerilatedVcdC;
        dut->trace(tfp, 99);
        tfp->open("simx.vcd");
    }

    vluint64_t time = 0;

    // 复位（Chisel 生成的模块有 reset，同步高有效）
    dut->clock = 0;
    dut->reset = 1;
    dut->io_en = 0;
    tick(dut.get(), time, tfp);
    printf("after reset: out = %u\n", dut->io_out);
    if (dut->io_out != 0) {
        printf("FAIL: expected 0 after reset\n");
        return 1;
    }

    // 使能计数 3 个周期
    dut->reset = 0;
    dut->io_en = 1;
    for (int i = 0; i < 3; ++i) {
        tick(dut.get(), time, tfp);
        printf("tick %d: out = %u\n", i + 1, dut->io_out);
    }
    if (dut->io_out != 3) {
        printf("FAIL: expected 3, got %u\n", dut->io_out);
        return 1;
    }

    // 禁用后保持
    dut->io_en = 0;
    tick(dut.get(), time, tfp);
    tick(dut.get(), time, tfp);
    printf("after disable: out = %u\n", dut->io_out);
    if (dut->io_out != 3) {
        printf("FAIL: expected 3 (hold), got %u\n", dut->io_out);
        return 1;
    }

    if (tfp) tfp->close();

    printf("PASS: Counter behaves as expected\n");
    return 0;
}
