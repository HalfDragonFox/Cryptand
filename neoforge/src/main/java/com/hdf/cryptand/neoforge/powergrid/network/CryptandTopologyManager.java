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
 *   - 【线程模型（2026-09-11 统一，用户：所有计算为引擎异步计算）】：
 *     拓扑管理器【恒异步】——调度线程（ThreadDispatchers.PHYSICS_HIGH 常驻 Worker，
 *     周期 = cryptandTopologyFrequencyHz）驱动 runBackgroundRound →
 *     PhasorPipeline.roundFromGraph（构建/求解/发热/储能全在后台；自管图构建源
 *     不读 Level）。主线程只做两件事：
 *       1) tick 内拓扑同步（WireGraph 版本感知 + 事件 drain）；
 *       2) PhasorPipeline.processPost：接收后台结果 → 落世界（孤儿清理/销毁/
 *          引擎消息/BE 参数消费；此处不做任何物理计算）。
 *     同步降级模式（主线程每 tick 直接 round）已删除。
 *   - 【线程安全边界】：Minecraft Level 非线程安全——凡读方块/端点（getBlockEntity）
 *     的步骤只在主线程执行（转换层 WireGraphStore.syncFromWorld 与 processPost 的
 *     世界消费）；核心求解（PhasorEngine.solveAll）运行在引擎侧线程池。
 *
 * 拓扑正确性保障（调用链）：
 *   调度线程 runBackgroundRound → PhasorPipeline.roundFromGraph（跨变压器/跨导线
 *     BFS 收集 cluster + 按【物理节点】去重 + 缓存）→ 主线程 processPost 接收结果。
 */

package com.hdf.cryptand.neoforge.powergrid.network;

import com.hdf.cryptand.circuitsimulation.compute.ScheduledHandle;
import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorEngine;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;
import com.hdf.cryptand.neoforge.powergrid.measurement.EngineMeasurements;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
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

    // ===== 异步调度（仅异步模式启动）——2026-08-30 线程统一 =====
    // 不再独立 Cryptand-TopologyMgr 线程，改走分配核心【固定周期 tick】
    //（PHYSICS_HIGH=常驻 Worker 独占直算、最高优先级；绝对时间累加不漂移）。
    private volatile ScheduledHandle schedulerHandle;
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

    /** 拓扑版本：任何拓扑事件（接线/拆线/方块变化）→ +1 → 下一轮惰性重建 DSU。
     *  事件来源 ElectricalNetworkMixin 的 addWire/removeWire/merge hook（主线程），
     *  与主线程 round 串行 → 普通 long 即可；volatile 保证调度线程读一致。
     *  2026-08-12【无感重建】：高频事件（addWire/removeWire）改走【网络级版本】
     *  （netVersions），不再全局 ++ —— 加设备只重建受影响网络，其他网络缓存
     *  命中不重建（不卡顿）。全局 topoVersion 保留给【跨网络结构变化】：
     *  merge（物理连通改变 → DSU 需全量重建）、方块变化兜底、命令/未知来源。 */
    private volatile long topoVersion;

    /** 自管拓扑版本感知（2026-08-13 阶段1）：WireGraph.version 变化 →
     *  topoVersion++（DSU 重建 + 全局失效）。WireGraph 由 WireGraphStore
     *  syncFromWorld 每 tick 增量同步（主线程）——版本变化即拓扑变化。
     *  与 addWire/removeWire hook 互为补充：hook 快（网络级），自管图
     *  兜底（覆盖 hook 漏网/自管源）。 */
    private long lastGraphVersion = -1;

    /** 网络级拓扑版本（2026-08-12 无感重建）：Map<ElectricalNetwork, 版本>。
     *  addWire/removeWire hook 携带网络实例 → 只递增该网络版本 → PhasorPipeline
     *  缓存校验按【网络级版本】失效 → 其他网络缓存命中（零重建、零卡顿）。
     *  ConcurrentHashMap：hook 主线程写，round/调度线程读。 */
    private final java.util.Map<ElectricalNetwork, Long> netVersions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前拓扑版本（任何接线/拆线/merge/方块变化 → +1）。
     *  供 PhasorPipeline 缓存失效判断：拓扑变化 → 强制重解重建 ctx，
     *  杜绝"旧 ctx 不含新接入导线 → 新导线一端 0V → 虚假大电流烧线"。
     *  2026-08-12 起缓存校验改用【网络级版本 netVersionOf(net)】（无感），
     *  此全局版本保留给 DSU 全量重建 + 跨网络结构变化兜底。 */
    public long topoVersion() { return topoVersion; }

    /** 网络当前版本（未记录 = 0）。PhasorPipeline 缓存校验用（网络级失效）：
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
     *  网络级失效也作废该网络测量缓存（EngineMeasurements.invalidate）。
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
        EngineMeasurements.invalidate(net);
        forceRound();
    }

    /**
     * 导线把【两个不同网络】连成一个（合并）—— 精确到这两个网络。
     *
     * 2026-09-13 用户："网络重建需要精确到具体网络，比如导线连接时连到两个不同
     * 网络则发合并，不能全世界重建"。
     *
     * 实现：`markNetworkChanged(a)` + `markNetworkChanged(b)` ——
     *   · 各自 netVer++ ⇒ 只有这两个网络的求解缓存 miss、各自重建；
     *   · 各自递增 topoVersion ⇒ DSU 重新分组（物理连通性确实变了），
     *     这正是"合并"需要的全部语义；
     *   · **不**调 EngineMeasurements.invalidateAll()、**不**动其他网络的
     *     netVer ⇒ 其余网络缓存全部命中，零感知（原实现补了一发
     *     markTopologyChanged() = 全世界重建，已删除）。
     *
     * @param a 导线一端的网络（本网络）
     * @param b 另一端的网络（可能为 null = 新接入、或与 a 相同 = 自环）
     */
    public void postWireMerge(ElectricalNetwork a, ElectricalNetwork b) {
        if (a == null && b == null) return;
        if (a != null) markNetworkChanged(a);
        if (b != null && b != a) markNetworkChanged(b);
        // 互动管理：拆合请求带上网络（传 null 会退化成全局兜底 → 全世界重建）
        MainThreadInteractionManager.get().postTopology(a != null ? a : b, true);
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

    private CryptandTopologyManager() {}

    /** 测试/调试用：直接触发一轮（去重，仅当无待执行信号时入队） */
    public void requestRound() { forceRound(); }
    /**
     * 电路仿真核心是否启用（2026-09-11 用户架构：两个主开关【完全分隔】）。
     * <p>本开关只代表【Cryptand 电路仿真核心】：引擎启动、后台 round、量测、消息池等
     * 核心内容全部由它决定；与「交错电网(PowerGrid)支持」互不依赖。
     * 两个都关闭 → 所有功能不工作（各叶子守卫自行返回）。
     * 构造期 spec 未加载 → 配置预读回退。
     */
    public static boolean simulationEnabled() {
        try {
            if (ConfigCircuit.SPEC.isLoaded()) {
                return ConfigCircuit.ENABLE_CRYPTAND_SIMULATION.get();
            }
            return com.hdf.cryptand.neoforge.core.config.ConfigLoad
                    .preloadBoolean("circuit", "enableCryptandSimulation", true);
        } catch (final Throwable t) {
            return true;
        }
    }
    // ==================== 生命周期 ====================

    /** 服务端启动：异步模式启动调度线程（round 仍在主线程 tick 执行）。 */
    public void start() {
        if (!simulationEnabled()) return; // 总闸关闭 → 不启动调度线程
        if (running) return;
        running = true;
        due = false;
        forced = false;
        // 每次启动（进世界）都重新进入初始化：先全量重建所有网络 + 参数更新
        initPending = true;
        initRounds = 0;
        queue.clear();
        // ⚠ 2026-09-11 线程统一（用户：所有计算为引擎异步计算，主线程仅接收）：
        // 恒启动调度线程——不再有“主线程每 tick 直接 round”的同步降级模式。
        // 调度分配核心【固定周期 tick】：PHYSICS_HIGH = 常驻 Worker 独占直算
        // （物理循环语义，2026-09-05）；回调在统一 Worker 常驻线程执行
        // runBackgroundRound（纯数据，无 level）。
        long intervalUs = intervalNanos() / 1000L;
        schedulerHandle = ThreadDispatchers.schedule(this::schedulerTick,
                TaskMode.PHYSICS_HIGH, 0, Math.max(intervalUs, 1000));
        CryptandNeoForge.WAF_LOGGER.info(
                "[TopoMgr] started mode={} freqHz={} cpus={}",
                "ASYNC",
                Runtime.getRuntime().availableProcessors());
    }

    /** 服务端停止：停调度线程、清状态。 */
    public void stop() {
        if (!running) return;
        running = false;
        queue.offer(Event.SHUTDOWN);
        ScheduledHandle h = schedulerHandle;
        if (h != null) {
            h.cancel();
            // ⚠ 2026-08-30 线程统一：cancel 停止未来触发；正在 Worker 执行的
            // 最后一次 round（最长 10s）会执行完——round 幂等（roundFromGraph
            // 构建时捕获图版本，结果只写构建时存在的点），新旧并发一轮无副作用。
            schedulerHandle = null;
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
        // ⚠ 2026-08-30 总闸（用户"关闭所有支持"）：仿真/求解器全关 → 本 tick 零开销
        // （不懒启动调度线程、不推进仿真时钟、不扫图）——此前关闭后仍每 tick 空转，
        // 造成主线程周期性卡顿。
        if (!simulationEnabled()) return;
        if (!running) start(); // 懒启动（首个世界 tick）
        // 【主线程驱动仿真时钟】（2026-08-20 用户要求）：时间只由主线程每 tick
        // 推进——主线程卡死 → 时间停 → 异步求解线程 dt=0 → 状态不演化（安全）。
        // 节流：温度等慢变量不必每 tick 推进（SimClock.tickThrottle）。
        try {
            PhasorEngine.tickSimClock();
        } catch (Throwable ignored) {
        }
        // 心跳诊断（5s 节流）：卡住时日志显示最后 tick 活动点（定位卡点）
        try {
            tickDbgCount++;
            long now = System.currentTimeMillis();
            if (now - tickDbgLast >= 5000) {
                tickDbgLast = now;
                WireNetworkManager mgrDbg =
                        WireNetworkManager.get();
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
            long gv = WireNetworkManager.get().version();
            if (lastGraphVersion >= 0 && gv != lastGraphVersion) {
                topoVersion++; // DSU 重建：物理连通性已变（连接合并/移除分裂）
                forceRound();
            }
            lastGraphVersion = gv;
        } catch (Throwable ignored) {
        }
        // ===== 主线程【不再执行任何求解】（2026-09-11 用户要求：全部计算由
        // 引擎侧异步完成，主线程只接收结果 + 与世界同步）=====
        // round 一律由调度线程 runBackgroundRound 驱动（start() 恒启动调度线程，
        // 见下）；主线程本 tick 只做拓扑同步（上方）、事件 drain 与后处理
        // （下方 processPost）。同步降级模式（每 tick 主线程直接 round）已移除。
        // 销毁 + 引擎消息（全部需要 level/BE，只能主线程）
        try {
            PhasorPipeline.processPost(lv);
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


    /**
     * 调度线程：按 cryptandTopologyFrequencyHz 心跳。职责：
     *   - 消费队列事件（FORCE_ROUND/RESET/SHUTDOWN）
     *   - 到达频率间隔 → 置 due=true（实际 round 由主线程下一 tick 执行）
     * 绝不直接触碰 Level / PhasorPipeline（线程安全边界）。
     */
    /** 分配核心固定周期 tick（替代原独立调度线程；PHYSICS_HIGH 常驻 Worker 直算） */
    private void schedulerTick() {
        if (!running) return;
        try {
            boolean force = false;
            Event e;
            // 非阻塞 drain 事件（schedule 已按周期触发，无需 poll 等待）
            while ((e = queue.poll()) != null) {
                switch (e) {
                    case FORCE_ROUND -> { force = true; lastForcedMs = System.currentTimeMillis(); }
                    case RESET -> { lastRoundMs = 0; force = true; }
                    case SHUTDOWN -> { running = false; return; }
                }
            }
            // 2026-08-15 后台调度脱离：心跳（100Hz）或强制事件 → 直接后台执行 round
            if (force || System.currentTimeMillis() - lastRoundMs >= intervalMs()) {
                lastRoundMs = System.currentTimeMillis();
                runBackgroundRound();
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.error("[TopoMgr] scheduler tick error", t);
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
            if (PowerGridWireConverter.isEnabled()
                    && WireNetworkManager.get()
                            .nodeCount() > 0) {
                PhasorPipeline.roundFromGraph(lv, initPending);
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
     *  PhasorPipeline 缓存由 topoVersion/worldWireSig 校验自动失效。
     *  2026-08-12 起【跨网络结构变化/兜底】才用（merge/方块变化/命令/未知来源）：
     *  高频接线事件改走 postWireConnect(net) 网络级失效（无感）。 */
    public void markTopologyChanged() {
        topoVersion++;
        EngineMeasurements.invalidateAll();
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

    // ==================== 配置 ====================

    public static double getFrequencyHz() {
        try {
            return ConfigCircuit.CRYPTAND_TOPOLOGY_FREQUENCY_HZ.get();
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
