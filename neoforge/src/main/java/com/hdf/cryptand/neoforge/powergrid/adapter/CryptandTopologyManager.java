/**
 * ===== Cryptand 网络拓扑管理器（2026-08-11） =====
 *
 * 完全接管 PowerGrid 网络的【求解调度】——原版 WorldNetworks 的多线程时域
 * 求解循环（background compute thread + singleTick 并行）在
 * enableCryptandSolver=true 时已被禁用；本管理器是 Cryptand 相量求解的
 * 统一驱动入口（WorldNetworksMixin.cryptand$doComputeRound → 本管理器）。
 *
 * 设计目标（参考原版 WorldNetworks 的调度 + SPICE 的“事件驱动重算”思想）：
 *   - 【消息驱动】：接线/拆线/方块变化 → postWireConnect()/postBlockChange()
 *     入队事件 → 下一轮立即强制重算（不是盲目每 tick 全量）。
 *   - 【主/异步双模式】（config 切换，重启生效）：
 *       · enableCryptandTopologyAsync=false（默认）：主线程每 tick 同步
 *         求解 + 写回。确定性最强，与方块 tick 严格同步。
 *       · true：独立调度线程按 cryptandTopologyFrequencyHz 控制求解节奏；
 *         消息事件可即时唤醒强制求解。
 *   - 【线程安全边界】：Minecraft Level 非线程安全——构建相量网络
 *     （buildContextFromNetwork，读方块/端点/connections）与电压写回
 *     （setValue）必须在主线程。因此异步线程负责【节流调度 + 事件
 *     时钟 + 心跳】，实际 round 仍在主线程 tick 内执行；核心求解
 *     （PhasorEngine.solve）本身运行在 PhasorEngine 的多线程求解器
 *     （11 线程）上——即“外部多线程调度接口”已经由 PhasorEngine 暴露，
 *     本管理器负责调用它并控制整体节奏。
 *
 * 拓扑正确性保障（调用链）：
 *   tick → round → PhasorWriteback.round（内部已实现：
 *     跨变压器/跨导线 BFS 收集 cluster + 按【物理节点】去重 + 缓存）。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class CryptandTopologyManager {

    private static final CryptandTopologyManager INSTANCE = new CryptandTopologyManager();
    public static CryptandTopologyManager get() { return INSTANCE; }

    /** 拓扑事件（消息队列）：主线程产生，调度/节流消费 */
    private enum Event {
        /** 拓扑变化（接线/拆线/方块变化）→ 强制下一轮立即重算 */
        FORCE_ROUND,
        /** 清除节流节拍（如区块重载后） */
        RESET,
        /** 关服 */
        SHUTDOWN
    }

    private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>();

    // ===== 异步调度线程（仅异步模式启动） =====
    private volatile Thread schedulerThread;
    private volatile boolean running;

    // ===== 主线程每 tick 传入的世界状态（volatile，供调度线程读） =====
    private volatile Level level;
    private volatile List<ElectricalNetwork> networks;

    /** 当前活动 Level（tick 时刷新；NetOp 求解/后台任务用，不依赖 CRYPTAND_LAST_LEVEL） */
    public Level getLevel() { return level; }

    // ===== 节流状态 =====
    private volatile boolean due;    // 异步：到达频率间隔，下一 tick 可执行一轮
    private volatile boolean forced; // 事件触发，下一 tick 立即执行一轮
    private volatile long lastRoundMs;
    private volatile long lastForcedMs;

    // ===== 进入世界初始化（2026-08-12 用户要求） =====
    // 进世界后先对所有网络【全量重建 + 参数更新】（forceInit，忽略缓存/稳定
    // 检测，不求解写回），INIT_ROUNDS 轮完成后才进入正常（稳定检测 + 求解）。
    private volatile boolean initPending = true;
    private int initRounds;
    private static final int INIT_ROUNDS = 6;

    // ===== tick 心跳诊断（2026-08-14 世界进入卡住定位：卡住时日志显示最后活动） =====
    private long tickDbgLast;
    private long tickDbgCount;

    // ===== DSU 物理连通图（2026-08-11，自建拓扑分组） =====
    // 持久并查集：OwnedFloatingNode（稳定对象，endpoint 固定、网络分裂不换）
    // → 分量 id。物理连通 = 导线两端（网络 wires + endpoint.connections 双源）。
    // 变压器 4 端子在求解时由 buildContextFromNetwork 的 BFS 合并，此处不 union
    // （不污染物理分组）。
    private final java.util.Map<OwnedFloatingNode, Integer> nodeComp = new java.util.HashMap<>();
    private int[] dsuParent = new int[0];
    private int[] dsuSize = new int[0];
    /** 拓扑版本：任何拓扑事件（接线/拆线/方块变化）→ +1 → 下一轮惰性重建 DSU。
     *  事件来源 ElectricalNetworkMixin 的 addWire/removeWire/merge hook（主线程），
     *  与主线程 round 串行 → 普通 long 即可；volatile 保证调度线程读一致。
     *  2026-08-12【无感重建】：高频事件（addWire/removeWire）改走【网络级版本】
     *  （netVersions），不再全局 ++ —— 加设备只重建受影响网络，其他网络缓存
     *  命中不重建（不卡顿）。全局 topoVersion 保留给【跨网络结构变化】：
     *  merge（物理连通改变 → DSU 需全量重建）、方块变化兜底、命令/未知来源。 */
    private volatile long topoVersion;
    private long lastBuiltVersion = -1;
    private boolean dsuValid;

    /** 自管拓扑版本感知（2026-08-13 阶段1）：WireGraph.version 变化 →
     *  topoVersion++（DSU 重建 + 全局失效）。WireGraph 由 WireGraphStore
     *  syncFromWorld 每 tick 增量同步（主线程）——版本变化即拓扑变化。
     *  与 addWire/removeWire hook 互为补充：hook 快（网络级），自管图
     *  兜底（覆盖 hook 漏网/自管源）。 */
    private long lastGraphVersion = -1;

    /** 网络级拓扑版本（2026-08-12 无感重建）：Map<ElectricalNetwork, 版本>。
     *  addWire/removeWire hook 携带网络实例 → 只递增该网络版本 → PhasorWriteback
     *  缓存校验按【网络级版本】失效 → 其他网络缓存命中（零重建、零卡顿）。
     *  ConcurrentHashMap：hook 主线程写，round/调度线程读。 */
    private final java.util.Map<ElectricalNetwork, Long> netVersions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前拓扑版本（任何接线/拆线/merge/方块变化 → +1）。
     *  供 PhasorWriteback 缓存失效判断：拓扑变化 → 强制重解重建 ctx，
     *  杜绝"旧 ctx 不含新接入导线 → 新导线一端 0V → 虚假大电流烧线"。
     *  2026-08-12 起缓存校验改用【网络级版本 netVersionOf(net)】（无感），
     *  此全局版本保留给 DSU 全量重建 + 跨网络结构变化兜底。 */
    public long topoVersion() { return topoVersion; }

    /** 网络当前版本（未记录 = 0）。PhasorWriteback 缓存校验用（网络级失效）：
     *  该网络 addWire/removeWire → 版本++ → 缓存 miss 重建；其他网络不变 → 命中。 */
    public long netVersionOf(ElectricalNetwork net) {
        if (net == null) return 0;
        Long v = netVersions.get(net);
        return v == null ? 0 : v;
    }

    /**
     * 网络级拓扑失效（2026-08-12）：只标记该网络版本（+1），其他网络缓存
     *  命中不重建（无感）。不递增全局 topoVersion → DSU 不重建（加导线不改变
     *  物理连通；新节点由 groupComponents 的 UNKNOWN_COMP 兜底，绝不丢网络）。
     *  网络级失效也作废该网络测量缓存（PhasorEngine.invalidate）。
     *  <p>2026-08-13 用户方案【连接合并 / 移除分裂】：addWire/removeWire 改变
     *  【物理连通性】——接线把两个网络连成一个（应合并为一个分量求解）、拆线
     *  把一个网络分成两个（应分裂为两个独立分量，各自全新求解）。只做网络级
     *  失效（netVersions++）不够：DSU 物理分组不重建 → 分裂后的两个网络仍被
     *  DSU 归为同一分量 → 每 tick 反复构建/稳定检测失败 → 清零 → 设备停转
     *  （[Unstable] 节点数 15↔16 抖动实锤）。故【也递增全局 topoVersion】触发
     *  DSU 重建：连接 → 两个网络 union 进同一分量；移除 → 分裂成两个分量。
     *  但不 invalidateAll（只作废受影响网络缓存，其他网络缓存命中 = 无感）。
     */
    public void markNetworkChanged(ElectricalNetwork net) {
        if (net == null) { markTopologyChanged(); return; }
        topoVersion++; // DSU 重建：物理连通性可能已变（连接合并/移除分裂）
        netVersions.merge(net, 1L, Long::sum);
        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine.invalidate(net);
        forceRound();
    }

    /** ⚠ 2026-08-24 只保留最新：同一时刻只允许一个待执行的 FORCE_ROUND。
     *  round 执行时网络状态已是最新，重复信号只会造成堆积/重复触发。 */
    private void forceRound() {
        if (!queue.contains(Event.FORCE_ROUND)) {
            queue.offer(Event.FORCE_ROUND);
        }
    }

    // ===== 诊断 =====
    private long rounds;
    private long lastDbgMs;

    private CryptandTopologyManager() {}

    /** 测试/调试用：直接触发一轮（去重，仅当无待执行信号时入队） */
    public void requestRound() { forceRound(); }

    // ==================== 生命周期 ====================

    /** 服务端启动：异步模式启动调度线程（round 仍在主线程 tick 执行）。 */
    public void start() {
        if (running) return;
        running = true;
        due = false;
        forced = false;
        // 每次启动（进世界）都重新进入初始化：先全量重建所有网络 + 参数更新
        initPending = true;
        initRounds = 0;
        queue.clear();
        if (isAsyncEnabled()) {
            schedulerThread = new Thread(this::schedulerLoop, "Cryptand-TopologyMgr");
            schedulerThread.setDaemon(true);
            schedulerThread.start();
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[TopoMgr] started mode={} freqHz={} cpus={}",
                isAsyncEnabled() ? "ASYNC" : "SYNC",
                String.format("%.1f", getFrequencyHz()),
                Runtime.getRuntime().availableProcessors());
    }

    /** 服务端停止：停调度线程、清状态。 */
    public void stop() {
        if (!running) return;
        running = false;
        queue.offer(Event.SHUTDOWN);
        Thread t = schedulerThread;
        if (t != null) {
            t.interrupt();
            schedulerThread = null;
        }
        level = null;
        networks = null;
        // 2026-08-16 网表相关操作类：世界切换/关闭 → 清空网络操作记录表（释放所有网络锁）
        try {
            MainThreadInteractionManager.get().clearRecords();
        } catch (Throwable ignored) {
        }
        CryptandNeoForge.WAF_LOGGER.info("[TopoMgr] stopped");
    }

    // ==================== 主线程入口（WorldNetworksMixin 每 tick 调用） ====================

    /**
     * 每 tick 主线程调用。完成：
     *   1) 刷新 level/networks 快照
     *   2) 消费消息队列事件（FORCE_ROUND → forced=true）
     *   3) 决定本轮是否执行求解：
     *        - 主线程模式：每 tick 都执行
     *        - 异步模式：仅当 forced（事件）或 due（频率节拍到）时执行
     */
    public void tick(Level lv, List<ElectricalNetwork> nets) {
        if (!running) start(); // 懒启动（首个世界 tick）
        // 【主线程驱动仿真时钟】（2026-08-20 用户要求）：时间只由主线程每 tick
        // 推进——主线程卡死 → 时间停 → 异步求解线程 dt=0 → 状态不演化（安全）。
        // 节流：温度等慢变量不必每 tick 推进（SimClock.tickThrottle）。
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine.tickSimClock();
        } catch (Throwable ignored) {
        }
        // 心跳诊断（5s 节流）：卡住时日志显示最后 tick 活动点（定位卡点）
        try {
            tickDbgCount++;
            long now = System.currentTimeMillis();
            if (now - tickDbgLast >= 5000) {
                tickDbgLast = now;
                com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager mgrDbg =
                        com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get();
                CryptandNeoForge.WAF_LOGGER.info(
                        "[TopoMgr] tick alive ticks={} nets={} nodes={} edges={} init={}",
                        tickDbgCount, nets == null ? -1 : nets.size(),
                        mgrDbg.nodeCount(), mgrDbg.edgeCount(), initPending);
            }
        } catch (Throwable ignored) {
        }
        this.level = lv;
        this.networks = nets;
        // 自管拓扑版本感知（2026-08-13 阶段1）：WireGraph 变化 → 全局失效
        // （DSU 重建 + FORCE_ROUND）。主线程执行（WireGraphStore.syncFromWorld
        // 已在本 tick 早于本方法完成同步）。
        try {
            long gv = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().version();
            if (lastGraphVersion >= 0 && gv != lastGraphVersion) {
                topoVersion++; // DSU 重建：物理连通性已变（连接合并/移除分裂）
                forceRound();
            }
            lastGraphVersion = gv;
        } catch (Throwable ignored) {
        }
        drainEvents();
        // 进入世界初始化（仅主线程模式；异步模式由调度线程 runBackgroundRound 处理）
        if (!isAsyncEnabled() && initPending) {
            round(lv, nets, true);
            if (++initRounds >= INIT_ROUNDS) {
                initPending = false;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[Init] world init done ({} rounds) → normal mode", initRounds);
            }
        }
        // 2026-08-15 后台调度脱离：异步模式 round 由调度线程直接执行（100Hz 后台），
        // 主线程不再执行 round——只做同步（preTick 已做）+ 后处理。
        // 主线程模式：round 仍主线程执行（安全兜底）。
        if (!isAsyncEnabled()) {
            round(lv, nets);
        }
        // 主线程后处理：消费后台/主线程 round 的发热/储能结果 + 孤儿清理 +
        // 销毁 + 引擎消息（全部需要 level/BE，只能主线程）
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.processPost(lv);
        } catch (Throwable ignored) {
        }
    }

    /** 消费队列事件（主线程；FORCE_ROUND 由调度线程 schedulerLoop poll 到即执行） */
    private void drainEvents() {
        Event e;
        while ((e = queue.poll()) != null) {
            switch (e) {
                case FORCE_ROUND -> { /* 调度线程收到即执行；主线程模式每 tick 都 round */ }
                case RESET -> lastRoundMs = 0;
                case SHUTDOWN -> { /* stop() 已处理 */ }
            }
        }
    }

    /** 执行一轮求解 + 写回（【主线程】执行，见类头注释：Level 非线程安全，构建
     *  读方块/端点与电压写回必须在主线程；PhasorEngine.solve 内部多线程求解。
     *  2026-08-12 曾把 round 移到后台线程 → getBlockEntity 竞态 null → 设备被
     *  跳过（[NullBe]）→ 电机不转 → 已回退主线程。构建期间拓扑变化 →
     *  PhasorWriteback 版本校验丢弃重建）。
     *  按自建 DSU 物理连通图分组：每分量只交给 PhasorWriteback.round 一次
     *  （内部再跨变压器 BFS 收集 + 按物理节点去重 + 缓存）。
     *  拓扑版本没变 → 复用现有 DSU 分组（不重建）；变了 → 惰性重建。 */
    private void round(Level lv, List<ElectricalNetwork> ns) {
        round(lv, ns, false);
    }

    /** 执行一轮（forceInit=true：进入世界初始化——全量重建+参数更新，不求解写回） */
    private void round(Level lv, List<ElectricalNetwork> ns, boolean forceInit) {
        if (lv == null || ns == null || ns.isEmpty()) return;
        long t0 = System.nanoTime();
        try {
            // ===== 自管图完整闭环（2026-08-13 用户决策） =====
            // 转换启用 + 自管图非空 → 主 round 由自管 WireGraph 分量驱动
            // （roundFromGraph：WireGraph 构建源 + 自管宿主写回，不写原版节点）。
            // 原版内容全部通过转换层转为自管内容（导线→WireGraph 边、端子→
            // TerminalRegistry、电压→DEVICE_TERMINAL_V）。原版网络仅作转换源。
            // 自管图空（转换关闭/无导线）→ 回退原版网络驱动（安全兜底）。
            if (com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter.isEnabled()
                    && com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().nodeCount() > 0) {
                com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.roundFromGraph(lv, forceInit);
                rounds++;
                if (isAsyncEnabled()) lastRoundMs = System.currentTimeMillis();
                return;
            }
            long ver = topoVersion;
            if (!dsuValid || ver != lastBuiltVersion) {
                rebuildDsu(lv, ns);
                lastBuiltVersion = ver;
            }
            // 每 tick 清空频率传递表，再在 round 内重新填充（防旧网络残留频率）
            MultimeterDebug.clearNetworkFrequencies();
            int solved = 0;
            List<List<ElectricalNetwork>> comps = groupComponents(ns);
            // 诊断（节流）：分量数 + 各分量网络数 + 各分量含哪些变压器 —— 定位
            // "变压器电路被分裂成多个分量 → 每分量独立 round → 空载/带载交替"
            // （[TfDiag] 同 tick 多条实锤）。DSU union 后应一个分量。
            long cNow = System.currentTimeMillis();
            if (cNow - lastDbgMs >= 5000) {
                StringBuilder csb = new StringBuilder();
                csb.append("comps=").append(comps.size()).append(" [");
                for (List<ElectricalNetwork> c : comps) {
                    java.util.Set<net.minecraft.core.BlockPos> tfs = new java.util.HashSet<>();
                    for (ElectricalNetwork en : c) {
                        for (INode in2 : en.getNodes()) {
                            if (in2 instanceof OwnedFloatingNode ofn2
                                    && ofn2.endpoint instanceof BlockWireEndpoint b2) {
                                try {
                                    net.minecraft.world.level.block.entity.BlockEntity be2 =
                                            lv.getBlockEntity(b2.getPos());
                                    if (be2 instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
                                        tfs.add(b2.getPos());
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                    csb.append("[").append(c.size()).append("nets tf=").append(tfs).append("] ");
                }
                csb.append("]");
                CryptandNeoForge.WAF_LOGGER.info("[TopoDbg] {}", csb);
            }
            for (List<ElectricalNetwork> comp : comps) {
                solved += PhasorWriteback.round(lv, comp, forceInit);
            }
            rounds++;
            if (isAsyncEnabled()) lastRoundMs = System.currentTimeMillis();
            dbg(System.nanoTime() - t0, solved);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.error("[TopoMgr] round crashed", t);
        }
    }

    private void dbg(long nanos, int solved) {
        long now = System.currentTimeMillis();
        if (now - lastDbgMs >= 5000) {
            lastDbgMs = now;
            int records = 0;
            try {
                records = MainThreadInteractionManager.get().recordSize();
            } catch (Throwable ignored) {
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[TopoMgr] rounds={} last={}ms solved={} mode={} netop={} records={}",
                    rounds, nanos / 1_000_000L, solved,
                    isAsyncEnabled() ? "ASYNC" : "SYNC",
                    MainThreadInteractionManager.enabled(), records);
        }
    }

    // ==================== 异步调度线程（仅异步模式） ====================

    /**
     * 调度线程：按 cryptandTopologyFrequencyHz 心跳。职责：
     *   - 消费队列事件（FORCE_ROUND/RESET/SHUTDOWN）
     *   - 到达频率间隔 → 置 due=true（实际 round 由主线程下一 tick 执行）
     * 绝不直接触碰 Level / PhasorWriteback（线程安全边界）。
     */
    private void schedulerLoop() {
        long interval = intervalNanos();
        while (running) {
            try {
                Event e = queue.poll(interval, TimeUnit.NANOSECONDS);
                if (!running) break;
                boolean force = false;
                if (e != null) {
                    switch (e) {
                        case FORCE_ROUND -> { force = true; lastForcedMs = System.currentTimeMillis(); }
                        case RESET -> { lastRoundMs = 0; force = true; }
                        case SHUTDOWN -> { return; }
                    }
                }
                // 2026-08-15 后台调度脱离：心跳（100Hz）或强制事件 → 直接后台执行
                // round（不再置 due 等主线程 tick 消费）
                if (force || System.currentTimeMillis() - lastRoundMs >= intervalMs()) {
                    lastRoundMs = System.currentTimeMillis();
                    runBackgroundRound();
                }
            } catch (InterruptedException ie) {
                if (!running) break;
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.error("[TopoMgr] scheduler error", t);
            }
        }
    }

    /**
     * 后台 round（异步模式，调度线程直接执行，2026-08-15 脱离主线程 tick）：
     * 只驱动自管模式（roundFromGraph——构建/求解/写回测试点均无 level，线程
     * 安全）；原版模式（buildContextFromNetwork 有 level 依赖）保持主线程。
     * 进入世界初始化（initPending）由后台处理。
     * <p>2026-08-16 网表相关操作类接入：启用时网络求解改经【主线程交互管理类 →
     * 异步交互管理类（记录表+消息缓冲+整合）→ 分配器 → 网表相关操作类（线程
     * C）】执行——多条求解请求整合为一条 roundFromGraph；关闭时回退直接调用。
     */
    private void runBackgroundRound() {
        try {
            if (MainThreadInteractionManager.enabled()) {
                // 网络操作记录表驱动：投递 SOLVE 请求（整合为一条求解）
                MainThreadInteractionManager.get().postSolve(initPending);
                if (initPending && ++initRounds >= INIT_ROUNDS) {
                    initPending = false;
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[Init] world init done ({} rounds) → normal mode", initRounds);
                }
                rounds++;
                return;
            }
            Level lv = level;
            if (lv == null) return;
            if (com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter.isEnabled()
                    && com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                            .nodeCount() > 0) {
                com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.roundFromGraph(lv, initPending);
                if (initPending && ++initRounds >= INIT_ROUNDS) {
                    initPending = false;
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[Init] world init done ({} rounds) → normal mode", initRounds);
                }
                rounds++;
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.error("[TopoMgr] bg round crashed", t);
        }
    }

    // ==================== 事件接口（供导线/方块拓扑 hook 调用，主线程） ====================

    /** 统一拓扑重建入口：任何电路变化（接线/拆线/方块添加移除/元件变化）调用。
     *  递增拓扑版本（缓存校验用）+ 清空 PhasorEngine 求解缓存 + 强制下一轮立即重算。
     *  PhasorWriteback 缓存由 topoVersion/worldWireSig 校验自动失效。
     *  2026-08-12 起【跨网络结构变化/兜底】才用（merge/方块变化/命令/未知来源）：
     *  高频接线事件改走 postWireConnect(net) 网络级失效（无感）。 */
    public void markTopologyChanged() {
        topoVersion++;
        PhasorEngine.invalidateAll();
        forceRound();
    }

    /** 导线接入（玩家接线/方块放置）→ 统一拓扑重建（无网络定位 → 全局兜底）。 */
    public void postWireConnect() {
        markTopologyChanged();
        // 2026-08-16 网表相关操作类：拆合(合并) + 重建请求 → 异步交互管理类
        MainThreadInteractionManager.get().postTopology(null, true);
    }

    /** 导线接入（带网络，2026-08-12 无感重建）：网络级失效——加设备/接线
     *  只重建该网络，其他网络缓存命中不重建（不卡顿）。null → 全局兜底。 */
    public void postWireConnect(ElectricalNetwork net) {
        if (net != null) markNetworkChanged(net);
        else markTopologyChanged();
        // 2026-08-16 网表相关操作类：拆合(合并) + 重建请求
        MainThreadInteractionManager.get().postTopology(net, true);
    }

    /** 导线移除 → 统一拓扑重建（无网络定位 → 全局兜底）。 */
    public void postWireDisconnect() {
        markTopologyChanged();
        // 2026-08-16 网表相关操作类：拆合(分裂) + 重建请求
        MainThreadInteractionManager.get().postTopology(null, true);
    }

    /** 导线移除（带网络，2026-08-12）：网络级失效。null → 全局兜底。 */
    public void postWireDisconnect(ElectricalNetwork net) {
        if (net != null) markNetworkChanged(net);
        else markTopologyChanged();
        // 2026-08-16 网表相关操作类：拆合(分裂) + 重建请求
        MainThreadInteractionManager.get().postTopology(net, true);
    }

    /** 方块变化（放置/移除/状态改变影响电气连接）→ 统一拓扑重建 */
    public void postBlockChange() {
        markTopologyChanged();
        // 2026-08-16 网表相关操作类：拆合 + 重建请求
        MainThreadInteractionManager.get().postTopology(null, true);
    }

    /** 强制立即执行一轮（测试/命令用；主线程调用） */
    public void requestRoundNow() {
        forced = true;
    }

    // ==================== DSU 物理连通图（自建拓扑分组） ====================

    /** 全量重建 DSU：收集节点 + 双源 union（网络 wires + endpoint.connections）。 */
    private void rebuildDsu(Level lv, List<ElectricalNetwork> nets) {
        nodeComp.clear();
        int idx = 0;
        for (ElectricalNetwork net : nets) {
            for (INode n : net.getNodes()) {
                if (n instanceof OwnedFloatingNode ofn && !nodeComp.containsKey(ofn)) {
                    nodeComp.put(ofn, idx++);
                }
            }
        }
        dsuParent = new int[idx];
        dsuSize = new int[idx];
        java.util.Arrays.fill(dsuParent, -1);
        // union 源1：网络 wires（TransmissionLine node1/node2 —— 网络级稳定节点）
        for (ElectricalNetwork net : nets) {
            for (AbstractElectricWire w : PhasorNetworkBuilder.getNetworkWires(net)) {
                if (w instanceof TransmissionLine tl) {
                    unionNode(resolveNode(tl.getNode1()), resolveNode(tl.getNode2()));
                }
            }
        }
        // union 源2：节点 endpoint.connections 反查导线（PowerGrid 权威连接记录）
        // ⚠ 必须【只取当前端子 ofn 对应的导线集合】。connections 是 Map<端子, 导线集合>，
        // 遍历 conns.values() 会把【同一方块所有端子】的导线都 union 到当前端子 →
        // 同一方块端子错误短路（电流表端子0≡端子1，通过彼此的导线）/跨网络误连 →
        // DSU 物理分组错误 → groupComponents 错分组 → PhasorWriteback.round 的
        // allWritten 误跳过 → 漏写回 → 电压表/电流表端子保持旧电压 → 巨大电流。
        // 2026-08-11 修复：按位置匹配（pos#term）只取当前端子对应的导线。
        for (OwnedFloatingNode ofn : nodeComp.keySet()) {
            if (!(ofn.endpoint instanceof BlockWireEndpoint bep)) continue;
            try {
                ElectricBehaviour beh = bep.getElectricBehaviour(lv);
                if (beh == null) continue;
                java.util.Map<?, ?> conns = beh.getConnections();
                if (conns == null) continue;
                for (java.util.Map.Entry<?, ?> en : conns.entrySet()) {
                    Object k = en.getKey();
                    if (!(k instanceof BlockWireEndpoint kb)) continue;
                    if (!kb.getPos().equals(bep.getPos()) || kb.getTerminal() != bep.getTerminal()) {
                        continue;
                    }
                    Object v = en.getValue();
                    if (!(v instanceof Set<?> set)) continue;
                    for (Object o : set) {
                        if (o instanceof AbstractElectricWire aw) {
                            unionNode(resolveNode(aw.getNode1()), ofn);
                            unionNode(resolveNode(aw.getNode2()), ofn);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // union 源3：变压器 4 端子（原边/副边归入同一物理分量，2026-08-12）。
        // ⚠ 之前故意不 union（"BFS 会跨变压器收集"）→ 但原边/副边各自成组 →
        //   每 tick 多个分量独立 round → 各自跨变压器 BFS 建 ctx（收集集合不同
        //   → 有的含副边回路【带载】、有的只含端子【空载】）→ 空载/带载交替
        //   写回 → 副边电机"有回路但不转"（[TfDiag] 同 tick A/A/B 实锤）。
        //   union 后整个变压器电路一个分量 → 每 tick 一次 round → 一个 ctx
        //   （BFS 完整收集原边+副边+负载）→ 结果一致、电压/电流稳定。
        // ⚠ 扩展到【所有设备端子】（2026-08-12 用户要求：灯座/电闸等内部元件
        //   也是电路通路）：开关（SwitchedWire 被 ElectricalNetworkMixin 过滤、
        //   断开时移除内部 wire）→ DSU 只按导线 union → 开关两端分到不同分量 →
        //   电路分裂 → 每分量独立 round → 空载/带载交替 → 灯座/电闸电压不稳定。
        //   同一设备方块的端子物理上属于同一电路（buildCircuit 内部 wire/coil
        //   连接），全部 union → 设备及其连接电路一个分量，一次求解结果一致。
        for (OwnedFloatingNode ofn : nodeComp.keySet()) {
            if (!(ofn.endpoint instanceof BlockWireEndpoint bep)) continue;
            try {
                net.minecraft.world.level.block.entity.BlockEntity be = lv.getBlockEntity(bep.getPos());
                if (be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity ebe) {
                    ElectricBehaviour beh = ebe.getElectricBehaviour();
                    if (beh == null) continue;
                    OwnedFloatingNode first = null;
                    for (int t = 0; t < 4; t++) {
                        OwnedFloatingNode tn = beh.getTerminal(t);
                        if (tn == null || !nodeComp.containsKey(tn)) continue;
                        if (first == null) first = tn;
                        else unionNode(first, tn);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        dsuValid = true;
    }

    private OwnedFloatingNode resolveNode(org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd) {
        if (nd instanceof OwnedFloatingNode ofn && nodeComp.containsKey(ofn)) return ofn;
        return null;
    }

    private void unionNode(OwnedFloatingNode a, OwnedFloatingNode b) {
        if (a == null || b == null || a == b) return;
        union(nodeComp.get(a), nodeComp.get(b));
    }

    private int find(int x) {
        while (dsuParent[x] >= 0) {
            if (dsuParent[dsuParent[x]] >= 0) dsuParent[x] = dsuParent[dsuParent[x]]; // 路径压缩
            x = dsuParent[x];
        }
        return x;
    }

    private void union(int a, int b) {
        if (a < 0 || b < 0 || a >= dsuParent.length || b >= dsuParent.length) return;
        int ra = find(a), rb = find(b);
        if (ra == rb) return;
        if (dsuSize[ra] < dsuSize[rb]) { int t = ra; ra = rb; rb = t; }
        dsuParent[rb] = ra;
        dsuSize[ra] += dsuSize[rb];
    }

    /** 未知分量 key：网络节点不在 DSU 时单独成组（绝不丢弃）。 */
    private static final Integer UNKNOWN_COMP = Integer.MIN_VALUE;

    /** 按 DSU 分量把网络分组（分量 → 网络列表），每分量求解一次。
     *  <p>2026-08-11 修复：网络节点不在 DSU（DSU 过期/merge 残留网络未收集）时
     *  【不再丢弃】——否则该网络不传给 PhasorWriteback.round → 端子永不写回 →
     *  保持旧电压 → 与写回侧巨大电压差 → 虚假大电流。改为单独成组（UNKNOWN_COMP），
     *  round 内部仍会为每个网络独立 buildPending 求解（不连通网络各自处理）。
     *  <p>2026-08-13 用户方案【连接合并 / 移除分裂】：导线接入 → 两个网络物理
     *  相连（合并为一个分量求解）；导线移除 → 一个网络物理断开（分裂为多个
     *  分量，各自作为【全新网络】求解）。剪线后 PowerGrid 可能【对象未分裂】
     *  （一个 ElectricalNetwork 对象仍含断开的两半节点）→ 只取第一个节点分量
     *  会把整对象归入一半 → 另一半节点永不 round → 残留旧电压 → 不稳定
     *  （[Unstable] 节点数 15↔16 抖动实锤）。故一个网络对象跨多个 DSU 分量时
     *  【加入每个涉及的分量】——每半各自独立 round（各自全新求解/写回），
     *  由 PhasorWriteback 内部按物理节点去重保证不重复写。 */
    private List<List<ElectricalNetwork>> groupComponents(List<ElectricalNetwork> nets) {
        java.util.Map<Integer, List<ElectricalNetwork>> byComp = new java.util.LinkedHashMap<>();
        for (ElectricalNetwork net : nets) {
            java.util.Set<Integer> comps = new java.util.LinkedHashSet<>();
            for (INode n : net.getNodes()) {
                if (n instanceof OwnedFloatingNode ofn) {
                    Integer id = nodeComp.get(ofn);
                    if (id != null) comps.add(find(id));
                }
            }
            if (comps.isEmpty()) comps.add(UNKNOWN_COMP); // 节点不在 DSU → 单独成组
            for (Integer c : comps) {
                byComp.computeIfAbsent(c, k -> new java.util.ArrayList<>()).add(net);
            }
        }
        return new java.util.ArrayList<>(byComp.values());
    }

    // ==================== 配置 ====================

    public static boolean isAsyncEnabled() {
        try {
            return ConfigLoad.ENABLE_CRYPTAND_TOPOLOGY_ASYNC.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static double getFrequencyHz() {
        try {
            return ConfigLoad.CRYPTAND_TOPOLOGY_FREQUENCY_HZ.get();
        } catch (Throwable t) {
            return 20.0;
        }
    }

    private static long intervalNanos() {
        return (long) (1_000_000_000.0 / getFrequencyHz());
    }

    private static long intervalMs() {
        return Math.max(1, (long) (1000.0 / getFrequencyHz()));
    }
}
