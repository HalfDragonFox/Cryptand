package com.hdf.cryptand.soc.api;

/**
 * ===== CPU 内核抽象（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>可插拔内核的统一契约：<b>自研 RV32 小核</b>（MCU 档）与<b>应用处理器档</b>
 * （如 Sedna 适配）实现同一接口，换内核不动上层（{@code SocBoard}/{@code SocSandbox}/UI）。</p>
 *
 * <p>执行模型：宿主每 tick 调 {@link #step(int)} 推进"指令预算"，到点返回——
 * 这是协作式中断点，<b>guest 无法逃避</b>（代码只能经本内核执行）。</p>
 */
public interface CpuCore {

    /** 内核名称（UI/诊断，如 "rv32im" / "sedna-rv64"） */
    String getName();

    /** 挂载内存映射（复位前调用一次） */
    void setMemoryMap(MemoryMap memoryMap);

    /** 挂载中断控制器（可空 = 无中断） */
    void setInterruptController(InterruptController controller);

    /** 复位（PC 回复位向量，清寄存器与 CSR） */
    void reset();

    /**
     * 推进至多 {@code instructionBudget} 条指令。
     *
     * @return 实际执行的指令数（&lt; budget 表示已停机/故障/等待中断）
     */
    int step(int instructionBudget);

    /** 当前故障态（无故障返回 {@link SocFault#NONE}） */
    SocFault getFault();

    /** 是否处于故障态（故障后 step 不再推进） */
    boolean isFaulted();

    /** 程序计数器 */
    long getProgramCounter();

    /** 通用寄存器 x0..x31 的快照（UI/自测） */
    long[] getRegisters();

    /** 累计执行指令数（minstret 语义） */
    long getInstructionsRetired();

    /** 复位向量（上电 PC） */
    long getResetVector();

    /** CSR 读（UI/自测；未实现返回 0） */
    long getCsr(int csr);

    /**
     * 使能/关闭"已派发中断"注入（宿主在设备 step 后调用 {@link #step} 即可生效）。
     * 默认空实现——不支持中断的内核可不覆写。
     */
    default void setInterruptsEnabled(boolean enabled) {
    }

    // ==================== 宿主侧内存访问（guest 物理地址）====================

    /*
     * ⚠ 为什么这三个方法在**内核**契约上，而不是在 MemoryMap / SocBoard 上：
     * RAM / ROM 的持有者就是内核本身 ——
     *   · Java 内核（{@link com.hdf.cryptand.soc.riscv.Rv32Core}）：RAM/ROM 挂在 MemoryMap 的设备表上；
     *   · native 内核（NativeRv32Core）：RAM/ROM 是 C++ 侧的缓冲，**只有设备区**才经 MMIO 事务回到 Java。
     * 所以宿主读 guest 内存必须问内核要。之前宿主直接读 SocBoard 的 MemoryMap，
     * 在 native 内核下读到的是"从来没被写过的影子设备"——固件写的字符阵列取回来全是 0，
     * 屏幕因此全黑（见 OcArchitectureCore 的组件缓冲区）。
     */

    /**
     * 读 guest 物理内存（RAM/ROM）。
     *
     * @param address guest 物理地址
     * @param length  字节数
     * @return 读到的字节副本（长度 = length；未覆盖的地址读到 0）
     */
    default byte[] readMemory(long address, int length) {
        throw new UnsupportedOperationException(getName() + " 不在宿主侧管理 guest 内存");
    }

    /**
     * 写 guest 物理内存（宿主注入数据：引导程序、配置块）。
     *
     * @param address guest 物理地址
     * @param data    要写入的字节
     */
    default void writeMemory(long address, byte[] data) {
        throw new UnsupportedOperationException(getName() + " 不在宿主侧管理 guest 内存");
    }

    /**
     * 烧录镜像到 guest 物理内存（装配期 / 重新烧录；<b>允许写 ROM</b>——真实烧写的语义）。
     *
     * <p>默认等同于 {@link #writeMemory}；只读 ROM 的内核（Java 内核的 {@code RomDevice}）
     * 需要覆写以绕过只读约束。</p>
     */
    default void loadImage(long address, byte[] image) {
        writeMemory(address, image);
    }

    /**
     * **异步投递**一次 guest 内存写入：不立刻写，而是排进内核自己的命令队列，
     * 由内核线程在"**下一条指令之前**"的固定点执行。
     *
     * <p>为什么要它（用户 2026-09-17 定案）：「是否成功只需要沙箱接收到相应数据后
     * **当前命令周期一条执行完成后先写入寄存器然后继续运行**即可」—— 也就是写入必须
     * 落在**两条指令之间**，而不是从宿主线程插进去。</p>
     *
     * <p>两个方案的区别：</p>
     * <ul>
     *   <li>宿主编好时机直接写（{@link #writeMemory}）：宿主线程与内核线程**并发写同一块内存**，
     *       语义上是共享可变状态；</li>
     *   <li>投递进内核命令队列（本方法）：内核线程**独占**机器状态，写入严格落在指令边界，
     *       时序确定、无数据竞争 —— 这是推荐路径。</li>
     * </ul>
     *
     * <p>精度由内核的粒度决定：自驱动沙箱默认 `quantum`（数百条一轮），需要精确到**单条**
     * 时用断点/同步点把它降到 1 条 —— 那套能力已经内建，且**只在真正需要时才付代价**。</p>
     *
     * @return true = 已投递（异步生效）；false = 该内核不支持异步投递，调用方应回落
     *         {@link #writeMemory} 同步写
     */
    default boolean postMemoryWrite(long address, byte[] data) {
        return false;
    }
}
