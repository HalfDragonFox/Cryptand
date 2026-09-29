/**
 * ===== 沙箱设备总线（OcSandboxBridge，2026-09-18）=====
 *
 * <p>自驱动沙箱访问"非 RAM/ROM 地址"时，会以 {@code MSG_MMIO} 消息把事务抛给 Java
 * （{@link com.hdf.cryptand.soc.nativebridge.SandboxVm.DeviceBus}），本类就是那个落点：
 * 把地址翻译成板级设备的读写，并处理两件只有板子才知道的事 ——
 * <b>设备时间</b>与<b>组件调用触发</b>。</p>
 *
 * <h3>一、设备时间：惰性推进（lazy catch-up）</h3>
 * <p>旧模型里 {@code SocBoard.step()} 每片都替所有 {@code Steppable} 走一步（定时器
 * {@code mtime} 递增、UART 按周期吐字节），因为宿主本来就在"按周期喂预算"。
 * 新模型下宿主不再喂预算 ⇒ 设备的"周期"只能有一个来源：<b>沙箱真正执行过的周期数</b>
 * （{@code instret}）。于是这里改成：</p>
 * <ul>
 *   <li>每次设备访问前，把 timer/uart 推进到"沙箱当前周期"（{@code instret - 上次推进}）；</li>
 *   <li>心跳里再推一次（覆盖"固件忙等而不访问设备"的情况，保证 FreeRTOS 的 tick 中断按时到）。</li>
 * </ul>
 * <p>语义上这比旧模型更准：{@code mtime} 不再"每片推一点可能落后"，而是**永远等于**已执行周期数。</p>
 *
 * <h3>二、组件调用：事件驱动，不再轮询</h3>
 * <p>固件的组件调用协议是"写 {@code CALL=1} → 轮询 {@code STATUS}"（{@link OcAbi}）。
 * 旧模型靠 {@code OcArchitectureCore.step()} 每轮跑完预算后泵一次；新模型下没有"每轮"
 * 了，所以改成<b>写 CALL 的那一刻就地兑现</b>：设备写进入这条路时立刻调
 * {@code onCall}（= {@code OcArchitectureCore.pump()}）。固件随后的读 STATUS 一定能
 * 看到 DONE/ERROR —— 因为调用在写 CALL 的同一个事务里就已经完成了。</p>
 *
 * <h3>三、线程归属（与 OC 官方 Lua 架构同线程直调，2026-09-17 核实）</h3>
 * <p>本类的读写、以及由此触发的 {@code onCall} → {@code OcComponentBus} →
 * {@code Machine.invoke}，全部发生在 {@link com.hdf.cryptand.soc.nativebridge.SandboxVm}
 * 的<b>消息泵线程</b>上（一个 Cryptand 自己分配的 pinned 线程，不是原版线程池）。
 * 依据：OC 官方的 Lua 架构同样在工作线程里直接调 {@code machine.invoke}
 * （{@code luaj/ComponentAPI.scala:67}），而 {@code Machine.invoke}
 * （{@code Machine.scala:402-424}）只做可见性检查后转发，<b>没有线程断言、没有加锁</b>；
 * 真正的共享状态由组件自己保护（如 {@code GraphicsCard} 的每个回调都过
 * {@code screen.synchronized(...)}）。所以这里**不需要**回主线程，也不需要
 * {@code ExecutionResult.SynchronizedCall}。</p>
 */
package com.hdf.cryptand.soc.oc;

import com.hdf.cryptand.soc.api.Steppable;
import com.hdf.cryptand.soc.device.TimerDevice;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.memory.SimpleInterruptController;
import com.hdf.cryptand.soc.memory.SimpleMemoryMap;
import com.hdf.cryptand.soc.nativebridge.SandboxVm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public final class OcSandboxBridge implements SandboxVm.DeviceBus {

    /** 只保护 {@link #syncedCycles}（快速，绝不在持锁时做设备调用的慢活） */
    private final Object counterLock = new Object();

    /** 保护设备本身（timer/uart 都不是线程安全的：消息泵与心跳线程都会推进它们） */
    private final Object deviceLock = new Object();

    private final SimpleMemoryMap memoryMap;
    private final SimpleInterruptController interruptController;
    private final List<Steppable> steppables = new ArrayList<>();
    private final long bridgeBase;
    private final LongSupplier cycles;
    private final Runnable onCall;

    /** 设备时间已推进到的沙箱周期数（{@link #syncDevices()} 的基准） */
    private long syncedCycles;

    /** 诊断：设备事务数 / 未映射地址访问数 / 因写 CALL 触发的组件调用数 */
    private long deviceAccesses;
    private long unmappedAccesses;
    private long callTriggers;

    /**
     * 未映射访问诊断（**保留**，别当"临时调试代码"删掉）：记下前 4 个**不同**的未映射地址
     * 与最近一个。2026-09-17 真机排障正是靠它一眼看出"固件在访问 `0xff85051f` 这类野地址"，
     * 才把方向从"设备桥没接好"扭到"系统镜像把正在跑的 Boot 覆盖了"。
     *
     * <p>为什么需要：真机实测出现过"每 4 个周期就有一次未映射设备访问"的风暴
     * （100 秒 13 万次；每次都跨语言往返 ⇒ 标称 100 MHz 被拖到实测 0.03 MHz，
     * 固件卡死在早期启动）。计数本身不说是哪个地址，只有把地址打出来才能判定
     * 是"固件访问了还没实现的设备"还是"地址算错了"。</p>
     */
    private final long[] unmappedAddrs = new long[4];
    private long lastUnmappedAddr = -1;

    private void noteUnmapped(long addr) {
        for (int i = 0; i < unmappedAddrs.length; i++) {
            if (unmappedAddrs[i] == addr) {
                return;
            }
            if (unmappedAddrs[i] == 0) {
                unmappedAddrs[i] = addr;
                return;
            }
        }
        lastUnmappedAddr = addr;
    }

    private OcSandboxBridge(SocBoard board, long bridgeBase, LongSupplier cycles, Runnable onCall) {
        this.memoryMap = board.memoryMap();
        this.interruptController = board.interruptController();
        this.bridgeBase = bridgeBase;
        this.cycles = cycles;
        this.onCall = onCall == null ? () -> {
        } : onCall;
        // 板子上所有会随周期前进的设备（定时器、UART…）——按能力表收集，
        // 将来加设备不需要改本类（沙箱这边只认 Steppable 这个语义）。
        for (final var device : board.devices()) {
            /* ⚠ 定时器（CLINT）已经是**虚拟机自己**的模块（2026-09-28 定案）：
             * mtime / mtimecmp / MTIP 全在沙箱内部产生，宿主不再喂时间、也不再推 bit7。
             * 这里跳过它，免得留下"会打架的第二份权威"（它的数字也不再有意义）。
             * 纯 Java 内核（对照/兜底）走 SocBoard 自己 step，不受影响。 */
            if (device instanceof TimerDevice) {
                continue;
            }
            if (device instanceof Steppable steppable) {
                steppables.add(steppable);
            }
        }
    }

    /**
     * 建桥。
     *
     * @param board      板子（提供地址空间与设备表）
     * @param bridgeBase OC 桥寄存器组的基址（固件写 {@code CALL} 的落点）
     * @param cycles     沙箱当前周期数（一般传 {@code SandboxVm::instructionsRetired}）
     * @param onCall     固件写 {@code CALL} 时要跑的组件调用泵
     */
    public static OcSandboxBridge create(SocBoard board, long bridgeBase,
                                         LongSupplier cycles, Runnable onCall) {
        return new OcSandboxBridge(board, bridgeBase, cycles, onCall);
    }

    // ==================== DeviceBus ====================

    /** 🔍 临时诊断：固件读 STATUS 的次数 / 最后读到的值（不打印，由心跳日志汇总） */
    private volatile int lastStatusRead = -1;
    private volatile long statusReads;

    /** 🔍 临时诊断：写 CALL=1 后"立刻回读" / "pump 之后回读" 到的值 */
    private volatile int writeBackAfterStore = -1;
    private volatile int writeBackAfterPump = -1;

    /** 🔍 临时诊断（见 write 里的说明） */
    public int writeBackAfterStore() {
        return writeBackAfterStore;
    }

    /** 🔍 临时诊断（见 write 里的说明） */
    public int writeBackAfterPump() {
        return writeBackAfterPump;
    }

    /** 🔍 临时诊断（见 read 里的说明） */
    public int lastStatusRead() {
        return lastStatusRead;
    }

    /** 🔍 临时诊断（见 read 里的说明） */
    public long statusReads() {
        return statusReads;
    }

    @Override
    public int read(int addr, int size) {
        deviceAccesses++;
        syncDevices();
        try {
            final int v = (int) memoryMap.load(Integer.toUnsignedLong(addr), size);
            // 🔍 临时诊断（决定性实验）：记录**固件实际读到**的 STATUS 值。
            //   配合 OcArchitectureCore.probeStatus()（入队后回读的值）一起看，即可判定：
            //     · 入队回读 = DONE 且 固件读到 = DONE ⇒ 问题不在寄存器，去看固件的退出条件
            //     · 入队回读 = DONE 但 固件读到 ≠ DONE ⇒ 期间被别处覆盖（查谁还在写 STATUS）
            //     · 入队回读 ≠ DONE ⇒ 写没落到同一块内存上
            if (Integer.toUnsignedLong(addr) == (bridgeBase + OcAbi.REG_STATUS)) {
                lastStatusRead = v;
                statusReads++;
            }
            return v;
        } catch (Throwable t) {
            // 未映射地址：与 native 同步路径同口径（读回 0），但要计数 —— 静默吞掉会让
            // "固件访问了不存在的设备"变成一个查不出来的幽灵。
            unmappedAccesses++;
            noteUnmapped(Integer.toUnsignedLong(addr));
            return 0;
        }
    }

    /**
     * 固件写过 {@code CALL=1}、但还没被 tick 上下文兑现的标记。
     *
     * <p>为什么需要它：见 {@link #write} 里那段"为什么不能就地兑现"的说明。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean callPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** "正在兑现"标志：把两条兑现路径（写入现场 / tick 的 drainCalls）串起来，防止重入放大 */
    private final java.util.concurrent.atomic.AtomicBoolean callBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void write(int addr, int size, int value) {
        deviceAccesses++;
        syncDevices();
        try {
            memoryMap.store(Integer.toUnsignedLong(addr), Integer.toUnsignedLong(value), size);
        } catch (Throwable t) {
            unmappedAccesses++;
            noteUnmapped(Integer.toUnsignedLong(addr));
            return;
        }
        // ⚠ 组件调用**不在这里兑现**，只置一个待处理标记。
        //
        // 为什么必须这样（实测教训，2026-09-17）：兑现组件调用会走 OC 的 Machine.invoke(…)
        // ——那是 OC 的对象（内部有锁，且与主线程每 tick 的组件校验共享状态）。而本方法跑在
        // **沙箱自己的消息泵线程**（cryptand-sandbox-pump-oc-…）上，与 OC 主线程是**真并发**：
        // 一旦在这里调 Machine.invoke，主线程读机器状态就会一直等锁，`oc_machine_state`
        // 直接 TimeoutException（并连带 oc_machine_power 失效 ⇒ E2E 的每次 PowerCycle 都无效）。
        //
        // ⚠ 不能拿"官方 Lua 架构也在 worker 线程直调"来类比：官方那个 worker 线程**就是 OC 的
        // machine 线程、受 OC 时间片调度**；我们这个是外挂的 pinned 线程，不受 OC 调度。
        // "同为 worker" ≠ "同受 OC 调度"。
        //
        // 所以：这里只标记；由 {@code CryptandOcArchitecture.sandboxHeartbeat()}（在 runThreaded 里，
        // 即 OC 的 tick 上下文）调 {@link #drainCalls} 兑现。固件侧协议完全不变
        // （写 CALL=1 → 轮询 STATUS），设备内存访问仍留在消息泵线程（那不碰 OC 对象）。
        if (Integer.toUnsignedLong(addr) == (bridgeBase + OcAbi.REG_CALL) && value != 0) {
            // 🔍 决定性诊断：写 CALL=1 之后**立刻回读**，再在 pump 之后再回读一次，把范围一分为二。
            //   现象：固件读到 STATUS 恒为 0(IDLE)、且 OcArchitectureCore 的入队探针从未触发
            //   ⇒ pumpComponentCalls() 的第一行 `if (reg(REG_CALL) == 0) return;` 就返回了。
            //   读法：
            //     afterStore == 0                    ⇒ 这次写根本没落到 bridge 那块寄存器上
            //     afterStore != 0 但 afterPump == 0  ⇒ 中间被别处清零（pump 之前/之中）
            //     两者都 != 0                        ⇒ 写入没问题，问题在 REG_CALL 的读取侧
            writeBackAfterStore = (int) memoryMap.load(Integer.toUnsignedLong(addr), size);
            callTriggers++;
            // ★★ 只置标记，**不在这里兑现**（2026-09-18 真机定性修复）。
            //
            // 原来这里紧跟一句 onCall.run()，与上面 202-216 行的注释（"组件调用不在这里兑现……
            // 由 sandboxHeartbeat() 调 drainCalls 兑现"）互相矛盾，实测代价是：
            // 固件只发起了 2 次引导请求（trying drive ×2 / handing over ×1 / program returned ×0），
            // 宿主却执行了 **34 次 loadProgram**（同一秒内第 8 次时打印的调用栈正是这条路径：
            // OcSandboxBridge.write:228 → pump → pumpComponentCalls → pumpBootService → loadProgram）。
            //
            // 原因是**重入**：兑现（读盘 + 刷 ROM）跑在消息泵线程上、耗时可达毫秒级，
            // 而自驱动的 guest 内核在另一条线程上继续跑 —— 它等不到 STATUS 就会再写一次 CALL=1，
            // 于是"处理中又触发处理"，一次请求被放大成几十次。
            // ABI 的语义是"CALL=1 ⇒ 一次调用"，保证这一点是**宿主的责任**，所以兑现收敛到
            // 唯一入口 drainCalls（tick 上下文，串行且带 CAS 消费）。
            callPending.set(true);
            commitPendingCalls();
            writeBackAfterPump = (int) memoryMap.load(Integer.toUnsignedLong(addr), size);
        }
    }

    /**
     * 在**调用方线程**兑现所有待处理的组件调用 —— 只能由 OC 的 tick 上下文调用。
     *
     * <p>循环取标记而不是只取一次：固件是串行的（一次调用 → 等 STATUS → 下一次），
     * 而同一次心跳里它往往已经发起了下一个调用，连续兑现能把一次开机动画的几十次
     * 组件调用压在少数几个 tick 里，而不是"一次调用等一个 tick"。</p>
     *
     * @param maxMillis 时间上限（防止固件高频调用把整个 tick 占满）
     * @return 本次兑现的调用数
     */
    /**
     * 兑现待处理的组件调用（**可重入安全**，2026-09-18 真机定性后的正解）。
     *
     * <p>背景（两次真机实验的结论）：</p>
     * <ul>
     *   <li>原来这里是无条件 {@code onCall.run()}。实测一次引导请求（固件只发起 2 次：
     *       {@code trying drive} ×2、{@code handing over} ×1）被兑现了 <b>34 次</b>
     *       —— 调用栈证明是 {@code write → pump → pumpBootService → loadProgram}，
     *       即"处理中又触发处理"的<b>重入</b>。</li>
     *   <li>改成"只置标记、交给 {@link #drainCalls}（tick 上下文）兑现"后，真机上 Boot
     *       直接卡在 {@code config: defaults} —— 说明 tick 那条路在本实现里<b>并没有被调用</b>，
     *       立即兑现是必需的。</li>
     * </ul>
     *
     * <p>所以既不能无条件兑现（重入放大），也不能不兑现（卡死）：用 {@code callBusy} 把
     * "正在兑现"这一段串起来 —— 处理期间再来的请求只置标记，由<b>当前这一轮</b>的循环取走。
     * 这既保证了"CALL=1 ⇒ 一次调用"的 ABI 语义，也让固件的高频请求被合并而不是被放大。</p>
     */
    private void commitPendingCalls() {
        if (!callBusy.compareAndSet(false, true)) {
            return;
        }
        try {
            int done = 0;
            while (callPending.compareAndSet(true, false)) {
                onCall.run();
                if (++done >= 256) {
                    // 防呆上限：固件若把 CALL 当心跳刷，不能让它把消息泵线程占死
                    break;
                }
            }
        } finally {
            // ★★ 兑现后把 CALL 强制归零（直接写设备内存，2026-09-18 真机定位的根因修复）。
            //
            // 证据链：固件只在引导瞬间发起 2 次请求（trying drive ×2 / handing over ×1），
            // 但 loadProgram 在整个会话里被调 259 次，且**第 2 次发生在 handing over 之后 3.9 秒**
            // （那时 console ready 早就打出、系统正在跑）⇒ 是"残留的 CALL=1 被后续每次 pump
            // 反复当成新请求处理"。
            // core 里的 setReg(REG_CALL, 0) 走的是核心侧的寄存器写入，实测没能让 guest 侧归零；
            // CALL 是"请求"语义 —— 它必须被**消费到 guest 可见**，否则一次请求会被放大成几十次。
            final int callAddr = (int) (bridgeBase + OcAbi.REG_CALL);
            memoryMap.store(callAddr, 0L, 4);
            writeBackAfterPump = (int) memoryMap.load(callAddr, 4);
            callBusy.set(false);
        }
    }

    public int drainCalls(long maxMillis) {
        // 与 commitPendingCalls() 互斥：两条路都可能被调（tick 上下文 / 写入现场），
        // 但同一次请求只会被其中一条取走（CAS 消费），另一条直接让位。
        if (!callBusy.compareAndSet(false, true)) {
            return 0;
        }
        try {
            final long deadline = System.nanoTime() + maxMillis * 1_000_000L;
            int done = 0;
            while (callPending.compareAndSet(true, false)) {
                onCall.run();
                done++;
                if (System.nanoTime() > deadline) {
                    break;
                }
            }
            return done;
        } finally {
            callBusy.set(false);
        }
    }

    /**
     * 是否有待兑现的组件调用。
     *
     * <p>供 OC 的 tick 上下文决定"要不要把这一轮交给主线程" —— 见 {@code CryptandOcArchitecture.runThreaded}
     * 里返回 {@code ExecutionResult.SynchronizedCall} 的那段说明。</p>
     */
    public boolean hasPendingCall() {
        return callPending.get();
    }

    // ==================== 设备时间 ====================

    /**
     * 把设备推进到沙箱当前周期（惰性 catch-up）。
     *
     * <p>调用点有两处：每次设备访问前（精确到事务），以及每 tick 心跳（覆盖忙等场景）。</p>
     */
    public void syncDevices() {
        final long now = cycles.getAsLong();
        final long delta;
        synchronized (counterLock) {
            delta = now - syncedCycles;
            if (delta <= 0) {
                return;
            }
            syncedCycles = now;
        }
        // 设备推进放在锁外：UART 的出队会回调宿主的字节出口（会打日志），
        // 让心跳线程为此等待是没必要的。拿锁只保证"同一段周期只有一个线程在推"。
        synchronized (deviceLock) {
            long remaining = delta;
            while (remaining > 0) {
                final int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
                for (final Steppable device : steppables) {
                    device.step(chunk);
                }
                remaining -= chunk;
            }
        }
    }

    /**
     * 当前待处理中断位图（RISC-V 标准位：7 = MTIP）。
     *
     * <p>心跳取它下发给沙箱 —— 与旧模型 {@code NativeRv32Core.step()} 开头推送位图的时机
     * 完全一致（都是"用上一轮结束时的设备状态"）。</p>
     */
    public int pendingInterrupts() {
        syncDevices();
        /* ⚠ bit7 = MTIP 由**虚拟机自己的 CLINT** 产生（2026-09-28 定案）：宿主只允许送
         * "外部事件"中断，定时器那一位在这里摘掉 —— 否则会和虚拟机自产的 MTIP 打架。 */
        return (int) interruptController.getPendingInterrupts() & ~(1 << 7);
    }

    // ==================== 诊断 ====================

    public long deviceAccesses() {
        return deviceAccesses;
    }

    public long unmappedAccesses() {
        return unmappedAccesses;
    }

    public long callTriggers() {
        return callTriggers;
    }

    /** 已推进到的沙箱周期（诊断：设备时间与 CPU 时间是否对齐）。 */
    public long syncedCycles() {
        synchronized (counterLock) {
            return syncedCycles;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("OcSandboxBridge[devices=").append(deviceAccesses)
                .append(", calls=").append(callTriggers)
                .append(", unmapped=").append(unmappedAccesses);
        for (int i = 0; i < unmappedAddrs.length && unmappedAddrs[i] != 0; i++) {
            sb.append(i == 0 ? " @0x" : "/0x").append(Long.toHexString(unmappedAddrs[i]));
        }
        if (lastUnmappedAddr != -1) {
            sb.append(" last=0x").append(Long.toHexString(lastUnmappedAddr));
        }
        return sb.append(", synced=").append(syncedCycles()).append(" cyc]").toString();
    }
}
