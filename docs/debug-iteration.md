# 调试迭代流程

本文档用于优化当前 CPU/BoardTop 的调试速度。核心原则是：先跑便宜检查，只有方向确认后再跑 Vivado 重流程，最后才生成 bitstream。

## 快速结论

不要每次改代码都跑完整 `sbt test` 或完整 Vivado bitstream。推荐顺序：

1. 只检查 Scala/Chisel 编译。
2. 只跑相关模块测试。
3. 只 elaborate 受影响的 top。
4. 用 Vivado OOC 看单模块 timing。
5. 用 BoardTop synth/place 看整体趋势。
6. 只有接近收敛时才 route。
7. 只有 routed timing 过了或需要实测时才 write_bitstream。

## Chisel 快速入口

脚本：

```bash
scripts/chisel-fast.sh
```

只检查编译，不跑测试：

```bash
scripts/chisel-fast.sh compile
```

只跑 IssueQueue 相关用例：

```bash
scripts/chisel-fast.sh test-issue
```

只跑后端结构测试：

```bash
scripts/chisel-fast.sh test-backend
```

只跑 Core 测试：

```bash
scripts/chisel-fast.sh test-core
```

完整测试只在阶段性确认时跑：

```bash
scripts/chisel-fast.sh test
```

生成 SystemVerilog：

```bash
scripts/chisel-fast.sh elab IssueQueue
scripts/chisel-fast.sh elab Backend
scripts/chisel-fast.sh elab BoardTop
```

## 最快的 Chisel 循环

如果要连续改很多次，打开常驻 sbt shell：

```bash
scripts/chisel-fast.sh shell
```

然后在 sbt 里反复输入：

```text
Test/compile
testOnly cpu.BackendStructuresSpec -- -z "wake operands"
testOnly cpu.BackendStructuresSpec
runMain Elaborate --top IssueQueue
runMain Elaborate --top BoardTop
```

这样可以避免每条命令都重新启动 JVM 和重新加载 sbt。一次性脚本更方便，但仍会付 Nix shell 和 sbt 启动成本。

## Vivado 快速入口

脚本：

```bash
scripts/vivado-fast.sh
```

这个脚本处理了当前 NixOS 上 Vivado 批处理需要的运行环境：

- 修 Vivado 启动脚本里的 `/bin/bash` shebang。
- 缓存运行库路径到 `build/vivado/vivado-runtime-ldpath`。
- 默认使用 `/home/ruri/Xilinx/Vivado/2024.2`，可用 `VIVADO_ROOT` 覆盖。
- 默认 `VIVADO_THREADS=6`。

第一次运行会慢一点，后续会复用缓存。

## 单模块 timing

用于快速判断某个模块本身的逻辑是否已经太深。先生成对应 SV：

```bash
scripts/chisel-fast.sh elab IssueQueue
```

只到 OOC synth：

```bash
VIVADO_STAGE=synth scripts/vivado-fast.sh ooc IssueQueue
```

默认到 OOC place：

```bash
scripts/vivado-fast.sh ooc IssueQueue
```

必要时到 OOC route：

```bash
VIVADO_STAGE=route scripts/vivado-fast.sh ooc IssueQueue
```

常用报告：

```text
build/vivado/IssueQueue_ooc_synth_timing.rpt
build/vivado/IssueQueue_ooc_synth_paths.rpt
build/vivado/IssueQueue_ooc_placed_timing.rpt
build/vivado/IssueQueue_ooc_placed_paths.rpt
```

注意：OOC timing 不等价于 BoardTop 真实 timing，但适合快速比较一次改动有没有减少逻辑层数、LUT/MUXF、明显长路径。

## BoardTop 分阶段 timing

先生成 BoardTop：

```bash
scripts/chisel-fast.sh elab BoardTop
```

只综合：

```bash
scripts/vivado-fast.sh synth
```

综合 + opt + place + phys_opt，不 route：

```bash
scripts/vivado-fast.sh place
```

route 但不写 bitstream：

```bash
scripts/vivado-fast.sh route
```

完整 bitstream：

```bash
scripts/vivado-fast.sh bit
```

烧录已生成的 bitstream：

```bash
scripts/vivado-fast.sh program
```

推荐节奏：

- 日常结构优化：`synth` 或 `place`。
- 判断是否真的接近 100MHz：`route`。
- 需要烧录或最终确认：`bit`。

不要把 `bit` 当默认迭代命令。

## 下板后的程序迭代

当前 `BoardTop` 带一个简单 UART loader。硬件 bitstream 固定后，程序镜像可以通过串口写入 IMEM/DMEM，再启动 CPU；只改软件程序时不需要重新跑 Vivado 布线。

板级连接：

- `uartTx`：PL 输出，约束到 `V12`，接 USB-UART RX。
- `uartRx`：PL 输入，约束到 `V15`，接 USB-UART TX。
- 串口参数默认 `115200 8N1`。

生成 FreeRTOS smoke 镜像：

```bash
scripts/build-freertos-image.sh
```

写入并启动：

```bash
scripts/uart-load-image.py --device /dev/ttyUSB0 --monitor 10
```

脚本会依次发送 reset、clear、load IMEM、load DMEM、boot，然后把程序 UART 输出打印到终端。正常 FreeRTOS smoke 会输出类似：

```text
FreeRTOS boot
A
B
PASS
```

如果只改 C/汇编程序，重复运行 `scripts/build-freertos-image.sh` 和 `scripts/uart-load-image.py` 即可。只有修改 CPU、memory map、loader、UART 或顶层连线时，才需要重新 elaborate 并跑 Vivado。

也可以把程序预初始化进 bitstream，适合不想接 loader RX 的场景：

```bash
IMEM_HEX=sim/programs/freertos/build/smoke.text.hex \
DMEM_HEX=sim/programs/freertos/build/smoke.data.hex \
scripts/chisel-fast.sh elab BoardTop
scripts/vivado-fast.sh bit
```

这种方式每次换程序都要重新生成 bitstream，因此只建议阶段性确认时使用。

## 快速看 timing 报告

脚本：

```bash
scripts/timing-summary.sh <report>
```

例子：

```bash
scripts/timing-summary.sh build/vivado/BoardTop_routed_timing.rpt
scripts/timing-summary.sh build/vivado/IssueQueue_ooc_synth_timing.rpt
```

重点看：

- `Worst Slack` / `Slack (VIOLATED)`：还差多少。
- `Source` / `Destination`：哪两个寄存器之间超时。
- `Data Path Delay`：总延迟，以及 logic/route 占比。
- `Logic Levels`：组合逻辑层数。

如果 route 占比很高，通常说明扇出、选择网络、跨模块路径或布局压力是问题，不是简单换 Vivado 参数能解决。

## 后端时序优化建议流程

改 IssueQueue、ROB、StoreBuffer 这类后端结构时：

```bash
scripts/chisel-fast.sh compile
scripts/chisel-fast.sh test-issue
scripts/chisel-fast.sh elab IssueQueue
VIVADO_STAGE=synth scripts/vivado-fast.sh ooc IssueQueue
scripts/timing-summary.sh build/vivado/IssueQueue_ooc_synth_timing.rpt
```

如果单模块方向看起来更好，再跑：

```bash
scripts/chisel-fast.sh test-backend
scripts/chisel-fast.sh elab BoardTop
scripts/vivado-fast.sh place
scripts/timing-summary.sh build/vivado/BoardTop_placed_timing.rpt
```

最后再跑：

```bash
scripts/vivado-fast.sh route
scripts/timing-summary.sh build/vivado/BoardTop_routed_timing.rpt
```

## 避免后台进程拖慢

检查是否有上一次被中断后还在跑的进程：

```bash
ps -eo pid,ppid,stat,pcpu,pmem,etime,cmd | rg 'sbt|java|vivado|firtool|nextpnr|yosys'
```

如果确认是自己刚才中断后残留的任务，可以停掉对应 PID：

```bash
kill <pid>
```

不要随便杀不认识的进程。当前系统上 Vivado FHS shell 相关的 `bwrap` 可能长期存在但不占 CPU，通常不用管。

## 当前本机实测参考

这些数字只用于估算等待时间：

- `scripts/chisel-fast.sh compile`：约 6 秒。
- `scripts/chisel-fast.sh test-issue`：约 9 秒，其中真实测试约 2 秒。
- `scripts/chisel-fast.sh elab IssueQueue`：约 8 秒。
- `VIVADO_STAGE=synth scripts/vivado-fast.sh ooc IssueQueue`：约 28 秒。
- `scripts/vivado-fast.sh ooc IssueQueue`：约 30 到 40 秒。

BoardTop 的 route 和 bitstream 仍然会明显更久，应该留到关键节点运行。
