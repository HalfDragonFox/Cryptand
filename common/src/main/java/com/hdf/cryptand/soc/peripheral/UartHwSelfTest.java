package com.hdf.cryptand.soc.peripheral;

import com.hdf.cryptand.soc.board.OcBoardLayout;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.board.UartRegs;
import com.hdf.cryptand.soc.device.DebugConsoleDevice;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.device.TimerDevice;
import com.hdf.cryptand.soc.oc.OcAbi;
import com.hdf.cryptand.soc.oc.OcArchitectureCore;
import com.hdf.cryptand.soc.oc.OcSandboxBridge;
import com.hdf.cryptand.soc.os.Programs;
import com.hdf.cryptand.soc.riscv.Rv32Core;
import com.hdf.cryptand.soc.sandbox.SocSandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== 外设硬件缓存闸门（UART 一路，纯 Java 零 MC，2026-09-27 用户定案）=====
 *
 * <p>把两条定案钉成可执行断言：</p>
 * <ol>
 *   <li>{@code no-mmio-stop-decree}："每次程序不应该强制暂停 rv 内核等待 mimo……可以用寄存器进行同步"；</li>
 *   <li>{@code peripheral-hw-buffer-stm32-model} + {@code vm-is-hardware-fabric}：
 *       **虚拟机 = 硬件层**，它为芯片建立模拟外设组件；UART 硬件缓存的"芯片设计值"默认是
 *       <b>1 字节保持寄存器（DR）</b>，&gt;1 字节要**装载通用 FIFO 模块**并**消耗组件槽位**；
 *       状态位照真实芯片（TXE/TC/RXNE/**OVR**），<b>溢出绝不静默丢</b>。</li>
 * </ol>
 *
 * <p>四段：① 布局与装配（单一来源）；② 假 guest RAM 跑完整寄存器协议（含 **OVR 语义**、
 * FIFO 使能/阈值/清缓冲）；③ hal.h 逐项对拍；④ ★ 真固件在**退役的 UART MMIO 窗口**上访问次数 = 0
 * （旧路径没有复活），并打印 MMIO 现场账。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runUartCacheTest}（需先跑 excode/firmware/build-all.ps1）。</p>
 */
public final class UartHwSelfTest {

    /** 退役的 16550 寄存器组窗口（旧 {@code OcBoardLayout.UART_BASE = 0x1000_2000}）：绝对不许再被访问 */
    private static final long RETIRED_UART_MMIO_BASE = 0x1000_2000L;
    private static final int RETIRED_UART_MMIO_SPAN = 0x1000;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        layout();
        registerProtocol();
        overflowSemantics();
        fifoModule();
        firmwareHeaderMirror();
        realFirmwareZeroMmio();
        report();
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 一、布局与装配（单一来源） ====================

    private static void layout() {
        check("寄存器区 64 字节 = 织物段(40) + 固件段(16) + 织物段(8)",
                UartRegs.HEADER_BYTES == 64
                        && UartRegs.HOST_A_OFFSET == 0 && UartRegs.HOST_A_BYTES == 40
                        && UartRegs.GUEST_OFFSET == 40 && UartRegs.GUEST_BYTES == 16
                        && UartRegs.HOST_B_OFFSET == 56 && UartRegs.HOST_B_BYTES == 8
                        && UartRegs.GUEST_OFFSET + UartRegs.GUEST_BYTES == UartRegs.HOST_B_OFFSET);
        check("寄存器偏移互不重叠（magic→sr→计数→cr1→三个固件计数→数据窗口）",
                UartRegs.OFF_MAGIC == 0x00 && UartRegs.OFF_SR == 0x04 && UartRegs.OFF_TX_TAKEN == 0x08
                        && UartRegs.OFF_RX_GIVEN == 0x0C && UartRegs.OFF_OVR == 0x10
                        && UartRegs.OFF_TX_SLOTS == 0x14 && UartRegs.OFF_TX_TOTAL == 0x1C
                        && UartRegs.OFF_CR1 == 0x28 && UartRegs.OFF_TX_PUSH == 0x2C
                        && UartRegs.OFF_RX_POP == 0x30 && UartRegs.OFF_OVR_ACK == 0x34
                        && UartRegs.OFF_TX_BUF == UartRegs.HEADER_BYTES
                        && UartRegs.OFF_RX_BUF == UartRegs.HEADER_BYTES + UartRegs.TX_BYTES);
        check("数据窗口容量都是 2 的幂（下标用位与，热路径无除法）",
                (UartRegs.TX_BYTES & (UartRegs.TX_BYTES - 1)) == 0
                        && (UartRegs.RX_BYTES & (UartRegs.RX_BYTES - 1)) == 0);
        check("窗口总长 = 寄存器区 + 两个数据窗口",
                UartRegs.TOTAL_BYTES == UartRegs.HEADER_BYTES + UartRegs.TX_BYTES + UartRegs.RX_BYTES);
        check("状态位与真实芯片同义且互不冲突",
                UartRegs.SR_TXE == 1 && UartRegs.SR_TC == 2 && UartRegs.SR_RXNE == 4 && UartRegs.SR_OVR == 8
                        && UartRegs.SR_TXFE == 0x10 && UartRegs.SR_RXFF == 0x20 && UartRegs.SR_RXFT == 0x40
                        && UartRegs.SR_FIFOEN == 0x80 && UartRegs.SR_FIFOMOD == 0x100);
        check("控制位：FIFOEN / 接收阈值两位 / 两个清缓冲位",
                UartRegs.CR1_FIFOEN == 1 && UartRegs.CR1_RXFTH_MASK == 6 && UartRegs.CR1_TXFTH_MASK == 0x18
                        && UartRegs.CR1_RXFLUSH == 0x20 && UartRegs.CR1_TXFLUSH == 0x40);
        check("生效容量规则：没装载 / 没使能都是经典 1 字节 DR；使能后 = 模块深度",
                UartRegs.effectiveSlots(0, true) == 1
                        && UartRegs.effectiveSlots(256, false) == 1
                        && UartRegs.effectiveSlots(256, true) == 256
                        && UartRegs.effectiveSlots(16, true) == 16);

        check("板级布局：UART 寄存器窗口 = 消息缓存区 + 消息缓存区大小",
                OcBoardLayout.UART_CACHE_BASE == OcBoardLayout.MSG_QUEUE_BASE + OcBoardLayout.MSG_QUEUE_BYTES);
        check("窗口总长常量 = 布局常量的唯一来源（OcBoardLayout 从 UartRegs 取）",
                OcBoardLayout.UART_CACHE_BYTES == UartRegs.TOTAL_BYTES);
        check("RAM 尾部窗口总长 = 邮箱段 + 显存 + 消息缓存区 + UART 窗口（装配唯一算式）",
                OcBoardLayout.GUEST_WINDOWS_BYTES == OcAbi.MAILBOX_SPAN + OcBoardLayout.VRAM_BYTES
                        + OcBoardLayout.MSG_QUEUE_BYTES + OcBoardLayout.UART_CACHE_BYTES);
        final long ramEnd = OcBoardLayout.RAM_BASE + 128 * 1024 + OcBoardLayout.GUEST_WINDOWS_BYTES;
        check("★ UART 窗口整段落在 guest RAM 内（0x" + Long.toHexString(OcBoardLayout.UART_CACHE_BASE)
                        + " + " + OcBoardLayout.UART_CACHE_BYTES + " <= 0x" + Long.toHexString(ramEnd) + "）",
                OcBoardLayout.UART_CACHE_BASE >= OcBoardLayout.RAM_BASE
                        && OcBoardLayout.UART_CACHE_BASE + OcBoardLayout.UART_CACHE_BYTES <= ramEnd);
        check("UART 窗口不与固件 128KB 运行时 RAM / 末尾配置块重叠",
                OcBoardLayout.UART_CACHE_BASE >= OcBoardLayout.RAM_BASE + 128 * 1024);
    }

    // ==================== 二、寄存器协议（假 guest RAM，照 hal.c 的固件侧写法） ====================

    private static void registerProtocol() {
        // ① 默认（没装载 FIFO 模块）= 经典 1 字节 DR
        final UartHardware dr = new UartHardware("T-DR", 1);
        final List<Integer> line = new ArrayList<>();
        dr.setByteSink(line::add);
        final FakeChip chip = new FakeChip();
        chip.publish(dr);
        check("初始镜像：magic 就绪、TXE/TC/TXFE 置位、生效容量 = 1（还没使能 FIFO）",
                chip.ready() && (chip.sr() & UartRegs.SR_TXE) != 0 && (chip.sr() & UartRegs.SR_TC) != 0
                        && (chip.sr() & UartRegs.SR_TXFE) != 0 && chip.read32(UartRegs.OFF_TX_SLOTS) == 1);
        check("没装载模块时 SR.FIFOMOD = 0（芯片知道自己没有硬件 FIFO）",
                (chip.sr() & UartRegs.SR_FIFOMOD) == 0);

        check("写 DR 一个字节（先轮询 TXE）", chip.putc('H'));
        dr.sync(chip, 0);                                  // 织物 tick
        check("织物把字节搬进硬件缓存（DR 满 ⇒ TXE 清 0）",
                dr.txAccepted() == 1 && (chip.sr() & UartRegs.SR_TXE) == 0);
        check("写读计数守规矩：tx_push = 1、tx_taken = 1",
                chip.read32(UartRegs.OFF_TX_PUSH) == 1 && chip.read32(UartRegs.OFF_TX_TAKEN) == 1);
        check("按波特率吐给世界侧出口（1 字节 = cyclesPerByte 个周期）", () -> {
            dr.sync(chip, OcBoardLayout.UART_CYCLES_PER_BYTE);
            return line.size() == 1 && line.get(0) == 'H';
        });
        check("发完 ⇒ TXE 与 TC/TXFE 重新置位（软件可以继续写）",
                (chip.sr() & UartRegs.SR_TXE) != 0 && (chip.sr() & UartRegs.SR_TC) != 0);

        // ② 接收：世界侧注料 → 器件 → RXNE → 芯片读 DR
        check("世界侧注入 2 字节只进织物队列（芯片看不到这一侧）",
                dr.offerRx('h') && dr.offerRx('i') && dr.inboxPending() == 2 && dr.rxTotal() == 0);
        dr.sync(chip, 0);
        check("★ 器件把它变成状态位 RXNE（用户定案「接收 ready 寄存器会置 1」）",
                (chip.sr() & UartRegs.SR_RXNE) != 0 && dr.rxWindowLevel() == 1);
        check("芯片读 DR 取到字节，读计数发布（rx_pop = 1）",
                chip.getc() == 'h' && chip.read32(UartRegs.OFF_RX_POP) == 1);
        check("取空后 RXNE 清 0（判据是状态位，不是猜）", () -> {
            dr.sync(chip, 0);
            return (chip.sr() & UartRegs.SR_RXNE) == 0;
        });

        // ③ 清缓冲（CR1 的上升沿位）；注入 4 字节时缓冲只有 1 格 ⇒ 3 个记 OVR
        chip.offer(dr, "abcd");
        dr.sync(chip, OcBoardLayout.UART_CYCLES_PER_BYTE * 4L);
        check("1 字节 DR 下注入 4 字节：1 个进窗口、3 个记 OVR（收到 " + dr.ovrTotal() + "）",
                dr.rxWindowLevel() == 1 && dr.ovrRx() == 3);
        chip.writeCr1(UartRegs.CR1_RXFLUSH);
        dr.sync(chip, 0);
        check("CR1.RXFLUSH 清接收缓冲（照真实芯片的清 FIFO 位）", dr.rxWindowLevel() == 0);
    }

    // ==================== 三、★ OVR 语义（1 字节缓冲：没及时取走就溢出，且不静默） ====================

    private static void overflowSemantics() {
        // ① 发送侧：软件没看 TXE，一个 tick 内连写两个字节 ⇒ 后到的被覆盖，记 OVR
        final UartHardware hw = new UartHardware("T-OVR", 1);
        final List<Integer> line = new ArrayList<>();
        hw.setByteSink(line::add);
        final FakeChip chip = new FakeChip();
        chip.publish(hw);
        check("★ 1 字节 DR 下连写两次 DR（软件没轮询 TXE）", chip.forcePush('A') && chip.forcePush('B'));
        hw.sync(chip, 0);
        check("★ 只有 1 个字节进了硬件缓存，另一个**记 OVR 丢弃**（绝不静默）",
                hw.txAccepted() == 1 && hw.ovrTx() == 1 && hw.ovrTotal() == 1);
        check("★ SR.OVR 置位（粘滞：软件确认之前一直是 1）", (chip.sr() & UartRegs.SR_OVR) != 0);
        check("★ 溢出的字节数是芯片可见的计数（ovr 字段 = 1）", chip.read32(UartRegs.OFF_OVR) == 1);
        check("软件确认（hal_uart_ack_ovr 的等价动作）后**本轮** SR.OVR 就归零", () -> {
            chip.write32(UartRegs.OFF_OVR_ACK, chip.read32(UartRegs.OFF_OVR));
            hw.sync(chip, 0);
            return (chip.sr() & UartRegs.SR_OVR) == 0;
        });
        check("被丢弃的那个字节没有从线路出来（线路只有 1 个字节）", () -> {
            hw.sync(chip, OcBoardLayout.UART_CYCLES_PER_BYTE);
            return line.size() == 1;
        });

        // ② 接收侧：1 字节缓冲没腾空，新到的字节丢失并计数（照真实芯片：新字节丢、老的保留）
        final UartHardware rx = new UartHardware("T-OVR-RX", 1);
        final FakeChip c2 = new FakeChip();
        c2.publish(rx);
        rx.offerRx('a');
        rx.offerRx('b');
        rx.offerRx('c');
        rx.sync(c2, OcBoardLayout.UART_CYCLES_PER_BYTE * 8);      // 8 个字节时间 ⇒ 三个都"到达"
        check("★ 接收缓冲只有 1 格：1 个留下、2 个记 OVR 丢掉（收到 OVR=" + rx.ovrRx()
                + "，窗口占用=" + rx.rxWindowLevel() + "）", rx.rxWindowLevel() == 1 && rx.ovrRx() == 2);
        check("★ 留下的还是**先到的那个**（照真实芯片：溢出丢新字节）", c2.getc() == 'a');
        check("接收侧溢出同样让 SR.OVR 置位", (c2.sr() & UartRegs.SR_OVR) != 0);
        final byte[] big = new byte[UartHardware.INBOX_MAX + 5];
        final int acceptedBig = rx.offerRx(big, 0, big.length);
        check("世界侧队列到上限就停下并计数丢弃（队列 " + rx.inboxPending() + "，接受 " + acceptedBig
                        + "，丢弃 " + rx.inboxDropped() + "，绝不静默）",
                acceptedBig == UartHardware.INBOX_MAX && rx.inboxPending() == UartHardware.INBOX_MAX
                        && rx.inboxDropped() == 1);
    }

    // ==================== 四、通用 FIFO 模块（装载 + 使能 + 阈值 + 清缓冲 + 资源计价） ====================

    private static void fifoModule() {
        final UartHardware hw = new UartHardware("T-FIFO", 16);
        final FakeChip chip = new FakeChip();
        chip.publish(hw);
        check("装载 16 字节 FIFO 模块：SR.FIFOMOD = 1、占 3 个组件槽位",
                (chip.sr() & UartRegs.SR_FIFOMOD) != 0 && hw.moduleDepth() == 16 && hw.moduleSlots() == 3);
        check("没使能前生效容量仍是 1（照真实芯片：FIFO 要软件使能）",
                chip.read32(UartRegs.OFF_TX_SLOTS) == 1 && hw.capacity() == 1);
        chip.writeCr1(UartRegs.CR1_FIFOEN | (1 << UartRegs.CR1_RXFTH_SHIFT));
        hw.sync(chip, 0);
        check("★ 软件使能 CR1.FIFOEN 后生效容量 = 模块深度（16；当前报告 "
                        + chip.read32(UartRegs.OFF_TX_SLOTS) + "）",
                hw.fifoEnabled() && hw.capacity() == 16
                        && chip.read32(UartRegs.OFF_TX_SLOTS) == 16
                        && (chip.sr() & UartRegs.SR_FIFOEN) != 0);
        check("★ 使能后一个 tick 能连写 16 字节（硬件 FIFO 吸收，不用逐字节等宿主）", () -> {
            for (int i = 0; i < 16; i++) {
                if (!chip.forcePush('0' + (i % 10))) {
                    return false;
                }
            }
            hw.sync(chip, 0);
            return hw.txAccepted() == 16 && hw.ovrTx() == 0;
        });
        check("阈值位生效：接收占用到 1/2（8）⇒ SR.RXFT 置位", () -> {
            for (int i = 0; i < 8; i++) {
                hw.offerRx('x');
            }
            hw.sync(chip, OcBoardLayout.UART_CYCLES_PER_BYTE * 8);
            return hw.rxWindowLevel() == 8 && (chip.sr() & UartRegs.SR_RXFT) != 0;
        });
        check("FIFO 清缓冲位（RXFLUSH）清掉占用（当前 " + hw.rxWindowLevel() + "）", () -> {
            chip.writeCr1(UartRegs.CR1_FIFOEN | UartRegs.CR1_RXFLUSH);
            hw.sync(chip, 0);
            return hw.rxWindowLevel() == 0;
        });

        check("★ FIFO 槽位阶梯：每翻一倍多占一个组件槽位（4→1 … 256→7），不装载 = 0 槽",
                PeripheralFifo.slotsFor(1) == 0 && PeripheralFifo.slotsFor(4) == 1
                        && PeripheralFifo.slotsFor(8) == 2 && PeripheralFifo.slotsFor(16) == 3
                        && PeripheralFifo.slotsFor(64) == 5 && PeripheralFifo.slotsFor(256) == 7);
        check("★ 本芯片装载的 UART FIFO 模块与设备树声明一致（深度 "
                        + com.hdf.cryptand.soc.board.SocModules.UART_FIFO_DEPTH + "，占 "
                        + com.hdf.cryptand.soc.board.SocModules.fifoSlotCost() + " 槽）",
                com.hdf.cryptand.soc.board.SocModules.UART_FIFO_DEPTH == UartRegs.TX_BYTES
                        && com.hdf.cryptand.soc.board.SocModules.fifoSlotCost()
                            == PeripheralFifo.slotsFor(UartRegs.TX_BYTES)
                        && new UartHardware("x", UartRegs.TX_BYTES).moduleSlots()
                            == com.hdf.cryptand.soc.board.SocModules.fifoSlotCost());
        check("★ 装载模块要占槽位：MCU 16 槽装得下，4 槽装不下（明确报错，不静默降级）", () -> {
            try {
                com.hdf.cryptand.soc.board.SocModules.validateFifoBudget(
                        com.hdf.cryptand.soc.board.SocCpuTiers.Family.MCU, 16);
                com.hdf.cryptand.soc.board.SocModules.validateFifoBudget(
                        com.hdf.cryptand.soc.board.SocCpuTiers.Family.MCU, 4);
                return false;                        // 4 槽必须抛
            } catch (IllegalStateException e) {
                return e.getMessage().contains("差");
            }
        });
    }

    // ==================== 五、C 侧镜像（hal.h）逐项对拍 ====================

    private static void firmwareHeaderMirror() {
        final Path header = locateFirmwareHeader();
        if (header == null) {
            check("找得到固件的 hal.h（布局的 C 侧镜像）", false);
            return;
        }
        final Map<String, Long> defines = new HashMap<>();
        try {
            final String[] lines = new String(Files.readAllBytes(header), StandardCharsets.ISO_8859_1)
                    .split("\\R");
            for (final String rawLine : lines) {
                final String line = rawLine.trim();
                if (!line.startsWith("#define")) {
                    continue;
                }
                final String[] parts = line.split("\\s+");
                if (parts.length < 3 || !parts[1].startsWith("HAL_UART_")) {
                    continue;
                }
                final String value = parts[2].replace("u", "").replace("U", "").replace("L", "");
                try {
                    defines.put(parts[1], Long.decode(value));
                } catch (NumberFormatException ignored) {
                    // 非数字：对拍时判成缺失
                }
            }
        } catch (Exception e) {
            check("读得到固件的 hal.h（" + header + "）", false);
            return;
        }
        check("解析到 hal.h 里的 HAL_UART_* 定义（" + defines.size() + " 条）", defines.size() >= 24);
        expect(defines, "HAL_UART_CACHE_BASE", OcBoardLayout.UART_CACHE_BASE);
        expect(defines, "HAL_UART_CACHE_BYTES", UartRegs.TOTAL_BYTES);
        expect(defines, "HAL_UART_MAGIC", UartRegs.MAGIC);
        expect(defines, "HAL_UART_TX_BYTES", UartRegs.TX_BYTES);
        expect(defines, "HAL_UART_RX_BYTES", UartRegs.RX_BYTES);
        expect(defines, "HAL_UART_OFF_MAGIC", UartRegs.OFF_MAGIC);
        expect(defines, "HAL_UART_OFF_SR", UartRegs.OFF_SR);
        expect(defines, "HAL_UART_OFF_TX_TAKEN", UartRegs.OFF_TX_TAKEN);
        expect(defines, "HAL_UART_OFF_RX_GIVEN", UartRegs.OFF_RX_GIVEN);
        expect(defines, "HAL_UART_OFF_OVR", UartRegs.OFF_OVR);
        expect(defines, "HAL_UART_OFF_TX_SLOTS", UartRegs.OFF_TX_SLOTS);
        expect(defines, "HAL_UART_OFF_RX_LEVEL", UartRegs.OFF_RX_LEVEL);
        expect(defines, "HAL_UART_OFF_TX_TOTAL", UartRegs.OFF_TX_TOTAL);
        expect(defines, "HAL_UART_OFF_RX_TOTAL", UartRegs.OFF_RX_TOTAL);
        expect(defines, "HAL_UART_OFF_CR1", UartRegs.OFF_CR1);
        expect(defines, "HAL_UART_OFF_TX_PUSH", UartRegs.OFF_TX_PUSH);
        expect(defines, "HAL_UART_OFF_RX_POP", UartRegs.OFF_RX_POP);
        expect(defines, "HAL_UART_OFF_OVR_ACK", UartRegs.OFF_OVR_ACK);
        expect(defines, "HAL_UART_OFF_TX_BUF", UartRegs.OFF_TX_BUF);
        expect(defines, "HAL_UART_OFF_RX_BUF", UartRegs.OFF_RX_BUF);
        expect(defines, "HAL_UART_SR_TXE", UartRegs.SR_TXE);
        expect(defines, "HAL_UART_SR_TC", UartRegs.SR_TC);
        expect(defines, "HAL_UART_SR_RXNE", UartRegs.SR_RXNE);
        expect(defines, "HAL_UART_SR_OVR", UartRegs.SR_OVR);
        expect(defines, "HAL_UART_SR_TXFE", UartRegs.SR_TXFE);
        expect(defines, "HAL_UART_SR_RXFF", UartRegs.SR_RXFF);
        expect(defines, "HAL_UART_SR_RXFT", UartRegs.SR_RXFT);
        expect(defines, "HAL_UART_SR_FIFOEN", UartRegs.SR_FIFOEN);
        expect(defines, "HAL_UART_SR_FIFOMOD", UartRegs.SR_FIFOMOD);
        expect(defines, "HAL_UART_CR1_FIFOEN", UartRegs.CR1_FIFOEN);
        expect(defines, "HAL_UART_CR1_RXFLUSH", UartRegs.CR1_RXFLUSH);
        expect(defines, "HAL_UART_CR1_TXFLUSH", UartRegs.CR1_TXFLUSH);
    }

    private static void expect(Map<String, Long> defines, String name, long javaValue) {
        final Long c = defines.get(name);
        check("hal.h 对拍：" + name + " = " + javaValue
                + (c == null ? "（C 侧缺失）" : ("（C 侧 " + c + "）")), c != null && c == javaValue);
    }

    private static Path locateFirmwareHeader() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            final Path candidate = dir.resolve("excode").resolve("firmware").resolve("common").resolve("hal.h");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    // ==================== 六、★ 真固件：UART 链零 MMIO（退役窗口挂陷阱） ====================

    private static void realFirmwareZeroMmio() {
        final byte[] mini = Programs.readById("mini-os");
        check("固件已编译（mini-os 镜像在 common 资源里）", mini.length > 0);
        if (mini.length > 0) {
            runFirmwareAndProbe("mini OS", mini, (int) OcBoardLayout.SYS_LOAD_BASE, 900);
        }
        final byte[] os = Programs.readById("cryptand-os");
        if (os.length > 0) {
            runFirmwareAndProbe("Cryptand OS", os, (int) OcBoardLayout.SYS_LOAD_BASE, 1500);
        }
    }

    private static void runFirmwareAndProbe(String label, byte[] image, int loadBase, int ticks) {
        final long t0 = System.nanoTime();
        final StringBuilder uartOut = new StringBuilder();
        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(loadBase).enableM(true));
        final RegBankDevice regBank = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        final TimerDevice timer = new TimerDevice("PROBE-TIMER");
        final Probe regProbe = new Probe("REGBRIDGE(OC ABI)", regBank);
        final Probe timerProbe = new Probe("TIMER(CLINT)", timer);
        final Probe debugProbe = new Probe("DEBUG0", new DebugConsoleDevice("PROBE-DEBUG"));
        final Probe trap = new Probe("UART-16550(已退役窗口)", new TrapDevice());
        final UartHardware uart = new UartHardware("PROBE-UART", UartRegs.TX_BYTES);
        uart.setByteSink(b -> {
            if (b == '\n' || (b >= 32 && b < 127)) {
                uartOut.append((char) b);
            }
        });

        final SocBoard board = SocBoard.builder(cpu)
                .rom(loadBase, image)
                .ram(OcBoardLayout.RAM_BASE, 128 * 1024 + OcBoardLayout.GUEST_WINDOWS_BYTES)
                .deviceWithIrq(OcBoardLayout.REG_BASE, regProbe, "REGBRIDGE")
                .deviceWithIrqAt(OcBoardLayout.TIMER_BASE, timerProbe, "TIMER", OcBoardLayout.IRQ_MTIP)
                .device(OcBoardLayout.DEBUG_BASE, debugProbe, "DEBUG0")
                .device(RETIRED_UART_MMIO_BASE, trap, "TRAP-UART-16550")
                .ram(OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES)
                .build();
        cpu.writeMemory(OcBoardLayout.UART_CACHE_BASE, uart.initialImage());
        final OcArchitectureCore core = new OcArchitectureCore(board, regBank, null);
        OcSandboxBridge.create(board, OcBoardLayout.REG_BASE, () -> 0L, core::pump);
        final SocSandbox sb = new SocSandbox(board,
                new SocSandbox.Limits().maxMemoryBytes(2 * 1024 * 1024).maxDevices(16));
        for (int i = 0; i < ticks && !cpu.isFaulted(); i++) {
            sb.tick();
            uart.sync(UartRegs.of(board), cpu.getInstructionsRetired());
            core.pump();
            core.pollMailbox();
            core.drainCalls(50);
        }
        for (int i = 0; i < 300; i++) {
            uart.sync(UartRegs.of(board), cpu.getInstructionsRetired() + i * 4096L);
        }
        final String out = uartOut.toString();
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  --- [" + label + "] " + ticks + " tick / " + ms + " ms，UART 出口 "
                + out.length() + " 字符，CPU 故障=" + cpu.isFaulted() + "，状态位=0x"
                + Integer.toHexString(uart.lastSr()) + "，溢出=" + uart.ovrTotal() + " ---");
        System.out.println("  --- MMIO 现场账（这就是「其余设备还剩哪些停摆点」）---");
        for (final Probe p : List.of(regProbe, timerProbe, debugProbe, trap)) {
            System.out.println("      " + p.report());
        }
        check("★ [" + label + "] UART 输出经硬件缓存到达出口（" + out.length() + " 字符，含 Cryptand="
                        + out.contains("Cryptand") + "）",
                out.length() > 0 && out.contains("Cryptand"));
        check("★ [" + label + "] 退役的 UART MMIO 窗口访问次数 = 0（旧的逐字节事务路径没有复活）",
                trap.loads() == 0 && trap.stores() == 0);
        check("★ [" + label + "] 调试口没有逐字节 UART 镜像（store 次数 = " + debugProbe.stores() + "）",
                debugProbe.stores() == 0);
        check("[" + label + "] 固件写回的计数器自洽（两侧布局一致）", uart.anomalies() == 0);
        check("[" + label + "] 芯片确实在写这块硬件（已发出 " + uart.txTotal() + " 字节）",
                uart.txTotal() > 0);
    }

    /** 一次设备访问的探针：转发给真设备，只数次数（不改变任何语义） */
    private static final class Probe implements com.hdf.cryptand.soc.api.MemoryMappedDevice,
            com.hdf.cryptand.soc.api.Steppable, com.hdf.cryptand.soc.api.InterruptSource,
            com.hdf.cryptand.soc.api.Resettable {
        private final String name;
        private final com.hdf.cryptand.soc.api.MemoryMappedDevice delegate;
        private long loads;
        private long stores;

        Probe(String name, com.hdf.cryptand.soc.api.MemoryMappedDevice delegate) {
            this.name = name;
            this.delegate = delegate;
        }

        long loads() {
            return loads;
        }

        long stores() {
            return stores;
        }

        String report() {
            return String.format("%-26s 读 %6d 次 / 写 %6d 次（合计 %d 次跨线程事务）",
                    name, loads, stores, loads + stores);
        }

        @Override
        public int getLength() {
            return delegate.getLength();
        }

        @Override
        public int getSupportedSizes() {
            return delegate.getSupportedSizes();
        }

        @Override
        public long load(int offset, int size) {
            loads++;
            return delegate.load(offset, size);
        }

        @Override
        public void store(int offset, long value, int size) {
            stores++;
            delegate.store(offset, value, size);
        }

        @Override
        public void step(int cycles) {
            if (delegate instanceof com.hdf.cryptand.soc.api.Steppable s) {
                s.step(cycles);
            }
        }

        @Override
        public boolean isInterrupting(int irq) {
            return delegate instanceof com.hdf.cryptand.soc.api.InterruptSource i && i.isInterrupting(irq);
        }

        @Override
        public void reset() {
            if (delegate instanceof com.hdf.cryptand.soc.api.Resettable r) {
                r.reset();
            }
        }
    }

    private static final class TrapDevice implements com.hdf.cryptand.soc.api.MemoryMappedDevice {
        @Override
        public int getLength() {
            return RETIRED_UART_MMIO_SPAN;
        }

        @Override
        public int getSupportedSizes() {
            return 0xFF;
        }

        @Override
        public long load(int offset, int size) {
            return 0;
        }

        @Override
        public void store(int offset, long value, int size) {
        }
    }

    // ==================== 假 guest RAM（芯片侧模型，逐行照 hal.c 的写法） ====================

    private static final class FakeChip implements UartRegs.GuestRam {
        private final byte[] mem = new byte[UartRegs.TOTAL_BYTES];
        private int txPush;
        private int rxPop;

        void publish(UartHardware hw) {
            final byte[] img = hw.initialImage();
            System.arraycopy(img, 0, mem, 0, img.length);
            txPush = read32(UartRegs.OFF_TX_PUSH);
            rxPop = read32(UartRegs.OFF_RX_POP);
        }

        @Override
        public byte[] read(long address, int length) {
            final byte[] out = new byte[length];
            System.arraycopy(mem, (int) (address - OcBoardLayout.UART_CACHE_BASE), out, 0, length);
            return out;
        }

        @Override
        public void write(long address, byte[] data) {
            System.arraycopy(data, 0, mem, (int) (address - OcBoardLayout.UART_CACHE_BASE), data.length);
        }

        boolean ready() {
            return read32(UartRegs.OFF_MAGIC) == UartRegs.MAGIC;
        }

        int sr() {
            return read32(UartRegs.OFF_SR);
        }

        void writeCr1(int v) {
            write32(UartRegs.OFF_CR1, v);
        }

        /** hal_uart_putc：先轮询状态位与硬不变式，再写 DR（正常路径） */
        boolean putc(int c) {
            if ((sr() & UartRegs.SR_TXE) == 0
                    || txPush - read32(UartRegs.OFF_TX_TAKEN) >= UartRegs.TX_BYTES) {
                return false;
            }
            return forcePush(c);
        }

        /** 不看状态位直接写 DR（用来验证"软件连写"时的 OVR 语义） */
        boolean forcePush(int c) {
            if (txPush - read32(UartRegs.OFF_TX_TAKEN) >= UartRegs.TX_BYTES) {
                return false;
            }
            mem[UartRegs.OFF_TX_BUF + (txPush & (UartRegs.TX_BYTES - 1))] = (byte) c;
            txPush++;
            write32(UartRegs.OFF_TX_PUSH, txPush);
            return true;
        }

        /** hal_uart_getc：看状态位 RXNE 才读 DR */
        int getc() {
            if ((sr() & UartRegs.SR_RXNE) == 0) {
                return -1;
            }
            final int c = mem[UartRegs.OFF_RX_BUF + (rxPop & (UartRegs.RX_BYTES - 1))] & 0xFF;
            rxPop++;
            write32(UartRegs.OFF_RX_POP, rxPop);
            return c;
        }

        void offer(UartHardware hw, String s) {
            for (final byte b : s.getBytes(StandardCharsets.US_ASCII)) {
                hw.offerRx(b & 0xFF);
            }
        }

        int read32(int off) {
            return UartRegs.rd32(mem, off);
        }

        void write32(int off, int v) {
            UartRegs.wr32(mem, off, v);
        }
    }

    // ==================== 报告 ====================

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static void check(String name, BoolSupplier body) {
        boolean ok;
        try {
            ok = body.get();
        } catch (Throwable t) {
            ok = false;
            System.out.println("        （断言抛异常：" + t + "）");
        }
        check(name, ok);
    }

    private interface BoolSupplier {
        boolean get();
    }

    private static void report() {
        System.out.println();
        System.out.println("=== UART 硬件缓存闸门（runUartCacheTest）：PASS " + passed + " / FAIL " + failed + " ===");
    }
}
