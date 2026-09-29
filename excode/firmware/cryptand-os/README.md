# Cryptand OS（FreeRTOS 版基本系统）

> 2026-09-17 建立。目标：对标 OpenComputers 里 **Lua BIOS + machine.lua 内核 + 组件 API + OpenOS** 那一套，
> 但跑在 **Cryptand 的 RV32IM 沙箱**上——FreeRTOS 负责调度，我们负责 HAL 与组件层。

## 为什么需要它（回应用户三个问题）

| 现象 / 问题 | 根因 | 本工程怎么解 |
|---|---|---|
| **OC 显示屏没有任何输出** | 架构（`CryptandOcArchitecture`）此前是空壳：`runThreaded` 只 `Sleep(1)`，没有 BIOS、没有内核、没有往 GPU 写一个字节的路径。OC 原生是靠 EEPROM 里的 **Lua BIOS** 去初始化 GPU/终端才有画面 | 内核起来后由控制台任务调 `gpu_set()/gpu_fill()`（走 MMIO ABI → 宿主组件桥 → `machine.invoke`）把画面打上去 |
| **EEPROM 在 OC 里是 Lua 启动，是否要 C 专属的？** | 是，必须。OC 的 EEPROM 存的是 **Lua 源码**，由 Lua 架构解释执行；我们的架构是 RV32 机器码，读 Lua 文本毫无意义 | 新增 **C 专属启动介质**（`cryptand:bios_c`，占 OC 的 EEPROM 槽）承载本文这个 `.bin`；架构启动时优先从它读镜像，没有就用内置默认镜像 |
| **SOC/APU 命名** | 命名不统一 | 显示名统一为 **SOC**（`chip_soc` / `soc_assembled` / `SocSpec.Kind.SOC` / 面板文案已改） |

## 目录内容

| 文件 | 作用 |
|---|---|
| `start.S` | 复位入口：装栈顶 → 搬 `.data`（ROM→RAM）→ 清 `.bss` → `call main`。**必须在 `.text.start`**（复位后 PC=0） |
| `FreeRTOSConfig.h` | 内核配置：抢占式调度、100Hz tick、12KB 堆、256 字 ISR 栈；**CLINT 地址对准 `TimerDevice`** |
| `cryptand_os.ld` | ROM 64KB @0x0 / RAM 64KB @0x2000_0000（与 `SocBoard` 装配一致） |
| `libc_shim.c` | 裸机必需的最小 libc：`memset/memcpy/memmove/memcmp/strlen`（GCC 会把结构体拷贝优化成它们） |
| `main.c` | 内核入口 + 系统任务：控制台（UART 横幅、后续接 GPU）、心跳（写 REG 供世界侧观测）、`component_invoke()`（C 侧的 `component.invoke`） |
| `build.ps1` | 构建脚本（纯 ASCII：PS 5.1 读无 BOM 的 UTF-8 脚本会乱码） |

## 构建

```powershell
# 1) 工具链：游戏内 /cryptand soc tools ui 一键下载，或设 CRYPTAND_RISCV_BIN
# 2) FreeRTOS 内核源码（不入库，按仓库约定放 .ai_cache）
git clone --depth 1 https://github.com/FreeRTOS/FreeRTOS-Kernel.git .ai_cache/FreeRTOS-Kernel
# 3) 构建
powershell -ExecutionPolicy Bypass -File firmware/cryptand-os/build.ps1
```

产物：`build/soc-fw/cryptand-os.{elf,bin,map}`（当前 **6216 字节**）。

## 硬件地址约定（改了 `SocBoard` 必须同步改这里）

```
0x0000_0000  ROM（复位向量，64KB）
0x1000_0000  REGBANK   OC 组件桥 ABI（OcAbi：CALL/STATUS/COMPONENT/METHOD/ARG/RESULT/BUF）
0x1000_1000  TIMER     CLINT 风格：mtime_lo@+0x00、mtimecmp_lo@+0x08  ← FreeRTOS 直接吃这对地址
0x1000_2000  （已删除）UART 16550 寄存器组：2026-09-27 起 UART 是 guest RAM 里的外设缓存
0x2002_4820  UART 寄存器窗口（头 64 + 发送窗口 256 + 接收窗口 256；虚拟机侧的 UART 硬件每 tick 同步，
             固件侧零 MMIO 事务：只写 DR / 读 DR / 轮询状态位 TXE TC RXNE OVR）
0x2000_0000  RAM       64KB（栈顶在末端）
```

⚠ **中断号必须是标准 RISC-V 号**：FreeRTOS 的 RISC-V port 只打开 `mie` 的 `1<<7`（MTIP），
所以定时器要注册到 **IRQ 7**。为此给内核加了 `SimpleInterruptController.registerSourceAt(irq, …)`
与 `SocBoard.Builder.deviceWithIrqAt(addr, dev, name, irq)`（原先中断号 = 注册顺序，定时器会落到 1 号 ⇒ tick 永远不来）。

## 踩过的坑（都已在代码里注释）

1. **入口必须在 ROM 首地址**：C 函数的 `.text` 顺序由编译器决定（实测 `uart_putc` 排到了最前），
   复位 PC=0 会跑错函数 ⇒ 入口用 `__attribute__((section(".text.start"), naked))`。
2. **复位时 `sp=0`**：C 序言 `addi sp,sp,-64` 会写到 `0xFFFFFFC0` ⇒ 立即内存故障。必须在裸汇编里先装栈顶。
3. **`static` 函数被汇编 `call`**：C 层没引用 ⇒ 编译器判定"未使用"而根本不生成 ⇒ `undefined reference`。
   入口要跳转的函数必须是外部链接。
4. **GCC 14+ 的 `rv32im` 不再隐含 `zicsr`**，而 FreeRTOS port 满屏 `csrs/csrc mstatus,8` ⇒ 必须写 `-march=rv32im_zicsr`
   （我们的 `Rv32Core` 已支持全部 CSR 指令含立即数形式 `CSRRWI/CSRRSI/CSRRCI` ✅）。
5. **`-nostdlib` 仍会引用 `memset/memcpy`**（编译器内建优化）⇒ 必须自带 `libc_shim.c`。
6. **PowerShell 5.1 + 无 BOM 的 UTF-8 脚本 = 乱码解析失败** ⇒ 本仓库的 `.ps1` 一律纯 ASCII。

## 下一步（按顺序）

1. **在 Java 内核里跑起来**：`SocBoard` 装 `cryptand-os.bin`（ROM@0 + RAM + RegBank + **Timer@IRQ7** + UART），
   用 `Rv32Core` 驱动，验证：tick 中断真的进 FreeRTOS 的 mtimer handler、任务切换、UART 打出启动横幅。
2. **宿主侧组件桥**：把 `ComponentBus` 接到 OC 的 `machine.invoke(address, method, args)`，
   兑现 `gpu.set/fill`（含字符串缓冲区：`REG_BUF_ADDR/REG_BUF_LEN` ⇒ 宿主读 guest 内存）⇒ **屏幕出画面**。
3. **C 专属启动介质**：`cryptand:bios_c`（EEPROM 槽驱动 + 承载镜像），`CryptandOcArchitecture` 优先从它加载。
4. **内存自适应**：OC 机箱内存条决定 RAM（16KB~96KB），内核要按实际内存调堆/栈（现在固定 64KB）。
