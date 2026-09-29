package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.InterruptSource;
import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Resettable;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== 寄存器区设备（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>把"世界量"映射成固件可读写的 32 位寄存器数组——这是芯片与游戏世界（电网/方块/
 * 传感器）之间的<b>唯一通道</b>，也是沙箱能力表的载体。</p>
 *
 * <p>地址布局：寄存器 i 位于 {@code offset = i * 4}（小端 32 位）。</p>
 *
 * <p>中断语义：任一寄存器被 guest 写入且 {@link #setInterruptOnWrite} 开启时拉高中断线
 * （宿主在 tick 边界消费并清标志）——用于"固件写入 → 世界响应"的握手。</p>
 *
 * <p>线程约定：MMIO（guest 线程）与宿主 {@code get/set}（世界线程）不并发；宿主侧
 * 只在 tick 边界同步（沙箱迁移也在闲置时），故无需加锁。</p>
 */
public final class RegBankDevice implements MemoryMappedDevice, InterruptSource, Resettable {

    private final int[] values;
    private final String name;
    private boolean interruptOnWrite;
    private boolean irqPending;
    private long writeCount;
    private long readCount;
    private int debugCallWrites;
    private int debugSetWrites;
    private int debugReads;

    /**
     * 寄存器诊断开关：{@code -Dcryptand.debug.regcall=1}。默认**完全静默**。
     *
     * <p>⚠ 成因已查清，别再被自己骗一次：当年"属性开关不生效"**不是** `Boolean.getBoolean` 的问题，
     * 而是属性设置得太晚（进程里类已经初始化过 / 属性在别处才设），于是诊断一次都没触发，
     * 连续两轮把"没人写寄存器"当成事实 —— 后来才定性为固件心跳写进了 ABI 偏移 0。
     * 现在每次现读属性（不是类加载时缓存成 final），并且**不做任何采样**：
     * 关掉零输出，打开就逐次打印。</p>
     */
    private static boolean debugOn() {
        return Boolean.getBoolean("cryptand.debug.regcall");
    }

    public RegBankDevice(int registerCount) {
        this(registerCount, "REGBANK");
    }

    public RegBankDevice(int registerCount, String name) {
        if (registerCount <= 0) {
            throw new IllegalArgumentException("registerCount must be > 0");
        }
        this.values = new int[registerCount];
        this.name = name == null ? "REGBANK" : name;
    }

    @Override
    public int getLength() {
        return values.length * Sizes.SIZE_32;
    }

    @Override
    public int getSupportedSizes() {
        return (1 << Sizes.SIZE_8_LOG2) | (1 << Sizes.SIZE_16_LOG2) | (1 << Sizes.SIZE_32_LOG2);
    }

    @Override
    public long load(int offset, int size) {
        final int index = offset / Sizes.SIZE_32;
        if (index < 0 || index >= values.length) {
            throw new MemoryAccessException(offset, name + ": register index out of range");
        }
        readCount++;
        final int value = values[index];
        // 支持按字节/半字读取（小端）
        return switch (size) {
            case Sizes.SIZE_8 -> (value >>> ((offset & 3) * 8)) & 0xFFL;
            case Sizes.SIZE_16 -> (value >>> ((offset & 2) * 8)) & 0xFFFFL;
            default -> value & 0xFFFF_FFFFL;
        };
    }

    @Override
    public void store(int offset, long rawValue, int size) {
        final int index = offset / Sizes.SIZE_32;
        if (index < 0 || index >= values.length) {
            throw new MemoryAccessException(offset, name + ": register index out of range");
        }
        final int value = (int) rawValue;
        switch (size) {
            case Sizes.SIZE_8 -> {
                final int shift = (offset & 3) * 8;
                values[index] = (values[index] & ~(0xFF << shift)) | ((value & 0xFF) << shift);
            }
            case Sizes.SIZE_16 -> {
                final int shift = (offset & 2) * 8;
                values[index] = (values[index] & ~(0xFFFF << shift)) | ((value & 0xFFFF) << shift);
            }
            default -> values[index] = value;
        }
        writeCount++;
        // 诊断（默认静默）：记录偏移 0（= REG_CALL）的**所有**写入 ——
        // 固件的 MMIO 写走这里，core 的 setReg 走 set()，两条路径天然可区分。
        if (debugOn() && offset == 0) {
            System.out.println("    [regbank.store] 寄存器0 <- " + value + "  第 " + (++debugCallWrites) + " 次");
        }
        if (interruptOnWrite) {
            irqPending = true;
        }
    }

    // ==================== 宿主 API（世界侧，tick 边界调用） ====================

    /** 读寄存器（世界侧） */
    public int get(int index) {
        final int v = index >= 0 && index < values.length ? values[index] : 0;
        // 诊断（默认静默）：读侧看到 CALL 非 0 —— 这一侧能看出"请求是否已被消费到 guest 可见"
        if (debugOn() && index == 0 && v != 0) {
            System.out.println("    [regbank.get] 寄存器0 == " + v + "  第 " + (++debugReads) + " 次");
        }
        return v;
    }

    /** 写寄存器（世界侧：把传感器/电网量写进去给固件读） */
    public void set(int index, int value) {
        if (index >= 0 && index < values.length) {
            values[index] = value;
        }
        // 诊断（默认静默）：宿主路径往寄存器 0 写 1（core.setReg 走这里）—— 带调用栈，一眼看出发起方
        if (debugOn() && index == 0 && value == 1) {
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(9, st.length); i++) {
                sb.append("\n          at ").append(st[i]);
            }
            System.out.println("    [regbank.set] 寄存器0 <- 1（第 " + (++debugSetWrites) + " 次）：" + sb);
        }
    }

    /** 批量快照（存档/UI） */
    public int[] snapshot() {
        return values.clone();
    }

    /** 批量恢复（存档读回） */
    public void restore(int[] source) {
        final int n = Math.min(source.length, values.length);
        System.arraycopy(source, 0, values, 0, n);
    }

    /** guest 写入是否拉中断 */
    public void setInterruptOnWrite(boolean enabled) {
        this.interruptOnWrite = enabled;
    }

    /** 清中断（宿主消费后调用） */
    public void clearInterrupt() {
        irqPending = false;
    }

    public int registerCount() {
        return values.length;
    }

    public long writeCount() {
        return writeCount;
    }

    public long readCount() {
        return readCount;
    }

    public String getName() {
        return name;
    }

    // ==================== InterruptSource / Resettable ====================

    @Override
    public boolean isInterrupting(int irq) {
        return irqPending;
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(values, 0);
        irqPending = false;
        writeCount = 0;
        readCount = 0;
    }

    @Override
    public String toString() {
        return name + "[" + values.length + " regs, writes=" + writeCount + "]";
    }
}
