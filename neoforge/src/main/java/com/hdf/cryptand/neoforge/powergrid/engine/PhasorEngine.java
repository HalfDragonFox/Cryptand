/**
 * ===== 相量求解引擎接入层 =====
 *
 * 把 Cryptand 引擎（common 模块：Network/ComplexMnaSolver/ComputeScheduler）
 * 接到游戏世界：从种子方块出发 → 收集网络导线 → 构建相量 Network →
 * 多线程调度求解（LocalComputeEngine 线程池）→ 返回 SolveResult（含相量）。
 *
 * 线程安全：构建/求解全在纯数据上（NetworkSnapshot），不触碰 PowerGrid 对象。
 */

package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.circuitsimulation.compute.*;
import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.*;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessage;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.state.CapacitorStateStore;
import com.hdf.cryptand.neoforge.powergrid.state.DevicePowerStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;

import java.util.Map;

public final class PhasorEngine {

    private static volatile ComputeScheduler scheduler;
    /** 统一多线程分发（指向全局通用分配器 ThreadDispatchers.get()，2026-08-14
     *  通用化：分配器为本 mod 通用基础设施，其他任何 mod 也可使用；
     *  PhasorEngine 只是使用方之一，Worker 池线程数受限、空闲休眠） */
    private static volatile ComputeEngine cpuEngine;


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
                        ConfigCircuit.SPARSE_SOLVER_THRESHOLD.get());
            } catch (Throwable ignored) {
            }
            scheduler = new ComputeScheduler();
            // 2026-09-11：消息池诊断输出（引擎层零 MC 依赖，由平台注入 logger）
            try {
                com.hdf.cryptand.engine.NetworkMessageBus.setReporter(
                        CryptandNeoForge.WAF_LOGGER::info);
                // 网络消息处理缓存上限（可配置；默认 20）
                com.hdf.cryptand.engine.NetworkMessageBus.setPoolLimit(
                        ConfigCircuit.MESSAGE_POOL_LIMIT.get());
            } catch (Throwable ignored) {
            }
            // 统一多线程分发（2026-08-12 用户要求）：ThreadDispatcher 管理受限
            // 线程池（空闲休眠/优先空闲/最低负载），线程数 = 配置 cryptandMaxThreads
            // （0 = 自动：CPU 核心数 - 1；正数 = 上限）。所有计算走此统一入口。
            int threads;
            try {
                int cfg = ConfigCircuit.CRYPTAND_MAX_THREADS.get();
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
                int vthreads = ConfigCircuit.CRYPTAND_MAX_VIRTUAL_THREADS.get();
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
     * 提交引擎求解（多线程调度）。包内可见（PhasorPipeline 相量回写复用）。 */
    public static SolveResult solve(Network net) {
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
            String s = ConfigCircuit.PHASOR_NONLINEAR_METHOD.get();
            com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod m =
                    s == null ? com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod.NONE
                            : com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod
                                    .valueOf(s.trim().toUpperCase());
            if (m == com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod.HARMONIC_BALANCE) {
                com.hdf.cryptand.circuitsimulation.solver.HarmonicBalanceSolver.harmonics = Math.max(1,
                        ConfigCircuit.PHASOR_HARMONICS.get());
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
    // ⚠ 2026-08-30 线程统一（用户：任务附带超时时间，最大无限）：批量求解不再
    // 用独立 ForkJoinPool（SOLVE_POOL 已删除）——改走【分配核心】提交每个频率
    // 组的 NORMAL 任务（虚拟线程）+ CompletableFuture.allOf().get(10s) 整体超时。
    // 虚拟线程挂起（native JNI pinned）只占 1 个 Worker 载体，其他 Worker 正常，
    // 不泄漏平台线程（替代原 ForkJoinPool 代际替换）；超时返回后任务仍在
    // 虚拟线程执行，本轮结果弃用（下轮重建覆盖）。

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
            // ⚠ 2026-08-30 根因修复（用户确认"真实流逝基准"）：simDt 经【时间基准
            // 接口 timeBase.advance()】获取——默认 SIM_CLOCK（SimClock.advance =
            // 距上次求解轮的真实时间差 + 主线程心跳门控；EDA/多客户端可 setTimeBase
            // 注入独立实例）。原实现每求解轮用 lastDt()（主线程 tick 量 0.05s），
            // 而求解轮频率（上限 100Hz）> 主线程 20Hz → 时间状态按轮次累计推进
            // → 超速且倍数随负载动态变化。真实流逝：每轮推进"该轮真实覆盖的
            // 时间"（轮多 dt 小、轮少 dt 大）→ 状态演化绑定真实时间，1 倍速。
            simDt = timeBase.advance();
            double simT = timeBase.time();
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
        // ⚠ 2026-08-30 线程统一 + 性能（用户"进入世界一卡一卡"排查）：频率组
        // ≤2（绝大多数小网络）→ 直接同步求解（零虚拟线程/CompletableFuture
        // 每轮分配——消除高频对象创建的 GC 压力）；>2 频率组（多频混合网络，
        // 罕见）才提交分配核心虚拟线程并行 + allOf 10s 整体超时（虚拟线程
        // 挂起只占 Worker 载体，不泄漏平台线程）。
        int groupCount = freqGroups.size();
        if (groupCount <= 2) {
            for (java.util.Map.Entry<Double, java.util.List<Integer>> ge
                    : freqGroups.entrySet()) {
                solveGroup(ge.getKey(), ge.getValue(), nets, out);
            }
        } else {
            java.util.List<java.util.concurrent.CompletableFuture<?>> solveFuts =
                    new java.util.ArrayList<>();
            for (java.util.Map.Entry<Double, java.util.List<Integer>> ge
                    : freqGroups.entrySet()) {
                final double gfreq = ge.getKey();
                final java.util.List<Integer> idxs = ge.getValue();
                solveFuts.add(com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                        .submitGeneric(() -> solveGroup(gfreq, idxs, nets, out),
                                com.hdf.cryptand.circuitsimulation.compute.TaskMode.NORMAL));
            }
            try {
                java.util.concurrent.CompletableFuture.allOf(
                        solveFuts.toArray(new java.util.concurrent.CompletableFuture<?>[0]))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[Phasor] solveAll timeout (native hang?), abandon this round, nets={}", n);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[Phasor] solveAll interrupted, nets={}", n);
            } catch (java.util.concurrent.ExecutionException ee) {
                CryptandNeoForge.WAF_LOGGER.error(
                        "[Phasor] solveAll failed", ee);
            }
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

    /** 单个频率组求解（组内网络按非线性/线性分类，统一相量合并求解）。
     *  ⚠ 2026-08-30 提取（solveAll 复用：组数≤2 同步 / >2 虚拟线程并行）。 */
    private static void solveGroup(double gfreq, java.util.List<Integer> idxs,
                                   java.util.List<Network> nets, SolveResult[] out) {
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
        // 统一相量：DC(0Hz) 也用等效小频率 omega（Backward Euler 分支），
        // AC 用真实频率——AC/DC 同一套求解器。
        double omega = 2 * Math.PI * Math.max(gfreq, 1e-4);
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
    }

    /* ============================================================================
     * 模型推进并行（2026-09-13 用户："不需要分发到具体处理器，直接按照数量比如
     * 128 为一线程分发或者其他方式是否更好"）
     *
     * 采用【按数量分块并行】而非按类型分发：
     *   · 无处理器注册表、无 instanceof 路由 —— 新模型类型零改动；
     *   · 与 SolvePipeline 同范式（数量达阈值才并行，小块串行零开销）；
     *   · 负载天然均摊（温度模型 ~ns、感应电机 ~μs，异构模型按类型分必然不均）。
     *
     * ⚠ 前提（已校验）：
     *   ① 模型实例按 pos/key 唯一，不同元件不共享模型对象；
     *   ② advanceState 自包含 —— 只读 va/vb（本轮求解结果）与自身状态，
     *     不读邻居、不依赖元件处理顺序；
     *   ③ 块间无共享写 —— 各块只写自己元件的字段。
     * 有副作用的部分（BE 消息消费 / DevicePowerStore / 诊断）留在调用方【串行】执行，
     * 因此并行段是纯计算，语义与串行完全一致。
     * ========================================================================== */

    /** 模型推进并行开关（false = 恒串行，行为等价，便于 A/B 对比） */
    static volatile boolean MODEL_PARALLEL = true;
    /** 并行阈值：待推进元件数 ≥ 此值才并行（小块串行零开销） */
    static volatile int MODEL_PARALLEL_THRESHOLD = 64;
    /** 单块最小规模（块太小 → 并行开销占比过高） */
    private static final int MODEL_CHUNK_MIN = 128;

    /**
     * 推进一批复合元件（纯计算段，可并行）：注入节点电压 → advanceState。
     * 只写各元件自身的状态字段，无共享写、无顺序依赖。
     */
    static void advanceModels(
            java.util.List<com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement> todo,
            Complex[] ph, double freq, double simDt) {
        if (todo == null || todo.isEmpty()) return;
        if (!MODEL_PARALLEL || todo.size() < MODEL_PARALLEL_THRESHOLD) {
            for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement c : todo) {
                advanceModelOne(c, ph, freq, simDt);
            }
            return;
        }
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int chunk = Math.max(MODEL_CHUNK_MIN,
                (todo.size() + cores * 2 - 1) / (cores * 2));
        int blocks = (todo.size() + chunk - 1) / chunk;
        java.util.List<java.util.concurrent.CompletableFuture<Void>> fs =
                new java.util.ArrayList<>(blocks);
        for (int bi = 0; bi < blocks; bi++) {
            final int from = bi * chunk;
            final int to = Math.min(todo.size(), from + chunk);
            fs.add(com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                    .submitGeneric(() -> {
                        for (int k = from; k < to; k++) {
                            advanceModelOne(todo.get(k), ph, freq, simDt);
                        }
                    }));
        }
        for (java.util.concurrent.CompletableFuture<Void> f : fs) {
            try {
                f.join();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 单个元件的纯推进（注入节点电压 + advanceState；异常不影响其他元件） */
    private static void advanceModelOne(
            com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement c,
            Complex[] ph, double freq, double simDt) {
        try {
            c.setNodeVoltages(ph);
            int a = c.nodeA(), b = c.nodeB();
            Complex va = (ph != null && a >= 0 && a < ph.length) ? ph[a] : null;
            Complex vb = (ph != null && b >= 0 && b < ph.length) ? ph[b] : null;
            c.advanceState(va, vb, freq, simDt,
                    com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC);
        } catch (Throwable ignored) {
        }
    }

    /** 电容充电状态保存（2026-08-21 跨重建/测量持久）：遍历网络复合电容存
     *  CapacitorStateStore（key=compositeKey "C"+pos，构建时稳定）。 */
    public static void saveCapacitorStates(Network net) {
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
            // 2026-08-20 修复"5A 电流导线 300°C"（双推进温度）：导线
            // 段（WireComposite）温度只由 computeWireHeatOne（processPost
            // 统一发热）推进——它负责烧毁检测；这里若再推进一次 → 温度
            // 按 2 倍速率累积 → 5A 也快速烧毁。跳过 WireComposite。
            java.util.List<com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement>
                    todo = new java.util.ArrayList<>(cs.size());
            for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement c : cs) {
                if (c instanceof com.hdf.cryptand.circuitsimulation.model.composite.WireComposite) {
                    continue;
                }
                if (!seenComps.add(c)) continue; // 同实例重复 → 只推进一次
                todo.add(c);
            }
            // ★ 纯计算段【按数量分块并行】：注入节点电压 + advanceState（见 advanceModels）
            advanceModels(todo, ph, freq, simDt);
            // 有副作用段（BE 消息消费 / 功率记录 / 诊断）保持【串行】，语义与串行一致
            for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement c : todo) {
                try {
                    // （节点电压注入已由上面的 advanceModels 并行段完成）
                    int a = c.nodeA(), b = c.nodeB();
                    Complex va = (ph != null && a >= 0 && a < ph.length) ? ph[a] : null;
                    Complex vb = (ph != null && b >= 0 && b < ph.length) ? ph[b] : null;
                    // （advanceState 已由上面的 advanceModels 并行段完成——
                    //  AC/DC 统一走 COMPLEX_AC 相量 mode，DC 也是 0Hz 相量）
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
                                    com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessage msg = com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser
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
                                com.hdf.cryptand.neoforge.powergrid.state.DevicePowerStore.put(bp, loss);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    // 诊断（2026-08-18 定位“加热器不发热”）：节流打印带温度复合
                    // 元件的损耗功率/温度/端口（确认 MotorModel 是否被推进）
                    if (c.thermal() != null && va != null && vb != null) {
                        if (AdapterDiag.gate("engine.advance", 5000)) {
                            CryptandNeoForge.WAF_LOGGER.info(
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
            if (AdapterDiag.gate("engine.advPseudo", 5000)) {
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
                CryptandNeoForge.WAF_LOGGER.info(
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
                                    CryptandNeoForge
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
                                    if (AdapterDiag.gate("engine.motorFull", 5000)) {
                                        try {
                                            CryptandNeoForge
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

    /** [MotorLoad] 诊断节流（2026-08-27 定位直接接入过热爆炸：负载自锁/EMF 负） */

    /** 默认时间基准：SimClock（真实时间，主线程驱动，2026-08-12） */
    private static final com.hdf.cryptand.circuitsimulation.solver.SimClock SIM_CLOCK =
            new com.hdf.cryptand.circuitsimulation.solver.SimClock();
    /** ⚠ 2026-08-30 当前时间基准（每实例可用，用户：以便多个客户端使用）——
     *  默认 SIM_CLOCK（MC 世界真实时间）；EDA/多客户端/仿真场景可
     *  {@link #setTimeBase} 注入各自独立的 SimClock（真实）或
     *  SimulatedTimeBase（仿真固定步长）——时间/预算/心跳按实例独立。 */
    private static volatile com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase = SIM_CLOCK;

    /** 注入时间基准（每实例；null 忽略）。多客户端各自持有独立 TimeBase。 */
    public static void setTimeBase(com.hdf.cryptand.circuitsimulation.solver.TimeBase tb) {
        if (tb != null) timeBase = tb;
    }

    /** 当前时间基准（诊断/扩展用） */
    public static com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase() {
        return timeBase;
    }

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
        double asyncHz = CryptandTopologyManager
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
     * 开路端子等电位修正（求解路径收尾）：无源两端口元件（R/C/L）一端悬空时，
     * 相量 MNA 会把悬空端判为 GMin→0V → 原版 internalWire 按 (V-0)/R 算出虚假
     * 大电流 → 烧毁（变压器次级"一根导线+开路电阻"场景）。修正后悬空端跟随另
     * 一端：AC 修正相量，DC 修正实电压。
     */
    public static void applyOpenTerminals(SolveResult res, PhasorNetworkContext ctx) {
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

    // 发热/储能/降温族已迁至 EngineThermalCompute（compute*Unified 等）。

}
