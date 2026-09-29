/**
 * ===== Cryptand 沙箱虚拟机 · Java 侧唯一对接面（2026-09-17）=====
 *
 * <p>把 {@link NativeSandbox} 的裸 JNI 包成一台"可用的虚拟机"：Java 只需要</p>
 * <ol>
 *   <li>{@link #create} 建一台沙箱；</li>
 *   <li>{@link #clock(int)} 配置一次主频（MHz）—— 之后它按<b>内部时钟</b>自己跑；</li>
 *   <li>{@link #start()} —— 线程从 Cryptand 自己的线程分配器（{@link ThreadDispatchers}）
 *       里取，用<b>两个专用线程</b>：一个阻塞在 {@code run()} 里推进周期，一个做消息泵；</li>
 *   <li>{@link #bus} 挂设备总线、{@link #events} 收故障/断点事件 —— 都是消息驱动的，
 *       Java 不需要每 tick 交互。</li>
 * </ol>
 *
 * <p>线程分工：沙箱主线程只跑指令；消息泵线程**阻塞等待**消息队列并在其中兑现设备访问
 * （{@code MSG_MMIO} → {@link DeviceBus} → {@code mmioResult}）。两者都在
 * pinned worker 上，不会污染原版线程池。</p>
 *
 * <p>JNI 调用面（这里定成败的关键 —— 每次跨语言调用都是纳秒到微秒级的固定开销，
 * 而沙箱每秒执行数百万条指令，任何"每周期一次"的调用模型都会被它压垮）：</p>
 * <ul>
 *   <li><b>配置</b>：{@link #clock(int)} / {@link #quantum(int)} —— <b>一次</b>，改配置时才再发；</li>
 *   <li><b>消息</b>：{@link #waitMessage} 阻塞等待，调用次数 = 事件数（不是每秒 1000 次轮询）；</li>
 *   <li><b>设备访问</b>：每条事务 2 次（取消息 + 回灌），且消息里已带回 instret
 *       ⇒ 设备时间推进不需要额外查询；</li>
 *   <li><b>状态</b>：{@link #heartbeat(int)} 每 tick <b>一次</b>，同时下发中断位图并取回状态
 *       （{@link #instructionsRetired()} 等访问器都读缓存，零 JNI）。</li>
 * </ul>
 *
 * <p>关闭顺序：{@link #close()} 先 {@code stop()} 让 {@code run()} 返回、等两个线程退出，
 * 再 {@code destroy()} 释放 native 机器 —— 顺序不能颠倒（run 还在跑时销毁会崩）。</p>
 */
package com.hdf.cryptand.soc.nativebridge;

import com.hdf.cryptand.circuitsimulation.compute.PinnedWorker;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SandboxVm implements AutoCloseable {

    /** 设备总线：沙箱访问非 RAM/ROM 地址时回调（在消息泵线程上执行） */
    public interface DeviceBus {
        int read(int addr, int size);

        void write(int addr, int size, int value);
    }

    /** 事件监听（在消息泵线程上执行；实现为空即可只用其中一部分） */
    public interface Events {
        default void onFault(int cause, int tval, int epc) {
        }

        default void onHalted(int pc) {
        }

        default void onBreakpoint(int pc) {
        }

        default void onReset(int entryPc) {
        }

        default void onStopped() {
        }
    }

    /** 消息泵等待超时（毫秒）。阻塞等待下它只决定**关闭响应速度**（以及 keep-alive 检查），
     *  不再决定 JNI 调用频率：有消息时 native 立刻唤醒，无消息时一次调用都不发生。 */
    private static final int PUMP_WAIT_MS = 50;

    /** {@link NativeSandbox#heartbeat} 的输出字数 */
    private static final int HEARTBEAT_WORDS = 12;

    /* 心跳标志位（{@link #flags}） */
    private static final int FLAG_RUNNING = 1;
    private static final int FLAG_WAITING_MMIO = 1 << 1;
    private static final int FLAG_FAULTED = 1 << 2;
    private static final int FLAG_HALTED = 1 << 3;

    private static final long CLOSE_WAIT_MS = 1500L;

    private final String name;
    private final long handle;
    private final CountDownLatch threadsDone = new CountDownLatch(2);

    /** 心跳输出缓冲（复用：心跳只在宿主线程调用，无并发） */
    private final long[] heartbeatOut = new long[HEARTBEAT_WORDS];
    /** 消息泵输出缓冲（复用：[type, a, b, c, instret]） */
    private final long[] messageOut = new long[5];

    private volatile DeviceBus bus;
    private volatile Events events = new Events() {
    };
    private volatile boolean closed;
    private volatile boolean started;
    private volatile int mhz;

    /* ---------- 状态缓存：由心跳与消息推送共同维护，读它们**不产生 JNI** ---------- */

    /** 累计执行周期（最后一条消息/最后一次心跳的权威值） */
    private volatile long instret;
    private volatile int pc;
    private volatile int flags;
    private volatile int pendingMessages;
    private volatile int faultCause;
    private volatile int faultTval;
    private volatile int faultEpc;
    /** 心跳断了超过看门狗阈值 ⇒ 虚拟机自己暂停了（任何宿主消息都会让它自动恢复） */
    private volatile boolean idlePaused;
    /** 本秒已执行周期（虚拟机自走配额：跑满即睡到下一秒） */
    private volatile long cyclesThisSecond;

    private PinnedWorker runWorker;
    private PinnedWorker pumpWorker;

    /* ---------- 空心跳（用户 2026-09-28 定案：默认 3 秒发一次，超时默认 10 秒） ---------- */
    /** 最近一次下发的中断位图（空心跳沿用它，不改任何状态） */
    private volatile int lastIrqBits;
    /** 空心跳间隔（毫秒，默认 3000；由配置下发） */
    private volatile long heartbeatIntervalMs = 3_000L;
    /** 空心跳线程（守护线程；宿主没了它也就没了 ⇒ 虚拟机超时自暂停） */
    private Thread keepAlive;
    /** 空心跳自己的输出缓冲（不与宿主线程的 heartbeatOut 共用） */
    private final long[] keepAliveOut = new long[HEARTBEAT_WORDS];

    /** 消息泵处理过的设备访问次数（诊断用） */
    private volatile long mmioCount;
    /** 消息泵处理过的消息总数（诊断用） */
    private volatile long messageCount;

    private SandboxVm(String name, long handle) {
        this.name = name;
        this.handle = handle;
    }

    /* ==================== 创建 / 销毁 ==================== */

    /**
     * 建一台沙箱。
     *
     * @param name       名字（用于 pinned 线程名与日志）
     * @param resetVector 复位入口
     * @param ramBase     RAM 基址
     * @param ramSize     RAM 字节数
     * @param romBase     ROM 基址
     * @param romSize     ROM 字节数
     * @return 沙箱；native 库不可用时返回 {@code null}（调用方回落纯 Java 内核）
     */
    public static SandboxVm create(String name, int resetVector, int ramBase, int ramSize,
                                   int romBase, int romSize) {
        if (!NativeRv32.available()) {
            return null;
        }
        final long h = NativeSandbox.create(resetVector, ramBase, ramSize, romBase, romSize);
        if (h == 0L) {
            return null;
        }
        return new SandboxVm(name == null ? "sandbox" : name, h);
    }

    public String name() {
        return name;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isStarted() {
        return started;
    }

    /* ==================== 配置（一次即可） ==================== */

    /**
     * 配置主频：**每秒执行多少条指令**。configure once，之后沙箱自己按内部时钟跑。
     *
     * @param mhz 主频（MHz）；例如 20 → 20_000_000 周期/秒
     */
    public void clock(int mhz) {
        if (closed) {
            return;
        }
        final int v = Math.max(1, mhz);
        this.mhz = v;
        NativeSandbox.setClockHz(handle, (long) v * 1_000_000L);
    }

    /** 当前主频（MHz）。 */
    public int mhz() {
        return mhz;
    }

    /** 每次唤醒连续执行的指令上限（越小越"实时"，断点命中越精确）。 */
    public void quantum(int cycles) {
        if (!closed) {
            NativeSandbox.setQuantum(handle, cycles);
        }
    }

    /** 挂设备总线（沙箱访问设备时回调）。 */
    public void bus(DeviceBus bus) {
        this.bus = bus;
    }

    /** 挂事件监听。 */
    public void events(Events events) {
        if (events != null) {
            this.events = events;
        }
    }

    /* ==================== 运行 ==================== */

    /**
     * 启动：把 {@code run()} 与消息泵各放到一个 pinned 线程上。
     *
     * <p>线程来自 Cryptand 的 {@link ThreadDispatchers}（用户要求：**不用原版线程池**）。</p>
     */
    public void start() {
        if (closed || started) {
            return;
        }
        started = true;
        runWorker = ThreadDispatchers.pinPermanent("cryptand-sandbox-" + name, false);
        pumpWorker = ThreadDispatchers.pinPermanent("cryptand-sandbox-pump-" + name, false);

        runWorker.post(() -> {
            try {
                NativeSandbox.run(handle);
            } catch (Throwable ignored) {
                // run() 返回即代表沙箱线程结束；异常不向外抛（native 侧已记录状态）
            } finally {
                threadsDone.countDown();
            }
        });

        pumpWorker.post(() -> {
            try {
                pumpLoop();
            } finally {
                threadsDone.countDown();
            }
        });

        keepAlive = new Thread(this::keepAliveLoop, "cryptand-vm-heartbeat-" + name);
        keepAlive.setDaemon(true);
        keepAlive.start();
    }

    /**
     * 空心跳线程（用户 2026-09-28 定案："心跳可配置默认发送速度，建议 3s 发送一次"）。
     *
     * <p>它只做一件事：每隔 {@link #heartbeatIntervalMs} 毫秒给虚拟机发一次**空心跳**
     * （沿用最近一次的中断位图，不改任何状态），告诉虚拟机"宿主还活着"。
     * 虚拟机自己是独立机器（宿主卡顿它不管）；只有这条心跳断了超过看门狗阈值，
     * 它才会在 {@code idlePauseMs} 之后自己暂停。线程是守护线程 + 随 {@link #close()} 结束
     * ⇒ 宿主进程消失时心跳自然停，虚拟机随后自暂停。</p>
     */
    private void keepAliveLoop() {
        while (!closed) {
            try {
                Thread.sleep(Math.max(200L, heartbeatIntervalMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed || !started) {
                return;
            }
            try {
                NativeSandbox.heartbeat(handle, lastIrqBits, 0, keepAliveOut);
                applyHeartbeat(keepAliveOut);
            } catch (Throwable ignored) {
                return;                  /* 沙箱已销毁等极端情况：安静退出 */
            }
        }
    }

    /** 空心跳间隔（毫秒，0/负 = 用默认 3 秒）。见 {@link NativeSandbox#setPauseOnSilence}。 */
    public void heartbeatIntervalMs(long ms) {
        if (ms > 0) {
            heartbeatIntervalMs = Math.max(200L, ms);
            final Thread t = keepAlive;
            if (t != null) {
                t.interrupt();           /* 唤醒后按新间隔继续 */
            }
        }
    }

    public void pause() {
        if (!closed) {
            NativeSandbox.pause(handle);
        }
    }

    public void resume() {
        if (!closed) {
            NativeSandbox.resume(handle);
        }
    }

    /** 单步 N 条指令后自动暂停。 */
    public void step(int cycles) {
        if (!closed) {
            NativeSandbox.step(handle, cycles);
        }
    }

    public void reset() {
        if (!closed) {
            NativeSandbox.reset(handle);
        }
    }

    /* ==================== 断点 / 内存 / 寄存器 ==================== */

    public void breakpoint(int addr) {
        if (!closed) {
            NativeSandbox.setBreakpoint(handle, addr);
        }
    }

    public void clearBreakpoint(int addr) {
        if (!closed) {
            NativeSandbox.clearBreakpoint(handle, addr);
        }
    }

    public void clearBreakpoints() {
        if (!closed) {
            NativeSandbox.clearBreakpoints(handle);
        }
    }

    /** 灌入镜像（写进 ROM 区）。 */
    public void loadImage(int addr, byte[] data) {
        if (!closed && data != null && data.length > 0) {
            NativeSandbox.loadImage(handle, addr, data);
        }
    }

    public void writeMemory(int addr, byte[] data) {
        if (!closed && data != null && data.length > 0) {
            NativeSandbox.writeMem(handle, addr, data);
        }
    }

    public byte[] readMemory(int addr, int len) {
        if (closed || len <= 0) {
            return new byte[0];
        }
        final byte[] out = new byte[len];
        final int got = NativeSandbox.readMemory(handle, addr, out, len);
        if (got == len) {
            return out;
        }
        final byte[] trimmed = new byte[Math.max(0, got)];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /** 推进设备中断位图（RISC-V 标准位：7 = MTIP，机器定时器）。 */
    public void interrupts(int bits) {
        if (!closed) {
            NativeSandbox.setInterrupts(handle, bits);
        }
    }

    /** 状态快照（PC / 寄存器 / 计数 / 标志）。 */
    public Snapshot snapshot() {
        final long[] out = new long[44];
        if (!closed) {
            NativeSandbox.snapshot(handle, out);
        }
        return new Snapshot(out);
    }

    /* ==================== 心跳与状态（宿主每 tick 一次） ==================== */

    /**
     * 心跳：**一次 JNI** 完成"下发宿主侧设备中断位图 + 取回沙箱状态"。
     *
     * <p>自驱动模型里这是宿主唯一的周期性动作（OC 的 {@code runThreaded} 每 tick 调一次）。
     * 之所以合并成一次：拆成 {@code setInterrupts} + {@code snapshot} + 若干 {@code faulted/halted}
     * 查询就是每 tick 三到五次跨语言调用，而且 snapshot 还会白拷 32 个寄存器 ——
     * 那些数据只有调试面板才需要（用 {@link #snapshot()}）。</p>
     *
     * @param irqBits 设备待处理中断位图（RISC-V 标准位：7 = MTIP，FreeRTOS 的 tick）
     */
    public void heartbeat(int irqBits) {
        if (closed) {
            return;
        }
        lastIrqBits = irqBits;
        NativeSandbox.heartbeat(handle, irqBits, 0, heartbeatOut);
        applyHeartbeat(heartbeatOut);
    }

    private void applyHeartbeat(long[] v) {
        pc = (int) v[0];
        instret = v[1];
        int f = 0;
        if (v[2] != 0) {
            f |= FLAG_RUNNING;
        }
        if (v[3] != 0) {
            f |= FLAG_WAITING_MMIO;
        }
        if (v[4] != 0) {
            f |= FLAG_FAULTED;
        }
        if (v[5] != 0) {
            f |= FLAG_HALTED;
        }
        flags = f;
        pendingMessages = (int) v[6];
        faultCause = (int) v[7];
        faultTval = (int) v[8];
        faultEpc = (int) v[9];
        idlePaused = v[10] != 0;
        cyclesThisSecond = v[11];
    }

    /**
     * 心跳看门狗阈值（毫秒，0 = 关闭）：心跳断了这么久虚拟机就自己暂停。
     *
     * <p>宿主卡顿不受影响（虚拟机自己按周期跑）；只有心跳真的停了才暂停。
     * 见 {@link NativeSandbox#setPauseOnSilence}。</p>
     */
    public void pauseOnSilenceMs(int ms) {
        if (!closed) {
            NativeSandbox.setPauseOnSilence(handle, ms);
        }
    }

    /** 虚拟机是否因"心跳断了"而自己暂停（不是用户按的暂停）。 */
    public boolean isIdlePaused() {
        return idlePaused;
    }

    /** 本秒已执行周期（虚拟机自走配额）。 */
    public long cyclesThisSecond() {
        return cyclesThisSecond;
    }

    /**
     * 读虚拟机自建 CLINT（定时器）状态：{@code [mtime, mtimecmp, enabled, pending, irqCount]}。
     *
     * <p><b>只读</b>：时间与周期由虚拟机自己管，宿主只是看客（诊断文本用）。</p>
     */
    public long[] clint() {
        final long[] out = new long[5];
        if (!closed) {
            NativeSandbox.clint(handle, out);
        }
        return out;
    }

    /** 累计执行周期（= 指令数）：最后一次心跳/消息的权威值，**不产生 JNI**。 */
    public long instructionsRetired() {
        return instret;
    }

    public int pc() {
        return pc;
    }

    /** 是否正在推进周期（暂停/断点/故障/停机时为 false）。 */
    public boolean isRunning() {
        return (flags & FLAG_RUNNING) != 0;
    }

    /** 是否卡在设备访问上等 Java 兑现（此时沙箱线程已让出，不占 CPU）。 */
    public boolean isWaitingMmio() {
        return (flags & FLAG_WAITING_MMIO) != 0;
    }

    public boolean isFaulted() {
        return (flags & FLAG_FAULTED) != 0;
    }

    public boolean isHalted() {
        return (flags & FLAG_HALTED) != 0;
    }

    /** 是否"停下来了"：硬件故障或程序正常停机（与 {@code CpuCore.isFaulted()} 同口径）。 */
    public boolean isStopped() {
        return (flags & (FLAG_FAULTED | FLAG_HALTED)) != 0;
    }

    public int pendingMessages() {
        return pendingMessages;
    }

    public int faultCause() {
        return faultCause;
    }

    public int faultTval() {
        return faultTval;
    }

    public int faultEpc() {
        return faultEpc;
    }

    /**
     * 非阻塞取一条消息（诊断/单步调试用）。
     *
     * @param out 长度须 ≥ 5（{@code [type, a, b, c, instret]}）
     * @return 是否取到
     */
    public boolean pollOnce(long[] out) {
        if (closed) {
            return false;
        }
        return NativeSandbox.waitMessage(handle, out, 0) == 1;
    }

    /**
     * 直通 guest 内存的零拷贝视图（DirectByteBuffer）。
     *
     * <p>⚠ 共享内存而非快照：沙箱可能仍在写它，而 {@link #close()} 会 free 掉它
     * ⇒ 只用于"读完立刻消费"的场景，绝不能缓存。</p>
     *
     * <p><b>什么时候它才真正划算</b>（2026-09-18 判断，避免后来者白接一遍）：
     * 当前的显示路径是"固件把 w×h 字符阵列写在 guest RAM 里 → 宿主读出来 →
     * {@code new String(cells, ISO_8859_1)} → {@code gpu.set} 收字符串"，
     * 也就是说那块字节**无论如何都要变成 String**，DirectByteBuffer 只省掉
     * {@code GetByteArrayElements} 一次数组拷贝（640~2000 字节，相对于每 tick 数千次
     * JNI 可忽略）⇒ <b>现在接入收益≈0</b>。</p>
     *
     * <p>它有意义的时候是显示改成「RV 直接写 guest RAM 里的 framebuffer（零 MMIO）+
     * 宿主按帧号抓取」—— 那时抓取的是 6KB 级像素缓冲，且<b>不再需要转成 String</b>，
     * 整块拷贝省下来才是实打实的。</p>
     *
     * @return 视图；地址不在 ROM/RAM 内或已关闭时返回 null
     */
    public java.nio.ByteBuffer directMemory(int addr, int len) {
        if (closed || len <= 0) {
            return null;
        }
        return NativeSandbox.directMemory(handle, addr, len);
    }

    public long mmioCount() {
        return mmioCount;
    }

    public long messageCount() {
        return messageCount;
    }

    /* ==================== 内部：消息泵 ==================== */

    /**
     * 消息泵：**阻塞等待**消息（{@link NativeSandbox#waitMessage}），有消息才醒。
     *
     * <p>⚠ 这里以前是 {@code while(poll(...)==1) dispatch(); parkNanos(1ms)} —— 每秒约 1000 次
     * JNI，其中几乎全部返回"队列空"。改成阻塞等待后，JNI 次数与**事件数**同阶
     * （设备访问 / 故障 / 停机才发生）。语义不变：消息仍按队列顺序逐条兑现。</p>
     */
    private void pumpLoop() {
        while (!closed) {
            final int r = NativeSandbox.waitMessage(handle, messageOut, PUMP_WAIT_MS);
            if (r == 1) {
                messageCount++;
                instret = messageOut[4];      // 每条消息都带回当前周期数（见 native 注释）
                dispatch(messageOut);
                continue;
            }
            if (r < 0) {
                break;                        // 沙箱已 stop：收摊
            }
            // r == 0：超时（无消息）。回到循环顶部重新检查 closed，不产生额外调用。
        }
    }

    private void dispatch(long[] msg) {
        final int type = (int) msg[0];
        final int a = (int) msg[1];
        final int b = (int) msg[2];
        final int c = (int) msg[3];
        switch (type) {
            case NativeSandbox.MSG_MMIO -> {
                mmioCount++;
                final int size = c & 0xFF;
                final boolean isWrite = (c & 0x100) != 0;
                final DeviceBus deviceBus = this.bus;
                int result = 0;
                if (deviceBus != null) {
                    if (isWrite) {
                        deviceBus.write(a, size, b);
                    } else {
                        result = deviceBus.read(a, size);
                    }
                }
                // ⚠ 兑现后必须回灌，沙箱线程正卡在这一条事务上等它
                NativeSandbox.mmioResult(handle, result);
            }
            case NativeSandbox.MSG_FAULT -> {
                faultCause = a;
                faultTval = b;
                faultEpc = c;
                flags |= FLAG_FAULTED;
                events.onFault(a, b, c);
            }
            case NativeSandbox.MSG_HALTED -> {
                flags |= FLAG_HALTED;
                pc = a;
                events.onHalted(a);
            }
            case NativeSandbox.MSG_BREAKPOINT -> {
                flags &= ~FLAG_RUNNING;
                pc = a;
                events.onBreakpoint(a);
            }
            case NativeSandbox.MSG_RESET -> {
                flags = 0;
                pc = a;
                faultCause = 0;
                faultTval = 0;
                faultEpc = 0;
                events.onReset(a);
            }
            case NativeSandbox.MSG_PAUSED -> {
                flags &= ~FLAG_RUNNING;
                pc = a;
            }
            case NativeSandbox.MSG_STOPPED -> {
                flags &= ~FLAG_RUNNING;
                events.onStopped();
            }
            default -> {
                // LOADED / STEPPED：状态快照里已可见，无需回调
            }
        }
    }

    /* ==================== 关闭 ==================== */

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (keepAlive != null) {
            keepAlive.interrupt();
            keepAlive = null;
        }
        try {
            NativeSandbox.stop(handle);
        } catch (Throwable ignored) {
            // 库已卸载等极端情况：继续往下走，尽量释放
        }
        try {
            threadsDone.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            NativeSandbox.destroy(handle);
        } finally {
            releaseWorker(runWorker);
            releaseWorker(pumpWorker);
            runWorker = null;
            pumpWorker = null;
        }
    }

    private static void releaseWorker(PinnedWorker worker) {
        if (worker == null) {
            return;
        }
        try {
            ThreadDispatchers.get().releasePinnedSlot(worker);
        } catch (Throwable ignored) {
            // 分配器可能已关闭：忽略
        }
    }

    /* ==================== 状态快照 ==================== */

    /** 沙箱状态快照（layout 见 {@link NativeSandbox#snapshot}）。 */
    public static final class Snapshot {

        private final long[] raw;

        Snapshot(long[] raw) {
            this.raw = raw;
        }

        public int pc() {
            return (int) raw[0];
        }

        public int reg(int index) {
            return index >= 1 && index <= 31 ? (int) raw[index] : 0;
        }

        /** 累计执行指令数（= 周期数，一周期一条指令）。 */
        public long instructions() {
            return raw[33];
        }

        public long clockHz() {
            return raw[34];
        }

        public boolean running() {
            return raw[35] != 0;
        }

        public boolean waitingMmio() {
            return raw[36] != 0;
        }

        public boolean faulted() {
            return raw[37] != 0;
        }

        public boolean halted() {
            return raw[38] != 0;
        }

        public int breakpoints() {
            return (int) raw[39];
        }

        public int faultCause() {
            return (int) raw[40];
        }

        public int faultTval() {
            return (int) raw[41];
        }

        public int faultEpc() {
            return (int) raw[42];
        }

        public int pendingMessages() {
            return (int) raw[43];
        }

        @Override
        public String toString() {
            return "pc=0x" + Integer.toHexString(pc())
                    + " instret=" + instructions()
                    + " clock=" + (clockHz() / 1_000_000) + "MHz"
                    + (running() ? " running" : " paused")
                    + (waitingMmio() ? " mmio" : "")
                    + (faulted() ? " FAULT(cause=" + faultCause() + " tval=0x"
                            + Integer.toHexString(faultTval()) + ")" : "")
                    + (halted() ? " halted" : "");
        }
    }
}
