/**
 * ===== C++ 内核的 Java 适配层（NativeRv32Core，2026-09-17）=====
 *
 * <p>实现 soc 子包的 {@link com.hdf.cryptand.soc.api.CpuCore} 契约 ⇒ 上层
 * {@code SocBoard} / {@code SocSandbox} / UI **一行都不用改**就能从"纯 Java 解释器"
 * 切到"C++ 内核"。</p>
 *
 * <h3>分工</h3>
 * <ul>
 *   <li><b>C++ 侧</b>：取指/译码/执行、寄存器与 CSR、陷阱与中断交付、RAM/ROM 读写。</li>
 *   <li><b>Java 侧（本类）</b>：
 *     <ol>
 *       <li>把 {@link MemoryMap} 上的设备访问兑现掉 —— native 遇到设备地址会停下并给出一条
 *           MMIO 事务，我们在这里 load/store 真实设备，然后把值交回去继续跑；</li>
 *       <li>每次推进前把 {@code InterruptController} 的待处理位图推进 native（定时器 tick 靠它）。</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p><b>线程</b>：一个实例只允许一个线程驱动（与 Java 版内核同样的约束），
 * 由 {@code SocScheduler} 保证。</p>
 */
package com.hdf.cryptand.soc.nativebridge;

import com.hdf.cryptand.soc.api.CpuCore;
import com.hdf.cryptand.soc.api.InterruptController;
import com.hdf.cryptand.soc.api.MemoryMap;
import com.hdf.cryptand.soc.api.SocFault;

public final class NativeRv32Core implements CpuCore {

    /** 默认地址布局（与 SocBoard 装配一致） */
    public static final long ROM_BASE = 0x0000_0000L;
    public static final long RAM_BASE = 0x2000_0000L;

    private final long resetVector;
    private final long ramBase;
    private final int ramSize;
    private final long romBase;
    private final int romSize;

    private long handle;
    private MemoryMap memoryMap;
    private InterruptController interruptController;

    /** 诊断：设备访问次数（MMIO 往返频率可观测量，用来判断 native 是否真的省下了开销） */
    private long mmioCount;
    private long instructions;

    public NativeRv32Core(long resetVector, int ramSize) {
        this(resetVector, RAM_BASE, ramSize, ROM_BASE, 512 * 1024);
    }

    public NativeRv32Core(long resetVector, long ramBase, int ramSize, long romBase, int romSize) {
        if (!NativeRv32.available()) {
            throw new IllegalStateException("native RV32 kernel unavailable: " + NativeRv32.loadError());
        }
        this.resetVector = resetVector;
        this.ramBase = ramBase;
        this.ramSize = ramSize;
        this.romBase = romBase;
        this.romSize = romSize;
        this.handle = NativeRv32.create((int) resetVector, (int) ramBase, ramSize, (int) romBase, romSize);
    }

    // ==================== CpuCore ====================

    @Override
    public String getName() {
        return "rv32im-native(C++)";
    }

    @Override
    public void setMemoryMap(MemoryMap memoryMap) {
        this.memoryMap = memoryMap;
    }

    @Override
    public void setInterruptController(InterruptController controller) {
        this.interruptController = controller;
    }

    @Override
    public void reset() {
        ensureOpen();
        NativeRv32.reset(handle);
        instructions = 0;
        mmioCount = 0;
    }

    @Override
    public int step(int instructionBudget) {
        ensureOpen();
        if (instructionBudget <= 0) {
            return 0;
        }
        int executed = 0;
        int remaining = instructionBudget;

        // 把设备中断位图推进 native（定时器 tick / UART / 引脚都靠这条）
        if (interruptController != null) {
            NativeRv32.setPendingInterrupts(handle, (int) interruptController.getPendingInterrupts());
        }

        // ⚠ 兑现设备访问**不消耗指令预算**：被打断的那条访存指令在 completeMmio 里推进 PC。
        //   所以预算用尽时直接退出，绝不能再"补一条"——否则像"每条指令都写设备寄存器"这种
        //   固件会无限续命（实测卡死过一次）。
        int guard = 0;
        while (remaining > 0 && guard++ < 1_000_000) {
            final int done = NativeRv32.step(handle, remaining);
            executed += done;
            remaining -= Math.max(done, 0);
            instructions += Math.max(done, 0);

            if (NativeRv32.faulted(handle) || NativeRv32.halted(handle)) {
                break;
            }
            if (!NativeRv32.hasMmio(handle)) {
                break;
            }
            serviceMmio();
        }
        return executed;
    }

    /** 把一条 MMIO 事务落到真实的 Java 设备上 */
    private void serviceMmio() {
        mmioCount++;
        final int addr = NativeRv32.mmioAddr(handle);
        final int size = Math.max(1, NativeRv32.mmioSize(handle));
        if (NativeRv32.mmioIsWrite(handle)) {
            final int value = NativeRv32.mmioValue(handle);
            if (memoryMap != null) {
                try {
                    memoryMap.store(Integer.toUnsignedLong(addr), Integer.toUnsignedLong(value), size);
                } catch (Throwable ignored) {
                    // 未映射地址：native 侧会当作读回 0 / 写丢弃（与 Java 内核的"越界故障"略有差异，
                    // 但设备区不会触发；真需要严格语义时在 native 侧加 faulted 标记）
                }
            }
            NativeRv32.completeMmio(handle, 0);
            return;
        }
        long value = 0;
        if (memoryMap != null) {
            try {
                value = memoryMap.load(Integer.toUnsignedLong(addr), size) & 0xFFFF_FFFFL;
            } catch (Throwable ignored) {
                value = 0;
            }
        }
        NativeRv32.completeMmio(handle, (int) value);
    }

    @Override
    public SocFault getFault() {
        if (!NativeRv32.faulted(handle)) {
            return SocFault.NONE;
        }
        return new SocFault(NativeRv32.faultCause(handle), NativeRv32.faultTval(handle),
                Integer.toUnsignedLong(NativeRv32.faultEpc(handle)), describeCause(NativeRv32.faultCause(handle)));
    }

    @Override
    public boolean isFaulted() {
        return NativeRv32.faulted(handle) || NativeRv32.halted(handle);
    }

    @Override
    public long getProgramCounter() {
        return Integer.toUnsignedLong(NativeRv32.pc(handle));
    }

    @Override
    public long[] getRegisters() {
        final long[] out = new long[32];
        for (int i = 0; i < 32; i++) {
            out[i] = Integer.toUnsignedLong(NativeRv32.reg(handle, i));
        }
        return out;
    }

    @Override
    public long getInstructionsRetired() {
        return NativeRv32.instructionsRetired(handle);
    }

    @Override
    public long getResetVector() {
        return resetVector;
    }

    @Override
    public long getCsr(int csr) {
        return Integer.toUnsignedLong(NativeRv32.csr(handle, csr));
    }

    @Override
    public void setInterruptsEnabled(boolean enabled) {
        // 中断由宿主每次 step 前推送位图（见 step），这里无需额外开关
    }

    // ==================== 额外能力（镜像装载 / 内存诊断） ====================

    /** 把固件镜像写进 ROM（上电时调用；= 烧录到 {@link #ROM_BASE}） */
    public void loadFirmware(byte[] image) {
        loadImage(romBase, image);
    }

    @Override
    public byte[] readMemory(long addr, int length) {
        ensureOpen();
        final byte[] out = new byte[length];
        NativeRv32.readMemory(handle, (int) addr, out);
        return out;
    }

    @Override
    public void writeMemory(long addr, byte[] data) {
        ensureOpen();
        NativeRv32.writeMemory(handle, (int) addr, data);
    }

    /** 烧录镜像：native 侧按 ROM/RAM 区间自行落点（{@code rv32_core.cpp Machine::loadImage}） */
    @Override
    public void loadImage(long addr, byte[] image) {
        ensureOpen();
        NativeRv32.loadImage(handle, (int) addr, image);
    }

    /** 设备访问次数（诊断：MMIO 往返频率） */
    public long mmioCount() {
        return mmioCount;
    }

    /** 释放 native 资源（板子销毁时调用） */
    public void close() {
        if (handle != 0L) {
            NativeRv32.destroy(handle);
            handle = 0L;
        }
    }

    @Override
    public String toString() {
        return "NativeRv32Core[pc=0x" + Long.toHexString(getProgramCounter())
                + ", retired=" + getInstructionsRetired() + ", mmio=" + mmioCount + "]";
    }

    // ==================== 内部 ====================

    private void ensureOpen() {
        if (handle == 0L) {
            throw new IllegalStateException("native RV32 machine already closed");
        }
    }

    private static String describeCause(int cause) {
        if ((cause & 0x8000_0000) != 0) {
            return "interrupt#" + (cause & 0x7FFF_FFFF);
        }
        return switch (cause) {
            case 0 -> "instruction address misaligned";
            case 1 -> "instruction access fault";
            case 2 -> "illegal instruction";
            case 3 -> "breakpoint";
            case 4 -> "load address misaligned";
            case 5 -> "load access fault";
            case 6 -> "store address misaligned";
            case 7 -> "store access fault";
            case 8 -> "ecall from U";
            case 9 -> "ecall from S";
            case 11 -> "ecall from M";
            default -> "cause " + cause;
        };
    }
}
