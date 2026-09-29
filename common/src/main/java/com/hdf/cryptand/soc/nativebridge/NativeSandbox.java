/**
 * ===== Cryptand 沙箱（自驱动虚拟机）· JNI 声明（2026-09-17）=====
 *
 * <p>用户定稿的架构：<b>Java → 沙箱 → 程序</b>。沙箱是一台完整虚拟机
 * （CPU + 内存 + 时钟 + 设备总线），Java 只与沙箱对接：</p>
 *
 * <ul>
 *   <li><b>配置一次就自动跑</b>：{@link #setClockHz} 告诉沙箱"1 秒执行多少周期"，
 *       它便按<b>内部时钟</b>自己推进，Java 不需要每 tick 同步调用。</li>
 *   <li><b>消息机制</b>：沙箱把事件推进消息队列（{@link #poll}：断点 / 故障 / 停机 /
 *       设备访问请求），Java 用命令队列回话（{@link #resume} / {@link #pause} /
 *       {@link #step} / {@link #mmioResult} …）。</li>
 *   <li><b>Java 分配线程</b>：{@link #run} 是<b>阻塞</b>调用，由 Java 在 Cryptand 自己的
 *       线程分配器上运行（不用原版线程池）。</li>
 *   <li><b>一周期一条指令</b>：死循环只会吃掉沙箱自己的周期预算，随时可暂停 / 单步 /
 *       设断点 / 读寄存器内存。</li>
 * </ul>
 *
 * <p>native 实现：{@code excode/cryptand-rv32/src/sandbox.cpp}，与 {@link NativeRv32}
 * <b>同一个 DLL</b>（{@code cryptand_rv32.dll}）—— 引用本类前请先确认
 * {@link NativeRv32#available()}。</p>
 */
package com.hdf.cryptand.soc.nativebridge;

public final class NativeSandbox {

    private NativeSandbox() {
    }

    /* ==================== 沙箱 → Java 的消息类型 ==================== */

    public static final int MSG_NONE = 0;
    /** 已复位。a = 入口地址 */
    public static final int MSG_RESET = 1;
    /** 镜像已灌入。a = 地址，b = 长度 */
    public static final int MSG_LOADED = 2;
    /** 命中断点。a = PC */
    public static final int MSG_BREAKPOINT = 3;
    /** 硬件故障。a = cause，b = tval，c = epc */
    public static final int MSG_FAULT = 4;
    /** 停机。a = PC */
    public static final int MSG_HALTED = 5;
    /** 需要设备兑现。a = addr，b = value，c = size | (isWrite << 8) */
    public static final int MSG_MMIO = 6;
    /** 已暂停。a = PC */
    public static final int MSG_PAUSED = 7;
    /** 单步完成。a = PC，b = 本次执行条数 */
    public static final int MSG_STEPPED = 8;
    /** run() 已退出 */
    public static final int MSG_STOPPED = 9;
    /** 心跳断了看门狗阈值 ⇒ 虚拟机自己暂停。a = 静默阈值 ms */
    public static final int MSG_IDLE_PAUSED = 10;

    /* ==================== 生命周期 ==================== */

    public static native long create(int resetVector, int ramBase, int ramSize, int romBase, int romSize);

    public static native void destroy(long handle);

    /* ==================== 配置（设置一次） ==================== */

    /** 主频：每秒执行多少条指令（= 周期）。10MHz 传 10_000_000。 */
    public static native void setClockHz(long handle, long hz);

    public static native long clockHz(long handle);

    /** 每次唤醒最多连续执行多少条（越小越实时）。 */
    public static native void setQuantum(long handle, int cycles);

    /* ==================== Java → 沙箱 命令 ==================== */

    public static native void loadImage(long handle, int addr, byte[] data);

    public static native void reset(long handle);

    /** 阻塞运行：内部时钟自动推进，直到 {@link #stop}。由 Java 的线程调用。 */
    public static native void run(long handle);

    public static native void stop(long handle);

    public static native void pause(long handle);

    /** 继续（从暂停 / 断点处恢复）。 */
    public static native void resume(long handle);

    public static native void step(long handle, int cycles);

    public static native void setBreakpoint(long handle, int addr);

    public static native void clearBreakpoint(long handle, int addr);

    public static native void clearBreakpoints(long handle);

    public static native void writeReg(long handle, int index, int value);

    public static native void writeMem(long handle, int addr, byte[] data);

    /** 宿主推进设备中断位图（RISC-V 标准位：7 = MTIP）。 */
    public static native void setInterrupts(long handle, int bits);

    /** 设备访问兑现：把读到的值交回（写事务传 0）。 */
    public static native void mmioResult(long handle, int value);

    /* ==================== 沙箱 → Java ==================== */

    /**
     * 取一条消息。out 长度须 ≥ 4，写入 {@code [type, a, b, c]}。
     *
     * @return 1 = 取到消息，0 = 队列为空
     */
    public static native int poll(long handle, int[] out);

    /**
     * 阻塞等待一条消息（**推荐路径**；{@link #poll} 保留作轮询/回退用）。
     *
     * <p>为什么加：{@code poll} 是"问一次答一次"，消息泵只能按固定间隔轮询占位
     * （1ms 一次 ⇒ 每秒 1000 次 JNI，绝大多数返回"队列空"）。本方法在 native 侧
     * 用条件变量阻塞，消息入队时才唤醒 ⇒ JNI 调用次数与**事件数**同阶。</p>
     *
     * <p>out 长度须 ≥ 5：{@code [type, a, b, c, instret]} —— instret（累计执行周期）
     * 随每条消息带回，处理设备访问时用它推进设备时间，**不需要**额外查询。</p>
     *
     * @param timeoutMs 最长等待毫秒数；≤0 表示只查一次（等价于 poll）
     * @return 1 = 取到消息，0 = 超时，-1 = 沙箱已 stop（run() 已退出）
     */
    public static native int waitMessage(long handle, long[] out, int timeoutMs);

    /**
     * 心跳：**一次调用**完成"下发设备中断位图 + 取回沙箱状态"。
     *
     * <p>自驱动模型下 Java 每 tick 只做这一件事，所以必须合并 ——
     * 拆成 setInterrupts()/snapshot()/faulted() 三次调用就是三倍开销。</p>
     *
     * <p>out 长度须 ≥ 10：
     * {@code [pc, instret, running, waitingMmio, faulted, halted, pendingMessages,
     * faultCause, faultTval, faultEpc]}。</p>
     *
     * @param irqBits  设备待处理中断位图（RISC-V 标准位：7 = MTIP）
     * @param quantum  连跑粒度；≤0 表示不改（配置一次即可）
     */
    public static native void heartbeat(long handle, int irqBits, int quantum, long[] out);

    /**
     * 心跳看门狗：**心跳断了**多少毫秒就让虚拟机自己暂停（默认 10000 = 10 秒，可配置）。
     *
     * <p>语义（2026-09-28 用户定案）：宿主卡顿虚拟机不管（它自己按周期跑），
     * 只有心跳停止超过这个时间才暂停——说明宿主已经不在了（世界卸载 / 服务端停 / 崩溃）。
     * 之后任何一条宿主消息（心跳/命令/设备兑现）都会让它自动恢复。0 = 关闭。</p>
     */
    public static native void setPauseOnSilence(long handle, int ms);

    /**
     * 直通 guest 内存的零拷贝视图（DirectByteBuffer）。
     *
     * <p>绕开 {@link #readMemory} 的 {@code GetByteArrayElements} + memcpy；
     * 只有整段落在 ROM/RAM 内才返回非 null，其余（设备区/越界）回落 {@link #readMemory}。</p>
     *
     * <p>⚠ 返回的是**共享**内存，不是快照：沙箱可能仍在写它，且 {@link #destroy}
     * 会 free 掉这块内存 ⇒ 调用方必须立即消费，绝不缓存跨 destroy 使用。</p>
     */
    public static native java.nio.ByteBuffer directMemory(long handle, int addr, int len);

    /**
     * 状态快照。out 长度须 ≥ 44：
     * <pre>
     *   [0]      PC
     *   [1..32]  x1..x31（[1] = x1 … [31] = x31，[32] = 保留 0）
     *   [33]     累计执行指令数（= 周期数）
     *   [34]     当前频率（Hz）
     *   [35]     running
     *   [36]     waitingMmio
     *   [37]     faulted
     *   [38]     halted
     *   [39]     断点数
     *   [40]     faultCause
     *   [41]     faultTval
     *   [42]     faultEpc
     *   [43]     待处理消息数
     * </pre>
     */
    public static native void snapshot(long handle, long[] out);

    /**
     * 读虚拟机自建 CLINT（机器定时器）状态 —— **宿主只读**。
     *
     * <p>out 长度须 ≥ 5：{@code [mtime, mtimecmp, enabled, pending, irqCount]}。
     * 定时器由虚拟机自己推进与产生 MTIP，宿主只能在装配时配置标称频率、之后只读诊断
     * （2026-09-28 定案：定时器和 UART 一样是虚拟机构建的模块，周期管理全由虚拟机完成）。</p>
     */
    public static native void clint(long handle, long[] out);

    /** 读内存（诊断）。返回实际拷贝字节数。 */
    public static native int readMemory(long handle, int addr, byte[] out, int len);
}
