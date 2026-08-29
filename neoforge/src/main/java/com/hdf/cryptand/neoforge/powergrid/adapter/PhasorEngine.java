/**
 * ===== 相量求解引擎接入层 =====
 *
 * 把 Cryptand 引擎（common 模块：Network/ComplexMnaSolver/ComputeScheduler）
 * 接到游戏世界：从种子方块出发 → 收集网络导线 → 构建相量 Network →
 * 多线程调度求解（LocalComputeEngine 线程池）→ 返回 SolveResult（含相量）。
 *
 * 线程安全：构建/求解全在纯数据上（NetworkSnapshot），不触碰 PowerGrid 对象。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.compute.ComputeResult;
import com.hdf.cryptand.circuitsimulation.compute.ComputeEngine;
import com.hdf.cryptand.circuitsimulation.compute.ComputeScheduler;
import com.hdf.cryptand.circuitsimulation.compute.ComputeTask;
import com.hdf.cryptand.circuitsimulation.compute.NetworkSnapshot;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.circuitsimulation.compute.ThreadQuality;
import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;

import java.util.Map;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;

public final class PhasorEngine {

    private static volatile ComputeScheduler scheduler;
    /** 统一多线程分发（指向全局通用分配器 ThreadDispatchers.get()，2026-08-14
     *  通用化：分配器为本 mod 通用基础设施，其他任何 mod 也可使用；
     *  PhasorEngine 只是使用方之一，Worker 池线程数受限、空闲休眠） */
    private static volatile ComputeEngine cpuEngine;

    /** 单槽求解缓存：同网络同频率且电路拓扑未变化时复用（多方块共享一次相量求解） */
    private static final class CacheEntry {
        final ElectricalNetwork net;
        final double freq;
        final long solveTick;
        final long netVer;   // 构建时的【网络级】拓扑版本（addWire/removeWire → 该网络失效）
        final long graphVer; // 构建时的自管图版本（2026-08-13：WireGraph 变化 → 测量缓存失效）
        final long paramVersion; // 上次求解时的参数版本（元件参数变化消息 → 失效重解）
        final PhasorNetworkContext ctx;
        final SolveResult result;
        CacheEntry(ElectricalNetwork net, double freq, long solveTick, long netVer,
                   long paramVersion, PhasorNetworkContext ctx, SolveResult result) {
            this(net, freq, solveTick, netVer,
                    com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().version(),
                    paramVersion, ctx, result);
        }
        CacheEntry(ElectricalNetwork net, double freq, long solveTick, long netVer,
                   long graphVer, long paramVersion, PhasorNetworkContext ctx,
                   SolveResult result) {
            this.net = net; this.freq = freq; this.solveTick = solveTick;
            this.netVer = netVer; this.graphVer = graphVer;
            this.paramVersion = paramVersion;
            this.ctx = ctx; this.result = result;
        }
    }

    /**
     * 每网络独立求解缓存（ConcurrentHashMap，按 ElectricalNetwork 对象分键）：
     * 万用表(50Hz)/变压器(5000Hz)/绕组/发电机各自有独立缓存槽，互不踢出。
     * 缓存命中（结构复用，不重建电路网络）：
     *   - 电路【结构】未变（网络级 netVer）→ ctx 永久复用。结构变化由
     *     CryptandTopologyManager.markNetworkChanged(net) 统一触发（netVer++，
     *     2026-08-12 无感重建：只失效受影响网络，其他网络缓存命中）。
     *   - 元件【参数】变化（电阻/源/电容/电感）→ 元件 setter 发送参数变化消息
     *     → ctx.paramVersion++ → 用同一 ctx 重解（网络不重建）。
     *   - 参数未变（无消息）→ 直接复用旧结果（零重解，温度/状态完全稳定）。
     * solve 失败/构建空时保留旧值（不写 0）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<ElectricalNetwork, CacheEntry> CACHES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 获取缓存条目；miss/过期则重建；失败时返回旧条目（可为 null=完全无缓存） */
    private static CacheEntry getOrSolve(Level level, ElectricalNetwork net, double frequency, long gameTime) {
        // 暂停闸门：单机 ESC / 服务器暂停时不再提交后台求解（保留缓存旧值）
        if (isServerPaused()) {
            return CACHES.get(net);
        }
        CacheEntry e = CACHES.get(net);
        long netVer = 0;
        try {
            netVer = com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                    .netVersionOf(net);
        } catch (Throwable ignored) {
        }
        // 缓存命中：频率相同 + 电路拓扑未变化（网络级版本 + 自管图版本）→
        // 【结构复用】——不重建电路网络（参数变化不重建）。
        // ⚠ 自管图版本校验（2026-08-13 完整闭环）：自管模式 line.tick 禁 →
        // netVersions 无人递增 → 纯导线增删（graph 变化）不会经 netVer 失效。
        // 加 graphVer：WireGraph 变化 → 测量缓存 miss 重建（防陈旧结构读数）。
        // 先刷新可调参数（读方块值 → setter；值变化经元件参数变化消息
        // → ctx.paramVersion++），参数未变则直接复用旧结果，参数变了才
        // 用同一 ctx（网络不重建）重解。
        long graphVer = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().version();
        if (e != null && e.freq == frequency && e.netVer == netVer
                && e.graphVer == graphVer) {
            // 无闭合回路 → 单独记录 + 不运算：重置计算值（测量路径返回缓存的全 0 结果）
            if (e.ctx.loopless) {
                try { PhasorWriteback.resetComputation(level, e.ctx); } catch (Throwable ignored) { }
                return e;
            }
            try { e.ctx.refreshParams(); } catch (Throwable ignored) { }
            if (e.ctx.paramVersion.get() == e.paramVersion) {
                // 参数未变化 → 复用结果，不重解。
                // ⚠ 温度不在此推进：温度只由主循环 round 每 tick 推进（单次、连续），
                //   测量路径（getOrSolve）若也推进 → 同一变压器每 tick 被推进多次
                //   （[TfHeat] 同 tick 多次打印）→ 升温虚快/不一致。
                return e;
            }
            // 参数变化（消息驱动）→ 结构复用 + 重解
            SolveResult res = null;
            try { res = solve(e.ctx.network); } catch (Throwable ignored) { }
            if (res != null && res.voltages != null) {
                try { applyOpenTerminals(res, e.ctx); } catch (Throwable ignored) { }
                CacheEntry ne = new CacheEntry(net, frequency, gameTime, netVer,
                        e.ctx.paramVersion.get(), e.ctx, res);
                CACHES.put(net, ne);
                return ne;
            }
            return e; // 重解失败 → 保留旧值
        }
        PhasorNetworkContext ctx = buildForMeasurement(level, net, frequency);
        if (ctx.network.elements().isEmpty()) {
            return e; // 构建空网络 → 保留旧值（可为 null）
        }
        // 无闭合回路 → 单独记录 + 不运算：重置计算值（测量路径返回全 0 结果）。
        if (ctx.loopless) {
            try { PhasorWriteback.resetComputation(level, ctx); } catch (Throwable ignored) { }
            SolveResult zero = zeroResultOf(ctx);
            CacheEntry ne = new CacheEntry(net, frequency, gameTime, netVer,
                    ctx.paramVersion.get(), ctx, zero);
            CACHES.put(net, ne);
            return ne;
        }
        SolveResult res = null;
        try {
            res = solve(ctx.network);
        } catch (Throwable ignored) {
        }
        // 诊断：直接求解 ctx.network（不走 NetworkSnapshot 序列化）对比 snapshot 路径
        try {
            if (res != null && res.complex != null && ctx.network.nodeCount() <= 32
                    && !ctx.transformerModels.isEmpty()) {
                com.hdf.cryptand.circuitsimulation.solver.SolveResult rd =
                        new com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver().solve(ctx.network);
                if (rd != null && rd.complex != null) {
                    for (PhasorNetworkContext.TransformerModel tm : ctx.transformerModels.values()) {
                        if (tm.pb1 >= 0 && tm.pb1 < rd.complex.length
                                && tm.pb2 >= 0 && tm.pb2 < rd.complex.length) {
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[TfDirect] v2_direct={} v2_snapshot={} v1_direct={} gnd={}",
                                    String.format("%.3f", rd.complex[tm.pb1].sub(rd.complex[tm.pb2]).abs()),
                                    String.format("%.3f", res.complex[tm.pb1].sub(res.complex[tm.pb2]).abs()),
                                    String.format("%.3f", rd.complex[tm.pa1].sub(rd.complex[tm.pa2]).abs()),
                                    ctx.network.groundNode);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (res == null || res.voltages == null) {
            return e; // solve 失败/超时 → 保留旧值（可为 null）
        }
        // 开路支路电压跟随（悬空端 = 参考端，物理开路无电流）
        applyOpenTerminals(res, ctx);
        // ⚠ 温度不在此推进（只由主循环 round 每 tick 推进，避免测量路径重复推进）
        CacheEntry ne = new CacheEntry(net, frequency, gameTime, netVer,
                ctx.paramVersion.get(), ctx, res);
        CACHES.put(net, ne);
        // 防膨胀：条目过多时清理过旧缓存
        if (CACHES.size() > 64) {
            CACHES.entrySet().removeIf(en -> gameTime - en.getValue().solveTick > 40);
        }
        return ne;
    }

    /**
     * 测量路径构建（2026-08-13 完整闭环：测量路径切自管图构建）。
     * <p>
     * 自管模式（PowerGridWireConverter.isEnabled() 且自管图非空）→ 从原版网络
     * 反查自管 seed 端点 → buildContextFromGraph（自管图构建源，变压器感知
     * 跨分量合并）。否则回退 buildContextFromNetwork（原版网络构建，安全兜底）。
     * <p>
     * 缓存：按原版网络分键（CACHES），自管构建的 ctx 同样按 net 缓存——同一
     * 网络多次测量复用（结构未变不重建）。
     */
    private static PhasorNetworkContext buildForMeasurement(Level level,
                                                            ElectricalNetwork net,
                                                            double frequency) {
        try {
            if (com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter.isEnabled()
                    && com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().nodeCount() > 0) {
                // 从网络任一端点反查自管 seed key
                for (org.patryk3211.powergrid.electricity.sim.node.INode in : net.getNodes()) {
                    if (!(in instanceof OwnedFloatingNode ofn)) continue;
                    if (!(ofn.endpoint instanceof
                            org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bep)) {
                        continue;
                    }
                    String seedKey = "B" + bep.getPos() + "#" + bep.getTerminal();
                    if (!com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                            .contains(new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(seedKey))) {
                        continue;
                    }
                    return com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                            .buildContextFromGraph(level, seedKey, frequency);
                }
            }
        } catch (Throwable ignored) {
        }
        return PhasorNetworkBuilder.buildContextFromNetwork(level, net, frequency);
    }

    /**
     * 方块集合测量构建（2026-08-13 完整闭环：万用表路径切自管图构建）。
     * 自管模式 → 从第一个种子方块反查自管 seed → buildContextFromGraph。
     * 否则回退 buildContextFromBlocks（原版网络构建，安全兜底）。
     * <p>
     * 2026-08-20 修复"孤立设备（电机等 IElectricEntity）无法测量"：原判定
     * {@code be instanceof ElectricBlockEntity} 排除了 ConstantSpeedMotor 等
     * IElectricEntity 设备（非 ElectricBlockEntity 子类）→ 反查不到 seed →
     * 回退原版构建 → 电机点不在原版网络 → 测不到。现改为【直接按方块位置 +
     * declaredTerminalCount 遍历自管图点】：只要 addDevice 入了自管图（放置即
     * 建网，ElectricBlockEntity 或 IElectricEntity 都入），就能反查 seed。
     */
    private static PhasorNetworkContext buildForBlocksMeasurement(
            Level level, java.util.List<BlockPos> blocks,
            java.util.List<BaseWireEntity> currentWires, double frequency) {
        try {
            if (com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter.isEnabled()
                    && com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().nodeCount() > 0) {
                for (BlockPos bp : blocks) {
                    if (bp == null) continue;
                    // 该方块声明端子数（Assemblers 注册的设备 → terminalCount；
                    // IElectricEntity 兜底 2）。从自管图直接查点，不依赖 BE 类型。
                    int termCount = com.hdf.cryptand.neoforge.powergrid.adapter
                            .PhasorNetworkBuilder.declaredTerminalCount(
                                    level.getBlockEntity(bp));
                    for (int t = 0; t < termCount; t++) {
                        String seedKey = "B" + bp + "#" + t;
                        if (com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                                .contains(new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(seedKey))) {
                            return com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                    .buildContextFromGraph(level, seedKey, frequency);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return PhasorNetworkBuilder.buildContextFromBlocks(level, blocks, currentWires, frequency);
    }

    /**
     * 统一缓存失效：电路变化时清空所有求解缓存（下一轮强制重建）。
     * 由 CryptandTopologyManager.markTopologyChanged() 在接线/拆线/方块变化时统一调用。
     */
    public static void invalidateAll() {
        CACHES.clear();
    }

    /** 网络级缓存失效（2026-08-12 无感重建）：只作废该网络的测量缓存，
     *  其他网络缓存命中不重建。由 CryptandTopologyManager.markNetworkChanged(net)
     *  在 addWire/removeWire（带网络）时调用。null → 全局。 */
    public static void invalidate(ElectricalNetwork net) {
        if (net == null) { invalidateAll(); return; }
        CACHES.remove(net);
    }

    /**
     * 使某网络所有端子测试点失效（2026-08-13 稳定清零联动）：网络不稳定
     * （结构签名变化）被清零/跳过时，端子测试点也必须失效——否则消费端
     * （风扇/电机）读到【上次求解的旧电压】→ 开路/不稳定期误启动。
     * 从缓存取该网络 ctx（blockTerminals → pos），invalidate 引擎端子测试点
     * （消费端读 valid=false → 不动作）。无缓存 → 无端子（尚未求解，无需处理）。
     */
    public static void invalidateTerminals(ElectricalNetwork net) {
        if (net == null) return;
        CacheEntry e = CACHES.get(net);
        if (e == null || e.ctx == null) return;
        try {
            // 端子注册表（2026-08-13 完全接管端子）：从注册表按位置失效，不依赖
            // ctx.network.terminals() 遍历——网络分裂/重建后注册表 key 仍稳定。
            if (e.ctx.blockTerminals != null) {
                for (BlockPos bp : e.ctx.blockTerminals.keySet()) {
                    for (int t = 0; t < 4; t++) {
                        com.hdf.cryptand.circuitsimulation.model.TerminalElement te =
                                com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry
                                        .get(bp, t);
                        if (te != null) te.invalidate();
                    }
                }
            }
            // 2026-08-15 端子即接入模型：消费端直读 TerminalRegistry 测试点（已
            // invalidate → valid=false → 读 null/不动作），无需清中转表。
        } catch (Throwable ignored) {
        }
    }

    private PhasorEngine() {}

    /**
     * 服务器是否暂停（单机 ESC / 服务器暂停）→ 暂停时不提交后台相量求解。
     * 客户端无服务器（getCurrentServer==null）→ false（不误伤）。
     */
    public static boolean isServerPaused() {
        try {
            net.minecraft.server.MinecraftServer srv =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return srv != null && srv.isPaused();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void init() {
        if (scheduler != null) return;
        synchronized (PhasorEngine.class) {
            if (scheduler != null) return;
            // 加载 SuperLU 稀疏求解 DLL（成功 → 超大型网络走稀疏 LU；失败回退稠密）
            NativeSparseLoader.load();
            // 同步稀疏切换阈值配置（0 = 始终走 SuperLU）
            try {
                com.hdf.cryptand.circuitsimulation.solver.NativeSparse.setSolverThreshold(
                        ConfigLoad.SPARSE_SOLVER_THRESHOLD.get());
            } catch (Throwable ignored) {
            }
            scheduler = new ComputeScheduler();
            // 统一多线程分发（2026-08-12 用户要求）：ThreadDispatcher 管理受限
            // 线程池（空闲休眠/优先空闲/最低负载），线程数 = 配置 cryptandMaxThreads
            // （0 = 自动：CPU 核心数 - 1；正数 = 上限）。所有计算走此统一入口。
            int threads;
            try {
                int cfg = ConfigLoad.CRYPTAND_MAX_THREADS.get();
                threads = cfg <= 0
                        ? Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
                        : cfg;
            } catch (Throwable t) {
                threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            }
            // 2026-08-14 通用分配器：线程数配置写入全局单例（首次 get() 前生效），
            // 本 mod 只是使用方之一，其他 mod 共享同一分配器。
            ThreadDispatchers.setDefaultThreads(threads);
            // 2026-08-16 虚拟线程支持：每个 Worker 最大管理的虚拟线程数写入全局
            // 单例（首次 get() 前生效；0 = 禁用虚拟线程走独占直算）。
            try {
                int vthreads = ConfigLoad.CRYPTAND_MAX_VIRTUAL_THREADS.get();
                ThreadDispatchers.setDefaultMaxVirtualThreads(vthreads);
            } catch (Throwable t) {
                ThreadDispatchers.setDefaultMaxVirtualThreads(1000);
            }
            cpuEngine = ThreadDispatchers.get();
            scheduler.register(cpuEngine);
            CryptandNeoForge.WAF_LOGGER.info(
                    "PhasorEngine initialized ({} solver threads, {} max virtual threads/worker, ThreadDispatcher)",
                    threads, ThreadDispatchers.defaultMaxVirtualThreads());
        }
    }

    /**
     * 提交引擎求解（多线程调度）。包内可见（PhasorWriteback 相量回写复用）。 */
    static SolveResult solve(Network net) {
        NetworkSnapshot snap = NetworkSnapshot.from(net);
        long budget = 500_000_000L; // 500ms 预算（多路 solve 竞争时降低超时概率）
        ComputeTask task = new ComputeTask(snap, ThreadQuality.SINGLE_CORE, 0, 0,
                System.nanoTime() + budget, budget);
        ComputeResult r = scheduler.execute(task);
        return new SolveResult(r.voltages, r.phasors, r.converged, r.iterations,
                r.solveNanos, r.mode);
    }

    /**
     * 批量并行求解（2026-08-11 起多线程；2026-08-12 加入【同网络合并】）。
     * <p>
     * 同网络合并：把多个【同频率】的交流网络合并成一块【分块对角稀疏矩阵】
     * 一次 SuperLU 稀疏求解（{@link com.hdf.cryptand.circuitsimulation.solver.MergedNetworkSolver}）
     * ——替代 N 次小矩阵求解，省调度/分配开销，大矩阵一次符号分解。不同频率
     * 的组之间并行；DC/时域（frequency≤0）是稠密矩阵，合并成对角 n² 反而不
     * 划算 → 保持逐个求解（组内仍并行）。
     * <p>
     * 增量求解：参数变化 → 子网级 paramVersion++ → 只对该子网重解（结构复用，
     * 不重建网络）；合并求解时该子网块数值更新、其余块不变。
     *
     * @return 与输入同序的结果列表；单个失败/超时对应位置为 null
     */
    /**
     * 当前非线性相量方法（读配置；HARMONIC_BALANCE 时同步谐波阶数）。
     * 2026-08-15 用户需求：分段线性化/谐波平衡/动态相量——非线性直接相量计算。
     */
    static com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod currentNonlinearMethod() {
        try {
            String s = com.hdf.cryptand.neoforge.core.config.ConfigLoad
                    .PHASOR_NONLINEAR_METHOD.get();
            com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod m =
                    s == null ? com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod.NONE
                            : com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod
                                    .valueOf(s.trim().toUpperCase());
            if (m == com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod.HARMONIC_BALANCE) {
                com.hdf.cryptand.circuitsimulation.solver.HarmonicBalanceSolver.harmonics = Math.max(1,
                        com.hdf.cryptand.neoforge.core.config.ConfigLoad.PHASOR_HARMONICS.get());
            }
            return m;
        } catch (Throwable t) {
            return com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod.NONE;
        }
    }

    static java.util.List<SolveResult> solveAll(java.util.List<Network> nets) {
        return solveAll(nets, null);
    }

    /**
     * 批量求解。ctxs（可选，与 nets 同序）：求解后、温度推进前先做悬空端子
     * 等电位（applyOpenTerminals）——否则有源 R-L 设备（加热器等）悬空端在
     * MNA 中假电压 → 假电流 → 假温度（未接入回路设备被烧到 213°C）。
     */
    static java.util.List<SolveResult> solveAll(java.util.List<Network> netsIn,
                                                java.util.List<PhasorNetworkContext> ctxsIn) {
        return solveAll(netsIn, ctxsIn, null);
    }

    /**
     * 批量求解（扩展）。expectHash（可选，与 netsIn 同序；0=不用）：调用方在
     * 【同一相位/同一构建轮】已拍 ctx.solveHash，此处把该值原样写回
     * res.networkHash——保证 res 与 ctx 的指纹【必然同源】，消除跨线程拍照
     * 时间差（主线程/异步并行推进 EMF → structureHash 含幅值每轮变 → 两次
     * 拍照中间隔差一拍 → 每轮假 [WireHeatMismatch]）。
     */
    static java.util.List<SolveResult> solveAll(java.util.List<Network> netsIn,
                                                java.util.List<PhasorNetworkContext> ctxsIn,
                                                long[] expectHash) {
        if (netsIn == null || netsIn.isEmpty()) return java.util.List.of();
        init();
        // 推进预算（2026-08-20 用户要求"矩阵解算按时钟推进次数"）：主线程每
        // tick 补充预算，每次矩阵批量求解消耗 1——防主线程卡死时异步线程跑飞。
        // ⚠ 2026-08-20 用户要求"所有网络都进行计算，防止出现静止现象"：预算
        // 耗尽【不跳过任何网络求解】（跳过 → 电压/电流静止 → 电机停转/设备假
        // 状态）。预算机制保留【心跳看门狗】防跑飞（consumeStep 内部检查主线程
        // 心跳，超时仍拒绝——主线程卡死 → 停），但【不再因预算次数不足跳求解】。
        SIM_CLOCK.consumeStep(); // 消耗预算（保留心跳检查语义，结果不用于跳过）
        final java.util.List<Network> nets = netsIn;
        final java.util.List<PhasorNetworkContext> ctxs = ctxsIn;
        // 伪时域（2026-08-12 用户要求：时间按真实时间计算，达到伪时域）：
        // 【2026-08-20 主线程驱动】时间只由主线程每 tick 推进（CryptandTopologyManager.tick
        // → PhasorEngine.tickSimClock），本方法（可在异步线程执行）只读
        // SIM_CLOCK 的当前时间/步长——主线程卡死 → 时间不推进 → 这里拿到的
        // dt 恒为 0 → 状态不演化（安全机制，异步线程无法脱离主线程自行推进）。
        double simDt = 0.05;
        try {
            simDt = SIM_CLOCK.lastDt(); // 只读：主线程最近一次 tick 的推进量
            double simT = SIM_CLOCK.time();
            for (Network net : nets) {
                net.dt = simDt;
                net.time = simT;
                // 2026-08-21 伪时域充电：同步电容/电感 Backward Euler 节拍
                // （stampComplex 用 simDt；DC/低频逐节拍演化需与网络 dt 一致）
                try {
                    for (com.hdf.cryptand.circuitsimulation.model.Element e : net.elements()) {
                        if (e instanceof com.hdf.cryptand.circuitsimulation.model.elements.Capacitor cap) {
                            cap.simDt = simDt;
                        } else if (e instanceof com.hdf.cryptand.circuitsimulation.model.elements.Inductor ind) {
                            ind.simDt = simDt;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        int n = nets.size();
        SolveResult[] out = new SolveResult[n];
        // 2026-08-24 求解时网表指纹快照：必须在【求解前/推进前】拍——
        // advancePseudoTime 会推进 EMF（setEmf 改 AcVoltageSource.amplitude →
        // structureHash 变），若推进后才标 hash → 与求解时网表不一致 → 假
        // [WireHeatMismatch]（每轮都拦）→ 真错配反而被淹没。
        long[] solveSnapHash = new long[n];
        for (int i = 0; i < n; i++) {
            try {
                if (nets.get(i) != null) solveSnapHash[i] = nets.get(i).structureHash();
            } catch (Throwable ignored) {
            }
        }
        // ⚠ 2026-08-21 用户要求"AC/DC 走同一套系统，DC 频率=0 计算"：所有网络
        //   （含 DC 0Hz）统一按频率分组相量求解，不再分 AC 组/DC 组。DC = 0Hz
        //   相量——omega 用等效小频率 2π×max(0,1e-4)（电容/电感触发 Backward
        //   Euler 伪时域分支充电/建流，源直流输出幅值，无除零）。
        java.util.Map<Double, java.util.List<Integer>> freqGroups = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            freqGroups.computeIfAbsent(nets.get(i).frequency,
                    k -> new java.util.ArrayList<>()).add(i);
        }
        int groupCount = freqGroups.size();
        // 并行：每个频率组一个任务（含 DC 0Hz 组，统一 MergedNetworkSolver）。
        // ⚠ 2026-08-14 "世界进入卡住"修复：native 求解（MergedNetworkSolver）
        // 可能永久卡死 → 公共 ForkJoinPool 的 parallel().forEach 永不返回 →
        // Server thread 永久阻塞（加载世界卡住，需杀进程）。改专用池 + 10s
        // 超时：超时放弃本轮结果（写入 null），主线程继续，不再永久卡。
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(
                Math.min(4, groupCount));
        try {
            pool.submit(() -> java.util.stream.IntStream.range(0, groupCount)
                    .parallel().forEach(g -> {
                        int gi = 0;
                        for (java.util.Map.Entry<Double, java.util.List<Integer>> e
                                : freqGroups.entrySet()) {
                            if (gi++ != g) continue;
                            java.util.List<Integer> idxs = e.getValue();
                            // 2026-08-15 混合相量非线性：含无记忆非线性元件
                            // （NonlinearPhasorElement：二极管等）的网络用增强
                            // 相量算法单独求解（分段线性化/谐波平衡/动态相量）；
                            // 纯线性/能量网络照旧合并求解。能量元件（电容/电感/
                            // 温度，isNonlinear 但非本接口）仍由伪时域
                            // advancePseudoTime 推进——增强相量 + 伪时域并存。
                            com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod nlMethod =
                                    currentNonlinearMethod();
                            java.util.List<Integer> linearIdx = new java.util.ArrayList<>();
                            java.util.List<Network> linearNets = new java.util.ArrayList<>();
                            for (int i : idxs) {
                                Network net = nets.get(i);
                                if (nlMethod != com.hdf.cryptand.circuitsimulation.solver
                                        .PhasorNonlinearMethod.NONE
                                        && net != null && net.hasPhasorNonlinear()) {
                                    try {
                                        out[i] = com.hdf.cryptand.circuitsimulation.solver.Solvers
                                                .createNonlinear(net, nlMethod).solve(net);
                                    } catch (Throwable ignored) {
                                    }
                                } else {
                                    linearIdx.add(i);
                                    linearNets.add(net);
                                }
                            }
                            // 统一相量：DC(0Hz) 也用等效小频率 omega（Backward
                            // Euler 分支），AC 用真实频率——AC/DC 同一套求解器。
                            double omega = 2 * Math.PI
                                    * Math.max(e.getKey(), 1e-4);
                            if (!linearNets.isEmpty()) {
                                java.util.List<SolveResult> rs;
                                try {
                                    rs = com.hdf.cryptand.circuitsimulation.solver.MergedNetworkSolver
                                            .solveMergedComplex(linearNets, omega);
                                } catch (Throwable ignored) {
                                    rs = java.util.List.of();
                                }
                                for (int k = 0; k < linearIdx.size() && k < rs.size(); k++) {
                                    out[linearIdx.get(k)] = rs.get(k);
                                }
                            }
                            break;
                        }
                    })).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[Phasor] solveAll timeout (native hang?), abandon this round, nets={}", n);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[Phasor] solveAll interrupted, nets={}", n);
        } catch (java.util.concurrent.ExecutionException ee) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                    "[Phasor] solveAll failed", ee);
        } finally {
            pool.shutdownNow();
        }
        // 悬空端子等电位（2026-08-18 修复"未接入回路设备假温度"）：在温度推进
        // 之前应用 openTerminal——加热器等有源 R-L 设备悬空端在 MNA 中假电压 →
        // 假电流 → 假温度（213°C）。roundFromGraph 后续的 applyOpenTerminals 太晚
        // （advancePseudoTime 已用假压差推进温度）。ctxs 为 null（其他调用路径）
        // → 行为不变。
        if (ctxs != null) {
            for (int i = 0; i < out.length && i < ctxs.size(); i++) {
                PhasorNetworkContext c = ctxs.get(i);
                if (c != null && out[i] != null) {
                    try { applyOpenTerminals(out[i], c); } catch (Throwable ignored) { }
                }
            }
        }
        // 伪时域能量推进（2026-08-15 用户架构：相量+固定节拍）：求解完成后推进
        // 复合元件能量状态（advanceState：版本+1、应用绑定参数、固定节拍步长 dt
        // 推进温度、过热通知）。非线性网络每轮强制求解 → 状态随节拍漂移；
        // 线性网络缓存命中不进入本方法。
        advancePseudoTime(nets, out, simDt);
        // 2026-08-24 网表指纹：标记每个结果来源网表。优先用调用方传入的期望
        // 指纹（与其 ctx.solveHash 同源，跨线程拍照时间差为零）；无则用求解前
        // 快照（兜底，其他调用路径）。
        try {
            for (int i = 0; i < out.length && i < nets.size(); i++) {
                if (out[i] != null) {
                    out[i].networkHash = (expectHash != null && i < expectHash.length
                            && expectHash[i] != 0) ? expectHash[i] : solveSnapHash[i];
                }
            }
        } catch (Throwable ignored) {
        }
        // 2026-08-21 电容充电状态持久化（跨重建保留；测量路径 solveBlocks 也调）
        try {
            for (Network net : nets) saveCapacitorStates(net);
        } catch (Throwable ignored) {
        }
        return java.util.Arrays.asList(out);
    }

    /** 电容充电状态保存（2026-08-21 跨重建/测量持久）：遍历网络复合电容存
     *  CapacitorStateStore（key=compositeKey "C"+pos，构建时稳定）。 */
    private static void saveCapacitorStates(Network net) {
        if (net == null) return;
        try {
            for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce : net.composites()) {
                if (ce instanceof com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel cm) {
                    String key = ce.compositeKey();
                    if (key != null && key.startsWith("C")) {
                        CapacitorStateStore.put(key, cm.capVPrev());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 伪时域能量推进（2026-08-15）：对每个已求解网络的复合元件调
     * {@code advanceState}（统一 EnergyState 入口）。
     */
    static void advancePseudoTime(java.util.List<Network> nets, SolveResult[] out,
                                  double simDt) {
        if (nets == null) return;
        // ⚠ 2026-08-24 实锤：nets 可能含【重复 network 引用】（同 seedKey 多频率
        // PendingG / restoredCtx 复用）→ 同一 em 每轮被推进 N 次（omega/EMF 振荡
        // 实锤：rpm 3→84→126→15→119、EMF -113→-13）。按【对象身份】去重推进。
        java.util.Set<Network> seenNets =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Network, Boolean>());
        for (int i = 0; i < nets.size(); i++) {
            Network net = nets.get(i);
            SolveResult res = (out != null && i < out.length) ? out[i] : null;
            if (net == null) continue;
            if (!seenNets.add(net)) continue; // 同对象重复 → 只推进一次
            // ⚠ 2026-08-24 用户：与时间相关模型【持续推进演算，不管网络是否开路
            // 与闭合】——res 无效（未收敛/开路/无结果）不再跳过：用 0 电压推进
            //（开路 → 惯性滑行/自然散热/储能释放，状态继续演化不冻结）。
            java.util.List<com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement> cs;
            try {
                cs = net.composites();
            } catch (Throwable ignored) {
                continue;
            }
            if (cs == null || cs.isEmpty()) continue;
            double freq = net.frequency;
            Complex[] ph = null;
            try {
                if (res != null && res.converged) {
                    ph = res.complex;
                    // 2026-08-21 DC 走真时域（RealMnaSolver Backward Euler，电容充电）：
                    // 结果无相量（complex=null）→ 用实数电压构造（re=v, im=0），
                    // 保证温度推进/损耗计算仍能注入节点电压（否则 va/vb=null → 跳过）。
                    if (ph == null && res.voltages != null) {
                        ph = new Complex[res.voltages.length];
                        for (int k = 0; k < res.voltages.length; k++) {
                            ph[k] = new Complex(res.voltages[k], 0);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // 无有效结果 → 0 电压（开路/未收敛）：动力学/温度/储能持续演化
            if (ph == null) {
                ph = new Complex[Math.max(net.nodeCount(), 1)];
                for (int k = 0; k < ph.length; k++) ph[k] = new Complex(0, 0);
            }
            boolean anyChanging = false;
            // ⚠ 2026-08-24 实锤（MotorFull 同 key 打两遍）：不同 Network 对象可能
            // 共享同一 CompositeElement 实例 → 按【复合元件身份】再去重一次，
            // 彻底杜绝同一 em 每轮被推进多次（omega/EMF 振荡源）。
            java.util.Set<Object> seenComps = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<Object, Boolean>());
            for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement c : cs) {
                try {
                    // 2026-08-20 修复"5A 电流导线 300°C"（双推进温度）：导线
                    // 段（WireComposite）温度只由 computeWireHeatOne（processPost
                    // 统一发热）推进——它负责烧毁检测；这里若再推进一次 → 温度
                    // 按 2 倍速率累积 → 5A 也快速烧毁。跳过 WireComposite。
                    if (c instanceof com.hdf.cryptand.circuitsimulation.model.composite.WireComposite) {
                        continue;
                    }
                    if (!seenComps.add(c)) continue; // 同实例重复 → 只推进一次
                    // 2026-08-18 用户要求：注入求解后节点电压数组 → 复合元件
                    // （MotorModel 加热器/绕组）用内部节点电压算【流过内部电阻的
                    // 真实电流】推进温度（而非端口电压差估算）
                    c.setNodeVoltages(ph);
                    int a = c.nodeA(), b = c.nodeB();
                    Complex va = (ph != null && a >= 0 && a < ph.length) ? ph[a] : null;
                    Complex vb = (ph != null && b >= 0 && b < ph.length) ? ph[b] : null;
                    // 2026-08-21 用户要求"AC/DC 同一套系统"：统一相量 mode
                    // （DC 也是 0Hz 相量，不再区分 REAL_DC——状态模型用相量
                    // complex 电压的实部即可）
                    com.hdf.cryptand.circuitsimulation.solver.SolveMode mode =
                            com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC;
                    c.advanceState(va, vb, freq, simDt, mode);
                    // 2026-08-27 消费 BE→引擎消息（断电滑行关键）：PowerGrid
                    // 电机 BE 经 PowerGridMotorParser.serverTick 每 tick 上报
                    // [λ, networkStressSU, 容量, networkRadS]（有网）或【空心跳】
                    // （无网 = 断电/剪线）。此前组装器从不 pollInput → networkStressSU
                    // 恒留旧值（16384 级）→ 断电消耗 = 546+16384 ≈ 1s 归零
                    // （“断电立即停无惯性”根因）。现在：
                    //   有网消息 → 更新 loadRatio/networkStressSU/networkRadS；
                    //   空心跳（无网）→ 清零三值（负载网络已脱离 → 纯空载滑行）。
                    if (c instanceof com.hdf.cryptand.circuitsimulation.model.composite
                            .ElectroMachineModel em2) {
                        try {
                            String ck2 = c.compositeKey();
                            if (ck2 != null && ck2.startsWith("D")) {
                                net.minecraft.core.BlockPos bp2 =
                                        WireKeyUtil.posOfComposite(ck2);
                                if (bp2 != null) {
                                    com.hdf.cryptand.neoforge.powergrid.device.motor
                                            .BeMessage msg = com.hdf.cryptand.neoforge
                                            .powergrid.device.motor.BeMessageParser
                                            .pollInput(bp2);
                                    if (msg != null) {
                                        int sz = msg.size();
                                        if (sz == 0) {
                                            // 空心跳（无网/断电）：负载脱离
                                            em2.setLoadRatio(0);
                                            em2.setNetworkStressSU(0);
                                            em2.setMotorLoadStressSU(0);
                                            em2.setNetworkRadS(0);
                                            em2.setNetworkConnected(false); // 断电
                                        } else if (sz >= 3) {
                                            // [λ, networkStressSU, 容量, networkRadS]
                                            double lambda =
                                                    (msg.raw(0) instanceof Number n0) ? n0.doubleValue() : 0;
                                            double stress =
                                                    (msg.raw(1) instanceof Number n1) ? n1.doubleValue() : 0;
                                            double capacity =
                                                    (msg.raw(2) instanceof Number n2) ? n2.doubleValue() : 0;
                                            double netRadS =
                                                    (sz >= 4 && msg.raw(3) instanceof Number n3)
                                                            ? n3.doubleValue() : 0;
                                            em2.setLoadRatio(lambda);
                                            em2.setNetworkStressSU(stress);
                                            // 2026-08-28 架构：注入本机负载应力（模型不读网络总应力）——
                                            // 多源按容量分摊由组装器后续在此处实现
                                            em2.setMotorLoadStressSU(stress);
                                            em2.setNetworkRadS(netRadS);
                                            // ⚠ 供电判据：网络【有源容量 capacity>0】
                                            // （空网络/仅电机自身 → 无源 → 断电）
                                            em2.setNetworkConnected(capacity > 0);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    // 稳态跟踪（2026-08-20）：任一状态模型仍在变化 → 网络需继续求解
                    if (c.stateChanging()) anyChanging = true;
                    // 2026-08-24 设备功率记录（供主线程经 BeBridge 发 [powerW] 消息）：
                    // 所有非导线复合元件（加热器/灯/电阻/电机损耗等）的损耗功率入
                    // DevicePowerStore（异步线程写、线程安全）。
                    try {
                        String ck = c.compositeKey();
                        if (ck != null && ck.startsWith("D")) {
                            net.minecraft.core.BlockPos bp =
                                    WireKeyUtil.posOfComposite(ck);
                            if (bp != null) {
                                double loss = (va != null && vb != null)
                                        ? c.lossPower(va, vb,
                                        2 * Math.PI * Math.max(freq, 0)) : 0;
                                com.hdf.cryptand.neoforge.powergrid.adapter
                                        .DevicePowerStore.put(bp, loss);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    // 诊断（2026-08-18 定位“加热器不发热”）：节流打印带温度复合
                    // 元件的损耗功率/温度/端口（确认 MotorModel 是否被推进）
                    if (c.thermal() != null && va != null && vb != null) {
                        long nowD = System.currentTimeMillis();
                        if (nowD - ADVANCE_DBG_LAST >= 5000) {
                            ADVANCE_DBG_LAST = nowD;
                            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                    "[AdvHeat] key={} cls={} a={} b={} loss={}W T={}C",
                                    c.compositeKey(),
                                    c.getClass().getSimpleName(),
                                    a, b,
                                    String.format("%.3f", c.lossPower(va, vb,
                                            2 * Math.PI * Math.max(freq, 0))),
                                    String.format("%.1f", c.thermal().tempCelsius()));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // 状态稳定标记（2026-08-20 稳态跳过：buildPending 据此跳过稳态网络）
            net.stateSettled = !anyChanging;
        }
        // 2026-08-24 推进诊断（WAF_LOGGER 进 latest.log；节流 5s）：确认
        // advancePseudoTime 是否真在推进、每轮推了多少元件/电机——断链排查。
        try {
            long advNow = System.currentTimeMillis();
            if (advNow - ADV_PSEUDO_DBG_LAST >= 5000) {
                ADV_PSEUDO_DBG_LAST = advNow;
                int motors = 0, compsN = 0;
                double maxOmega = 0;
                for (Network netr : nets) {
                    try {
                        if (netr == null) continue;
                        for (var cc : netr.composites()) {
                            compsN++;
                            if (cc instanceof com.hdf.cryptand.circuitsimulation.model.composite
                                    .ElectroMachineModel emr) {
                                motors++;
                                maxOmega = Math.max(maxOmega,
                                        Math.abs(emr.rotorSpeedRadS));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[AdvPseudo] nets={} comps={} motors={} maxOmega={} dt={}",
                        nets.size(), compsN, motors,
                        String.format("%.2f", maxOmega),
                        String.format("%.4f", simDt));
                // 2026-08-24 用户：应力从 4000 多慢慢降 → [MotorFull] 打印每台
                // 电机全部数据（WAF_LOGGER 5s/台）：V/EMF/I/P/应力/ω/R/L/惯量/τ/规格。
                java.util.Set<Network> seenD = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Network, Boolean>());
                java.util.Set<Object> seenEm = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Object, Boolean>());
                for (Network netr : nets) {
                    try {
                        if (netr == null || !seenD.add(netr)) continue;
                        for (var cc2 : netr.composites()) {
                            if (cc2 instanceof com.hdf.cryptand.circuitsimulation.model
                                    .composite.ElectroMachineModel emm
                                    && seenEm.add(cc2)) {
                                try {
                                    com.hdf.cryptand.neoforge.CryptandNeoForge
                                            .WAF_LOGGER.info(
                                            "[MotorFull] key={} mode={} Vab={} dDrive={} I={} EMF={} K={} Pset={} Pout={} stress={}/{} omega={} rpm={}/{}" +
                                                    " R={} L={} J={} B={} tau={} ratedV={} maxV={} ratedRPM={} setRPM={} nodes={},{},{} dt={}",
                                            cc2.compositeKey(),
                                            emm.generatorMode ? "GEN"
                                                    : (emm.servoMode ? "SERVO"
                                                    : (emm.constantSpeed ? "CS"
                                                    : "NORM")),
                                            String.format("%.2f", emm.lastVabV),
                                            String.format("%.2f", emm.lastDriveV),
                                            String.format("%.3f", emm.lastCurrentA),
                                            String.format("%.2f", emm.lastEmfV),
                                            String.format("%.4f", emm.emfConstant),
                                            String.format("%.1f", emm.outputPowerW),
                                            String.format("%.1f", emm.lastOutputPowerW),
                                            String.format("%.0f", emm.lastStressSU),
                                            String.format("%.0f", emm.maxStress),
                                            String.format("%.2f", emm.rotorSpeedRadS),
                                            String.format("%.0f",
                                                    emm.rotorSpeedRadS * 60.0
                                                            / (2.0 * Math.PI)),
                                            String.format("%.0f", emm.ratedRadS
                                                    * 60.0 / (2.0 * Math.PI)),
                                            String.format("%.4f", emm.resistance),
                                            String.format("%.4f", emm.inductance),
                                            String.format("%.3f", emm.inertia),
                                            String.format("%.4f", emm.friction),
                                            String.format("%.2f",
                                                    emm.shaftInertia == null ? -1
                                                            : emm.shaftInertia.tau()),
                                            String.format("%.0f", emm.ratedVoltage),
                                            String.format("%.0f", emm.maxVoltage),
                                            String.format("%.0f", emm.ratedRadS
                                                    * 60.0 / (2.0 * Math.PI)),
                                            String.format("%.0f", emm.setSpeedRPM),
                                            emm.a, emm.b, emm.x,
                                            String.format("%.4f", simDt));
                                    if (System.currentTimeMillis()
                                            - MotorFullDbgLast >= 5000) {
                                        MotorFullDbgLast = System.currentTimeMillis();
                                        try {
                                            com.hdf.cryptand.neoforge.CryptandNeoForge
                                                    .WAF_LOGGER.info(
                                                    "[MotorLoad] key={} netStress={} lambda={} tRated={} tLoad={} iReal={} emfSrc={} conn={}",
                                                    cc2.compositeKey(),
                                                    String.format("%.0f", emm.networkStressSU),
                                                    String.format("%.3f", emm.loadRatio),
                                                    String.format("%.1f",
                                                            emm.torqueConstant * (emm.ratedVoltage
                                                                    / Math.max(emm.resistance, 1e-9))),
                                                    String.format("%.1f",
                                                            Math.min(1.0, emm.networkStressSU
                                                                    / Math.max(emm.maxStress, 1e-6))
                                                                    * (emm.torqueConstant * (emm.ratedVoltage
                                                                    / Math.max(emm.resistance, 1e-9)))),
                                                    String.format("%.2f", emm.lastCurrentA),
                                                    String.format("%.2f", emm.lastEmfV),
                                                    emm.networkConnected);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** [AdvHeat] 诊断节流（2026-08-18 定位加热器不发热） */
    private static volatile long ADVANCE_DBG_LAST;

    /** [MotorLoad] 诊断节流（2026-08-27 定位直接接入过热爆炸：负载自锁/EMF 负） */
    private static volatile long MotorFullDbgLast;

    /** 统一伪时域仿真时钟（2026-08-12：时间按真实时间计算） */
    private static final com.hdf.cryptand.circuitsimulation.solver.SimClock SIM_CLOCK =
            new com.hdf.cryptand.circuitsimulation.solver.SimClock();

    /**
     * 【主线程驱动时间】每 tick 调用一次（2026-08-20 用户要求）：
     * 时间只由主线程推进（CryptandTopologyManager.tick 每 tick 调本方法），
     * 异步/后台求解线程只读 SIM_CLOCK 的 time/lastDt——主线程卡死 →
     * 时间不再推进 → 后台线程 dt=0 → 状态不演化（安全机制）。
     * 节流：温度等慢变量不必每 tick 推进（tickThrottle 见 SimClock）。
     * 推进预算：预算 = 异步线程每秒计算次数 / 主线程每秒 tick 数
     * （如异步 100Hz / 20 tick = 每 tick 5 次推进）——矩阵求解每 tick 最多
     * 推进预算次；其他（温度等动力学）节流推进。
     * 返回本次实际推进的 dt（s；节流中为 0）。
     */
    public static double tickSimClock() {
        // 预算 = 异步计算频率 / 主线程 tick 频率（50ms → 20/s）
        double asyncHz = com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager
                .getFrequencyHz();
        int budget = (int) Math.max(1, Math.round(asyncHz / 20.0));
        return SIM_CLOCK.tick(budget);
    }

    /** 设置仿真时钟节流：每 N tick 推进一次动力学（默认 1 = 每 tick）。 */
    public static void setSimClockThrottle(int n) {
        SIM_CLOCK.setTickThrottle(n);
    }

    /** 当前仿真时钟累计时间（s，只读）。 */
    public static double simTime() {
        return SIM_CLOCK.time();
    }

    /** 主线程最近一次 tick 实际推进的 dt（s；节流中为 0，只读）。 */
    public static double lastSimDt() {
        return SIM_CLOCK.lastDt();
    }

    /**
     * 获取统一计算引擎（2026-08-14：返回全局通用分配器；供线程分发调试 mixin /
     * 外部访问）。分配器为本 mod 通用能力（ThreadDispatcher Worker 池）。
     */
    public static com.hdf.cryptand.circuitsimulation.compute.ComputeEngine computeEngine() {
        return ThreadDispatchers.get();
    }

    /**
     * 注册周期定时任务（微秒级，2026-08-13 用户要求；2026-08-14 转发到全局通用
     * 分配器，无需 PhasorEngine 预先 init）。供需要定时触发计算的 mod 挂载
     * （PLC 载波刷新/示波器高频采样/外部计算）：任务由统一分配器定时线程调度
     * （纳秒基准，不漂移），到点后提交到受限 Worker 池执行。
     *
     * @param action   到期执行的回调
     * @param periodUs 周期（微秒）
     * @return 句柄（可取消）
     */
    public static com.hdf.cryptand.circuitsimulation.compute.ScheduledHandle schedule(
            Runnable action, long periodUs) {
        return ThreadDispatchers.schedule(action, periodUs);
    }

    /** 单网络求解（异常 → null） */
    private static SolveResult solveOne(Network net) {
        try {
            return solve(net);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 服务端网络级：求某方块两端子间的跨压【峰值幅值】|V|（相量核心，无时域失真）。
     * t1/t2 取该方块前两个非空端子（Winding 等两端口方块）。
     *
     * @return 峰值幅值（V）；无网络/无回路/求解失败返回 0
     */
    public static double voltageAcross(Level level, BlockPos pos, double frequency, long gameTime) {
        return voltageAcross(level, pos, -1, -1, frequency, gameTime);
    }

    /**
     * 服务端网络级：求某方块指定端子 (t1,t2) 间跨压【峰值幅值】|V|（相量核心）。
     * t1/t2 &lt; 0 → 取该方块前两个非空端子。
     * 变压器两侧是独立 ElectricalNetwork——相量构建跨变压器合并求解（见
     * buildContextFromNetwork），因此变压器的初级端口和次级端口都在同一相量
     * 网络内，指定各自端子索引即可得精确匝数比电压。求解结果缓存
     * （同网络同频率 4 tick 内复用），不影响 PowerGrid 的多线程网络求解。
     */
    public static double voltageAcross(Level level, BlockPos pos, int t1, int t2,
                                       double frequency, long gameTime) {
        init();
        if (level == null || pos == null || level.isClientSide) return 0;
        try {
            if (!(level.getBlockEntity(pos) instanceof ElectricBlockEntity ebe)) return 0;
            ElectricBehaviour beh = ebe.getElectricBehaviour();
            if (beh == null) return 0;
            for (int t = 0; t < 4; t++) {
                OwnedFloatingNode node = beh.getTerminal(t);
                if (node == null) continue;
                ElectricalNetwork net = node.getNetwork();
                if (net == null) continue;
                CacheEntry e = getOrSolve(level, net, frequency, gameTime);
                if (e == null) {
                    return Double.NaN;
                }
                return voltageFrom(e.ctx, e.result, pos, t1, t2);
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.debug("PhasorEngine.voltageAcross failed", t);
        }
        return Double.NaN;
    }

    // ========== 合并求解 API（同一网络一次求解，多方块反查） ==========

    /** 一次网络求解的封装：ctx（含 blockTerminals）+ result，供多个目标反查 */
    public static final class NetworkSolve {
        public final ElectricalNetwork net;
        public final double freq;
        public final PhasorNetworkContext ctx;
        public final SolveResult result;
        final Level level;
        NetworkSolve(ElectricalNetwork net, double freq, PhasorNetworkContext ctx,
                     SolveResult result, Level level) {
            this.net = net; this.freq = freq; this.ctx = ctx; this.result = result;
            this.level = level;
        }
    }

    /**
     * 按网络求解一次（同网络同频率、结构未变时复用缓存，不重复 build/solve）。
     * 供服务器测量系统对同一网络的多请求合并：一次求解 → 各自反查。
     * 失败（无缓存且 solve 失败）返回 null。
     */
    public static NetworkSolve solveNetwork(Level level, ElectricalNetwork net,
                                            double frequency, long gameTime) {
        init();
        if (level == null || net == null || level.isClientSide) return null;
        try {
            CacheEntry e = getOrSolve(level, net, frequency, gameTime);
            if (e == null) return null;
            return new NetworkSolve(net, frequency, e.ctx, e.result, level);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.debug("PhasorEngine.solveNetwork failed", t);
            return null;
        }
    }

    /**
     * 从【方块位置集合】求解一次（万用表专用：孤立器件 / 跨网络导线）。
     * 与 solveNetwork 不同：种子是方块而非单一 ElectricalNetwork，因此
     * 不走 per-net 缓存（每次直接构建 + 求解）。失败返回 null。
     */
    public static NetworkSolve solveBlocks(Level level, java.util.List<BlockPos> blocks,
                                           java.util.List<BaseWireEntity> currentWires,
                                           double frequency, long gameTime) {
        init();
        if (level == null || blocks == null || blocks.isEmpty() || level.isClientSide) return null;
        if (isServerPaused()) return null; // 暂停 → 不计算
        try {
            PhasorNetworkContext ctx = buildForBlocksMeasurement(level, blocks, currentWires,
                    frequency);
            // 接线端子等纯节点网络（无元件但有节点）也应可测（电压=GMIN 兜底 0）
            if (ctx.network.nodeCount() == 0) {
                CryptandNeoForge.WAF_LOGGER.info("[MeterFail] solveBlocks empty-net blocks={} wires={}",
                        blocks, currentWires == null ? 0 : currentWires.size());
                return null;
            }
            // ⚠ 2026-08-21 修复"DC 电容测量恒 163A"：测量路径必须【直接求解原网络】
            //   （ComplexMnaSolver 求解后 commit 更新原 net 电容 vPrev）。原走 solve()
            //   （NetworkSnapshot 快照副本）——副本 vPrev 更新后不同步回原网络 →
            //   每次测量构建新网络 vPrev=0 → 恒第一拍充电电流（10V 电容短路级 163A）。
            //   直接求解 + 求解后 saveCapacitorStates（跨测量持久）→ 充电状态连续。
            SolveResult res = com.hdf.cryptand.circuitsimulation.solver.Solvers
                    .create(com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC,
                            ctx.network)
                    .solve(ctx.network);
            // 电容充电状态持久化（下次测量/重建恢复）
            saveCapacitorStates(ctx.network);
            if (res == null || res.voltages == null) {
                CryptandNeoForge.WAF_LOGGER.info("[MeterFail] solveBlocks null-res blocks={}",
                        blocks);
                return null;
            }
            if (res.complex != null) {
                for (int i = 0; i < res.complex.length; i++) {
                    if (Double.isNaN(res.complex[i].re) || Double.isNaN(res.complex[i].im)
                            || Double.isInfinite(res.complex[i].re) || Double.isInfinite(res.complex[i].im)) {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[MeterFail] solveBlocks NaN node={} nodes={} elems={} freq={}",
                                i, res.complex.length, ctx.network.elements().size(),
                                String.format("%.2f", frequency));
                        break;
                    }
                }
            }
            // 开路支路电压跟随（悬空端 = 参考端）→ 反查
            applyOpenTerminals(res, ctx);
            return new NetworkSolve(null, frequency, ctx, res, level);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.info("[MeterFail] solveBlocks exc blocks={} err={}",
                    blocks, t.toString());
            return null;
        }
    }

    /** 从已求解网络反查方块指定端子(t1,t2)跨压【峰值】；t1/t2<0 → 前两个非空端子；无效 → NaN */
    public static double voltageFromSolve(NetworkSolve s, BlockPos pos, int t1, int t2) {
        if (s == null || pos == null) return Double.NaN;
        int a, b;
        if (t1 >= 0 && t2 >= 0) {
            a = terminalNodeId(s, pos, t1);
            b = terminalNodeId(s, pos, t2);
        } else {
            Integer[] arr = s.ctx.blockTerminals.get(pos);
            if (arr == null) return Double.NaN;
            Integer aa = null, bb = null;
            for (Integer id : arr) {
                if (id == null) continue;
                if (aa == null) aa = id;
                else if (bb == null) { bb = id; break; }
            }
            if (aa == null || bb == null) return Double.NaN;
            a = aa; b = bb;
        }
        if (a < 0 || b < 0) return Double.NaN;
        return s.result.complex != null
                ? s.result.complex[a].sub(s.result.complex[b]).abs()
                : Math.abs(s.result.voltages[a] - s.result.voltages[b]);
    }

    /** 从已求解网络反查任意两点(posA/tA, posB/tB)相量电压【峰值】；无效 → NaN */
    public static double voltageBetweenSolve(NetworkSolve s, BlockPos posA, int tA,
                                             BlockPos posB, int tB) {
        if (s == null || posA == null || posB == null) return Double.NaN;
        int a = terminalNodeId(s, posA, tA);
        int b = terminalNodeId(s, posB, tB);
        if (a < 0 || b < 0) return Double.NaN;
        return s.result.complex != null
                ? s.result.complex[a].sub(s.result.complex[b]).abs()
                : Math.abs(s.result.voltages[a] - s.result.voltages[b]);
    }

    /**
     * 示波器（2026-08-13）：从已求解网络反查方块指定端子的【每频率相量】。
     * 多频叠加（toneVoltages）→ 每频率 {freq, amp, phase}；单频 → 1 个分量；
     * 失败 → 空列表。按频率升序。
     */
    public static java.util.List<double[]> scopeToneFromSolve(NetworkSolve s, BlockPos pos, int t) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (s == null || pos == null || t < 0) return out;
        int id = terminalNodeId(s, pos, t);
        if (id < 0) return out;
        SolveResult r = s.result;
        if (r == null) return out;
        if (r.toneVoltages != null && !r.toneVoltages.isEmpty()) {
            for (java.util.Map.Entry<Double, Complex[]> e : r.toneVoltages.entrySet()) {
                if (e.getValue() == null || id >= e.getValue().length) continue;
                Complex v = e.getValue()[id];
                if (v == null) continue;
                double a = v.abs();
                if (a < 1e-6) continue; // 忽略可忽略分量
                out.add(new double[]{e.getKey(), a, Math.toDegrees(Math.atan2(v.im, v.re))});
            }
        } else if (r.complex != null && id < r.complex.length && r.complex[id] != null) {
            Complex v = r.complex[id];
            out.add(new double[]{s.freq > 0 ? s.freq : 0, v.abs(),
                    Math.toDegrees(Math.atan2(v.im, v.re))});
        } else if (r.voltages != null && id < r.voltages.length) {
            out.add(new double[]{s.freq > 0 ? s.freq : 0, Math.abs(r.voltages[id]), 0});
        }
        out.sort(java.util.Comparator.comparingDouble(a -> a[0]));
        return out;
    }

    /**
     * 方块指定端子 → 引擎节点 id。方式1（首选）：方块 ElectricBehaviour 的
     * 端子节点对象 → nodeToEngine 直接映射——处理【代理方块】（设备连接器：
     * 端子节点委托到被代理设备，pos=设备方块，pos+terminal 猜测会失败）。
     * 方式2（兜底）：blockTerminals[pos][t]。无效返回 -1。
     */
    private static int terminalNodeId(NetworkSolve s, BlockPos pos, int t) {
        if (s == null || pos == null || t < 0) return -1;
        try {
            if (s.level != null
                    && s.level.getBlockEntity(pos) instanceof ElectricBlockEntity ebe) {
                ElectricBehaviour beh = ebe.getElectricBehaviour();
                if (beh != null) {
                    OwnedFloatingNode n = beh.getTerminal(t);
                    if (n != null) {
                        Integer id = s.ctx.nodeToEngine.get(n);
                        if (id != null) return id;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return nodeAt(s.ctx.blockTerminals.get(pos), t);
    }

    /** 从已求解网络反查导线【相量电流】【峰值】；无效 → NaN */
    public static double wireCurrentSolve(NetworkSolve s, BaseWireEntity wire, double frequency) {
        if (s == null || wire == null) return Double.NaN;
        try {
            IWireEndpoint ep1 = wire.getEndpoint1();
            IWireEndpoint ep2 = wire.getEndpoint2();
            int n1 = endpointNode(s, ep1);
            int n2 = endpointNode(s, ep2);
            if (n1 < 0 || n2 < 0 || n1 == n2) {
                dbgWire("node-lookup-fail n1={} n2={} ep1={} ep2={} netNodes={}",
                        n1, n2,
                        ep1 == null ? "null" : ep1.getClass().getSimpleName(),
                        ep2 == null ? "null" : ep2.getClass().getSimpleName(),
                        s.ctx.blockTerminals.size());
                return Double.NaN;
            }
            double vDiff = s.result.complex != null
                    ? s.result.complex[n1].sub(s.result.complex[n2]).abs()
                    : Math.abs(s.result.voltages[n1] - s.result.voltages[n2]);
            double r = 0;
            double l = 0;
            if (wire instanceof BaseWireEntity bwe) {
                r = bwe.getResistance();
            } else {
                DeviceWire dw = DeviceWire.of(wire);
                r = dw.resistance >= 0 ? dw.resistance : 0;
                l = dw.inductance;
            }
            double x = 2 * Math.PI * frequency * l;
            double z = Math.hypot(r, x);
            if (z <= 1e-9) {
                dbgWire("z-zero r={} l={} freq={}", r, l, frequency);
                return Double.NaN;
            }
            double i = vDiff / z;
            if (++dbgWireCounter % 10 == 0) {
                dbgWire("ok n1={} n2={} vDiff={} r={} z={} i={}",
                        n1, n2, String.format("%.3f", vDiff),
                        String.format("%.4f", r), String.format("%.4f", z),
                        String.format("%.4f", i));
            }
            return i;
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.debug("PhasorEngine.wireCurrentSolve failed", t);
            return Double.NaN;
        }
    }

    private static int dbgWireCounter;

    /**
     * 单点电流（2026-08-15 用户要求：万用表单点接线 → 测流经该点的电流）。
     * <p>⚠ 2026-08-16 语义修正：原按 KCL 相量和（流入-流出）计算 → 稳态节点
     * 进出抵消 → 中间节点/设备端子恒≈0（用户报"电流很低 7µA"实为数值残差）。
     * 正确语义 = 【支路电流绝对值和的一半】：
     *   - 单回路串联节点（度 2）→ = 回路电流（|I|+|I|)/2 = |I| ✓
     *   - 分叉节点（度 d）→ 主干电流（(I主+I支1+I支2)/2 = I主）✓
     * 与电流钳表语义一致（测量"经过该点"的流通电流幅值）。
     * 支路 = 求解 ctx 中所有 elements（导线段 WireComposite 展开为电阻、设备
     * 展开为基础元件）——复合元件已展开进 {@link Network#elements()}。
     * 多端口耦合元件（理想变压器/互感）支路电流无法独立算 → 跳过。
     */
    public static double nodeCurrentSolve(NetworkSolve s, BlockPos pos, int t, double freq) {
        if (s == null || pos == null || t < 0) return Double.NaN;
        try {
            int id = terminalNodeId(s, pos, t);
            if (id < 0) {
                dbgWire("nodeCurrent node-lookup-fail pos={} t={}", pos, t);
                return Double.NaN;
            }
            SolveResult r = s.result;
            boolean complexMode = r != null && r.complex != null;
            double totalMag = 0;
            boolean any = false;
            for (Element el : s.ctx.network.elements()) {
                int a = el.nodeA(), b = el.nodeB();
                if (a != id && b != id) continue;
                Complex iab = branchCurrent(el, r, freq, complexMode);
                if (iab == null) continue; // 无法独立算（多端口耦合/零导纳）→ 跳过
                any = true;
                // 支路电流幅值（不计方向）→ 总和一半 = 该点流通电流
                totalMag += iab.abs();
            }
            if (!any) return Double.NaN;
            double mag = totalMag / 2.0;
            if (++dbgWireCounter % 10 == 0) {
                dbgWire("nodeCurrent pos={} t={} node={} i={} (Σ|支路|/2, {} 支路)",
                        pos, t, id, String.format("%.4f", mag),
                        complexMode ? "相量" : "实数");
            }
            return mag;
        } catch (Throwable e) {
            CryptandNeoForge.WAF_LOGGER.debug("PhasorEngine.nodeCurrentSolve failed", e);
            return Double.NaN;
        }
    }

    /**
     * 等效电阻测量（2026-08-19 手持电阻表；测试电流法）：
     * <ol>
     *   <li>构建 A/B 所在网络的测量上下文（{@link #buildForBlocksMeasurement}）</li>
     *   <li>构建【直流测试网络】：零化所有独立源（电压源 → 只留内阻电阻；
     *       电流源/受控源 → 开路；电感/电容原样——直流 stamp 下 L≈短路、
     *       C≈开路，天然正确）</li>
     *   <li>在 A-B 引擎节点间注入 1A 直流测试电流源</li>
     *   <li>直流求解 → R = |V_A − V_B| / 1A</li>
     * </ol>
     * 目标无效/不在同一网络（开路）→ NaN；同电位点 → 0。
     *
     * @return 等效电阻（Ω）；失败 NaN
     */
    public static double resistanceBetween(Level level, BlockPos posA, int tA,
                                           BlockPos posB, int tB) {
        if (level == null || posA == null || posB == null) return Double.NaN;
        try {
            PhasorNetworkContext ctx = buildForBlocksMeasurement(
                    level, java.util.List.of(posA, posB), java.util.List.of(), 0);
            if (ctx == null || ctx.network == null) return Double.NaN;
            Integer[] na = ctx.blockTerminals.get(posA);
            Integer[] nb = ctx.blockTerminals.get(posB);
            if (na == null || nb == null) return Double.NaN;
            if (tA < 0 || tA >= na.length || na[tA] == null
                    || tB < 0 || tB >= nb.length || nb[tB] == null) {
                return Double.NaN;
            }
            int a = na[tA];
            int b = nb[tB];
            int n = ctx.network.nodeCount();
            if (a < 0 || b < 0 || a >= n || b >= n) return Double.NaN;
            if (a == b) return 0.0; // 同电位点 → 0Ω

            // 直流测试网络：节点布局与原网络一致（NetworkSnapshot 按 nodeCount 装配）
            Network testNet = new Network();
            testNet.frequency = 0;
            testNet.dt = ctx.network.dt;
            for (int i = 0; i < n; i++) testNet.addNode();
            for (Element e : ctx.network.elements()) {
                if (e == null) continue;
                Element t = zeroSourceForResistance(e);
                if (t != null) testNet.addElement(t);
            }
            // 2026-08-20 修复"电机测 10MΩ"：设备复合元件（电机/加热器等，经
            // addComposite 进 composites 而非 elements）的内部电阻（EMF 源内阻 /
            // 绕组 R-L）不在 elements() 里 → 测试网络只剩 GMIN 兜底 → 10MΩ。
            // 必须 decompose 展开复合元件，其基础元件（AcVoltageSource 内阻、
            // Resistor、Inductor）一并装入测试网络。
            for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce :
                    ctx.network.composites()) {
                if (ce == null) continue;
                try {
                    for (Element sub : ce.decompose()) {
                        if (sub == null) continue;
                        Element t = zeroSourceForResistance(sub);
                        if (t != null) testNet.addElement(t);
                    }
                } catch (Throwable ignored) {
                }
            }
            testNet.addElement(new CurrentSource(a, b, 1.0)); // 注入 1A 测试电流

            SolveResult res = solve(testNet);
            if (res == null || res.voltages == null) return Double.NaN;
            if (a >= res.voltages.length || b >= res.voltages.length) return Double.NaN;
            double v = Math.abs(res.voltages[a] - res.voltages[b]);
            return v; // R = V / 1A
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /** 源零化（电阻测量用）：电压源 → 内阻电阻（短路源、留内阻）；
     *  电流源/受控源 → null（开路）；电感/电容 → 全新实例（清空能量历史，
     *  直流测试下 L≈短路、C≈开路）；其余元件原样保留。 */
    private static Element zeroSourceForResistance(Element e) {
        if (e instanceof DcVoltageSource vs) {
            return new Resistor(e.nodeA(), e.nodeB(), vs.seriesResistance);
        }
        if (e instanceof AcVoltageSource av) {
            return new Resistor(e.nodeA(), e.nodeB(), av.seriesResistance);
        }
        if (e instanceof WaveformSource wf) {
            return wf.voltage
                    ? new Resistor(e.nodeA(), e.nodeB(), wf.seriesResistance)
                    : null;
        }
        if (e instanceof Inductor ind) {
            // 全新实例（iPrev=0）：直流测试下电感 = 短路，不带历史电流
            return new Inductor(e.nodeA(), e.nodeB(), ind.inductance);
        }
        if (e instanceof Capacitor cap) {
            // 全新实例（vPrev=0）：直流测试下电容 = 开路，不带历史电荷
            return new Capacitor(e.nodeA(), e.nodeB(), cap.capacitance);
        }
        if (e.isSource()) return null; // 电流源 / 受控源 → 开路
        return e;
    }

    /** 支路电流相量 I_ab（a→b 方向）；按元件类型解析。不可独立计算（耦合/
     *  零导纳）→ null（调用方跳过）。实数模式时结果放在 re（im=0）。 */
    private static Complex branchCurrent(Element el, SolveResult r, double freq,
                                         boolean complexMode) {
        try {
            double[] p = el.params();
            int a = el.nodeA(), b = el.nodeB();
            Complex vab = vDiffOf(r, a, b, complexMode);
            if (vab == null) return null;
            double omega = 2 * Math.PI * freq;
            switch (el.type()) {
                case RESISTOR: {
                    double rr = p.length > 0 ? p[0] : 0;
                    if (rr <= 1e-12) return null;
                    return vab.div(new Complex(rr, 0));
                }
                case CAPACITOR: {
                    double c = p.length > 0 ? p[0] : 0;
                    if (c <= 0 || omega <= 1e-9) return Complex.ZERO; // 直流开路
                    // jωC·V
                    return new Complex(-omega * c * vab.im, omega * c * vab.re);
                }
                case INDUCTOR: {
                    double l = p.length > 0 ? p[0] : 0;
                    if (l <= 0 || omega <= 1e-9) return null; // 直流短路（电流由外电路定）
                    return vab.div(new Complex(0, omega * l));
                }
                case DC_VOLTAGE_SOURCE: {
                    double v = p.length > 0 ? p[0] : 0;
                    double rs = p.length > 1 ? Math.max(p[1], 1e-9) : 1e-9;
                    // I = (Va - Vb - V)/Rs
                    return vab.sub(new Complex(v, 0)).div(new Complex(rs, 0));
                }
                case AC_VOLTAGE_SOURCE: {
                    double amp = p.length > 0 ? p[0] : 0;
                    double ph = p.length > 1 ? Math.toRadians(p[1]) : 0;
                    double rs = p.length > 2 ? Math.max(p[2], 1e-9) : 1e-9;
                    Complex vs = Complex.fromPolar(amp, ph);
                    return vab.sub(vs).div(new Complex(rs, 0));
                }
                case CURRENT_SOURCE: {
                    double amp = p.length > 0 ? p[0] : 0;
                    return new Complex(amp, 0);
                }
                case WAVEFORM_SOURCE: {
                    // params = [isVoltage, amp, freq, phaseDeg, duty, offset, seriesR]
                    boolean isVoltage = p.length > 0 && p[0] > 0.5;
                    double amp = p.length > 1 ? p[1] : 0;
                    if (!isVoltage) return new Complex(amp, 0);
                    double ph = p.length > 3 ? Math.toRadians(p[3]) : 0;
                    double rs = p.length > 6 ? Math.max(p[6], 1e-9) : 1e-9;
                    return vab.sub(Complex.fromPolar(amp, ph)).div(new Complex(rs, 0));
                }
                default:
                    return null; // 理想变压器/互感等多端口耦合 → 不可独立算
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 求解结果中 a-b 节点电压差相量；实数模式结果放 re。节点越界/无值 → null */
    private static Complex vDiffOf(SolveResult r, int a, int b, boolean complexMode) {
        try {
            if (r == null) return null;
            if (complexMode && r.complex != null
                    && a >= 0 && a < r.complex.length && b >= 0 && b < r.complex.length
                    && r.complex[a] != null && r.complex[b] != null) {
                return r.complex[a].sub(r.complex[b]);
            }
            if (r.voltages != null && a >= 0 && a < r.voltages.length
                    && b >= 0 && b < r.voltages.length) {
                return new Complex(r.voltages[a] - r.voltages[b], 0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 变压器详细相量诊断（节流） */
    private static volatile long tfDetailDbgLast = 0;

    /** 导线电流诊断（节流） */
    private static volatile long dbgWireLastLog = 0;
    private static void dbgWire(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - dbgWireLastLog < 1000) return;
        dbgWireLastLog = now;
        try {
            CryptandNeoForge.WAF_LOGGER.info("[WireCur] " + fmt, args);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 服务端网络级：求任意两点 (posA/tA, posB/tB) 间相量电压【峰值幅值】（万用表跨方块测量）。
     * 从 posA 端子网络出发 buildContextFromNetwork（跨变压器合并收集，零导线 BFS）→
     * 求解 → 两点节点电压差。posB 不在同一电路（含跨变压器）→ NaN。
     */
    public static double voltageBetween(Level level, BlockPos posA, int tA,
                                        BlockPos posB, int tB, double frequency, long gameTime) {
        init();
        if (level == null || posA == null || posB == null || level.isClientSide) return Double.NaN;
        try {
            if (!(level.getBlockEntity(posA) instanceof ElectricBlockEntity ebeA)) return Double.NaN;
            ElectricBehaviour behA = ebeA.getElectricBehaviour();
            if (behA == null) return Double.NaN;
            for (int t = 0; t < 4; t++) {
                OwnedFloatingNode node = behA.getTerminal(t);
                if (node == null) continue;
                ElectricalNetwork net = node.getNetwork();
                if (net == null) continue;
                CacheEntry e = getOrSolve(level, net, frequency, gameTime);
                if (e == null) return Double.NaN;
                int a = nodeAt(e.ctx.blockTerminals.get(posA), tA);
                int b = nodeAt(e.ctx.blockTerminals.get(posB), tB);
                if (a < 0 || b < 0) return Double.NaN;
                return e.result.complex != null
                        ? e.result.complex[a].sub(e.result.complex[b]).abs()
                        : Math.abs(e.result.voltages[a] - e.result.voltages[b]);
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.debug("PhasorEngine.voltageBetween failed", t);
        }
        return Double.NaN;
    }

    /**
     * 服务端网络级：求导线【相量电流】【峰值幅值】（万用表电流模式，完全相量，零 BFS）。
     * 从导线一端方块端子网络 buildContextFromNetwork（跨变压器合并）→ 两端子电压差 / 导线阻抗
     * |Z| = sqrt(R² + (2πf·L)²)。
     */
    public static double wireCurrent(Level level, BaseWireEntity wire, double frequency, long gameTime) {
        init();
        if (level == null || wire == null || level.isClientSide) return Double.NaN;
        try {
            IWireEndpoint ep1 = wire.getEndpoint1();
            IWireEndpoint ep2 = wire.getEndpoint2();
            if (!(ep1 instanceof BlockWireEndpoint b1) || !(ep2 instanceof BlockWireEndpoint b2)) {
                return Double.NaN;
            }
            BlockPos p1 = b1.getPos();
            BlockPos p2 = b2.getPos();
            if (!(level.getBlockEntity(p1) instanceof ElectricBlockEntity ebeA)) return Double.NaN;
            ElectricBehaviour behA = ebeA.getElectricBehaviour();
            if (behA == null) return Double.NaN;
            for (int t = 0; t < 4; t++) {
                OwnedFloatingNode node = behA.getTerminal(t);
                if (node == null) continue;
                ElectricalNetwork net = node.getNetwork();
                if (net == null) continue;
                CacheEntry e = getOrSolve(level, net, frequency, gameTime);
                if (e == null) return Double.NaN;
                int n1 = nodeAt(e.ctx.blockTerminals.get(p1), b1.getTerminal());
                int n2 = nodeAt(e.ctx.blockTerminals.get(p2), b2.getTerminal());
                if (n1 < 0 || n2 < 0 || n1 == n2) {
                    return Double.NaN;
                }
                double vDiff = e.result.complex != null
                        ? e.result.complex[n1].sub(e.result.complex[n2]).abs()
                        : Math.abs(e.result.voltages[n1] - e.result.voltages[n2]);
                // ⚠ 导线实体是 BaseWireEntity（非内部 ElectricWire）。
                //   DeviceWire.of 只识别 ElectricWire/SwitchedWire/LRSeriesWire 等
                //   内部对象 → 对 BaseWireEntity 返回 resistance=-1 → z=0 → NaN。
                //   直接用 BaseWireEntity.getResistance()（float，导线每米电阻）。
                double r = 0;
                double l = 0;
                if (wire instanceof BaseWireEntity bwe) {
                    r = bwe.getResistance();
                } else {
                    DeviceWire dw = DeviceWire.of(wire);
                    r = dw.resistance >= 0 ? dw.resistance : 0;
                    l = dw.inductance;
                }
                double x = 2 * Math.PI * frequency * l;
                double z = Math.hypot(r, x);
                if (z <= 1e-9) {
                    return Double.NaN;
                }
                return vDiff / z;
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.debug("PhasorEngine.wireCurrent failed", t);
        }
        return Double.NaN;
    }

    /** 从求解结果反查方块指定端子(t1,t2)的相量跨压幅值；t1/t2<0 → 前两个非空端子 */
    private static double voltageFrom(PhasorNetworkContext ctx, SolveResult res,
                                      BlockPos pos, int t1, int t2) {
        Integer[] arr = ctx.blockTerminals.get(pos);
        if (arr == null) return 0;
        int a, b;
        if (t1 >= 0 && t2 >= 0 && t1 < arr.length && t2 < arr.length
                && arr[t1] != null && arr[t2] != null) {
            a = arr[t1];
            b = arr[t2];
        } else {
            Integer aa = null, bb = null;
            for (Integer id : arr) {
                if (id == null) continue;
                if (aa == null) aa = id;
                else if (bb == null) { bb = id; break; }
            }
            if (aa == null || bb == null) return 0;
            a = aa;
            b = bb;
        }
        if (res.complex != null) {
            Complex ca = res.complex[a];
            Complex cb = res.complex[b];
            double v = ca.sub(cb).abs(); // 相量差幅值（峰值）
            // 微小数值噪声（孤立/悬空节点 GMIN=1e-7 在 LU 分解中的浮点泄漏，
            // 实测 0.365mV @ 50Hz）→ 清零。正常电路电压远大于 1mV，不受影响。
            return Math.abs(v) < 1e-3 ? 0 : v;
        }
        double v = Math.abs(res.voltages[a] - res.voltages[b]); // DC
        return Math.abs(v) < 1e-3 ? 0 : v;
    }

    /** 端子索引 → 引擎节点 id；无效返回 -1 */
    private static int nodeAt(Integer[] arr, int t) {
        if (arr == null || t < 0 || t >= arr.length) return -1;
        Integer id = arr[t];
        return id == null ? -1 : id;
    }

    /** 无回路网络的全 0 结果（所有节点 0V，跳过求解）。 */
    private static SolveResult zeroResultOf(PhasorNetworkContext ctx) {
        int nc = 0;
        for (Integer id : ctx.nodeToEngine.values()) nc = Math.max(nc, id + 1);
        return new SolveResult(new double[nc], true, 0, 0, SolveMode.REAL_DC);
    }

    /**
     * 开路支路电压跟随：把 ctx.openTerminal 标记的悬空节点电压设为参考节点电压。
     * <p>
     * 物理：开路支路无电流 → 两端等电位。无源两端口元件（R/C/L）一端悬空时，
     * 相量 MNA 会把悬空端判为 GMin→0V → PowerGrid 原版元件 internalWire
     * 按 (V-0)/R 算出虚假大电流 → 烧毁（变压器次级"一根导线+开路电阻"场景）。
     * 修正后悬空端跟随另一端 → 原版 internalWire (V-V)/R=0 不烧、变压器 iS=0 不发热。
     * AC 修正相量；DC 修正实电压。
     */
    static void applyOpenTerminals(SolveResult res, PhasorNetworkContext ctx) {
        if (res == null || ctx == null || ctx.openTerminal.isEmpty()) return;
        try {
            if (res.complex != null) {
                for (Map.Entry<Integer, Integer> e : ctx.openTerminal.entrySet()) {
                    int from = e.getKey(), to = e.getValue();
                    if (from >= 0 && from < res.complex.length
                            && to >= 0 && to < res.complex.length) {
                        res.complex[from] = res.complex[to];
                    }
                }
            }
            if (res.voltages != null) {
                for (Map.Entry<Integer, Integer> e : ctx.openTerminal.entrySet()) {
                    int from = e.getKey(), to = e.getValue();
                    if (from >= 0 && from < res.voltages.length
                            && to >= 0 && to < res.voltages.length) {
                        res.voltages[from] = res.voltages[to];
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 求解后：按【真实变压器算法】计算损耗 → TransformerHeatStore。
     * 模型（互感建模，2026-08-11）：
     *   原边 pa1 --rCp-- x(a1) --(rCore ∥ 互感绕组1)-- pa2，
     *   副边 b1(w) --rCs-- pb1，互感绕组2 (w, pb2)（MutualInductor 对称耦合）：
     *   - 初级总电流 I_p = (Vpa1 - Vx) / rCp（相量，互感耦合已由 MutualInductor
     *     的 Y 矩阵体现，铜阻电流即绕组总电流：磁化+负载反射）
     *   - 铁损电流 I_core = (Vx - Vpa2) / rCore——铁损 P = |I_core|²·rCore/2
     *   - 次级电流 I_s = (Vw - Vpb1) / rCs（副边铜阻电流）
     *   - 铜损 P_cu = |I_p|²·rCp/2 + |I_s|²·rCs/2；峰值→平均 /2
     * 次级悬空 → I_s≈0 → 铜损≈0 → 不发热/无声（解决"只有一根线也有声音"）。
     */
    /** 统一变压器发热处理（round 级，2026-08-12）：变压器【隔开】原边/副边网络，
     *  同一 pos 会在多个网络 ctx 的 transformerModels 出现。每 tick 每个 pos 只选
     *  【最高频率】网络处理一次（AC 优先；全 DC → 清状态 + 自然冷却）——
     *  满足"计算/发热放在两个网络的任意一处，但只能有一处"。 */
    static void computeTransformerHeatUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<BlockPos, Object[]> best = new java.util.HashMap<>();
        for (Object[] o : nets) {
            if (o == null || o.length < 3) continue;
            PhasorNetworkContext ctx = (PhasorNetworkContext) o[0];
            if (ctx == null || ctx.transformerModels.isEmpty()) continue;
            double freq = (Double) o[2];
            for (BlockPos pos : ctx.transformerModels.keySet()) {
                Object[] cur = best.get(pos);
                if (cur == null || (Double) cur[2] < freq) {
                    best.put(pos, new Object[]{ctx, o[1], freq});
                }
            }
        }
        for (java.util.Map.Entry<BlockPos, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            try {
                computeTransformerHeatOne(level, (PhasorNetworkContext) o[0],
                        (SolveResult) o[1], (Double) o[2], e.getKey());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 单变压器发热处理（每 tick 每 pos 一次） */
    private static void computeTransformerHeatOne(Level level, PhasorNetworkContext ctx,
            SolveResult res, double frequency, BlockPos pos) {
        if (ctx == null || res == null) return;
        double minHz = ConfigLoad.TRANSFORMER_MIN_FREQUENCY_HZ.get();
        // 直流（freq=0）或低频（< 最低通过频率）→ DC 隔离：清状态（无声/停热）+ 自然冷却
        if (res.complex == null || frequency < minHz) {
            TransformerHeatStore.resetElectrical(pos);
            com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel thc =
                    TransformerHeatStore.getThermal(pos);
            if (thc != null) thc.advance(0, System.nanoTime());
            return;
        }
        PhasorNetworkContext.TransformerModel m = ctx.transformerModels.get(pos);
        if (m == null) return;
        int n = res.complex.length;
                if (m.pa1 < 0 || m.x < 0 || m.pa2 < 0 || m.w < 0 || m.pb1 < 0 || m.pb2 < 0
                        || m.pa1 >= n || m.x >= n || m.pa2 >= n
                        || m.w >= n || m.pb1 >= n || m.pb2 >= n) {
                    // 副边支路被剔除（次级端子悬空/无回路）→ 次级电流=0：
                    // 全 0 put → STATE.remove → 次级电流立即归 0 → 声音停止、发热停止。
                    // （否则 STATE 残留旧 iS/iP → "断开负载仍嗡鸣"）
                    TransformerHeatStore.put(pos, 0, 0, 0, 0, 0);
                    // 温度以 0 功率自然冷却（保留热惯性，不冻结不突变）
                    com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th0 =
                            TransformerHeatStore.getThermal(pos);
                    if (th0 != null) th0.advance(0, System.nanoTime());
                    return;
                }
                double omega = 2 * Math.PI * frequency;
                // 原边支路电流：pa1 → x 经 (rCp + jω·lLp) 串联阻抗（真实铜阻+漏感）
                Complex zP = new Complex(m.rCp, omega * m.lLp);
                Complex vP = res.complex[m.pa1].sub(res.complex[m.x]);
                Complex iP = zP.abs() < 1e-12 ? Complex.ZERO : vP.div(zP);
                // 铁损电流：x → pa2 经 rCore（励磁支路）
                Complex iCore = res.complex[m.x].sub(res.complex[m.pa2]).scale(1.0 / m.rCore);
                // 副边支路电流：w → pb1 经 (rCs + jω·lLs) 串联阻抗。
                Complex zS = new Complex(m.rCs, omega * m.lLs);
                Complex vS = res.complex[m.w].sub(res.complex[m.pb1]);
                Complex iS = zS.abs() < 1e-12 ? Complex.ZERO : vS.div(zS);
                // ⚠ 副边电流物理校验（2026-08-12）：副边开路/悬空时 pb1 是浮动节点
                // （无闭合回路），求解器给浮动电压 → (Vw−Vpb1)/zS 算出虚假巨大 iS
                // （日志：iP=0.02A 但 iS=533A → 铜损 71106W → 空载瞬间爆炸）。
                // 物理：理想变压器 I2 = I1/ratio（反射）。若 |iS| 远超反射值（8 倍，
                // 留短路余量）→ 无真实负载电流 → 置 0（开路不发热/不响）。
                double maxIS = Math.max(iP.abs() / Math.max(m.ratio, 1e-6) * 8.0, 0.05);
                if (iS.abs() > maxIS) iS = Complex.ZERO;
                // 诊断（节流）：副边电流来源（各端子电压/压差）——定位"接开路导线
                // 到绘图仪后电机误转"：副边端子是否意外有压差（形成电流路径）。
                if (level != null && level.getGameTime() % 40 == 0) {
                    try {
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[TfDiag] pos={} freq={} ratio={} pa1={} x={} pa2={} w={} pb1={} pb2={} "
                                        + "vpa1={} vx={} vpa2={} vw={} vpb1={} vpb2={} iP={} iS={} maxIS={}",
                                pos, String.format("%.1f", frequency), String.format("%.2f", m.ratio),
                                m.pa1, m.x, m.pa2, m.w, m.pb1, m.pb2,
                                tfb(res.complex, m.pa1), tfb(res.complex, m.x),
                                tfb(res.complex, m.pa2), tfb(res.complex, m.w),
                                tfb(res.complex, m.pb1), tfb(res.complex, m.pb2),
                                tfb2(iP), tfb2(iS), String.format("%.3f", maxIS));
                    } catch (Throwable ignored) {
                    }
                }
                // 真实铜损（原边+副边）+ 铁损；峰值 → 平均功率 /2
                double cuP = iP.abs() * iP.abs() * m.rCp / 2.0;
                double cuS = iS.abs() * iS.abs() * m.rCs / 2.0;
                double coreLoss = iCore.abs() * iCore.abs() * m.rCore / 2.0;
                TransformerHeatStore.put(pos, cuP + cuS, coreLoss, iP.abs(), iS.abs(), frequency);
                // 温度模型（静态持久：跨网络重建保留温度与冷却）。
                // 2026-08-20 用户要求"移除变压器的单独支持，改成通用支持"：
                // 冷却倍率不再在此单独反射同步——统一由 FanCoolingMixin 在吹风
                // 检测时直接设置（TransformerHeatStore 温度模型，与普通设备一致）。
                com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                        TransformerHeatStore.thermalFor(pos);
                // 温度推进用【瞬时功率】：平滑统一由 ThermalModel 内部 EMA 处理
                // （避免双重平滑响应过慢）；解析解 + 内部平滑 → 温度稳定趋稳。
                th.advance(cuP + cuS + coreLoss, System.nanoTime());
                // 诊断：节流打印变压器温度曲线（服务端）
                if (level != null && level.getGameTime() % 100 == 0) {
                    try {
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[TfHeat] pos={} P={}+{} T={}°C G={}",
                                pos,
                                String.format("%.1f", cuP + cuS),
                                String.format("%.1f", coreLoss),
                                String.format("%.1f", th.tempCelsius()),
                                String.format("%.2f", th.effectiveConductance()));
                    } catch (Throwable ignored) {
                    }
                }
    }

    /** 相量幅值格式化（诊断用） */
    private static String tfb(com.hdf.cryptand.circuitsimulation.solver.Complex[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return "-";
        try {
            return String.format("%.1f", arr[idx].abs());
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /** 相量幅值格式化（诊断用） */
    private static String tfb2(com.hdf.cryptand.circuitsimulation.solver.Complex c) {
        return c == null ? "-" : String.format("%.3f", c.abs());
    }

    /**
     * 求解后：按设备复合模型（ThermalDevice）算平均损耗 → 推进温度模型
     * （DeviceThermalStore 持久）。电机/加热器/电磁铁/灯具等所有带温度设备：
     * 损耗由模型 {@code lossPower} 提供（如绕组铜耗 I²·R/2），
     * 风扇冷却检测提高散热系数（与变压器一致）。
     */
    static void computeDeviceHeatUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<BlockPos, Object[]> best = new java.util.HashMap<>();
        for (Object[] o : nets) {
            if (o == null || o.length < 3) continue;
            PhasorNetworkContext ctx = (PhasorNetworkContext) o[0];
            if (ctx == null) continue;
            SolveResult ctxRes = (SolveResult) o[1];
            // 2026-08-22 供电/亮度：对所有参与求解的设备（含无发热设备如灯座）写
            // 设备电流缓存——先前只写 deviceThermals（发热设备），灯座 SwitchModel
            // 非 ThermalDevice → 电流恒 0 → 灯泡不亮/不烧。
            try {
                if (ctx.blockTerminals != null && ctx.network != null) {
                    for (BlockPos p : ctx.blockTerminals.keySet()) {
                        DeviceCurrent.write(p, ctx.network, ctxRes);
                    }
                }
            } catch (Throwable ignored) {
            }
            if (ctx.deviceThermals == null || ctx.deviceThermals.isEmpty()) continue;
            double freq = (Double) o[2];
            for (BlockPos pos : ctx.deviceThermals.keySet()) {
                Object[] cur = best.get(pos);
                if (cur == null || (Double) cur[2] < freq) {
                    best.put(pos, new Object[]{ctx, o[1], freq});
                }
            }
        }
        for (java.util.Map.Entry<BlockPos, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            PhasorNetworkContext bctx = (PhasorNetworkContext) o[0];
            SolveResult bres = (SolveResult) o[1];
            try {
                computeDeviceHeatOne(level, bctx, bres, (Double) o[2], e.getKey());
                // 2026-08-22 用户需求【供电判断走电流】：求解 round 计算设备端子
                // 电流（内部元件电流）写入组装器 DeviceCache，主线程 tick 检测。
                DeviceCurrent.write(e.getKey(), bctx.network, bres);
            } catch (Throwable ignored) {
            }
        }
    }

    /** DC 超频温度惩罚基准功率（W）：惩罚 = (freq/maxFreq)³ − 1 × 本基准。
     *  轻微超频(≈1) → 小惩罚；频率越高指数越大（2 倍超频 → 8×基准）。 */
    private static final double DC_PENALTY_BASE_W = 2000.0;

    /** 单设备发热处理（每 tick 每 pos 一次）：温度推进由后台 solveAll 的伪时域
     *  推进（advanceState，固定节拍步长 dt）完成。
     *  2026-08-21 用户要求【全部接管原版算法，不写回原版内容】：不再反射写回原版
     *  ThermalBehaviour（护目镜/方块温度计等读取方统一由 Cryptand 接管，直接读
     *  DeviceThermalStore/WireThermalStore）。本方法只注入【DC 超频惩罚】，不写回原版。
     *  <p>2026-08-22 用户需求：原版 DC 设备（灯/风扇/铃/加热器等）只支持 DC——
     *  网络频率超过 dcDeviceMaxFrequencyHz 时温度【指数型】惩罚：轻微超频不明显、
     *  频率越高越严重（按超额倍数 o³ 放大）。0 = 关闭惩罚。 */
    private static void computeDeviceHeatOne(Level level, PhasorNetworkContext ctx,
            SolveResult res, double frequency, BlockPos pos) {
        try {
            if (!DeviceThermalStore.isDcOnly(pos)) return;
            double maxF = ConfigLoad.DC_DEVICE_MAX_FREQUENCY_HZ.get();
            if (maxF <= 0 || frequency <= maxF) return; // 未超频/关闭 → 无惩罚
            double over = frequency / maxF;          // >1：超频倍数
            double factor = Math.pow(over, 3);       // 指数放大（2x 超频 → 8）
            double penaltyW = (factor - 1) * DC_PENALTY_BASE_W;
            // J/次推进（tick≈0.05s）；温度推进仍由引擎伪时域完成
            DeviceThermalStore.thermalFor(pos).addHeat(penaltyW * 0.05);
        } catch (Throwable ignored) {
        }
    }

    /** 统一储能推进（round 级，2026-08-12 用户要求：电容/电池能量模型）：
     *  储能复合模型（{@code EnergyDevice}，电容/电池）求解后从端口相量电压
     *  同步储能电荷（时间相关变量绑定）——隔直通交由基础元件相量导纳提供，
     *  瞬态/DC 下相量求不出时由电荷状态驱动电压（类似电池）。每 pos 选最高
     *  频率网络处理一次（与变压器/设备/导线一致）。 */
    static void computeEnergyUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<BlockPos, Object[]> best = new java.util.HashMap<>();
        for (Object[] o : nets) {
            if (o == null || o.length < 3) continue;
            PhasorNetworkContext ctx = (PhasorNetworkContext) o[0];
            if (ctx == null || ctx.energyDevices == null || ctx.energyDevices.isEmpty()) continue;
            double freq = (Double) o[2];
            for (java.util.Map.Entry<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice> e
                    : ctx.energyDevices.entrySet()) {
                Object[] cur = best.get(e.getKey());
                if (cur == null || (Double) cur[1] < freq) {
                    best.put(e.getKey(), new Object[]{e.getValue(), freq, o[1]});
                }
            }
        }
        for (java.util.Map.Entry<BlockPos, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            try {
                com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice ed =
                        (com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice) o[0];
                com.hdf.cryptand.circuitsimulation.solver.SolveResult res =
                        (com.hdf.cryptand.circuitsimulation.solver.SolveResult) o[2];
                if (ed == null || res == null || res.complex == null) continue;
                int n = res.complex.length;
                int na = ed.nodeA(), nb = ed.nodeB();
                if (na < 0 || nb < 0 || na >= n || nb >= n) continue;
                ed.syncCharge(res.complex[na], res.complex[nb]);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 统一导线段发热处理（round 级，2026-08-12）：导线连续段 = 复合元件
     *  WireComposite（Resistor + 温度模型），用统一复合元件生命周期 update()
     *  算损耗 → 推进温度（散热与发热同时，解析解）。段 key = 路径签名；
     *  同一段在多网络 ctx 出现时只选最高频率处理一次（与变压器/设备一致）。 */
    static void computeWireHeatUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<String, Object[]> best = new java.util.HashMap<>();
        java.util.Set<String> activeKeys = new java.util.HashSet<>();
        for (Object[] o : nets) {
            if (o == null || o.length < 3) continue;
            PhasorNetworkContext ctx = (PhasorNetworkContext) o[0];
            if (ctx == null || ctx.wireSegments == null || ctx.wireSegments.isEmpty()) continue;
            double freq = (Double) o[2];
            long batchHash = (o.length >= 4 && o[3] instanceof Long l) ? l : 0;
            for (com.hdf.cryptand.circuitsimulation.model.composite.WireComposite seg : ctx.wireSegments) {
                if (seg.compositeKey() == null) continue;
                activeKeys.add(seg.compositeKey());
                Object[] cur = best.get(seg.compositeKey());
                if (cur == null || (Double) cur[2] < freq) {
                    // 第5元素 = 批内冻结指纹（校验用，勿读 ctx.solveHash——被覆盖）
                    best.put(seg.compositeKey(),
                            new Object[]{ctx, o[1], freq, seg, batchHash});
                }
            }
        }
        for (java.util.Map.Entry<String, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            try {
                long bh = (o.length >= 5 && o[4] instanceof Long l) ? l : 0;
                computeWireHeatOne(level, (PhasorNetworkContext) o[0],
                        (SolveResult) o[1],
                        (com.hdf.cryptand.circuitsimulation.model.composite.WireComposite) o[3],
                        bh);
            } catch (Throwable ignored) {
            }
        }
        // 段消失清理（节流 ~5s）：拆线/烧毁后残留温度模型移除（防泄漏 + 防旧温
        // 度影响重接后的新段——新段 key 不同自然不冲突）
        long now = System.currentTimeMillis();
        if (now - WIRE_CLEANUP_LAST >= 5000) {
            WIRE_CLEANUP_LAST = now;
            try {
                WireThermalStore.retainOnly(activeKeys);
            } catch (Throwable ignored) {
            }
            try {
                com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                        .retainSegments(activeKeys);
            } catch (Throwable ignored) {
            }
        }
    }
    private static volatile long WIRE_CLEANUP_LAST;

    /** 导线段烧毁阈值（°C；与 WireThermalStore 构造最高温度 473.15K=200°C 一致） */
    private static final double WIRE_BURN_TEMP_C = 200.0;

    /** 导线段烧毁防反复冷却（2026-08-18：烧毁请求后 30s 内同段不重复——后台
     *  求解每轮检测到段温度仍 >200 会反复 requestWire → 反复重建风暴 → 卡死） */
    private static final java.util.Map<String, Long> WIRE_BURN_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long WIRE_BURN_COOLDOWN_MS = 30_000L;
    /** 烧毁详细诊断节流（2026-08-24 key 级 5s） */
    private static final java.util.Map<String, Long> WIRE_BURN_DBG_LAST =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** [WireHeat] 诊断节流（时间戳 5s——后台求解线程 gameTime 不变导致 %100 失效） */
    private static volatile long WIRE_HEAT_DBG_LAST;
    /** [WireHeatNorm] 诊断节流（每段 key 5s；正常工况核对发热/散热） */
    private static final java.util.Map<String, Long> WIRE_HEAT_NORM_LAST =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** [AdvPseudo] 诊断节流（2026-08-24） */
    private static volatile long ADV_PSEUDO_DBG_LAST;

    /**
     * 全量温度降温（2026-08-23 用户：开路/无功率 → 温度【持续计算】——按散热
     * 每 tick 驱动（ServerTickEvent.Post）；静态全局存储（导线/设备/变压器）。
     */
    public static void coolAllTemperatures(double dt) {
        try { com.hdf.cryptand.neoforge.powergrid.adapter.WireThermalStore.coolAll(dt); }
        catch (Throwable ignored) { }
        try { com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore.coolAll(dt); }
        catch (Throwable ignored) { }
        try { com.hdf.cryptand.neoforge.powergrid.adapter.TransformerHeatStore.coolAll(dt); }
        catch (Throwable ignored) { }
    }

    /** 单导线段发热处理（每 tick 每段一次）：统一复合元件生命周期
     *  WireComposite.update —— 算 I²R 损耗 → 推进温度模型。
     *  开路/悬空分支无电流（I=0）→ 自然冷却；真实回路才有电流/发热。
     *  段温度超阈值 → 【同段统一烧毁】（移除段内全部导线，2026-08-14 用户要求）。 */
    private static void computeWireHeatOne(Level level, PhasorNetworkContext ctx,
            SolveResult res,
            com.hdf.cryptand.circuitsimulation.model.composite.WireComposite seg,
            long batchHash) {
        if (ctx == null || res == null || res.complex == null || seg == null) return;
        int n = res.complex.length;
        int na = seg.nodeA(), nb = seg.nodeB();
        if (na < 0 || nb < 0 || na >= n || nb >= n) return;
        if (seg.resistance <= 1e-9) return;
        // ⚠ 2026-08-24 断点数据实锤（用户）：res.complex=[-4.23,199.97,199.99,
        // -0.0138,0]（n1≈n2=200V 但 n0=-4.23V → 线2 压降 4.2V/0.0038≈1110A 而线1
        // 压降 0.014V≈3.6A —— 同一串联环 KCL 矛盾）→ res 与当前网表节点【错配】
        //（旧轮/另一网络解被当成当前解 → 假大电流 → 误烧线）。防护：① res 节点
        // 数必须与 ctx.network 节点数一致；② res 网表指纹必须等于【批内冻结指纹】
        // batchHash（== res.networkHash，同轮同源；不能用 ctx.solveHash 字段——
        // ctx 跨轮复用，字段会被下轮覆盖 → 假 mismatch 实锤）。
        if (ctx.network != null) {
            if (res.complex.length != ctx.network.nodeCount()
                    || (batchHash != 0 && res.networkHash != batchHash)) {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                        "[WireHeatMismatch] resN={} netNodes={} hash={}/{} key={} SKIP",
                        res.complex.length, ctx.network.nodeCount(),
                        res.networkHash, batchHash,
                        seg.compositeKey());
                return;
            }
        }
        double omega = 2 * Math.PI * (ctx.frequency > 0 ? ctx.frequency : 0);
        // 统一生命周期：算损耗（I²R/2）→ 推进温度（发热与散热【一起计算】后
        // 再加减：ΔT = (P − G·(T−Tamb))/C × dt——ThermalModel.linearStep）
        seg.update(res.complex[na], res.complex[nb], omega, System.nanoTime());
        // 正常工况诊断（2026-08-23 用户“23A 温度上升很快”）：节流每 key 5s 打印
        // 电流/段R/损耗/温度——核对发热−散热平衡（金线 23A：P≈0.4W → 稳态温升
        // ≈0.2K，T≈25.2°C；若 T 快升 → 看 P 与 R 是否异常）。
        try {
            if (seg.thermal() != null) {
                double vd = res.complex[na].sub(res.complex[nb]).abs();
                double iRms = vd / seg.resistance / 1.41421356237;
                if (iRms > 0.5 && seg.thermal().tempCelsius() > 30.0) {
                    Long lastN = WIRE_HEAT_NORM_LAST.get(seg.compositeKey());
                    long nowN = System.currentTimeMillis();
                    if (lastN == null || nowN - lastN >= 5000) {
                        WIRE_HEAT_NORM_LAST.put(seg.compositeKey(), nowN);
                        double p = vd * vd / seg.resistance / 2.0;
                        // 2026-08-23 网表摘要（电机短路烧线定位）：打印段所在网表
                        // 节点数 + 全部元素（类型+端点）——确认电机 EMF 是否在本
                        // 网表（无电机元素 → 源+双线直接短路线 → 烧毁）
                        StringBuilder es = new StringBuilder();
                        int emfN = 0;
                        try {
                            if (ctx.network != null) {
                                es.append(" nodes=").append(ctx.network.nodeCount());
                                for (com.hdf.cryptand.circuitsimulation.model.Element el
                                        : ctx.network.elements()) {
                                    es.append(" [").append(el.type()).append('(')
                                            .append(el.nodeA()).append(',')
                                            .append(el.nodeB()).append(')').append(']');
                                    if (el instanceof com.hdf.cryptand.circuitsimulation.model.elements
                                            .AcVoltageSource av) {
                                        emfN++;
                                        es.append(':').append(String.format("%.1f/%.2f",
                                                av.amplitude, av.seriesResistance));
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                            es.append(" esEX");
                        }
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[WireHeatNorm] key={} T={}C i={}A R={} P={}W G={} C={}"
                                        + " | seg=({},{}) emf={}{}",
                                seg.compositeKey(),
                                String.format("%.1f", seg.thermal().tempCelsius()),
                                String.format("%.1f", iRms),
                                String.format("%.4f", seg.resistance),
                                String.format("%.2f", p),
                                String.format("%.2f", seg.thermal().conductance),
                                String.format("%.1f", seg.thermal().heatCapacity),
                                na, nb, emfN, es);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 烧毁检测：段温度超阈值 → 【先移除】段内导线（立即从自管图移除，后续
        // 求解不再含此段——否则下一轮又检测到过热 → 反复烧毁 + 重建风暴 → 卡死）
        // + 冷却防反复 → 再请求销毁（清理温度/爆炸/客户端同步）。
        // ⚠ 用户要求：移入销毁队列前必须先从网络移除该元件，确保不再被引用。
        // 诊断（2026-08-23 物理化温度定位）：段温度反常偏高（>150°C 未到烧毁）
        // 节流打印段参数——R/长度/电压差/电流/模型散热，定位"温度非常大的值"
        //（物理化后坐标/长度/模型错位）。
        try {
            if (seg.thermal() != null && seg.thermal().tempCelsius() > 150.0) {
                long now3 = System.currentTimeMillis();
                if (now3 - WIRE_HEAT_DBG_LAST >= 5000) {
                    WIRE_HEAT_DBG_LAST = now3;
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[WireHeatHi] key={} T={}C R={} G={} C={} vA={} vB={}",
                            seg.compositeKey(),
                            String.format("%.1f", seg.thermal().tempCelsius()),
                            String.format("%.4f", seg.resistance),
                            String.format("%.2f", seg.thermal().conductance),
                            String.format("%.1f", seg.thermal().heatCapacity),
                            String.format("%.2f", res.complex[na].abs()),
                            String.format("%.2f", res.complex[nb].abs()));
                }
            }
        } catch (Throwable ignored) {
        }
        if (seg.thermal() != null && seg.thermal().tempCelsius() > WIRE_BURN_TEMP_C) {
            try {
                String key = seg.compositeKey();
                // ⚠ 2026-08-24 烧毁详细日志（用户要求，key 级 5s 节流）：打印当前
                // 轮 res/网表全部数据，供定位"误烧/真烧"——KCL 校验：段两端电压差 +
                // 网表元素（含 EMF 幅值/内阻）+ res 全数组。
                long nowB = System.currentTimeMillis();
                Long lastB = WIRE_BURN_DBG_LAST.get(key);
                if (lastB == null || nowB - lastB >= 5000) {
                    WIRE_BURN_DBG_LAST.put(key, nowB);
                    StringBuilder sb = new StringBuilder();
                    sb.append("[WireBurnDbg] key=").append(key)
                            .append(" T=").append(String.format("%.1f",
                                    seg.thermal().tempCelsius()))
                            .append("C seg=(").append(na).append(',').append(nb).append(')')
                            .append(" R=").append(String.format("%.6f", seg.resistance))
                            .append(" | resN=").append(n)
                            .append(" netNodes=").append(ctx.network == null
                                    ? -1 : ctx.network.nodeCount())
                            .append(" freq=").append(ctx.frequency)
                            .append(" conv=").append(res.converged)
                            .append(" iters=").append(res.iterations);
                    // 段两端电压（复数值）
                    if (na < res.complex.length && nb < res.complex.length) {
                        sb.append(" | v").append(na).append('=')
                                .append(String.format("%.4f", res.complex[na].abs()))
                                .append(" v").append(nb).append('=')
                                .append(String.format("%.4f", res.complex[nb].abs()))
                                .append(" vd=")
                                .append(String.format("%.6f",
                                        res.complex[na].sub(res.complex[nb]).abs()));
                    }
                    // res 全数组
                    sb.append(" | res=[");
                    for (int ri = 0; ri < res.complex.length; ri++) {
                        if (ri > 0) sb.append(',');
                        sb.append(String.format("%.4f", res.complex[ri].abs()));
                    }
                    sb.append(']');
                    // 网表元素（type(nodeA,nodeB);Ac:幅值/内阻）
                    if (ctx.network != null) {
                        sb.append(" | net[");
                        int ei = 0;
                        for (com.hdf.cryptand.circuitsimulation.model.Element el
                                : ctx.network.elements()) {
                            if (ei++ > 0) sb.append(' ');
                            sb.append(el.type()).append('(')
                                    .append(el.nodeA()).append(',').append(el.nodeB()).append(')');
                            if (el instanceof com.hdf.cryptand.circuitsimulation.model.elements
                                    .AcVoltageSource av) {
                                sb.append(':').append(String.format("%.2f/%.4f",
                                        av.amplitude, av.seriesResistance));
                            }
                        }
                        sb.append(']');
                    }
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(sb.toString());
                }
                java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge> edges =
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                .segmentEdges(key);
                if (key != null && edges != null && !edges.isEmpty()) {
                    long nowC = System.currentTimeMillis();
                    Long lastC = WIRE_BURN_COOLDOWN.get(key);
                    if (lastC == null || nowC - lastC >= WIRE_BURN_COOLDOWN_MS) {
                        WIRE_BURN_COOLDOWN.put(key, nowC);
                        // 【先移除】：立即从自管图移除段内全部导线（线程安全
                        //  writeLock）——确保该段不再被任何网络引用/求解
                        var mgr = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get();
                        for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge ed : edges) {
                            try {
                                if (ed.a != null && ed.b != null
                                        && mgr.contains(ed.a) && mgr.contains(ed.b)) {
                                    mgr.removeEdge(ed.a, ed.b);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        // 【后销毁】：请求清理（温度模型/爆炸/客户端同步）
                        com.hdf.cryptand.neoforge.powergrid.adapter.DestructionQueue.requestWire(
                                key, edges,
                                "过热 " + String.format("%.0f",
                                        seg.thermal().tempCelsius()) + "°C");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 诊断（时间戳节流 5s + 只打温度 >60°C——computeWireHeatOne 在后台求解
        // 线程跑，gameTime 可能不变导致 %100 节流失效 → 刷屏 37 万行卡死）
        try {
            long nowD = System.currentTimeMillis();
            double tNow = seg.thermal() == null ? 20.0 : seg.thermal().tempCelsius();
            // 2026-08-20 排查"5A 电流导线 300°C"：临时降阈值到 30°C + 打印段
            // 长度/散热/热容参数（定位温度虚高根因：R 过大 / G 过小 / 电流虚高）
            if (nowD - WIRE_HEAT_DBG_LAST >= 5000 && tNow > 30.0) {
                WIRE_HEAT_DBG_LAST = nowD;
                double vDiff = res.complex[na].sub(res.complex[nb]).abs(); // 峰值压降
                double iPeak = vDiff / seg.resistance;
                double pAvg = iPeak * iPeak * seg.resistance / 2.0;
                double lenM = 0;
                double G = 0, C = 0;
                try {
                    if (seg.thermal() instanceof com.hdf.cryptand.circuitsimulation.model
                            .thermal.ThermalModel tm) {
                        G = tm.effectiveConductance();
                        C = tm.heatCapacity;
                    }
                    // 段长度（Σ 边长度；convertWires 导入的边 length=0 → 0）
                    java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge> edgs =
                            com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                    .segmentEdges(seg.compositeKey());
                    if (edgs != null) {
                        for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge ed : edgs) {
                            lenM += Math.max(ed.length, 0);
                        }
                    }
                } catch (Throwable ignored) {
                }
                String sk = seg.compositeKey();
                if (sk != null && sk.length() > 40) sk = sk.substring(0, 40) + "...";
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[WireHeat] R={}Ω I={}A(峰值) P={}W T={}°C len={}m G={}W/K "
                                + "C={}J/K key=[{}]",
                        String.format("%.4f", seg.resistance),
                        String.format("%.2f", iPeak),
                        String.format("%.1f", pAvg),
                        String.format("%.1f", tNow),
                        String.format("%.1f", lenM),
                        String.format("%.2f", G),
                        String.format("%.1f", C),
                        sk);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清除上下文涉及的所有变压器发热状态（DC 隔离 / 低频跳过时防残留声音/发热）。
     *  用 blockTerminals 遍历（含 transformerModels 为空的低频场景）。 */
    private static void clearTransformerStates(Level level, PhasorNetworkContext ctx) {
        try {
            if (level == null || ctx == null || ctx.blockTerminals == null) return;
            for (BlockPos pos : ctx.blockTerminals.keySet()) {
                if (level.getBlockEntity(pos)
                        instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
                    TransformerHeatStore.remove(pos);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 反射读取实例字段（沿继承链查找）；失败返回 null */
    private static Object reflectField(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 端点 → 引擎节点 id（支持方块端点与接线端子）；无效返回 -1 */
    private static int endpointNode(NetworkSolve s, IWireEndpoint ep) {
        if (s == null || ep == null) return -1;
        // 方式1（首选）：导线端点的【实际节点对象】 → nodeToEngine 直接映射。
        //   处理三种情况：
        //     - 设备连接器（代理方块）：BlockWireEndpoint.getNode() 返回被代理
        //       设备的端子节点（pos=设备方块），而 blockTerminals 键是方块 pos
        //       → 用节点对象映射才能命中（pos+terminal 猜测会失败）
        //     - 变压器端子节点不在网络 getNodes() 列表但被无条件收集（nodeToEngine 有）
        //     - 孤立端子（无网络）
        try {
            OwnedFloatingNode node = null;
            if (ep instanceof BlockWireEndpoint bep) {
                node = bep.getNode(s.level);
            } else if (ep instanceof JunctionWireEndpoint jep) {
                node = jep.getNode(s.level);
            }
            if (node != null) {
                Integer id = s.ctx.nodeToEngine.get(node);
                if (id != null) return id;
            }
        } catch (Throwable ignored) {
        }
        // 方式2（兜底）：pos+terminal 查 blockTerminals
        if (ep instanceof BlockWireEndpoint bep) {
            return nodeAt(s.ctx.blockTerminals.get(bep.getPos()), bep.getTerminal());
        }
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                BlockPos p = BlockPos.containing(jep.getExactPosition(s.level));
                return nodeAt(s.ctx.blockTerminals.get(p), 0);
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }
}
