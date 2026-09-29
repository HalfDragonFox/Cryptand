package com.hdf.cryptand.soc;

import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.device.AdcDevice;
import com.hdf.cryptand.soc.device.PwmDevice;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.device.TimerDevice;
import com.hdf.cryptand.soc.riscv.Rv32Asm;
import com.hdf.cryptand.soc.riscv.Rv32Core;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== SoC 外设自测（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>覆盖 RegBank / Timer / PWM / ADC 的宿主 API 与 MMIO 行为，以及<b>端到端</b>：
 * 固件通过 MMIO 读写这些外设（读 timer、写寄存器区、配 PWM、读 ADC）。</p>
 *
 * <p>⚠ <b>UART 已不在本用例的 MMIO 名单里</b>（2026-09-27 用户定案）：它的 16550 MMIO 寄存器组
 * 按定案整条删除，改成"虚拟机建立的一块硬件 + 寄存器窗口 + 状态位（TXE/TC/RXNE/OVR）+
 * 可装载的通用 FIFO 模块"。这里只断言**这块硬件的设计规格与模块计价**；
 * 寄存器/状态位/OVR 语义的完整回归在 {@code :common:runUartCacheTest}。</p>
 *
 * <p>运行：{@code gradlew :common:runPeripheralTest}。</p>
 */
public final class PeripheralSelfTest {

    private static final long ROM_BASE = 0x0000_0000L;
    private static final long REG_BASE = 0x1000_0000L;
    private static final long TIMER_BASE = 0x1000_1000L;
    private static final long PWM_BASE = 0x1000_3000L;
    private static final long ADC_BASE = 0x1000_4000L;
    private static final long RAM_BASE = 0x2000_0000L;

    private static int passed;
    private static int failed;
    private static final StringBuilder log = new StringBuilder();

    public static void main(String[] args) {
        System.out.println("=== Cryptand SoC Peripheral Self Test (common/soc/device) ===");

        t1_regBank();
        t2_timer();
        t3_uart();
        t4_pwm();
        t5_adc();
        t6_endToEnd();

        System.out.println();
        System.out.print(log);
        System.out.println();
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** T1：寄存器区（读写 / 中断 / 快照恢复） */
    private static void t1_regBank() {
        final RegBankDevice reg = new RegBankDevice(4, "TEST-REG");
        check("T1 长度 = 4×4 字节", reg.getLength() == 16);

        reg.set(0, 0x11223344);
        check("T1 宿主写入可读回", reg.get(0) == 0x11223344);
        check("T1 MMIO 读回一致", reg.load(0, 4) == 0x11223344L);

        reg.setInterruptOnWrite(true);
        reg.store(4, 0x55, 1);                    // guest 写 → 拉中断
        check("T1 guest 写触发中断", reg.isInterrupting(0));
        reg.clearInterrupt();
        check("T1 清中断", !reg.isInterrupting(0));

        reg.setInterruptOnWrite(false);
        reg.store(8, 7, 4);
        check("T1 关闭后写不拉中断", !reg.isInterrupting(0));

        final int[] snap = reg.snapshot();
        reg.reset();
        check("T1 复位清零", reg.get(0) == 0 && reg.get(2) == 0);
        reg.restore(snap);
        check("T1 快照恢复", reg.get(0) == 0x11223344 && reg.get(2) == 7);

        check("T1 越界访问抛异常", throwsMemoryFault(() -> reg.load(999, 4)));
    }

    /** T2：定时器（周期递增 / 到期中断 / 分频 / 清标志） */
    private static void t2_timer() {
        final TimerDevice timer = new TimerDevice("TEST-TIMER");
        timer.step(500);
        check("T2 mtime 随周期递增", timer.getMtime() == 500);

        // 设置比较值 1000 → 使能
        timer.store(TimerDevice.REG_MTIMECMP_LO, 1000, 4);
        timer.store(TimerDevice.REG_CTRL, 1, 4);
        check("T2 未到期不中断", !timer.isInterrupting(0));
        timer.step(400);
        check("T2 未到期（900 < 1000）", !timer.isInterrupting(0));
        timer.step(200);
        check("T2 到期拉中断", timer.isInterrupting(0));
        check("T2 status 置位（诊断位：曾经到期）", timer.load(TimerDevice.REG_STATUS, 4) == 1);

        // 撤除方式照真实 CLINT：**把比较值推到未来**（FreeRTOS 每个 tick 就是这么做的）。
        // 曾经写成"必须写 STATUS 才清中断"，那是锁存语义 —— 真机会变成中断风暴，
        // guest 交付完立刻又进中断，任务永远拿不到 CPU。
        timer.store(TimerDevice.REG_MTIMECMP_LO, 5000, 4);
        check("T2 调大比较值后中断撤除（CLINT 电平语义）", !timer.isInterrupting(0));
        check("T2 到期诊断位仍在（status=1）", timer.load(TimerDevice.REG_STATUS, 4) == 1);
        timer.store(TimerDevice.REG_STATUS, 1, 4);
        check("T2 写 status 清诊断位", timer.load(TimerDevice.REG_STATUS, 4) == 0);

        // ★ 回归：**只写 mtimecmp（不写 ctrl）也必须能产生中断**。
        //   真实 CLINT 没有使能位，FreeRTOS 的 RISC-V port 也只写 mtimecmp ——
        //   曾经因为"要 ctrl 才使能"，真机上 tick 中断永不到、所有任务从未运行
        //   （症状："进系统后打不了字"）。这条断言就是那个 bug 的看门狗。
        final TimerDevice clint = new TimerDevice("CLINT-LIKE");
        clint.store(TimerDevice.REG_MTIMECMP_LO, 100, 4);      // 只写比较值，绝不碰 ctrl
        check("T2 【回归】只写 mtimecmp 不写 ctrl 也能启用", clint.isEnabled());
        clint.step(99);
        check("T2 【回归】未到期不中断", !clint.isInterrupting(0));
        clint.step(1);
        check("T2 【回归】到期即中断（FreeRTOS 的 tick 靠这条）", clint.isInterrupting(0));

        // 分频：div=10 → 每 10 周期 mtime 才 +1
        final TimerDevice d = new TimerDevice();
        d.store(TimerDevice.REG_DIV, 10, 4);
        d.step(95);
        check("T2 分频生效（95/10=9）", d.getMtime() == 9);

        check("T2 累计周期计数独立于分频", d.load(TimerDevice.REG_CYCLES_LO, 4) == 95);
        timer.reset();
        check("T2 复位清状态", timer.getMtime() == 0 && !timer.isInterrupting(0));
    }

    /**
     * T3：UART **硬件的"芯片设计值"与通用 FIFO 模块计价**（2026-09-27 用户定案）。
     *
     * <p>⚠ UART 不再是 MMIO 设备（旧的 16550 寄存器组按定案整条删除）⇒ 这里只测"这块硬件是什么规格、
     * 装载模块占多少资源"；寄存器/状态位/OVR 语义的完整回归在
     * {@code :common:runUartCacheTest}（用真固件 + 假 guest RAM 跑完整收发协议）。</p>
     */
    private static void t3_uart() {
        final com.hdf.cryptand.soc.peripheral.UartHardware dr =
                new com.hdf.cryptand.soc.peripheral.UartHardware("TEST-UART-DR", 1);
        check("T3 默认不装载 FIFO 模块（= 经典 1 字节保持寄存器 DR）",
                !dr.moduleLoaded() && dr.moduleDepth() == 0 && dr.moduleSlots() == 0
                        && dr.capacity() == 1 && !dr.fifoEnabled());
        final com.hdf.cryptand.soc.peripheral.UartHardware f16 =
                new com.hdf.cryptand.soc.peripheral.UartHardware("TEST-UART-F16", 16);
        check("T3 装载 16 字节 FIFO 模块 = 3 个组件槽位（4→1 / 8→2 / 16→3）",
                f16.moduleLoaded() && f16.moduleDepth() == 16 && f16.moduleSlots() == 3);
        check("T3 未使能 FIFO ⇒ 生效容量退回 1（照真实芯片：要软件使能 CR1.FIFOEN）",
                f16.capacity() == 1 && !f16.fifoEnabled());
        check("T3 世界侧注入只进织物队列（芯片看不到这一侧）",
                f16.offerRx('h') && f16.inboxPending() == 1 && f16.rxTotal() == 0);
        final byte[] big = new byte[com.hdf.cryptand.soc.peripheral.UartHardware.INBOX_MAX + 8];
        final int accepted = f16.offerRx(big, 0, big.length);
        check("T3 队列到上限就停下并计数丢弃（接受 " + accepted + "、丢弃 " + f16.inboxDropped()
                        + "；绝不静默）",
                accepted == com.hdf.cryptand.soc.peripheral.UartHardware.INBOX_MAX - 1
                        && f16.inboxPending() == com.hdf.cryptand.soc.peripheral.UartHardware.INBOX_MAX
                        && f16.inboxDropped() == 1);
        check("T3 槽位阶梯：不装载 0 槽、4 字节 1 槽、256 字节 7 槽（每翻一倍多占一个）",
                com.hdf.cryptand.soc.peripheral.PeripheralFifo.slotsFor(1) == 0
                        && com.hdf.cryptand.soc.peripheral.PeripheralFifo.slotsFor(4) == 1
                        && com.hdf.cryptand.soc.peripheral.PeripheralFifo.slotsFor(256) == 7);
    }

    /** T4：PWM（占空比 / 输出电平 / 停机） */
    private static void t4_pwm() {
        final PwmDevice pwm = new PwmDevice("TEST-PWM");
        pwm.store(0, 100, 4);                     // period
        pwm.store(4, 25, 4);                      // duty
        check("T4 未使能时占空比 0", pwm.dutyRatio() == 0.0);
        pwm.store(8, 1, 4);                       // enable
        check("T4 占空比 = 25/100", Math.abs(pwm.dutyRatio() - 0.25) < 1e-9);

        pwm.step(10);
        check("T4 前 25 周期输出高", pwm.outputHigh());
        pwm.step(80);
        check("T4 超过占空比输出低", !pwm.outputHigh());
        pwm.step(10);                             // 回到 0
        check("T4 计数器回绕后再次输出高", pwm.outputHigh());

        pwm.store(4, 200, 4);                     // duty > period → 限幅为满占空
        check("T4 占空比限幅到 1.0", Math.abs(pwm.dutyRatio() - 1.0) < 1e-9);

        pwm.store(8, 0, 4);
        check("T4 停机后占空比 0", pwm.dutyRatio() == 0.0);

        // ---- 状态位（2026-09-18 补：内部外设统一"有状态位"这条定案）----
        pwm.store(4, 50, 4);                          // duty = 50 / period = 100
        pwm.store(8, 1, 4);                           // enable
        // 0x14 = PWM status（PwmDevice.REG_STATUS 与 ADC/UART 一样是 private ⇒ 测试用字面量）
        pwm.store(0x14, PwmDevice.STATUS_EDGE, 4);    // 先清 EDGE
        check("T4 STATUS：使能且周期有效 ⇒ READY 置位",
                (pwm.load(0x14, 4) & PwmDevice.STATUS_READY) != 0);
        pwm.step(100);                                // 跑满一整个周期 ⇒ 必然产生边沿
        check("T4 STATUS：整周期推进 ⇒ EDGE 粘滞置位（读-读之间的翻转不漏）",
                (pwm.load(0x14, 4) & PwmDevice.STATUS_EDGE) != 0);
        pwm.store(0x14, PwmDevice.STATUS_EDGE, 4);    // W1C 清除
        check("T4 STATUS：写 1 清 EDGE（W1C，与 ADC 同风格）",
                (pwm.load(0x14, 4) & PwmDevice.STATUS_EDGE) == 0);
        pwm.store(8, 0, 4);                           // 停机
        check("T4 STATUS：停机 ⇒ READY 清零（READY 是硬件状态，清不掉也不需要清）",
                (pwm.load(0x14, 4) & PwmDevice.STATUS_READY) == 0);
    }

    /** T5：ADC（通道读写 / 就绪 / 中断 / 归一化） */
    private static void t5_adc() {
        final AdcDevice adc = new AdcDevice("TEST-ADC");
        adc.setChannel(0, 2048);
        check("T5 宿主写入可读回", adc.getChannel(0) == 2048);
        check("T5 MMIO 读通道", adc.load(0, 4) == 2048L);
        check("T5 归一化 ≈ 0.5", Math.abs(adc.normalized(0) - 0.5) < 0.01);

        adc.store(0x20, 1, 4);                    // 使能
        check("T5 使能后数据就绪", adc.load(0x24, 4) == 1);
        check("T5 使能后拉中断", adc.isInterrupting(0));
        adc.store(0x24, 1, 4);                    // 清就绪
        check("T5 清就绪后不中断", !adc.isInterrupting(0));

        adc.setChannel(1, 99999);                 // 超量程 → 限幅
        check("T5 超量程限幅到 fullScale", adc.getChannel(1) == 4095);

        adc.store(0x28, 1023, 4);                 // 改满量程
        adc.setChannel(2, 5000);                  // 按新量程限幅
        check("T5 满量程可改并影响限幅", adc.getChannel(2) == 1023);
    }

    /** T6：端到端——固件经 MMIO 访问全部外设 */
    private static void t6_endToEnd() {
        final Rv32Asm a = new Rv32Asm();
        a.li(2, (int) TIMER_BASE);
        a.lw(1, 0, 2);                            // x1 = mtime_lo
        a.li(5, (int) REG_BASE);
        a.li(6, 0x1234);
        a.sw(6, 0, 5);                            // RegBank[0] = 0x1234
        a.li(7, (int) PWM_BASE);
        a.addi(8, 0, 100);
        a.sw(8, 0, 7);                            // PWM period = 100
        a.addi(9, 0, 25);
        a.sw(9, 4, 7);                            // PWM duty = 25
        a.addi(10, 0, 1);
        a.sw(10, 8, 7);                           // PWM enable
        a.li(11, (int) ADC_BASE);
        a.lw(12, 0, 11);                          // x12 = ADC ch0
        a.ecall();

        final RegBankDevice reg = new RegBankDevice(4, "E2E-REG");
        final TimerDevice timer = new TimerDevice("E2E-TIMER");
        final PwmDevice pwm = new PwmDevice("E2E-PWM");
        final AdcDevice adc = new AdcDevice("E2E-ADC");
        adc.setChannel(0, 2048);
        reg.setInterruptOnWrite(true);            // guest 写寄存器区 → 拉中断（世界侧握手）

        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(ROM_BASE));
        final SocBoard board = SocBoard.builder(cpu)
                .rom(ROM_BASE, a.toBytes())
                .ram(RAM_BASE, 1024)
                .deviceWithIrq(REG_BASE, reg, "REGBANK")
                .deviceWithIrq(TIMER_BASE, timer, "TIMER")
                .device(PWM_BASE, pwm, "PWM")
                .device(ADC_BASE, adc, "ADC")
                .build();

        // 第一 tick：外设先步进（timer.mtime += 1000）→ CPU 执行固件
        board.step(1000);
        check("T6 固件执行完毕（ecall 停机）", cpu.isFaulted());
        check("T6 固件读到 timer mtime=1000", cpu.getRegisters()[1] == 1000);
        check("T6 固件读到 ADC ch0=2048", cpu.getRegisters()[12] == 2048);
        check("T6 寄存器区被写入 0x1234", reg.get(0) == 0x1234);
        check("T6 寄存器区写触发中断", reg.isInterrupting(0));
        check("T6 PWM 配置生效（占空比 0.25）", Math.abs(pwm.dutyRatio() - 0.25) < 1e-9);
        // 交替推进 ⇒ 设备随指令切片前进（UART 已不是 MMIO 设备，它的寄存器/状态位回归在
        // :common:runUartCacheTest —— 本用例只测"其余外设仍然能被 MMIO 访问"）
        board.step(1000);
        check("T6 交替推进（设备随指令切片前进）", timer.getMtime() >= 2000);

        // 能力表含 ROM + RAM + 4 个外设（UART 自 2026-09-27 起不再是设备区里的 MMIO 设备）
        check("T6 能力表含 ROM 与 4 个外设", board.capabilityNames().size() >= 5
                && board.capabilityNames().stream().anyMatch(s -> s.contains("PWM"))
                && board.capabilityNames().stream().anyMatch(s -> s.contains("ADC")));
        check("T6 地址空间总量正确", board.memoryBytes() >= 0x2C + 1024);
    }

    // ==================== 辅助 ====================

    private static boolean throwsMemoryFault(Runnable action) {
        try {
            action.run();
            return false;
        } catch (com.hdf.cryptand.soc.api.MemoryAccessException e) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            log.append("  [PASS] ").append(name).append('\n');
        } else {
            failed++;
            log.append("  [FAIL] ").append(name).append('\n');
        }
    }
}
