package com.hdf.cryptand.neoforge.powergrid.measurement;

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
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorEngine;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry;
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

/**
 * ===== 测量与读数：把一轮求解结果翻译成"某个点上的物理量" =====
 *
 * <p>从 {@code PhasorEngine} 拆出。本类回答的是测量侧的问题：
 * <b>「方块 (pos, 端子) 上现在是多少伏 / 多少安 / 多少欧？」</b>
 *
 * <h3>三个层次</h3>
 * <ol>
 *   <li><b>缓存层</b>（{@code CacheEntry} / {@code CACHES} / {@code getOrSolve}）：
 *       同网络同频率的求解结果复用，键 = 网络身份 + 频率；结构或参数变化由
 *       {@code invalidate*} 系列作废。</li>
 *   <li><b>构建层</b>（{@code buildForMeasurement} / {@code buildForBlocksMeasurement}）：
 *       为测量临时构建 ctx（走自管图或给定方块集合）。</li>
 *   <li><b>读数层</b>（{@code voltageAcross} / {@code voltageBetween} / {@code wireCurrent}
 *       / {@code resistanceBetween} / {@code nodeCurrentSolve} …）：从 {@link NetworkSolve}
 *       里取节点相量、做节点查找与支路求和。</li>
 * </ol>
 *
 * <p>{@link NetworkSolve} 是测量结果的载体（网络 + 频率 + ctx + 解），
 * 由测量入口返回给万用表 / 示波器 / 测温计等平台侧消费者。
 *
 * <p>线程约定：{@code solveNetwork} 等入口可在<b>任意线程</b>调用（内部只用
 * 纯数据 + 原子缓存）；不含任何主线程对象写入。
 */
public final class EngineMeasurements {

    private EngineMeasurements() {}

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
                    WireNetworkManager.get().version(),
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
    // ⚠ 2026-08-30 审计 #7：缓存键改 (net, freq) 复合——原按 ElectricalNetwork
    // 单槽分键【不含频率】：同一网络不同频率测量（万用表 50Hz / 变压器 5000Hz /
    // 绕组）miss 后 put 互相覆盖 → 每次频率切换重建 ctx + 状态重置。
    private static final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> CACHES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 缓存键 = 网络身份 + 频率（同网络不同频率独立槽） */
    private static String cacheKey(ElectricalNetwork net, double freq) {
        return System.identityHashCode(net) + "@" + Double.doubleToLongBits(freq);
    }

    /** 指定频率缓存查询 */
    private static CacheEntry cacheGet(ElectricalNetwork net, double freq) {
        return CACHES.get(cacheKey(net, freq));
    }

    /** 任一频率缓存查询（invalidateTerminals 用；网络级操作不区分频率） */
    private static CacheEntry cacheAny(ElectricalNetwork net) {
        String prefix = System.identityHashCode(net) + "@";
        for (java.util.Map.Entry<String, CacheEntry> en : CACHES.entrySet()) {
            if (en.getKey().startsWith(prefix)) return en.getValue();
        }
        return null;
    }

    /** 获取缓存条目；miss/过期则重建；失败时返回旧条目（可为 null=完全无缓存） */
    private static CacheEntry getOrSolve(Level level, ElectricalNetwork net, double frequency, long gameTime) {
        // 暂停闸门：单机 ESC / 服务器暂停时不再提交后台求解（保留缓存旧值）
        if (PhasorEngine.isServerPaused()) {
            return cacheGet(net, frequency);
        }
        CacheEntry e = cacheGet(net, frequency);
        long netVer = 0;
        try {
            netVer = CryptandTopologyManager.get()
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
        long graphVer = WireNetworkManager.get().version();
        if (e != null && e.freq == frequency && e.netVer == netVer
                && e.graphVer == graphVer) {
            // 无闭合回路 → 单独记录 + 不运算：重置计算值（测量路径返回缓存的全 0 结果）
            if (e.ctx.loopless) {
                try { PhasorPipeline.resetComputation(level, e.ctx); } catch (Throwable ignored) { }
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
            try { res = PhasorEngine.solve(e.ctx.network); } catch (Throwable ignored) { }
            if (res != null && res.voltages != null) {
                try { PhasorEngine.applyOpenTerminals(res, e.ctx); } catch (Throwable ignored) { }
                CacheEntry ne = new CacheEntry(net, frequency, gameTime, netVer,
                        e.ctx.paramVersion.get(), e.ctx, res);
                CACHES.put(cacheKey(net, ne.freq), ne);
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
            try { PhasorPipeline.resetComputation(level, ctx); } catch (Throwable ignored) { }
            SolveResult zero = zeroResultOf(ctx);
            CacheEntry ne = new CacheEntry(net, frequency, gameTime, netVer,
                    ctx.paramVersion.get(), ctx, zero);
            CACHES.put(cacheKey(net, ne.freq), ne);
            return ne;
        }
        SolveResult res = null;
        try {
            res = PhasorEngine.solve(ctx.network);
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
        PhasorEngine.applyOpenTerminals(res, ctx);
        // ⚠ 温度不在此推进（只由主循环 round 每 tick 推进，避免测量路径重复推进）
        CacheEntry ne = new CacheEntry(net, frequency, gameTime, netVer,
                ctx.paramVersion.get(), ctx, res);
        CACHES.put(cacheKey(net, ne.freq), ne);
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
            if (PowerGridWireConverter.isEnabled()
                    && WireNetworkManager.get().nodeCount() > 0) {
                // 从网络任一端点反查自管 seed key
                for (org.patryk3211.powergrid.electricity.sim.node.INode in : net.getNodes()) {
                    if (!(in instanceof OwnedFloatingNode ofn)) continue;
                    if (!(ofn.endpoint instanceof
                            org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bep)) {
                        continue;
                    }
                    String seedKey = "B" + bep.getPos() + "#" + bep.getTerminal();
                    if (!WireNetworkManager.get()
                            .contains(new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(seedKey))) {
                        continue;
                    }
                    return PhasorNetworkBuilder
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
            if (PowerGridWireConverter.isEnabled()
                    && WireNetworkManager.get().nodeCount() > 0) {
                for (BlockPos bp : blocks) {
                    if (bp == null) continue;
                    // 该方块声明端子数（Assemblers 注册的设备 → terminalCount；
                    // IElectricEntity 兜底 2）。从自管图直接查点，不依赖 BE 类型。
                    int termCount = com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder.declaredTerminalCount(
                                    level.getBlockEntity(bp));
                    for (int t = 0; t < termCount; t++) {
                        String seedKey = "B" + bp + "#" + t;
                        if (WireNetworkManager.get()
                                .contains(new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(seedKey))) {
                            return PhasorNetworkBuilder
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
        // #7：移除该网络全部频率槽（前缀匹配 identityHashCode）
        String prefix = System.identityHashCode(net) + "@";
        CACHES.keySet().removeIf(k -> k.startsWith(prefix));
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
        CacheEntry e = cacheAny(net);
        if (e == null || e.ctx == null) return;
        try {
            // 端子注册表（2026-08-13 完全接管端子）：从注册表按位置失效，不依赖
            // ctx.network.terminals() 遍历——网络分裂/重建后注册表 key 仍稳定。
            if (e.ctx.blockTerminals != null) {
                for (BlockPos bp : e.ctx.blockTerminals.keySet()) {
                    for (int t = 0; t < 4; t++) {
                        com.hdf.cryptand.circuitsimulation.model.TerminalElement te =
                                TerminalRegistry
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
        // ⚠ 2026-09-11 用户：禁用主线程求解，只允许异步——本方法改为【只读后台缓存】
        //（自管分量最近一次求解结果）。绝不在主线程 build/solve；未命中 → NaN，
        // 等下一次后台 round 产出（引擎求解在 ThreadDispatchers Worker 上）。
        if (level == null || pos == null || level.isClientSide) return 0;
        try {
            Object[] cv = PhasorPipeline.cachedSolveAt(pos);
            if (cv == null) return Double.NaN;
            return voltageFrom((PhasorNetworkContext) cv[0], (SolveResult) cv[1],
                    pos, t1, t2);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

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
        PhasorEngine.init();
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
        PhasorEngine.init();
        if (level == null || blocks == null || blocks.isEmpty() || level.isClientSide) return null;
        if (PhasorEngine.isServerPaused()) return null; // 暂停 → 不计算
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
            PhasorEngine.saveCapacitorStates(ctx.network);
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
            PhasorEngine.applyOpenTerminals(res, ctx);
            return new NetworkSolve(null, frequency, ctx, res, level);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.info("[MeterFail] solveBlocks exc blocks={} err={}",
                    blocks, t.toString());
            return null;
        }
    }

    /** 从已求解网络反查方块指定端子(t1,t2)跨压【峰值】；t1/t2<0 → 前两个非空端子；无效 → NaN */

    /**
     * 【后台求解入口（2026-09-11 用户：所有计算为引擎异步计算，主线程仅接收）】
     * 从自管图种子（"Bpos#term"）构建 + 求解一次：构建源
     * {@link PhasorNetworkBuilder#buildContextFromGraph} 只读自管图与主线程预同步的
     * DeviceParamCache，【不读 Level/BE】→ 可在后台线程安全调用。
     * <p>供服务器测量系统（万用表/示波器）后台求解：主线程解析种子 → 本方法在
     * 后台完成构建 + 矩阵求解（最重的部分）→ 结果入队 → 主线程反查读数 + 回发。
     *
     * @param seedKey 自管图端点键（"B" + BlockPos + "#" + 端子号）
     * @return 求解封装（ctx + result）；种子无效/求解失败 → null
     */
    public static NetworkSolve solveFromGraphSeed(String seedKey, double frequency) {
        PhasorEngine.init();
        if (seedKey == null) return null;
        if (PhasorEngine.isServerPaused()) return null; // 暂停 → 不计算
        try {
            PhasorNetworkContext ctx = PhasorNetworkBuilder
                    .buildContextFromGraph(null, seedKey, frequency);
            if (ctx == null || ctx.network == null || ctx.network.nodeCount() == 0) {
                return null;
            }
            SolveResult res = com.hdf.cryptand.circuitsimulation.solver.Solvers
                    .create(com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC,
                            ctx.network)
                    .solve(ctx.network);
            if (res == null || res.voltages == null) return null;
            // 电容充电状态持久（与 solveBlocks 一致：跨测量连续）
            PhasorEngine.saveCapacitorStates(ctx.network);
            PhasorEngine.applyOpenTerminals(res, ctx);
            return new NetworkSolve(null, frequency, ctx, res, null);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.info("[MeterFail] solveFromGraphSeed exc seed={} err={}",
                    seedKey, t.toString());
            return null;
        }
    }
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
            int branchCount = 0;
            for (Element el : s.ctx.network.elements()) {
                int a = el.nodeA(), b = el.nodeB();
                if (a != id && b != id) continue;
                Complex iab = branchCurrent(el, r, freq, complexMode);
                if (iab == null) continue; // 无法独立算（多端口耦合/零导纳/开路源）→ 跳过
                any = true;
                branchCount++;
                // 支路电流幅值（不计方向）
                totalMag += iab.abs();
            }
            if (!any) return Double.NaN;
            // ⚠ 2026-08-30 语义修正（审计 U1b）：度 1 节点（悬空端/端子端点，
            // 只连一条支路）流通电流 = 该支路 |I|（不除 2）；度 ≥2 才是
            // Σ|支路|/2（钳流语义：串联节点两向抵消后取一半）。
            double mag = branchCount <= 1 ? totalMag : totalMag / 2.0;
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

            SolveResult res = PhasorEngine.solve(testNet);
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
            // ⚠ 2026-08-30 修复 35KA 假电流（审计 U1）：源被 markOpenCurrentSources
            // 标记 openCircuit（一端接下游但两端无闭合回路 → stamp 不注入，纯内阻）
            // 后，不能再按完整源公式回推支路电流——否则 I=(Vab−Vs)/Rs（Rs=1e-4）
            // 在开路网络 Vab≈0 时回推假 100KA → nodeCurrentSolve Σ|支路|/2 →
            // 万用表显示 35.4KA（实测 35355.339 = 50000/√2 铁证）。开路源 = 断路，
            // 该支路电流不可独立回推 → null（调用方跳过）。
            if (el instanceof AcVoltageSource av && av.isOpenCircuit()) return null;
            if (el instanceof DcVoltageSource dv && dv.isOpenCircuit()) return null;
            if (el instanceof WaveformSource wf && wf.isOpenCircuit()) return null;
            if (el instanceof CurrentSource cs && cs.isOpenCircuit()) return null;
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
        PhasorEngine.init();
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
        PhasorEngine.init();
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
