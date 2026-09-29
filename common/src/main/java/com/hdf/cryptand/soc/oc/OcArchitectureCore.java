/**
 * ===== Cryptand ⇄ OpenComputers 桥接核心（纯 Java，零 MC / 零 OC 依赖，2026-09-16）=====
 *
 * <p>把「RV32 沙箱（{@link SocBoard}）」与「OC 组件总线（{@link ComponentBus}）」接起来：</p>
 * <ol>
 *   <li><b>推进会话</b>：两种执行模型共用本类 ——
 *       ① <b>自驱动</b>（默认）：沙箱按内部时钟自跑，本类不参与推进，
 *          只在固件写 {@code CALL=1} 时被 {@link #pump()} 叫醒兑现组件调用；
 *       ② <b>宿主喂预算</b>（回退）：平台/线程分配器按 tick 调 {@link #step(int)} 跑固定预算；</li>
 *   <li><b>组件调用泵</b>：从 ABI 寄存器组（{@link OcAbi}）读出 C 固件写的请求 →
 *       组 {@link ComponentBus.Call} → 交平台兑现 → 结果回填寄存器。</li>
 * </ol>
 *
 * <h3>线程约定</h3>
 * <p>本类**只在核心侧单线程调用**（沙箱所在线程，通常是 common 的 ThreadDispatchers /
 * PinnedWorker）；{@link ComponentBus} 的实现方负责"跨到主线程兑现"的协调
 * （可跨 tick，与 soc 既有的「MMIO 只走消息」同构）。</p>
 *
 * <h3>为什么寄存器通道先行</h3>
 * <p>它是唯一**脱离 MC 也能自测**的形态：给个假 {@code ComponentBus} 就能在
 * {@code gradlew :common:runSocTest} 体系里跑通"固件发调用 → 核心组包 → 回填结果"，
 * 不依赖 OC 与游戏。ecall / C 头文件封装属于二期（见评估文档 P3）。</p>
 */
package com.hdf.cryptand.soc.oc;

import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.device.RegBankDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class OcArchitectureCore {

    /** ABI 寄存器组需要多少个寄存器（={@link OcAbi#REG_SPAN} / 4） */
    public static final int BRIDGE_REGISTERS = OcAbi.REG_SPAN / 4;

    private final SocBoard board;
    private final RegBankDevice bridge;
    private volatile ComponentBus bus;

    /**
     * 待消化的组件调用队列 —— Java 侧的"**消息接口队列**"（用户 2026-09-17 定案）。
     *
     * <p>生产者是沙箱的**消息泵线程**（固件写 {@code CALL=1} 的那一刻入队），消费者是 **OC 主线程**
     * （{@link #drainCalls}，经 {@code runSynchronized()} 进来）。用并发队列是因为两者是真并发 ——
     * 但**只有队列本身跨线程**：真正的组件调用（{@code ComponentBus.invoke}）严格只发生在消费者线程上。</p>
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<ComponentBus.Call> pendingCalls =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 队列上限（防御：固件疯了也不至于把宿主内存吃光） */
    private static final int PENDING_MAX = 4096;

    /** 因超上限被丢弃的调用数（诊断） */
    private volatile long droppedCalls;

    // ==================== 邮箱配对账本（"已认领 / 已写回"，2026-09-27） ====================
    //
    // 为什么需要它（真机现象）：高级分析器的"邮箱未消化调用"读的是 **guest 内存里的 MB_STATE**
    // （数 BUSY 的通道）。而宿主侧的写入是**排队**进 native 内核命令队列、由沙箱线程在指令边界
    // 落地的（{@code SandboxVm.writeMemory} = CMD_WMEM）—— 也就是说 guest 看到的 BUSY/DONE
    // **永远滞后于宿主自己的动作**。于是"BUSY 通道数"这个采样口径天然会把"宿主已经兑现完、
    // 只是 DONE 还没落地"的那一刻算成"未消化"，机器越忙这个偏差越常被撞上。
    //
    // 正确口径 = **宿主侧账本**：认领（claim）时 +1、写回结果（finish）时 -1。
    // 它不依赖任何跨线程时序，也不采样 guest 内存，所以"空闲时也应该恒为 0"。
    // 账本同时把**每条通道**的认领/写回计数留着，用来区分两种成因：
    //   · 认领与写回**配平**（各通道 claims == finishes）⇒ 没有丢配对，问题在读数口径；
    //   · 某通道 claims > finishes ⇒ 真有调用没写回（写侧漏配对，必须修）。
    // 它与 guest 的 STATE 是**两条独立证据**，所以谁也不能替谁说话（见 mailboxPendingCalls 的对账）。

    /** 邮箱在途调用数（已认领 + 还没写回结果）——宿主侧权威口径 */
    private final java.util.concurrent.atomic.AtomicInteger mailboxInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 每通道的认领次数（诊断用；下标 = 通道号） */
    private final java.util.concurrent.atomic.AtomicIntegerArray mailboxClaims =
            new java.util.concurrent.atomic.AtomicIntegerArray(OcAbi.MAILBOX_CHANNELS);

    /** 每通道的写回次数（诊断用；与 {@link #mailboxClaims} 配对，差就是"没写回"的那几次） */
    private final java.util.concurrent.atomic.AtomicIntegerArray mailboxFinishes =
            new java.util.concurrent.atomic.AtomicIntegerArray(OcAbi.MAILBOX_CHANNELS);

    /** 每通道"认领时刻"（纳秒；0 = 没有在途）。用来算**积压**：在途超过宽限期才算"消化不动" */
    private final java.util.concurrent.atomic.AtomicLongArray mailboxClaimedAt =
            new java.util.concurrent.atomic.AtomicLongArray(OcAbi.MAILBOX_CHANNELS);

    /**
     * "在途多久才算积压"的宽限期 = **2 个 tick**（100 ms）。
     *
     * <p>为什么是这个量级：宿主每 tick 消化一次（{@code runSynchronized} → {@code drainCalls}），
     * 所以一条正常的调用在途时间上界就是 1 个 tick（50 ms）+ native 命令队列落地的零头。
     * 超过两个 tick 还没写回 ⇒ 不是"正在处理"，而是真的消化不动/卡住了 —— 那才是用户要看的东西。</p>
     */
    public static final long MAILBOX_DIGEST_GRACE_NANOS = 100_000_000L;

    /** 🔍 临时诊断：入队后回读到的 STATUS 值（-1 = 还没测过） */
    private volatile int probeStatus = -1;

    /** 🔍 临时诊断：入队后回读到的 STATUS（见 {@code enqueue} 里的说明）。 */
    public int probeStatus() {
        return probeStatus;
    }
    private volatile BootLoaderService bootLoader;

    private volatile boolean initialized;
    private long totalCycles;
    private long callCount;
    private long errorCount;

    public OcArchitectureCore(SocBoard board, RegBankDevice bridge, ComponentBus bus) {
        this.board = Objects.requireNonNull(board, "board");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.bus = bus == null ? ComponentBus.EMPTY : bus;
    }

    // ==================== 生命周期（对应 OC Architecture 的 start / stop）====================

    /** 上电：清寄存器、复位沙箱、回到"未初始化"（固件随后会写 INIT 标志） */
    public void start() {
        bridge.reset();
        board.reset();
        initialized = false;
        callCount = 0;
        errorCount = 0;
    }

    /** 复位（OC 的 stop / 崩溃后重启路径） */
    public void reset() {
        start();
    }

    /** 是否已初始化完成（OC 的 {@code Architecture.isInitialized()} 转发到这里） */
    public boolean isInitialized() {
        return initialized;
    }

    /** 由平台在"固件完成引导"时调用 */
    public void markInitialized() {
        initialized = true;
    }

    // ==================== 预算化推进 ====================

    /**
     * 推进沙箱若干指令并泵一次组件调用 —— <b>只服务于"宿主喂预算"的旧同步路径</b>
     * （纯 Java 内核，或 native 同步内核）。
     *
     * @param cycles 本轮的指令预算（来自 oc 子包配置；0/负值直接返回）
     * @return 实际执行的指令数（沙箱可能提前停下：等待中断/故障）
     */
    public int step(int cycles) {
        if (cycles <= 0) {
            return 0;
        }
        final int done = board.step(cycles);
        totalCycles += done;
        pumpComponentCalls();
        return done;
    }

    // ==================== 组件调用泵 ====================

    /**
     * 组件调用泵的**事件驱动入口**（自驱动沙箱路径用）。
     *
     * <p>旧模型下泵由 {@link #step(int)} 在每轮预算跑完后调用；自驱动模型里没有"每轮"了
     * （宿主不再喂预算），改由设备层在"固件写 {@code CALL=1}"的那一刻调用本方法
     * （见 {@code OcSandboxBridge.write}）。两者语义相同 —— <b>请求出现即兑现</b> ——
     * 但新的不再依赖宿主的推进节奏，也不会在"没有请求"时白跑一趟。</p>
     */
    public void pump() {
        pumpComponentCalls();
    }

    /**
     * 一问一答：固件写 {@code CALL=1} ⇒ 核心组包调总线 ⇒ 结果写回 + {@code STATUS=DONE/ERROR}。
     *
     * <p>⚠ 平台侧适配器负责把 {@code Call.method}（形如 {@code "#3"} 的方法 id）解析成真实方法名 ——
     * 它比核心更清楚该类型的组件有哪些方法。</p>
     */
    /**
     * 组件调用泵的诊断开关：{@code -Dcryptand.debug.calls=1}（测试里可用
     * {@code System.setProperty} 打开）。默认**完全静默**。
     *
     * <p>⚠ 这里原来写的是"打印前 3 次 / 每 100 次打印一次"的硬编码上限。那种写法**会掩盖高频现象**：
     * 排查"引导请求被兑现 300 次"时，正是采样上限把关键序列截掉了，害得结论绕了好几轮。
     * 现在只有开/关两态：关掉零输出，打开**逐次**打印、不做任何采样。</p>
     */
    private static boolean debugCalls() {
        return Boolean.getBoolean("cryptand.debug.calls");
    }

    private void pumpComponentCalls() {
        if (reg(OcAbi.REG_CALL) == 0) {
            return;
        }
        if (debugCalls()) {
            System.out.println("    [core] 兑现组件调用：COMPONENT=" + reg(OcAbi.REG_COMPONENT)
                    + " METHOD=" + reg(OcAbi.REG_METHOD) + " callCount=" + callCount);
        }
        setReg(OcAbi.REG_CALL, 0);                       // 读走请求，释放通道
        setReg(OcAbi.REG_STATUS, OcAbi.STATUS_BUSY);
        callCount++;
        if (debugCalls()) {
            // 清零后立即回读：CALL 是"请求"语义，必须被**消费到 guest 可见**
            System.out.println("    [core] 第 " + callCount + " 次兑现：清零后回读 CALL="
                    + reg(OcAbi.REG_CALL));
        }

        // ⚠ 引导服务走特殊句柄（**不是组件表下标**）：Cryptand Boot 靠它逐盘找程序。
        if (reg(OcAbi.REG_COMPONENT) == OcAbi.HANDLE_BOOT) {
            pumpBootService();
            return;
        }

        final ComponentBus b = bus;
        if (b == null) {
            fail(OcAbi.ERR_NO_BUS);
            return;
        }
        try {
            final List<ComponentBus.Entry> table = b.components();
            final int handle = reg(OcAbi.REG_COMPONENT);
            final int methodId = reg(OcAbi.REG_METHOD);
            final int argc = clamp(reg(OcAbi.REG_ARG_COUNT), 0, OcAbi.ARG_SLOTS);

            final List<Object> args = new ArrayList<>(argc);
            for (int i = 0; i < argc; i++) {
                args.add(reg(OcAbi.REG_ARG0 + i * 4));    // 标量直传
            }
            // 缓冲区参数（文本 / w×h 字符阵列）：固件把 guest 物理地址写进 REG_BUF_ADDR/LEN，
            // 核心按地址从 guest 内存读出来交给平台层。
            // gpu.set 的字符串、gpu.blit 的一整屏字符都走这条路（标量槽只有 8 个，装不下一行文本）。
            final int bufAddr = reg(OcAbi.REG_BUF_ADDR);
            final int bufLen = clamp(reg(OcAbi.REG_BUF_LEN), 0, OcAbi.BUF_MAX);
            final byte[] buffer = bufLen > 0
                    ? board.readMemory(Integer.toUnsignedLong(bufAddr), bufLen) : null;

            final String address = addressOf(handle, table);
            final String component = componentOf(handle, table);

            // ★ 不在这里执行 —— 只入队，并**立刻**告诉固件"已接受"（STATUS=DONE）。
            //   为什么必须这样（实测定案）：固件的 `component_invoke` 是**同步自旋**（hal.c 的
            //   OC_POLL_LIMIT = 2M 次 ≈ 120ms @100MHz），而宿主能在 OC 认可的上下文
            //   （runSynchronized，主线程）里兑现的节奏是**每 tick 一次**（50ms）—— 两者同量级，
            //   只要主线程被别的事占住一拍，固件必然超时；超时后它立刻重试，于是每 tick 都要
            //   回主线程一次，把主线程占满（实测 oc_machine_state 直接 TimeoutException）。
            //   入队 + 立即应答把这条链断掉：固件拿到 DONE 就继续跑、**不需要等**；宿主按自己的
            //   节奏在 tick 上下文里批量消化。⇒ **固件侧代码一行不用改。**
            final ComponentBus.Call call =
                    new ComponentBus.Call(address, component, "#" + methodId, args, buffer);
            pendingCalls.add(call);
            while (pendingCalls.size() > PENDING_MAX) {
                final ComponentBus.Call dropped = pendingCalls.poll();
                droppedCalls++;
                // ⚠ **丢弃也必须配对**（2026-09-27）：邮箱来源的调用（channel >= 0）被丢掉之后，
                //   对应通道在固件眼里永远停在 BUSY —— 而固件的 component_invoke 是无限自旋等
                //   DONE/ERROR 的，于是整台机器**卡死在那个调用上**（不是"看着像空闲"而已）。
                //   所以丢之前先把 ERROR 写回它的通道（与 finishMailboxError 同一条口径）。
                if (dropped != null && dropped.channel() >= 0) {
                    finishMailboxError(dropped, OcAbi.ERR_COMPONENT_FAILED);
                    if (debugCalls()) {
                        System.out.println("    [core] 队列超上限：邮箱通道 " + dropped.channel()
                                + " 的调用已丢弃并写回 ERROR（绝不留下停在 BUSY 的通道）");
                    }
                }
            }
            setReg(OcAbi.REG_RESULT_COUNT, 0);
            setReg(OcAbi.REG_ERROR, OcAbi.ERR_NONE);
            setReg(OcAbi.REG_STATUS, OcAbi.STATUS_DONE);
            // 🔍 决定性诊断（临时）：入队后回读 STATUS，确认"写进去的"确实是 DONE。
            //   固件当前表现为一直在自旋读 STATUS（devices/cycles ≈ 1/5）却拿不到 DONE，
            //   这里回读一次即可区分"没写进去" vs "写了之后被别处覆盖"。
            if (callCount <= 3) {
                probeStatus = reg(OcAbi.REG_STATUS);
            }
        } catch (Throwable t) {
            fail(OcAbi.ERR_COMPONENT_FAILED);
        }
    }

    /**
     * **扫描外部设备邮箱区**：把 {@code STATE=REQUEST} 的通道认领（置 BUSY）并组包入队。
     *
     * <p>这是用户 2026-09-17 定案的落地：外部设备走**内存邮箱**而不是 MMIO 寄存器 ——
     * 固件写参数就是普通 {@code sw}（native 内核自持 RAM ⇒ 零 MMIO 往返），宿主每 tick 扫一次
     * 邮箱头（{@code board.readInt}，几十字节）即可发现请求。</p>
     *
     * <p>⚠ 对比被替换掉的旧路径：固件以前是「填寄存器 → {@code sw CALL=1} → 自旋 {@code lw STATUS}
     * 200 万次」。每一次 {@code sw}/{@code lw} 都是一次完整往返（≈0.44ms），于是自旋把 100 MHz 的
     * 机器拖成 0.01 MHz（日志指纹 devices/cycles ≈ 1/5）。现在**轮询的是 RAM**，只烧自己的 CPU 周期。</p>
     *
     * <p>认领时立刻置 BUSY（而不是等执行完才改状态），避免同一个请求被重复认领。</p>
     *
     * @return 本次认领的通道数
     */
    public int pollMailbox() {
        int claimed = 0;
        for (int i = 0; i < OcAbi.MAILBOX_CHANNELS; i++) {
            final long base = OcAbi.mailboxChannel(i);
            if (board.readInt(base + OcAbi.MB_STATE) != OcAbi.MB_STATE_REQUEST) {
                continue;
            }
            guestWriteInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_BUSY);
            try {
                final List<ComponentBus.Entry> table = bus == null ? List.of() : bus.components();
                final int handle = board.readInt(base + OcAbi.MB_COMPONENT);
                final int methodId = board.readInt(base + OcAbi.MB_METHOD);
                final int argc = clamp(board.readInt(base + OcAbi.MB_ARGC), 0, OcAbi.ARG_SLOTS);
                final List<Object> args = new ArrayList<>(argc);
                for (int k = 0; k < argc; k++) {
                    args.add(board.readInt(base + OcAbi.MB_ARG0 + k * 4));
                }
                final int bufAddr = board.readInt(base + OcAbi.MB_BUF_ADDR);
                final int bufLen = clamp(board.readInt(base + OcAbi.MB_BUF_LEN), 0, OcAbi.BUF_MAX);
                final byte[] buffer = bufLen > 0
                        ? board.readMemory(Integer.toUnsignedLong(bufAddr), bufLen) : null;
                // 句柄 = OC 组件表下标（盘也是组件表里的一项，见 OcAbi 的"句柄空间分工"）。
                // 表外句柄走 addressOf/componentOf 的映射（含 PE 服务 HANDLE_PE）；
                // 真·未知句柄在那里得到空串，让平台侧明确失败（不静默当一个组件用）。
                final String address = addressOf(handle, table);
                final String component = componentOf(handle, table);
                pendingCalls.add(new ComponentBus.Call(
                        address, component, "#" + methodId, args, buffer, i));
                // ★ 配对账本：认领 +1（写回在 finishMailbox / finishMailboxError 里 -1）
                mailboxInFlight.incrementAndGet();
                mailboxClaims.incrementAndGet(i);
                mailboxClaimedAt.set(i, System.nanoTime());
                callCount++;
                claimed++;
            } catch (Throwable t) {
                // 组包失败：别让通道永远停在 BUSY（固件会一直等）
                guestWriteInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_ERROR);
            }
        }
        return claimed;
    }

    /**
     * 句柄 → 组件地址（**唯一映射点**，两条通道共用）。
     *
     * <p>句柄空间的两种含义都在这里收口：</p>
     * <ul>
     *   <li>组件表下标（GPU/屏幕/键盘/盘…）；</li>
     *   <li><b>PE 服务</b>（{@link OcAbi#HANDLE_PE}）—— 它不是 OC 机箱里插的组件，而是
     *       宿主提供的"装机能力"（分区/格式化/安装），与引导服务同构，所以地址固定为 {@code "pe"}。</li>
     * </ul>
     * <p>表外且不是 PE 的句柄 ⇒ 空串 ⇒ 平台侧明确报"no such component"（不静默挑一个组件）。</p>
     */
    private String addressOf(int handle, List<ComponentBus.Entry> table) {
        if (handle == OcAbi.HANDLE_PE) {
            return "pe";
        }
        return handle >= 0 && handle < table.size() ? table.get(handle).address() : "";
    }

    /** 句柄 → 组件名（与 {@link #addressOf} 同一套规则） */
    private String componentOf(int handle, List<ComponentBus.Entry> table) {
        if (handle == OcAbi.HANDLE_PE) {
            return "pe";
        }
        return handle >= 0 && handle < table.size() ? table.get(handle).component() : "";
    }

    /** 队列里是否有待消化的组件调用（供 tick 上下文决定要不要把这一轮交给主线程）。 */
    public boolean hasPendingCalls() {
        return !pendingCalls.isEmpty();
    }

    /** 因超队列上限被丢弃的调用数（诊断）。 */
    public long droppedCalls() {
        return droppedCalls;
    }

    // ==================== 邮箱账本（平台口径 / 诊断，2026-09-27 加） ====================

    /**
     * 邮箱**在途**调用数 = 已认领、还没写回结果的调用数（宿主侧权威口径；未开机 = 0）。
     *
     * <p>⚠ 它与 guest 内存里的 {@code MB_STATE == BUSY} **不是**同一个口径：宿主的写回是排队进
     * native 内核命令队列、由沙箱线程在指令边界才落地的（{@code SandboxVm.writeMemory} =
     * {@code CMD_WMEM}），所以 guest 看到的 BUSY/DONE 永远滞后于宿主账本 ——
     * "BUSY 通道数"必然会把"宿主已经兑现完、DONE 还在路上"算成未消化。
     * 显示"未消化调用"一律用本方法；guest 的 BUSY 只当**对账证据**（见平台侧 mailboxPendingCalls）。</p>
     */
    public int mailboxInFlight() {
        return mailboxInFlight.get();
    }

    /**
     * **积压**的调用数 = 在途**且**已经超过 {@link #MAILBOX_DIGEST_GRACE_NANOS}（2 个 tick）的通道数。
     *
     * <p>这才是"未消化"该显示的数：宿主每 tick 消化一次，所以正常的在途调用（≤1 个 tick）
     * <b>不算积压</b> —— 把它算进去正是真机上"机器空闲也恒报 1"的来源（采样撞上在途事务）。
     * 真积压只可能有两种成因：写侧漏配对（认领了没写回）、或宿主长时间没进 runSynchronized
     * 上下文（主线程被卡住）——两种都必须看见。</p>
     *
     * @param nowNanos 当前时刻（纳秒，{@link System#nanoTime()} 的时基）；离线闸门用合成时刻断言
     */
    public int mailboxBacklog(long nowNanos) {
        int stuck = 0;
        for (int i = 0; i < OcAbi.MAILBOX_CHANNELS; i++) {
            final long claimedAt = mailboxClaimedAt.get(i);
            if (claimedAt != 0L && nowNanos - claimedAt >= MAILBOX_DIGEST_GRACE_NANOS) {
                stuck++;
            }
        }
        return stuck;
    }

    /** 同 {@link #mailboxBacklog(long)}，用当前时刻 */
    public int mailboxBacklog() {
        return mailboxBacklog(System.nanoTime());
    }

    /** 通道 {@code channel} 被认领过几次（诊断；越界 = -1） */
    public int mailboxClaims(int channel) {
        return channel >= 0 && channel < OcAbi.MAILBOX_CHANNELS ? mailboxClaims.get(channel) : -1;
    }

    /** 通道 {@code channel} 写回过几次（诊断；越界 = -1） */
    public int mailboxFinishes(int channel) {
        return channel >= 0 && channel < OcAbi.MAILBOX_CHANNELS ? mailboxFinishes.get(channel) : -1;
    }

    /** 通道 {@code channel} 的认领与写回是否配平（{@code false} = 真有调用认领了没写回） */
    public boolean mailboxPaired(int channel) {
        return mailboxClaims(channel) == mailboxFinishes(channel);
    }

    /** 全部通道是否都配平（{@code true} = 不存在"认领了没写回"的调用） */
    public boolean mailboxAllPaired() {
        for (int i = 0; i < OcAbi.MAILBOX_CHANNELS; i++) {
            if (!mailboxPaired(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * **消化队列**：出队 → 调组件总线 → 结果写回寄存器。
     *
     * <p>只能由 OC 认可的上下文调用（{@code runSynchronized()} = 主线程），理由见
     * {@code CryptandOcArchitecture.runThreaded} 里 {@code SynchronizedCall} 那段。</p>
     *
     * <p>连续消化 + 时间上限：固件是串行的（投递完立刻继续跑），一个 tick 里往往积累了好几个
     * 操作，批量处理比"每次调用等一个 tick"快得多；上限则保证固件高频投递时不会把主线程占满
     * —— 这就是「**拿不到就跳过、绝不卡主线程**」的落实。</p>
     *
     * @param maxMillis 时间上限
     * @return 本次消化的调用数
     */
    public int drainCalls(long maxMillis) {
        // ⚠ 用户 2026-09-25 定案：**一次性消化完**，不再按时间截断（maxMillis 保留只为兼容旧调用）。
        //   原实现有 maxMillis 上限，而固件**每个字符都 blit 一次**、单个 gpu 渲染就可能吃掉整个
        //   限额 ⇒ 每 tick 只消化一两个、队列永远排不空 —— 真机表现就是屏幕/日志滞后若干字符。
        //   用户原话："宿主每 tick 消化是**一次性消化完成**…… 主线程有一个队列**只要不满就发送**……
        //   UART 也是**只写不回读**，这样速度会很快"。
        //   积压本身才是病态：队列排空后每 tick 只有几个调用，不会占住主线程。
        int drained = 0;
        while (true) {
            final ComponentBus.Call call = pendingCalls.poll();
            if (call == null) {
                break;
            }
            applyCall(call);
            drained++;
        }
        return drained;
    }

    /** 兑现单个调用并把结果写回寄存器 / 邮箱。 */
    private void applyCall(ComponentBus.Call call) {
        final ComponentBus b = bus;
        if (b == null) {
            finishMailbox(call, false, 0, null);
            fail(OcAbi.ERR_NO_BUS);
            return;
        }
        try {
            final ComponentBus.Result r = b.invoke(call);
            if (r == null || !r.ok()) {
                // 用 Result 自带的 ABI 错误码（文件系统要靠它区分 ERR_NOT_FOUND / ERR_NO_SPACE…），
                // 没有码时才退化成"组件调用失败"。
                final int code = r == null ? OcAbi.ERR_COMPONENT_FAILED : r.errCode();
                finishMailboxError(call, code);
                fail(code);
                return;
            }
            final List<Object> values = new ArrayList<>(r.values());
            // ★ 出方向缓冲区（宿主 → guest）：FS_READ / FS_LIST 的"返回体"不是标量，而是要写进
            //   **固件自带的那块缓冲区**的数据（ABI 表里标"出"）。地址与容量由固件放在邮箱的
            //   MB_BUF_ADDR / MB_BUF_LEN 里；方向判定只有 OcAbi.isFsOutbound 一张表，两侧共用
            //   —— 没有额外的方向标志位（见 cryptand-fs-design.md §3.3.2）。
            //   写回后把 result0 换成**实际写入的字节数**，固件读 result0 就知道该取多少
            //   （FS_READ 的 EOF 语义 = -1，由平台层的文件系统实现给出）。
            //   ⚠ 时序：guestWrite 走内核命令队列（FIFO），finishMailbox 随后写的 STATE=DONE
            //     排在它后面 ⇒ 固件看到 DONE 时，数据一定已经在内存里了。
            if (call.channel() >= 0 && OcAbi.isOutbound(call.component(), methodIdOf(call))
                    && !values.isEmpty() && values.get(0) instanceof byte[] payload) {
                values.set(0, writeOutboundBuffer(call.channel(), payload));
            }
            final int n = Math.min(values.size(), OcAbi.RESULT_SLOTS);
            for (int i = 0; i < n; i++) {
                setReg(OcAbi.REG_RESULT0 + i * 4, toInt(values.get(i)));
            }
            setReg(OcAbi.REG_RESULT_COUNT, n);
            setReg(OcAbi.REG_ERROR, OcAbi.ERR_NONE);
            setReg(OcAbi.REG_STATUS, OcAbi.STATUS_DONE);
            finishMailbox(call, true, n, values);
        } catch (Throwable t) {
            finishMailbox(call, false, 0, null);
            fail(OcAbi.ERR_COMPONENT_FAILED);
        }
    }

    /**
     * 写 guest 内存的**唯一入口**：优先投进内核的异步命令队列。
     *
     * <p>用户 2026-09-17 定案：「是否成功只需要沙箱接收到相应数据后**当前命令周期一条执行完成后
     * 先写入寄存器然后继续运行**即可」—— 写入必须落在**两条指令之间**，而不是从宿主线程插进去。</p>
     *
     * <p>{@code postMemoryWrite} 就是那条"插入一条改值命令"的路：内核线程在自己循环的固定点
     * drain 命令队列，因此写入时序确定、且**内核独占机器状态**（没有跨线程并发写同一块内存）。
     * 内核不支持异步投递时（返回 false）回落到同步 {@code writeMemory} —— 行为等价，只是少了
     * 时序保证。精度由内核粒度决定：需要单条级时用断点/同步点把 quantum 降到 1（已内建）。</p>
     */
    private void guestWrite(long address, byte[] data) {
        if (!board.cpu().postMemoryWrite(address, data)) {
            board.writeMemory(address, data);
        }
    }

    /**
     * 取调用里的方法 id —— 核心给的是 {@code "#16"} 形式（平台层才翻成方法名），
     * 非该形式返回 -1。
     */
    private static int methodIdOf(ComponentBus.Call call) {
        final String m = call.method();
        if (m == null || m.length() < 2 || m.charAt(0) != '#') {
            return -1;
        }
        try {
            return Integer.parseInt(m.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 把出方向数据写进 guest 缓冲区，返回**实际写入的字节数**。
     *
     * <p>只做搬运，不替文件系统解释语义：容量不足时写入 {@code min(容量, 数据长度)} 并返回实际量，
     * 由平台层决定要不要用 {@code ERR_BUF_TOO_SMALL} 表达"装不下"（绝不在这里静默吞掉）。</p>
     */
    private int writeOutboundBuffer(int channel, byte[] data) {
        final long base = OcAbi.mailboxChannel(channel);
        final int cap = clamp(board.readInt(base + OcAbi.MB_BUF_LEN), 0, OcAbi.BUF_MAX);
        final long addr = Integer.toUnsignedLong(board.readInt(base + OcAbi.MB_BUF_ADDR));
        if (addr == 0 || cap <= 0) {
            return data.length;      // 固件没给缓冲区：只报"本应有多少"，由它自己决定怎么办
        }
        final int n = Math.min(data.length, cap);
        guestWrite(addr, n == data.length ? data : java.util.Arrays.copyOf(data, n));
        return n;
    }

    /** 写一个 32 位小端整数到 guest 内存（邮箱字段用；走 {@link #guestWrite} 的单一路径）。 */
    private void guestWriteInt(long address, int value) {
        guestWrite(address, new byte[]{
                (byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF),
                (byte) ((value >>> 16) & 0xFF), (byte) ((value >>> 24) & 0xFF)});
    }

    /**
     * 失败写回邮箱：`STATE=ERROR` 且 **`RESULT0` = ABI 错误码**、`RESULT_COUNT=1`。
     *
     * <p>为什么借用 RESULT0 而不是新增 `MB_ERROR` 槽：每通道跨度 96 字节（0x60）已经被现有
     * 字段排满，再加字段就要动 stride（两侧同步、还得重编固件）；而 `STATE=ERROR` 时
     * `RESULT_*` 本来是空的 —— 拿第一个槽放错误码零成本，语义也直白：
     * <b>"结果区第一个数 = 错误码"</b>（两侧同一条约定，见 {@code hal.h} 的 `OC_MB_RESULT0` 注释）。</p>
     */
    private void finishMailboxError(ComponentBus.Call call, int errCode) {
        final int channel = call.channel();
        if (channel < 0) {
            return;
        }
        // ★ 配对账本：写回（无论成功还是失败）都要记一笔 —— 只有"认领了没写回"才是病
        mailboxInFlight.decrementAndGet();
        mailboxFinishes.incrementAndGet(channel);
        mailboxClaimedAt.set(channel, 0L);
        final long base = OcAbi.mailboxChannel(channel);
        try {
            guestWriteInt(base + OcAbi.MB_RESULT_COUNT, 1);
            guestWriteInt(base + OcAbi.MB_RESULT0, errCode);
            guestWriteInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_ERROR);
        } catch (Throwable ignored) {
            // 写回失败不该影响机器继续跑
        }
    }

    /**
     * 把结果写回**邮箱通道**（{@code call.channel() >= 0} 时才做）。
     *
     * <p>写回方向同样是普通内存写：宿主经 {@code board.writeMemory} → {@code cpu.writeMemory} →
     * native 内核自持的那块 RAM。固件那边只是普通的 {@code lw} —— 零 MMIO、零往返。</p>
     *
     * <p>顺带把寄存器路径也写一遍（{@code applyCall} 里已写），这样**引导/旧协议**与**邮箱协议**
     * 可以并存：固件选哪条路都拿得到结果。</p>
     */
    private void finishMailbox(ComponentBus.Call call, boolean ok, int count, List<Object> values) {
        final int channel = call.channel();
        if (channel < 0) {
            return;
        }
        // ★ 配对账本：认领（pollMailbox）与写回（这里）是唯一的一对增减点
        mailboxInFlight.decrementAndGet();
        mailboxFinishes.incrementAndGet(channel);
        mailboxClaimedAt.set(channel, 0L);
        final long base = OcAbi.mailboxChannel(channel);
        try {
            if (ok) {
                guestWriteInt(base + OcAbi.MB_RESULT_COUNT, count);
                for (int i = 0; i < count; i++) {
                    guestWriteInt(base + OcAbi.MB_RESULT0 + i * 4, toInt(values.get(i)));
                }
                // SEQ 自增：固件可用它识别"这是新一帧的结果"（防 ABA）
                guestWriteInt(base + OcAbi.MB_SEQ, board.readInt(base + OcAbi.MB_SEQ) + 1);
                guestWriteInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_DONE);
            } else {
                guestWriteInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_ERROR);
            }
        } catch (Throwable ignored) {
            // 写回失败不该影响机器继续跑
        }
    }

    /**
     * 引导服务（Cryptand Boot 用）：兑现一次 <b>BIOS 读盘服务</b>调用
     * —— {@code boot.readFile(path, loadAddr)}，即 <b>INT 13h 的等价物</b>。
     *
     * <p>平台侧实现（{@code OcBootLoader}）负责"打开引导盘、按路径读文件、写进 guest 内存"，
     * 核心只做寄存器/缓冲区搬运 ⇒ 与组件桥一样的分层纪律。</p>
     *
     * <p>⚠ 路径走的是<b>固件自带的缓冲区</b>（{@link OcAbi#REG_BUF_ADDR}/{@link OcAbi#REG_BUF_LEN}）
     * —— 这不是新机制：GPU 的文本与 FS 的路径一直是这么传的，固件的 {@code oc_boot_invoke}
     * 也一直把 {@code buf/bufLen} 写进这两个寄存器（{@code hal.c}）。这里只是第一次把它读出来用。</p>
     *
     * <p>⚠ 收口史（2026-09-25 → 09-27）：这里先后是"列举盘 / 读第 N 块盘"（bootloader 自己找盘）
     * 与"向宿主索要系统本体"（无参数、路径写死在宿主）。两者都把**策略**放在了宿主 ——
     * 而现实里"读哪个文件"是引导程序的事。现在 guest 给路径与载入地址，宿主只管读出来放进去。</p>
     */
    private void pumpBootService() {
        final BootLoaderService svc = bootLoader;
        if (svc == null) {
            fail(OcAbi.ERR_NO_BUS);
            return;
        }
        try {
            if (reg(OcAbi.REG_METHOD) != OcAbi.BOOT_METHOD_READ_FILE) {
                fail(OcAbi.ERR_UNKNOWN_METHOD);
                return;
            }
            // 要读哪个文件：NUL 结尾的路径串放在固件缓冲区里（与 FS_OPEN 的入方向缓冲同一形态）
            final int bufAddr = reg(OcAbi.REG_BUF_ADDR);
            final int bufLen = clamp(reg(OcAbi.REG_BUF_LEN), 0, OcAbi.BUF_MAX);
            final String path = bufLen > 0 ? cString(bufAddr, bufLen) : "";
            if (path.isEmpty()) {
                // 没说读哪个文件 = ABI 用错（不是"盘上没有"）⇒ 明确报错，绝不猜一个默认路径
                fail(OcAbi.ERR_BAD_ARGS);
                return;
            }
            // 装到哪：arg0 = guest 物理地址（INT 13h 的 ES:BX 位）
            final long loadAddr = Integer.toUnsignedLong(reg(OcAbi.REG_ARG0));
            final long[] r = svc.readFile(path, loadAddr);
            // 结果区按下标偏移（OcAbi 只定义 REG_RESULT0，第 i 个结果在 +i*4）
            if (r == null || r.length < 2 || r[0] <= 0) {
                // 盘上没有这个文件 / 读不出来：返回 0（bootloader 会打 "no system file" 并停机）
                setReg(OcAbi.REG_RESULT0, 0);
                setReg(OcAbi.REG_RESULT0 + 4, 0);
            } else {
                setReg(OcAbi.REG_RESULT0, (int) r[0]);
                setReg(OcAbi.REG_RESULT0 + 4, (int) r[1]);
            }
            setReg(OcAbi.REG_RESULT_COUNT, 2);
            setReg(OcAbi.REG_ERROR, OcAbi.ERR_NONE);
            setReg(OcAbi.REG_STATUS, OcAbi.STATUS_DONE);
        } catch (Throwable t) {
            fail(OcAbi.ERR_COMPONENT_FAILED);
        }
    }

    /**
     * 从 guest 内存里的缓冲区取一个 NUL 结尾字符串（读盘服务的路径参数用）。
     *
     * <p>只认 ASCII：路径是 ABI 里的**标识符**（与 {@code FS_*} 的路径同一约定），
     * 不是给人看的文本 —— 宿主按同一口径解码，两侧不会对同一个字节有两种读法。</p>
     */
    private String cString(int addr, int max) {
        final byte[] bytes = board.readMemory(Integer.toUnsignedLong(addr), max);
        int n = 0;
        while (n < bytes.length && bytes[n] != 0) {
            n++;
        }
        return new String(bytes, 0, n, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private void fail(int code) {        errorCount++;
        setReg(OcAbi.REG_ERROR, code);
        setReg(OcAbi.REG_RESULT_COUNT, 0);
        setReg(OcAbi.REG_STATUS, OcAbi.STATUS_ERROR);
    }

    /** 中间类型 → ABI 32 位标量（字符串/字节数组二期走缓冲区） */
    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return 0;
    }

    // ==================== 寄存器换算（ABI 用字节偏移，RegBankDevice 用寄存器索引）====================

    private int reg(int byteOffset) {
        return bridge.get(byteOffset / 4);
    }

    private void setReg(int byteOffset, int value) {
        bridge.set(byteOffset / 4, value);
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : Math.min(value, hi);
    }

    // ==================== 访问器 ====================

    public SocBoard board() {
        return board;
    }

    public RegBankDevice bridge() {
        return bridge;
    }

    public ComponentBus bus() {
        return bus;
    }

    /** 换总线（平台在 OC 组件表变化时刷新） */
    public void setBus(ComponentBus value) {
        this.bus = value == null ? ComponentBus.EMPTY : value;
    }

    /** 挂引导服务（Cryptand Boot 用；不挂则引导调用返回"服务不可用"） */
    public void setBootLoader(BootLoaderService value) {
        this.bootLoader = value;
    }

    public BootLoaderService bootLoader() {
        return bootLoader;
    }

    public long totalCycles() {
        return totalCycles;
    }

    public long callCount() {
        return callCount;
    }

    public long errorCount() {
        return errorCount;
    }

    @Override
    public String toString() {
        return "OcArchitectureCore[cycles=" + totalCycles + ", calls=" + callCount
                + ", errors=" + errorCount + (initialized ? ", ready]" : ", booting]");
    }
}
