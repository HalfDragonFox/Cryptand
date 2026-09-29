/**
 * ===== 相量回写：PowerGrid 时域求解的彻底替代 =====
 *
 * 时域求解已彻底禁用（CryptandMna.singleTick 空操作 + WorldNetworksMixin
 * 跳过 prepare/singleTick 循环）。本类每 tick（主线程 preTick）遍历所有
 * ElectricalNetwork，用相量核心求解，并把节点电压直接写回 PowerGrid 网络：
 *
 *   - network.setValue(node, RMS) → 原版设备 getValue() 读到相量电压
 *   - current() = potentialDifference() × conductance() → 从回写电压算
 *   - power() = V²/R → 从回写电压算（RMS → 平均功率正确）
 *
 * 因此原版设备行为（灯泡亮度、加热功率、电流表）全部由相量电压驱动，
 * 而 PowerGrid 时域（LRSeriesWire 电感历史积分、变压器 Tr2P2S 耦合）完全
 * 不参与 —— 彻底消灭"电流无限上升"类发散，相量是唯一求解器。
 *
 * 线程安全：仅在服务端主线程（WorldNetworks.preTick → cryptand$doComputeRound）
 * 调用，不触碰任何后台线程。
 */

package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import com.hdf.cryptand.neoforge.powergrid.device.Assemblers;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionConfig;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessage;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser;
import com.hdf.cryptand.neoforge.powergrid.measurement.EngineMeasurements;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.network.DestructionQueue;
import com.hdf.cryptand.neoforge.powergrid.network.NetworkSignatures;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireDanglingDetector;
import com.hdf.cryptand.neoforge.powergrid.persistence.CryptandSqlite;
import com.hdf.cryptand.neoforge.powergrid.persistence.NetworkCacheManager;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheTable;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerHeatStore;
import com.hdf.cryptand.neoforge.powergrid.state.VirtualDeviceStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;

import java.util.List;
import java.util.Map;

public final class PhasorPipeline {

    private PhasorPipeline() {}

    /** 求解缓存：同一网络同频率 4 tick 内复用（网络结构/导线变化 → 强制重解）。 */
    private static final class Cache {
        final double freq;
        final long gameTime;
        final PhasorNetworkContext ctx;
        final SolveResult result;
        final int pgNodes; // 构建时的 PowerGrid 网络节点数（结构变化检测）
        final int wireSig; // 构建时的网络 wires 签名（导线接入/移除检测）
        final int nodeSig; // 构建时的网络【节点集合】签名（2026-08-13：新设备/端子接入 → miss）
        final long netVer; // 构建时的【网络级】拓扑版本（addWire/removeWire → 该网络失效）
        final long graphVer; // 构建时的自管图版本（WireGraph.version，2026-08-13 阶段1）
        final long paramVersion; // 上次求解时的参数版本（元件参数变化消息 → 失效重解）
        Cache(double freq, long gameTime, PhasorNetworkContext ctx, SolveResult result,
              int pgNodes, int wireSig, int nodeSig, long netVer, long paramVersion) {
            this(freq, gameTime, ctx, result, pgNodes, wireSig, nodeSig, netVer,
                    WireNetworkManager.get().version(),
                    paramVersion);
        }
        Cache(double freq, long gameTime, PhasorNetworkContext ctx, SolveResult result,
              int pgNodes, int wireSig, int nodeSig, long netVer, long graphVer,
              long paramVersion) {
            this.freq = freq; this.gameTime = gameTime; this.ctx = ctx; this.result = result;
            this.pgNodes = pgNodes; this.wireSig = wireSig; this.nodeSig = nodeSig;
            this.netVer = netVer; this.graphVer = graphVer; this.paramVersion = paramVersion;
        }
    }
    /** 求解缓存（按 ElectricalNetwork 分键）。命中（结构复用，不重建网络）：
     *   - 电路【结构】未变（节点数 + 节点集合签名 + wires 签名 + 网络级 netVer）
     *     → ctx 永久复用。
     *   - 元件【参数】变化（电阻/源/电容/电感）→ 元件 setter 发送参数变化消息
     *     → ctx.paramVersion++ → 用同一 ctx 重解（网络不重建）。
     *   - 参数未变（无消息）→ 直接复用旧结果（零重解，温度/状态完全稳定）。
     *   无定期兜底更新：消息处理与求解在同一线程（服务端主线程 round），
     *   同一 tick 同一网络多个元件变化 → 合并为一次重解。
     *   <p>2026-08-12【无感重建】：版本校验从【全局 topoVersion + worldWireSig】
     *   改为【网络级 netVer】——addWire/removeWire hook 携带网络实例 → 只递增该
     *   网络版本 → 只重建受影响网络，其他网络缓存命中（零重建零卡顿）。烧线
     *   防伪由【网络级 wires 签名】（addWire 后导线进 wires）+【阶段0 世界导线
     *   定位】（WORLD_WIRES 新增导线 → 按端点网络精确失效）双重兜底。
     *   <p>2026-08-13 修复：加【节点集合签名 nodeSig】——PowerGrid 网络对象不稳定
     *   （分裂/替换），netVer 按对象键可能漏标记（标记旧对象/查询新对象）→ 旧 ctx
     *   命中（不含新负载端子）→ 负载 0V 不工作/烧线。节点集合变化（新设备/端子）
     *   → 签名必变 → miss 重建。其他网络节点集合不变 → 仍无感。 */
    private static final Map<ElectricalNetwork, Cache> CACHES = new java.util.concurrent.ConcurrentHashMap<>();

    /** 最近一次回写的服务端 Level（供诊断 mixin [WireBurn] 反查方块/变压器用）。
     *  ElectricalNetwork/OwnedFloatingNode 都不持有 Level，诊断只能从这里取。 */
    public static volatile Level CRYPTAND_LAST_LEVEL;


    /** 世界全量导线表快照（WorldNetworks.transmissionLines，主线程每 tick 更新）。
     *  <p>网络 wires 可能因 PowerGrid addWire/merge 延迟而【不含新导线】（变压器副边
     *  接设备后数秒才进 wires）→ buildFromTerminals 只从网络 wires 收集会漏设备端子
     *  → 设备端 0V vs 变压器端 113V → 导线虚假大电流（[WireBurn] 实锤：(-1,0,9)#3
     *  =113V vs (3,0,11)#0=0V）。transmissionLines 是【全量导线表】（导线创建即加入，
     *  不依赖 addWire）→ 从它做【连通性扩展】收集导线另一端，保证设备端子无论何时
     *  都进入求解。 */
    public static volatile java.util.List<org.patryk3211.powergrid.electricity.sim.special.TransmissionLine>
            WORLD_WIRES = java.util.Collections.emptyList();

    /** 当前世界全部电气网络快照（WorldNetworksMixin 每 tick 更新：subnetworks ∪
     *  transmissionLines 网络 ∪ globalExternalNodes 网络）。供电路原理图导出等工具使用。 */
    public static volatile java.util.List<ElectricalNetwork> WORLD_NETS =
            java.util.Collections.emptyList();

    /** 最近一次求解结果（后台 round 写入；供 [WireBurn] 诊断反查：烧线导线两端
     *  节点在 nodeToEngine 的映射 id + 求解电压 → 实证定位“合并成功但电压不同”
     *  的环节：不在 ctx（未收集→0V）/ 同 id 但电压不同（求解）/ 不同 id（合并失效）。 */
    public static volatile PhasorNetworkContext LAST_CTX;
    public static volatile SolveResult LAST_RES;

    // ===== 阶段0：世界导线定位（2026-08-12 无感重建烧线兜底） =====
    // 原 worldWireSig【全局】缓存校验 → 任何新导线 → 所有网络缓存全失效 → 加
    // 设备卡顿。改为【新增导线按端点网络精确失效】：WORLD_WIRES（全量导线表，
    // 创建即加入）签名变化 → 找出新增导线 → 按其 node1/node2 所属网络
    // markNetworkChanged（网络级）→ 只有新增导线涉及的网络重建，其他网络缓存
    // 命中。覆盖 addWire hook 漏网（变压器副边等特殊路径），无烧线窗口。
    private static volatile int LAST_WW_SIG = Integer.MIN_VALUE; // 首轮强制处理
    private static volatile java.util.Set<org.patryk3211.powergrid.electricity.sim.special.TransmissionLine>
            LAST_WIRES = java.util.Collections.emptySet();

    /** 世界导线签名变化 → 新增导线所属网络 → 网络级失效（主线程 round 内调用）。 */
    private static void trackNewWorldWires() {
        try {
            int wwSig = NetworkSignatures
                    .worldWiresSignature();
            if (wwSig == LAST_WW_SIG) return;
            java.util.List<org.patryk3211.powergrid.electricity.sim.special.TransmissionLine>
                    cur = WORLD_WIRES;
            if (!cur.isEmpty()) {
                for (org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl : cur) {
                    if (LAST_WIRES.contains(tl)) continue;
                    ElectricalNetwork n1 = networkOfNode(tl.getNode1());
                    ElectricalNetwork n2 = networkOfNode(tl.getNode2());
                    if (n1 != null) CryptandTopologyManager
                            .get().markNetworkChanged(n1);
                    if (n2 != null) CryptandTopologyManager
                            .get().markNetworkChanged(n2);
                }
            }
            LAST_WIRES = new java.util.HashSet<>(cur);
            LAST_WW_SIG = wwSig;
        } catch (Throwable ignored) {
        }
    }

    private static ElectricalNetwork networkOfNode(
            org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd) {
        if (nd instanceof OwnedFloatingNode ofn) {
            try { return ofn.getNetwork(); } catch (Throwable ignored) { }
        }
        return null;
    }

    // ===== 网络级稳定检测（2026-08-12 用户要求：重建完成一个网络这个网络开始求解）=====
    // 进世界/接线时网络正在重建（addWire/islandDiscovery），未稳定时求解会因未写回
    // 节点产生虚假大电流烧线（[WireBurn] 实锤：i=14141A，一端 inCtx=true 有电压、
    // 一端 inCtx=false 未写回 0V）。每个【分量】独立判断：所有网络连续 STABLE_TICKS
    // 结构签名（节点数 + 网络 wires + 网络级版本）不变才算稳定；稳定前【清零该分量
    // 所有节点电压】（导线两端 0 → 无压差 → 不烧），稳定后才正常求解写回。
    // 全局预热 4s 不可靠（用户反馈）→ 改为网络级：稳定一个求解一个，接线瞬间
    // 短暂清零（~3 tick），不依赖固定时长。
    // 2026-08-12【无感重建】：版本用【网络级 netVer】替代全局 topoVersion——
    // 加设备只重置受影响网络稳定检测，其他网络稳定不重置（不闪烁不卡顿）。
    // 2026-08-13 修复：加【节点集合签名】——netVer 按对象键在 PowerGrid 网络
    // 分裂/替换时可能漏标记（标记旧对象/查询新对象）→ 新设备接入但稳定检测不
    // 重置 → 旧 ctx 复用 → 负载 0V 不工作。节点签名变化 → 必重置。
    private static final int STABLE_TICKS = 3;
    private static final class NetStable {
        int ticks; int pgNodes; int wireSig; int nodeSig; long netVer;
        NetStable(int pgNodes, int wireSig, int nodeSig, long netVer) {
            this.pgNodes = pgNodes; this.wireSig = wireSig; this.nodeSig = nodeSig;
            this.netVer = netVer;
        }
    }
    private static final Map<ElectricalNetwork, NetStable> NET_STABLE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 稳定检测/版本校验失败诊断节流（2026-08-12） */
    /** 构建结果为空诊断节流 */
    private static volatile long BUILD_EMPTY_LAST;
    /** 进入世界初始化诊断节流（2026-08-12） */

    /** 网络是否重建完成（连续 STABLE_TICKS 结构签名不变）。 */
    private static boolean isNetworkStable(ElectricalNetwork net) {
        int pg = net.getNodes().size();
        int ws = NetworkSignatures.wiresSignature(net);
        int ns = NetworkSignatures.nodeSignature(net);
        long nv = CryptandTopologyManager.get()
                .netVersionOf(net);
        NetStable st = NET_STABLE.get(net);
        if (st == null || st.pgNodes != pg || st.wireSig != ws || st.nodeSig != ns
                || st.netVer != nv) {
            NET_STABLE.put(net, new NetStable(pg, ws, ns, nv));
            return false;
        }
        st.ticks++;
        return st.ticks >= STABLE_TICKS;
    }

    /** 清零网络所有节点电压（稳定前防旧电压/未写回节点冲突烧线）。
     *  2026-08-20 用户决策：完全禁止写入原版 PowerGrid 节点——自管模式只写
     *  自管宿主（TerminalRegistry 端子测试点）；原版节点恒为转换源，不再
     *  setValue 清零（非自管 round 已废弃，本方法仅剩失效端子测试点）。 */
    private static void clearNetworkVoltages(Level level, ElectricalNetwork net) {
        try {
            // 端子测试点失效（2026-08-13）：清零网络时同步失效端子——否则
            // DEVICE_TERMINAL_V 残留上次求解电压 → 风扇/电机在重建/清零期
            // 读到旧值误启动。失效后消费端读 null → 不转。
            try {
                EngineMeasurements.invalidateTerminals(net);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 三阶段流水线中的“待求解/待写回”任务（2026-08-11）。
     *  cached != null → 缓存命中，阶段3 直接用旧结果写回（不重新求解）。
     *  buildNetVer：构建开始时的【网络级】版本——构建/求解期间该网络 addWire/
     *  removeWire（netVer++）→ 本批结果基于旧拓扑 → 阶段2 后丢弃（不写回）。
     *  （2026-08-12 无感重建：由全局 topoVersion 快照改为网络级——其他网络
     *  变化不影响本网络构建结果，无需整批丢弃。） */
    private static final class Pending {
        final ElectricalNetwork net;
        final double freq;
        final PhasorNetworkContext ctx;
        final SolveResult cached;
        final long gameTime;
        long buildNetVer;
        Pending(ElectricalNetwork net, double freq, PhasorNetworkContext ctx,
                SolveResult cached, long gameTime) {
            this.net = net; this.freq = freq; this.ctx = ctx;
            this.cached = cached; this.gameTime = gameTime;
        }
    }

    /** 分阶段性能诊断（2026-08-12）：累计 build/solve/post 耗时，节流打印 */
    private static long PERF_BUILD_NS, PERF_SOLVE_NS, PERF_POST_NS;
    private static int PERF_ROUNDS;
    private static long PERF_LAST_MS;
    private static void perfAdd(long t0, long t1, long t2, long t3) {
        PERF_BUILD_NS += t1 - t0;
        PERF_SOLVE_NS += t2 - t1;
        PERF_POST_NS += t3 - t2;
        PERF_ROUNDS++;
        long now = System.currentTimeMillis();
        if (now - PERF_LAST_MS >= 5000) {
            PERF_LAST_MS = now;
            int r = Math.max(1, PERF_ROUNDS);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[Perf] rounds={} build={}ms solve={}ms post={}ms total={}ms",
                    PERF_ROUNDS,
                    PERF_BUILD_NS / 1_000_000 / r,
                    PERF_SOLVE_NS / 1_000_000 / r,
                    PERF_POST_NS / 1_000_000 / r,
                    (PERF_BUILD_NS + PERF_SOLVE_NS + PERF_POST_NS) / 1_000_000 / r);
            PERF_BUILD_NS = PERF_SOLVE_NS = PERF_POST_NS = 0;
            PERF_ROUNDS = 0;
        }
    }

    /**
     * ===== 自管图驱动求解（2026-08-13 完整闭环） =====
     *
     * 由自管 WireGraph 的物理分量驱动主 round（替代原版 ElectricalNetwork 列表
     * 驱动）：枚举 WireGraph.components() → 每分量选 seed 端点 → 用
     * buildContextFromGraph（自管构建源）构建引擎网络 → 求解 → 写回。
     *
     * 完整闭环要点（2026-08-13 用户决策：完全接管，不写回原版节点）：
     *   - 拓扑真相 = 自管 WireGraph（WireGraphStore，由转换类从原版导线同步）
     *   - 构建源 = buildContextFromGraph（不再依赖原版网络扫描）
     *   - 写回 = 只写自管宿主：TerminalRegistry 端子测试点（TerminalRecorder 每次
     *     求解回填电压+频率，2026-08-15 端子即接入模型）+ TerminalElement
     *     测试点（TerminalRecorder 回填）。原版内容全部通过转换层转成本 mod 内容，
     *     原版节点不再写回（原版网络仅作转换源，非设备宿主）。
     *   - 频率 = 分量内端点方块反查（发电机/AC 源，MultimeterDebug）
     *   - 稳定检测 = WireGraph.version 连续不变（替代原版网络签名）
     *   - 缓存 = 按分量 seedKey + graphVer（WireGraph 变更即失效）
     *
     * 门控：调用方（CryptandTopologyManager）在 PowerGridWireConverter.isEnabled()
     * 且自管图非空时走本路径；否则回退原版 round（安全兜底）。
     */
    public static int roundFromGraph(Level level, boolean forceInit) {
        return roundFromGraph(level, forceInit, false);
    }

    /**
     * 从图轮询构建+求解（自管模式主路径）。
     * @param fromOpTable true = 由操作表任务（NetlistOperation.executeSolve）调用——
     *        此时该网络已从求解表移入操作表并完成操作（拆合/重建），可直接求解；
     *        false = 主线程/轮询路径（CryptandTopologyManager tick）——必须遵守
     *        "网络移动表到操作列表需在求解之前"：只要操作表（AsyncInteractionManager
     *        记录表）还有未完成的网络操作（拆合/重建/设备增量等），本轮【不求解】
     *        （等操作完成；完成后再正常求解）。未检测到移动操作（操作表空）→ 正常求解。
     */
    public static int roundFromGraph(Level level, boolean forceInit, boolean fromOpTable) {
        if (PhasorEngine.isServerPaused()) return 0;
        if (level == null) return 0;
        CRYPTAND_LAST_LEVEL = level;
        PhasorEngine.init();
        // ⚠ 2026-08-26 用户：优先操作表，其余网络正常求解——不再因操作表非空整轮
        // return 0（全局卡住：一个网络在操作 → 所有网络都不求解）。被操作表占用
        // 的网络由 NetlistOperation.executeSolve（fromOpTable=true 优先求解）；
        // 轮询照常构建+求解全部分量（其余网络正常求解）。
        // "求解不抢在移表操作前"由阶段3 图版本校验兜底：构建期间图(版本)变化 → 结果
        // 丢弃不写回；操作表完成、版本稳定后下一轮才写回 = 等效"移表操作后再求解"，
        // 但不阻塞其余网络。
        if (!fromOpTable) {
            try {
                var am = com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager.get().asyncManager();
                if (am != null && am.size() > 0) {
                    long now0 = System.currentTimeMillis();
                    if (AdapterDiag.gate("pipeline.opWait", 5000)) {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[SolveWaitOp] opTable busy={} (op-table first; others solve, ver-guard)",
                                am.size());
                    }
                    // ⚠ 2026-08-26：不再 return 0（其余网络照常求解）
                }
            } catch (Throwable ignored) {
            }
        }
        long t0 = System.nanoTime();
        var mgr = WireNetworkManager.get();
        // 诊断（节流 5s）：转换状态 + 自管图大小 + 世界导线数（定位"电压全 0"）
        // 2026-08-20 排查"交流源+电阻+电机 导线烧毁"：追加打印每个分量的节点
        // key + 边数——直接看用户电路是否分裂（应 1 分量含全部设备端子）。
        try {
            long now = System.currentTimeMillis();
            if (AdapterDiag.gate("pipeline.graphRound", 5000)) {
                StringBuilder cb = new StringBuilder();
                try {
                    int ci = 0;
                    for (java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp
                            : mgr.components()) {
                        cb.append(" C").append(ci++).append('[');
                        int n = 0;
                        for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                            if (n++ > 8) { cb.append("..."); break; }
                            cb.append(p.key).append(' ');
                        }
                        cb.append("]");
                    }
                } catch (Throwable ignored) {
                }
                CryptandNeoForge.WAF_LOGGER.info(
                        "[GraphRound] convert={} graphNodes={} graphEdges={} graphVer={} "
                                + "worldWires={} worldNets={} comps={}",
                        PowerGridWireConverter.isEnabled(),
                        mgr.nodeCount(), mgr.edgeCount(), mgr.version(),
                        PhasorPipeline.WORLD_WIRES.size(),
                        PhasorPipeline.WORLD_NETS.size(),
                        cb);
            }
        } catch (Throwable ignored) {
        }
        if (mgr.nodeCount() == 0) return 0;
        long graphVer = mgr.version();
        try {
            // ===== 进入世界初始化（2026-08-13 自管版） =====
            if (forceInit) {
                // 全量清空自管缓存 + 稳定检测 + 强制重建所有分量 ctx（不求解不写回）
                GRAPH_CACHES.clear();
                GRAPH_STABLE = null;
                return 0;
            }

            // ===== 稳定检测（自管版）：WireGraph.version 连续不变才算稳定 =====
            {
                GraphStable st = GRAPH_STABLE;
                if (st == null || st.version != graphVer) {
                    // ⚠ 2026-08-21 修复 [GraphRound] crashed NPE：必须把新建的
                    //   GraphStable 同时赋给局部变量 st（原来只赋 GRAPH_STABLE，
                    //   st 仍 null → 下方 st.ticks++ 空指针 → 每轮崩溃 → 网络
                    //   永不求解 → 电流/温度全异常）。
                    st = new GraphStable(graphVer, 1);
                    GRAPH_STABLE = st;
                    // 2026-08-20 修复"电机放下+连线后不求解 / 温度表读不到"：
                    // 版本变化【不再 return 0】——若版本持续抖动（如电机
                    // IElectricEntity 原版网络反复重建 → 导线表每 tick 变化 →
                    // convertWires 每 tick 增边；或某处每 tick addDevice），
                    // 稳定检测永不过 → 永不求解 → 电机不转、温度不推进（直到
                    // 放置新设备触发版本稳定才"碰巧"恢复）。改为：清空端子电压
                    // 防旧值烧线，但【继续构建+求解】——只要构建/求解期间版本
                    // 稳定（阶段3 mgr.version()==graphVer 校验通过），结果即写回。
                    clearGraphVoltages();
                    // 诊断（节流 5s）：确认版本是否持续抖动（抖动 → 本轮结果
                    // 可能被阶段3 丢弃 → 电机/温度延迟）
                    long nowV = System.currentTimeMillis();
                    if (AdapterDiag.gate("pipeline.stable", 5000)) {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[GraphStable] verChanged={} graphVer={} nodes={} edges={}",
                                graphVer, graphVer,
                                mgr.nodeCount(), mgr.edgeCount());
                    }
                }
                st.ticks++;
                if (st.ticks < STABLE_TICKS) {
                    // 未满稳定 tick：仍清端子防旧值，但【继续构建+求解】（同上，
                    // 结果按阶段3 版本校验，稳定后即写回——解决"放置后延迟启动"）
                    clearGraphVoltages();
                }
            }

            // ===== 阶段1（调用线程：主线程或异步核心线程）：枚举分量 → 构建 ctx =====
            // 缓存驱动（DeviceParamCache），不碰 level/BE；发热/清理/销毁后处理
            // 一律入 POST_NETS → 主线程 processPost 消费（铁律：核心不写回 Level）。
            // ⚠ 2026-08-24 用户：版本变化（图改变）后必须用【新图】重新构建+求解
            // 一次——原实现构建后版本再变仍用旧构建的 ctx（可能缺设备/缺电机 →
            // 短路 0V/大电流烧线）。构建循环 ≤3 次：每次以最新版本为基线构建，
            // 构建完成后校验版本，变了 → 用最新图重构建（保证构建与重构建一致）。
            java.util.List<PendingG> pendings = new java.util.ArrayList<>();
            int buildTry = 0;
            while (buildTry < 3) {
                graphVer = mgr.version();
                pendings = new java.util.ArrayList<>();
                java.util.List<java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint>> comps =
                        mgr.components();
                graphStageDbg("components comps=" + comps.size()
                        + " try=" + (buildTry + 1) + " ver=" + graphVer);
                // 进世界首轮标志：整轮共用，用后即清（下一轮起走内存缓存）
                boolean restorePreferred = RESTORE_PREFERRED;
                RESTORE_PREFERRED = false;
                for (java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp : comps) {
                if (comp == null || comp.isEmpty()) continue;
                // 选 seed：优先方块端子（B 前缀），否则任意
                String seedKey = null;
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                    if (WireKeyUtil.isBlock(p.key)) { seedKey = p.key; break; }
                }
                if (seedKey == null) seedKey = comp.iterator().next().key;
                // 孤立分量（无导线边，2026-08-19 用户要求"放下即运行" + 2026-08-21
                // 用户要求"所有电气设备放下有网络"）：所有孤立分量一律构建求解——
                // 源设备（发电机/创造源）端子产生开路电压（运行）；负载设备
                //（电机/加热器）无源电流 0 无发热；孤立接线端子/悬空导线端 0V
                // 可测。不依赖 DeviceParamCache（可能漏登记导致跳过 → 没网络），
                // 只要自管图有点（addDevice/接线端点）就构建。孤立点数量有限，
                // 空网络求解 0V 开销可忽略，换来【任何孤立元件都有网络可测】。
                double freq = graphComponentFreq(comp);
                // ⚠ 2026-08-26 用户：SQLite 结构恢复【仅世界加载/必要时】读，每轮不碰
                // （此前每轮 signatureOf+tryRestoreStructure 都查 SQLite 缓存；且恢复的
                //  ctx 无 paramSources → refreshParams 空转 → 参数不刷新 → 改电阻后
                //  网络不更新）。每轮走 buildPendingFromGraph（内存 GRAPH_CACHES +
                //  refreshParams 从 DeviceParamCache 拉最新参数 → setter 更新）；仅当其
                //  构建失败时才读 SQLite 结构兜底（跨区块）。signatureOf 是纯哈希不碰
                //  SQLite，仅在确需恢复/持久化时调用。
                // ===== 2026-09-15 用户架构："进入存档直接从 sqlite 恢复" =====
                // 进世界【首轮】优先从 SQLite 恢复结构，而不是从自管图重建：
                //   - 恢复的 ctx 与退出世界那一刻完全一致（含电机等设备的展开元件
                //     与 KV 状态，经 CompositeFactory 交回组装器重建）；
                //   - 参数源由 reattachParams 重新挂上（恢复的 ctx 本身不带 lambda），
                //     否则 refreshParams 空转 → 改参数不生效（2026-08-26 踩过的坑）。
                // 首轮之后一律走内存 ctx（GRAPH_CACHES + 每轮 refreshParams），
                // 继续遵守"游戏期间零 SQLite 交互"。
                PendingG p = null;
                if (restorePreferred) {
                    p = tryRestoreFromDb(comp, seedKey, freq);
                }
                if (p == null) {
                    p = buildPendingFromGraph(level, seedKey, freq, graphVer);
                }
                if (p == null) {
                    try {
                        String sigR = com.hdf.cryptand.neoforge.powergrid.persistence
                                .NetworkCacheManager.signatureOf(comp, freq);
                        PhasorNetworkContext restoredCtx =
                                com.hdf.cryptand.neoforge.powergrid.persistence
                                        .NetworkCacheManager.tryRestoreStructure(sigR);
                        if (restoredCtx != null) {
                            reattachParams(restoredCtx);
                            p = new PendingG(seedKey, freq, sigR, restoredCtx, null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (p == null) continue;
                // 补 sig（buildPendingFromGraph 未设；留空则 recordSolved 不持久化。
                // signatureOf 纯哈希不碰 SQLite，仅确需持久化时调用。）
                if (p.sig == null) {
                    try {
                        String sg = com.hdf.cryptand.neoforge.powergrid.persistence
                                .NetworkCacheManager.signatureOf(comp, freq);
                        p = new PendingG(seedKey, freq, sg, p.ctx, p.cached);
                    } catch (Throwable ignored) {
                    }
                }
                // 电压每轮强制重新求解（2026-08-24：不 tryApply 旧电压——签名
                // 不含设备，旧电压可能对应无电机网表 → 与当前网表错配 → 假电流
                // 烧线）。cached 恒 null → 阶段2 全量 solveAll。
                // 2026-08-24：求解前快照 ctx 网表指纹（advancePseudoTime 推进 EMF
                // 会改 structureHash；solveHash 固定为求解时状态，供 WireHeat 校验）
                try {
                    p.ctx.solveHash = p.ctx.network.structureHash();
                } catch (Throwable ignored) {
                }
                pendings.add(p);
                }
                buildTry++;
                if (pendings.isEmpty()) return 0;
                if (mgr.version() == graphVer) break; // 构建期间版本未变 → 本轮可用
                // 版本又变 → 继续重试（用最新图重建），保证"图改变后求解一次新图"
            }
            if (pendings.isEmpty()) return 0;
            long t1 = System.nanoTime();
            graphStageDbg("built pendings=" + pendings.size() + " ver=" + graphVer);

            // ===== 阶段2（并行）：未缓存命中的求解 =====
            java.util.List<com.hdf.cryptand.circuitsimulation.model.Network> toSolve = new java.util.ArrayList<>();
            int[] solveIdx = new int[pendings.size()];
            java.util.Arrays.fill(solveIdx, -1);
            for (int i = 0; i < pendings.size(); i++) {
                if (pendings.get(i).cached == null) {
                    solveIdx[i] = toSolve.size();
                    toSolve.add(pendings.get(i).ctx.network);
                }
            }
            // ctxs 与 toSolve 对齐（solveIdx[i]>=0 → pendings[i].ctx）：求解后在
            // 温度推进前应用 openTerminal（悬空端子等电位，防假电流假温度）
            java.util.List<PhasorNetworkContext> solveCtxs = new java.util.ArrayList<>();
            long[] expectHash = new long[toSolve.size()];
            for (int i = 0; i < pendings.size(); i++) {
                if (solveIdx[i] >= 0) {
                    solveCtxs.add(pendings.get(i).ctx);
                    expectHash[solveCtxs.size() - 1] = pendings.get(i).ctx.solveHash;
                }
            }
            // ⚠ 2026-09-11 用户架构：网络【计算前】接收消息——把本网络相关消息应用到
            // 元件（只在引擎线程写模型 → 与主线程握手无竞态）。主线程前处理只负责投递。
            for (int mi = 0; mi < pendings.size(); mi++) {
                if (solveIdx[mi] < 0) continue; // 本批不求解的网络无需转入消息
                try {
                    PipelinePostProcess.applyPendingMessages(pendings.get(mi).seedKey, pendings.get(mi).ctx);
                } catch (Throwable ignored) {
                }
            }
            java.util.List<SolveResult> results = toSolve.isEmpty()
                    ? java.util.List.of() : PhasorEngine.solveAll(toSolve, solveCtxs, expectHash);
            long t2 = System.nanoTime();
            graphStageDbg("solved toSolve=" + toSolve.size());
            // 2026-08-24 配对诊断（节流 5s）：solveAll 返回顺序与 expectHash 对齐
            // 校验——若某 si 的 res.networkHash != expectHash[si] → solveAll 内部
            // out[] 被并行/顺序写错（底层根因，直接定位）。
            try {
                long nowP = System.currentTimeMillis();
                if (AdapterDiag.gate("pipeline.pair", 5000)) {
                    for (int i = 0; i < pendings.size(); i++) {
                        int si2 = solveIdx[i];
                        if (si2 < 0 || si2 >= results.size()) continue;
                        SolveResult rr = results.get(si2);
                        if (rr != null && rr.networkHash != expectHash[si2]) {
                            CryptandNeoForge
                                    .WAF_LOGGER.warn(
                                    "[PairDbg] pend={} si={} resHash={} expect={} seed={}",
                                    i, si2, rr.networkHash, expectHash[si2],
                                    pendings.get(i).seedKey);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // ===== 阶段3（调用线程：主线程或异步核心线程）：缓存 + 写回自管宿主 =====
            int solved = 0;
            java.util.List<Object[]> nets = new java.util.ArrayList<>();
            for (int i = 0; i < pendings.size(); i++) {
                PendingG p = pendings.get(i);
                // 构建/求解期间 WireGraph 版本变化 → 拓扑已变，本轮求解结果基于
                // 旧拓扑，直接写回可能错。2026-08-20 修复"版本抖动 → 永不写回"：
                // 版本持续抖动时（如电机 IElectricEntity 原版网络反复重建 →
                // convertWires 每 tick 增边）→ 若一律丢弃，端子永不写回 →
                // 电机不转、温度不推进。改为：优先复用【同 seedKey 旧缓存结果】
                // （安全滞后，版本抖动多为幂等重复/转换抖动，拓扑未真变）；
                // 无旧结果才跳过本轮。
                // ⚠ 2026-08-24 用户：每轮全量求解所有网络，不跳过。
                // 版本变化（构建期间图又变）→ 不再复用旧结果/删除跳过：本轮
                // 构建 ctx 已捕获图，继续用本轮求解结果（写回只写构建时存在的
                // 点，无新端子错写）；下轮以新版本重建覆盖。
                if (mgr.version() != graphVer) {
                    long nowV2 = System.currentTimeMillis();
                    if (AdapterDiag.gate("pipeline.stable", 5000)) {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[GraphVer] changed ver={} graphVer={} seed={} (keep solve)",
                                mgr.version(), graphVer, p.seedKey);
                    }
                }
                SolveResult res = p.cached;  // 恒 null（2026-08-24：禁用旧电压回放）
                if (res == null) {
                    int si = solveIdx[i];
                    res = (si >= 0 && si < results.size()) ? results.get(si) : null;
                    if (res == null || !res.converged) {
                        // ⚠ 2026-08-24 用户：求解失败【绝不复用旧结果】——solveAll
                        // 已尝试（不跳过求解）；本轮不投递（不写回/不发热），
                        // 结构与缓存保留，下轮自动重解。
                        continue;
                    }
                    // ⚠ 2026-08-24 求解源诊断（不改行为）：本轮 res 应来自
                    // p.ctx.network（solveAll 以 expectHash 回填 = 同源）。
                    // 同源基准 = p.ctx.solveHash（阶段1 拍的）；勿用推进后的
                    // structureHash 重拍（advancePseudoTime 已推进 EMF → 假阳性）。
                    try {
                        if (res.networkHash != 0 && p.ctx.network != null
                                && res.networkHash != p.ctx.solveHash) {
                            long nowM = System.currentTimeMillis();
                            if (AdapterDiag.gate("pipeline.solveMismatch", 5000)) {
                                CryptandNeoForge
                                        .WAF_LOGGER.warn(
                                        "[SolveMismatch] si={} seed={} resHash={} ctxSolve={} netNodes={}/{}",
                                        si, p.seedKey, res.networkHash, p.ctx.solveHash,
                                        res.complex == null ? -1 : res.complex.length,
                                        p.ctx.network.nodeCount());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    PhasorEngine.applyOpenTerminals(res, p.ctx);
                } catch (Throwable ignored) {
                }
                LAST_CTX = p.ctx;
                LAST_RES = res;
                // 内存缓存统一记录（含 SQLite 缓存命中恢复的结果 → 后续轮次
                // 直接内存命中不再重建）
                GRAPH_CACHES.put(p.seedKey, new CacheG(p.freq, p.ctx, res, graphVer));
                // 端子回填（组装器侧数据：TerminalRegistry 端子测试点每次求解回填
                // 电压+频率，消费端/风扇/仪表由 mixin 直读——不写原版节点/BE）
                try {
                    com.hdf.cryptand.circuitsimulation.solver.TerminalRecorder
                            .record(p.ctx.network, res);
                } catch (Throwable ignored) {
                }
                solved++;
                // 记录最新结果（纯内存；世界保存时批量落库，游戏期间零 DB）
                try {
                    NetworkCacheManager
                            .recordSolved(p.sig, networkIdOf(p.seedKey),
                                    p.seedKey, p.freq, p.ctx, res);
                } catch (Throwable ignored) {
                }
                // ⚠ 2026-08-24 批内冻结指纹：p.ctx（PhasorNetworkContext）跨轮
                // 复用（restoredCtx），ctx.solveHash 字段会被【下一轮 phase1】
                // 覆盖 → processPost 时批内 res（旧轮）与字段（新轮）错位 →
                // 假 [WireHeatMismatch]（实锤：同一 hash 上一行是 ctxHash、下
                // 一行变 resHash）。第4元素 = 本批指纹（equals res.networkHash）
                // ——校验只用批内值，不读会被覆盖的字段。
                nets.add(new Object[]{p.ctx, res, p.freq, res.networkHash});
            }
            long t3 = System.nanoTime();
            // ===== 引擎异步计算（2026-09-11 用户要求：全部计算由引擎侧异步完成，
            // 主线程只接收结果）——发热/储能/温度推进是【纯虚拟模型运算】，不碰
            // level/BE：变压器铜损铁损、设备损耗、导线段发热、电容/电池储能推进、
            // 设备电流与供电缓存回填，全部在本轮后台线程直接算完（level 传 null：
            // 这些函数只用 level 做诊断节流，且都带 null 守卫）。
            if (!nets.isEmpty()) {
                try { EngineThermalCompute.computeTransformerHeatUnified(null, nets); } catch (Throwable ignored) { }
                try { EngineThermalCompute.computeDeviceHeatUnified(null, nets); } catch (Throwable ignored) { }
                try { EngineThermalCompute.computeWireHeatUnified(null, nets); } catch (Throwable ignored) { }
                try { EngineThermalCompute.computeEnergyUnified(null, nets); } catch (Throwable ignored) { }
            }
            // ⚠ 2026-09-11 用户：热扩散【暂时停用】（重新设计中）——数值传播不执行。
            // try { applyThermalDiffusion(); } catch (Throwable ignored) { }
            // 求解完成消息（唯一需要主线程的部分：DeviceBinding.onMessage 平台交互）
            // ⚠ 2026-08-24 根因修复：异步 100Hz 投递 > 主线程 20Hz 消费 → 旧批
            // 积压 → 主线程处理【旧 res】配【新 ctx/新网表】→ 假电流/温度错位
            // （[WireHeatMismatch] res hash 逐行变、ctx 稳定实锤）。改为【只保留
            // 最新一批】：producer 覆盖旧批（丢弃过期），consumer 每次处理最新
            // 一轮结果——结果滞后最多 1 消费周期，绝无跨轮交叉。
            if (!nets.isEmpty()) {
                POST_NETS.clear();
                POST_NETS.offer(nets);
            }
            perfAdd(t0, t1, t2, t3);
            return solved;
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.error(
                    "[GraphRound] crashed", t);
            return 0;
        }
    }

    /**
     * 分量内是否含电气设备方块（2026-08-19 孤立设备求解判定）：DeviceParamCache
     * 有登记 → 该方块有组装模型可求解（PowerGrid 设备 / Cryptand 自造方块 /
     * 接线端子块全含）。后台线程安全（DeviceParamCache 是主线程同步的线程安全
     * 缓存，不碰 level/BE）。
     */
    private static boolean compContainsDevice(
            java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp) {
        try {
            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                if (!WireKeyUtil.isBlock(p.key)) {
                    continue;
                }
                net.minecraft.core.BlockPos bp =
                        PhasorNetworkBuilder
                                .pointPosOfPublic(p.key);
                if (bp == null) continue;
                if (DeviceParamCache.get(bp) != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 后处理数据队列（后台 round → 主线程）：每元素 = ctx/result/freq 列表 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<List<Object[]>> POST_NETS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 世界卸载：清全部跨世界静态缓存（2026-08-22 对象化——新世界 = 全新状态，
     *  旧世界缓存彻底隔离，杜绝"新世界带上一个存档网络"）。主线程调用（卸载事件）。 */
    public static void worldUnload() {
        try { CACHES.clear(); } catch (Throwable ignored) { }
        try { NET_STABLE.clear(); } catch (Throwable ignored) { }
        try { GRAPH_CACHES.clear(); } catch (Throwable ignored) { }
        try { POST_NETS.clear(); } catch (Throwable ignored) { }
        GRAPH_STABLE = null;
        LAST_CTX = null;
        LAST_RES = null;
        // 2026-09-15 用户："进入存档直接从 sqlite 恢复" —— 下次进世界首轮优先恢复。
        RESTORE_PREFERRED = true;
        CRYPTAND_LAST_LEVEL = null;
        WORLD_WIRES = java.util.Collections.emptyList();
        WORLD_NETS = java.util.Collections.emptyList();
        LAST_WW_SIG = Integer.MIN_VALUE;
        LAST_WIRES = java.util.Collections.emptySet();
    }

    /**
     * 主线程每 tick【接收】后台引擎结果：把组装器侧数据落到世界（孤儿清理/
     * 销毁/引擎消息/BE 参数上报消费）。此处【不做任何物理计算】——发热/储能/
     * 温度推进已由后台 round 在求解完成后立即执行（引擎异步计算；主线程只接收）。
     */
    public static void processPost(Level level) {
        if (level == null) return;
        // ① 求解完成 → 绑定器消息（后台每个结果批次逐条投递；平台侧处理）
        deliverSolveDoneMessages();
        // ② 后处理阶段链（组合：顺序即执行顺序，增删阶段只改表）
        for (PostStage stage : POST_STAGES) {
            try {
                stage.run(level);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 主线程后处理阶段：跑一次；阶段内异常由 {@link #processPost} 统一兜住。 */
    @FunctionalInterface
    interface PostStage {
        void run(Level level);
    }

    /**
     * 有序后处理阶段表（2026-09-12 组合化改造）。
     *
     * <p>原先这些调用连同各自的 `try/catch` 散落在 {@code processPost} 里（8 处样板），
     * 现在只保留一张表——顺序即执行顺序，增删/停用阶段改一处即可。
     *
     * <ol>
     *   <li>设备功率消息 → 绑定接口 BE（{@code [powerW]} 协议）</li>
     *   <li>设备过热销毁（每 8 tick）</li>
     *   <li>BE 桥 tick（活跃 BeBridge.serverTick）</li>
     *   <li>BE→引擎电机消息消费（每 tick，与求解轮无关）</li>
     *   <li>导线悬空检测（懒检测，周期由配置决定）</li>
     *   <li>绑定清理（每 4 tick）</li>
     *   <li>销毁队列处理</li>
     *   <li>引擎消息总线处理</li>
     * </ol>
     *
     * <p>已停用（重新设计中，实现保留待恢复）：热扩散
     * {@code collectThermalDiffusion/applyThermalDiffusion}、风扇冷却 {@code FanCoolingRegistry.apply}。
     */
    private static final List<PostStage> POST_STAGES = List.of(
            PipelinePostProcess::syncDevicePowerMessages,
            lv -> { if ((lv.getGameTime() & 0x7) == 0) PipelinePostProcess.checkDeviceOverheat(lv); },
            BeMessageParser::tickAll,
            PipelinePostProcess::consumeBecToEngineMotorMessages,
            WireDanglingDetector::tick,
            lv -> { if ((lv.getGameTime() & 0x3) == 0) DeviceBinding.cleanupRemoved(lv); },
            DestructionQueue::process,
            EngineBus::process);

    /**
     * 求解完成消息投递：后台 round 的每个结果批次 → 该网络已登记的绑定器
     * （{@code DeviceBinding.onMessage}，平台实现 common Binding——平台侧处理）。
     * 主线程遍历（平台处理安全）；单批次异常不影响后续批次。
     */
    private static void deliverSolveDoneMessages() {
        List<Object[]> nets;
        while ((nets = POST_NETS.poll()) != null) {
            try {
                for (Object[] o : nets) {
                    if (o == null || o.length < 1) continue;
                    if (o[0] instanceof PhasorNetworkContext nctx
                            && nctx.bindings != null) {
                        com.hdf.cryptand.engine.EngineMessage done =
                                com.hdf.cryptand.engine.EngineMessage.of(
                                        com.hdf.cryptand.engine.EngineMessage.Type.SOLVE_DONE,
                                        o.length >= 2 ? o[1] : null);
                        for (com.hdf.cryptand.engine.Binding b : nctx.bindings.values()) {
                            try { b.onMessage(done); } catch (Throwable ignored) { }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 设备计算功率 → BeBridge 消息（2026-08-24 建立；2026-08-26 用户架构修正：
     * 组装器只与【绑定的接口 BE】交互 → 唯一入口 be instanceof ICryptandCircuitBe，
     * 且桥协议必须 POWER 才发 [powerW]——绝不把功率消息发给电机桥（特化解析器
     * 按协议丢弃）。主线程；频率 = processPost 每 tick 一次。
     */
    // 后处理族（消息消费/过热销毁/热扩散）已迁至 PipelinePostProcess。

    // ===== 自管图缓存 / 稳定检测（roundFromGraph 专用） =====

    /** 自管分量缓存：seedKey → ctx + result + graphVer。WireGraph 变更即失效。 */
    private static final java.util.Map<String, CacheG> GRAPH_CACHES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class CacheG {
        final double freq;
        final PhasorNetworkContext ctx;
        final SolveResult result;
        final long graphVer;
        CacheG(double freq, PhasorNetworkContext ctx, SolveResult result, long graphVer) {
            this.freq = freq; this.ctx = ctx; this.result = result; this.graphVer = graphVer;
        }
    }

    /**
     * 【主线程只读】按方块位置查询后台最近一次求解结果（自管缓存 GRAPH_CACHES）。
     * <p>2026-09-11 用户：禁用主线程求解，只允许异步——主线程的设备行为读取
     *（发电机/变压器/绕组电压等）只消费后台产出，【绝不 build/solve】。
     * 未命中（本 tick 后台尚未求解/图变更失效）→ null，调用方按「暂无结果」处理。
     *
     * @return [0] = PhasorNetworkContext，[1] = SolveResult；无 → null
     */
    public static Object[] cachedSolveAt(BlockPos pos) {
        if (pos == null) return null;
        try {
            for (CacheG c : GRAPH_CACHES.values()) {
                if (c == null || c.ctx == null || c.result == null) continue;
                if (c.ctx.blockTerminals != null
                        && c.ctx.blockTerminals.containsKey(pos)) {
                    return new Object[]{c.ctx, c.result};
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 自管稳定检测：WireGraph 版本 + 连续稳定 tick 数。 */
    private static final class GraphStable {
        final long version;
        int ticks;
        GraphStable(long version, int ticks) { this.version = version; this.ticks = ticks; }
    }
    private static volatile GraphStable GRAPH_STABLE;
    /** [GraphBuild] 空构建/异常诊断节流（2026-08-26 定位"进世界不运行"） */
    /** 稳定检测版本变化诊断节流（2026-08-20） */
    /** [PairDbg] 诊断节流（2026-08-24 solveAll 顺序配对校验） */
    /** [SolveWaitOp] 诊断节流（2026-08-24） */
    /** [SolveMismatch] 诊断节流（2026-08-24） */

    /** roundFromGraph 诊断节流 */
    /** 阶段日志独立节流（2026-08-15 刷屏修复：原在阶段变化时每次都打，100Hz
     *  求解每轮 3 个阶段 → 日志 67% 都是 stage 行。改为 5s 固定节流，同时持续
     *  记录当前阶段——卡住定位仍可用（卡住时最后一条 stage 即卡点阶段）。 */
    private static String GRAPH_LAST_STAGE = "";

    /** 阶段日志（节流）：世界进入卡住定位——卡住时最后 stage 显示卡点阶段 */
    private static void graphStageDbg(String stage) {
        try {
            if (!stage.equals(GRAPH_LAST_STAGE)) {
                GRAPH_LAST_STAGE = stage;
            }
            long now = System.currentTimeMillis();
            if (AdapterDiag.gate("pipeline.graphStage", 5000)) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[GraphRound] stage={}", GRAPH_LAST_STAGE);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 进世界首轮是否优先从 SQLite 恢复结构（2026-09-15 用户："进入存档直接从 sqlite
     * 恢复"）。世界卸载时置 true，首轮消费后自动清除 —— 之后一律走内存缓存，
     * 保持"游戏期间零 SQLite 交互"的约定。
     */
    private static volatile boolean RESTORE_PREFERRED;

    /**
     * 从 SQLite 恢复一个分量的完整结构（用户架构：进存档直接恢复，不重建）。
     * <p>
     * 命中条件 = 稳定签名（排序端点键 + 排序边参数 + 频率的纯哈希，跨会话确定）一致；
     * 未命中返回 null，调用方退回 {@link #buildPendingFromGraph} 重建 ——
     * 恢复是【优先路径】而非唯一路径，存档里没有的网络照常能建起来。
     */
    private static PendingG tryRestoreFromDb(
            java.util.Collection<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp,
            String seedKey, double freq) {
        try {
            String sig = com.hdf.cryptand.neoforge.powergrid.persistence
                    .NetworkCacheManager.signatureOf(comp, freq);
            if (sig == null) return null;
            PhasorNetworkContext ctx = com.hdf.cryptand.neoforge.powergrid.persistence
                    .NetworkCacheManager.tryRestoreStructure(sig);
            if (ctx == null) return null;
            reattachParams(ctx);
            return new PendingG(seedKey, freq, sig, ctx, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 给【恢复来的】 ctx 重新挂参数源（2026-09-15）。
     * <p>
     * 恢复的 ctx 只有纯数据（元件/端子/映射），**没有** paramSources —— 那些是构建时
     * 注册的 lambda。不补这一步，`refreshParams()` 就空转：改电阻/变阻器/开关后网络
     * 不更新（2026-08-26 踩过同一个坑，当时的结论就是"恢复的 ctx 无 paramSources"）。
     * <p>
     * 怎么找回来：每个复合元件的 {@code compositeKey} 就是【类型码 + 坐标】
     *（如 {@code "MBlockPos{x=1, y=2, z=3}"}）⇒ 解析出 pos ⇒ 从主线程同步下来的
     * 参数缓存找到设备类 ⇒ 找到它的组装器 ⇒ 让组装器自己注册参数源
     *（它最清楚自己建了哪些可调元件）。全程只读纯数据缓存，不碰 Level/BE。
     */
    private static void reattachParams(PhasorNetworkContext ctx) {
        if (ctx == null || ctx.network == null) return;
        try {
            for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce
                    : ctx.network.composites()) {
                if (!(ce instanceof com.hdf.cryptand.circuitsimulation.model.composite
                        .CompositeModel cm)) {
                    continue;
                }
                net.minecraft.core.BlockPos pos =
                        com.hdf.cryptand.neoforge.powergrid.device
                                .CryptandCompositeFactory.posOf(ce.compositeKey());
                if (pos == null) continue;
                com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.Entry de =
                        DeviceParamCache.get(pos);
                if (de == null || de.deviceClass == null) continue;
                com.hdf.cryptand.neoforge.powergrid.device.Assembler asm =
                        com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                                .getByClass(de.deviceClass);
                if (asm != null) asm.registerParams(pos, cm, ctx.paramSources);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 自管模式构建（缓存检查 + buildContextFromGraph）。 */
    private static PendingG buildPendingFromGraph(Level level, String seedKey, double freq,
                                                  long graphVer) {
        try {
            CacheG c = GRAPH_CACHES.get(seedKey);
            if (c != null && c.graphVer == graphVer && c.freq == freq) {
                try { c.ctx.refreshParams(); } catch (Throwable ignored) { }
                // 2026-08-20 用户要求"网络不再记录是否有非线性部件，所有网络都
                // 进行计算，防止出现静止现象"：移除非线性判断——所有网络恒求解
                // （每轮推进状态，杜绝电压/电流静止导致电机停转/设备假状态）。
                // 原逻辑：非线性恒重解、全线性缓存命中（paramVersion==0 时复用）。
                // 现改为：恒重解（缓存仅复用 ctx 结构，结果每轮重新求解）。
                return new PendingG(seedKey, freq, c.ctx, null);
            }
            PhasorNetworkContext ctx = PhasorNetworkBuilder
                    .buildContextFromGraph(level, seedKey, freq);
            int elN = 0, ptN = 0;
            try { elN = ctx.network.elements().size(); } catch (Throwable ignored) { }
            try { ptN = ctx.pointToEngine.size(); } catch (Throwable ignored) { }
            if (elN == 0 || ptN == 0) {
                // ⚠ 2026-08-26 用户：强制所有网络求解——空构建（seed 找不到 network /
                //  分量无设备）【不再跳过】。空 ctx 也进求解（结果 0V、pointToEngine 空
                //  → 阶段3 无写回），杜绝"进世界不运行/静置不求解"（此前 return null
                //  → pendings 空 → 每轮停在 components 阶段从不 solved）。
                long nw = System.currentTimeMillis();
                if (AdapterDiag.gate("pipeline.graphEmpty", 2000)) {
                    boolean netFound = com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager.get().networkOf(seedKey) != null;
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[GraphBuild] EMPTY(force-solve) seed={} netFound={} elems={} pts={}",
                            seedKey, netFound, elN, ptN);
                }
                return new PendingG(seedKey, freq, ctx, null); // 空也强制求解
            }
            return new PendingG(seedKey, freq, ctx, null);
        } catch (Throwable t) {
            GRAPH_CACHES.remove(seedKey);
            long ew = System.currentTimeMillis();
            if (AdapterDiag.gate("pipeline.graphEmpty", 2000)) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[GraphBuild] EX seed={} err={}", seedKey, String.valueOf(t));
            }
            // 异常兜底：空网络强制求解（保证每轮都有求解产出，杜绝静默不运行）
            try {
                return new PendingG(seedKey, freq,
                        new PhasorNetworkContext(
                                new com.hdf.cryptand.circuitsimulation.model.Network(),
                                java.util.Collections.emptyMap(), freq), null);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** 自管分量待求解/待写回任务。 */
    private static final class PendingG {
        final String seedKey;
        final double freq;
        final String sig;   // 网络缓存稳定签名（null=未计算）
        final PhasorNetworkContext ctx;
        final SolveResult cached;
        PendingG(String seedKey, double freq, PhasorNetworkContext ctx, SolveResult cached) {
            this(seedKey, freq, null, ctx, cached);
        }
        PendingG(String seedKey, double freq, String sig,
                 PhasorNetworkContext ctx, SolveResult cached) {
            this.seedKey = seedKey; this.freq = freq; this.sig = sig;
            this.ctx = ctx; this.cached = cached;
        }
    }

    /** seed 端点所属网络 64 位 id（网络缓存记录用；找不到 → -1） */
    private static long networkIdOf(String seedKey) {
        try {
            com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork net =
                    WireNetworkManager.get()
                            .networkOf(seedKey);
            return net == null ? -1 : net.id;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 分量频率：从分量内端点方块反查（发电机/AC 源）。
     *  2026-08-13 自管化：直接枚举分量内 B 方块点 → 方块类型判定
     *  （AC 源/换向器）。不用导线 BFS（导线实体已删除）、不用原版网络
     *  节点扫描（自管模式禁原版维护，且属"读原版网络"）。
     *  2026-08-15 去 level：读 DeviceParamCache（主线程预同步频率）。 */
    private static double graphComponentFreq(
            java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp) {
        double best = 0;
        try {
            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                if (!WireKeyUtil.isBlock(p.key)) continue;
                net.minecraft.core.BlockPos pos = com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder.pointPosOfPublic(p.key);
                if (pos == null) continue;
                // 2026-08-15 去 level：频率从 DeviceParamCache（主线程预同步，含
                // AC 源配置频率 + 换向器动态频率）
                DeviceParamCache.Entry e = DeviceParamCache.get(pos);
                if (e == null || e.frequencyHz <= 0) continue;
                if (e.kind == DeviceParamCache.Kind.AC_VOLTAGE_SRC
                        || e.kind == DeviceParamCache.Kind.AC_CURRENT_SRC) {
                    // 2026-08-15 多 AC 源修复：主导频率 = 最低非零 AC 频率
                    // （多频时与 WaveformGroup.dominantFrequency 一致）
                    if (best <= 0 || e.frequencyHz < best) best = e.frequencyHz;
                } else if (e.deviceClass != null
                        && e.deviceClass.endsWith("CommutatorBlockEntity")) {
                    if (e.frequencyHz > best) best = e.frequencyHz; // 换向器兜底（无 AC 源）
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    /** 清零自管图所有分量节点电压（稳定前防残留旧电压烧线）。
     *  2026-08-15 去 level：自管模式只写自管宿主（TerminalRegistry 测试点），
     *  原版节点电压宿主已废弃（getVoltage 被接管直读测试点）→ 只失效测试点。 */
    private static void clearGraphVoltages() {
        try {
            TerminalRegistry.invalidateAll();
        } catch (Throwable ignored) {
        }
    }

    /**
     *   1. 全量清空 ctx 缓存 + 稳定检测（杜绝旧世界/旧拓扑 ctx 复用——旧网络
     *      若已消失，其 CACHES 条目残留会导致虚拟电路停留在旧状态）。
     *   2. 清空跨世界绑定/销毁队列（DeviceBinding/DestructionQueue，防 pos 串扰）。
     *   3. 孤儿状态清理：区块【已加载】但该位置【无对应设备】的虚拟快照/温度
     *      （设备已破坏/替换但虚拟残留）→ 删除，与实际模型对齐。
     *   4. 强制重建所有网络 ctx（构建时基于实际 BE：BE 不存在 → 自动跳过建模 →
     *      虚拟网表与实际一致）+ 参数刷新。
     *   5. 同步世界导线快照（避免正常模式首轮把已有导线误判为新增）。
     * 不求解不写回（[WorldSync] 日志报告检测结果）。
     */
    private static void worldSynchronize(Level level, List<ElectricalNetwork> networks) {
        // 1. 全量清空 ctx 缓存 + 稳定检测
        int cachedBefore = CACHES.size();
        CACHES.clear();
        NET_STABLE.clear();
        // 2. 跨世界绑定/销毁队列
        try {
            DeviceBinding.clearAll();
            DestructionQueue.clearAll();
            // ⚠ 2026-08-30 审计（C14 补充）：世界切换清设备缓存注册表——否则
            // 旧世界 pos 的缓存/组装器条目残留到新世界（同坐标换类型复用错误）。
            DeviceCacheRegistry.clearAll();
        } catch (Throwable ignored) {
        }
        // 2.5 SQLite 设备缓存加载（2026-08-13 用户架构：彻底取消 NBT——不用
        //     SavedData/NBT，缓存走 DeviceCacheTable 强类型列存储，根治"NBT 过
        //     大"）。进世界时从 SQLite 恢复【未加载区块】设备参数快照到
        //     VirtualDeviceStore——未加载区设备参数立即可用（虚拟建模不等区块
        //     加载扫描）。随后孤儿清理只处理已加载区（未加载保留），重建会
        //     覆盖已加载区快照。
        int restoredCache = 0;
        try {
            if (level instanceof net.minecraft.server.level.ServerLevel) {
                DeviceCacheTable cache = CryptandSqlite.deviceCache();
                if (cache != null) {
                    restoredCache = cache.count();
                    cache.restore();
                }
            }
        } catch (Throwable ignored) {
        }
        // 3. 孤儿状态清理（2026-08-13 用户要求：区块未加载的元件【跳过校验】）
        //    ——只有【已加载区块】（isLoaded=true）且该位置【无对应设备】
        //    （getBlockEntity==null，设备确已被破坏/替换）才算孤儿 → 删除。
        //    区块未加载（isLoaded=false，虚拟设备：BE 暂不在内存、用快照建模）
        //    → 跳过校验、保留快照/温度（绝不能删，加载后重建自然对齐）。
        int orphanVirtual = 0, orphanThermal = 0;
        try {
            for (BlockPos pos : VirtualDeviceStore.keys()) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) == null) {
                    VirtualDeviceStore.remove(pos);
                    orphanVirtual++;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            for (BlockPos pos : DeviceThermalStore.keys()) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) == null) {
                    DeviceThermalStore.remove(pos);
                    orphanThermal++;
                }
            }
        } catch (Throwable ignored) {
        }
        // 4. 强制重建所有网络 ctx（构建时基于实际 BE）+ 参数刷新
        int built = 0;
        long gt = level.getGameTime();
        for (ElectricalNetwork net : networks) {
            if (net == null || net.isEmpty()) continue;
            try {
                Pending p = buildPending(level, net, true);
                if (p == null) continue;
                if (p.cached == null) {
                    CACHES.put(net, new Cache(p.freq, gt, p.ctx, null,
                            net.getNodes().size(),
                            NetworkSignatures
                                    .wiresSignature(net),
                            NetworkSignatures
                                    .nodeSignature(net),
                            CryptandTopologyManager.get()
                                    .netVersionOf(net),
                            p.ctx.paramVersion.get()));
                }
                built++;
            } catch (Throwable ignored) {
            }
        }
        // 5. 同步世界导线快照（避免正常模式首轮误判新增）
        try {
            LAST_WW_SIG = NetworkSignatures
                    .worldWiresSignature();
            LAST_WIRES = new java.util.HashSet<>(WORLD_WIRES);
        } catch (Throwable ignored) {
        }
        // 检测日志（节流）
        long inNow = System.currentTimeMillis();
        if (AdapterDiag.gate("pipeline.init", 1000)) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[WorldSync] built={}/{} nets ctxCleared={} cacheRestored={} orphan virtual={} thermal={}",
                    built, networks.size(), cachedBefore, restoredCache,
                    orphanVirtual, orphanThermal);
        }
    }

    /** 阶段1：缓存检查 + 构建 ctx（主线程）。失败返回 null。 */
    private static Pending buildPending(Level level, ElectricalNetwork net) {
        return buildPending(level, net, false);
    }

    /** 阶段1：缓存检查 + 构建 ctx。force=true（进入世界初始化）→ 忽略缓存强制重建。 */
    private static Pending buildPending(Level level, ElectricalNetwork net, boolean force) {
        long gt = level.getGameTime();
        try {
            double freq = MultimeterDebug.getNetworkFrequencyHz(net); // 0 = 直流

            // 缓存命中：同频率、网络节点数相同、且 wires 签名相同（无导线接入/移除）、
            // 且【网络级拓扑版本相同】→ 结构复用（不重建电路网络）：先刷新可调参数
            // （读方块值 → setter；值变化经元件参数变化消息 → ctx.paramVersion++），
            // 参数未变则直接复用旧结果，参数变了才用同一 ctx（网络不重建）重解。
            // 无定期兜底更新。
            // ⚠ wires 签名是关键：新导线接入【不改变节点数】，只按节点数判断会
            // 命中旧 context（不含新导线合并）→ 新导线两端不合并 → 一端 0V 一端
            // 有电压 → 虚假大电流烧线（[WireBurn] 实锤：(0,0,12)#1=0V vs 副边=35V）。
            // ⚠【网络级 netVer 是治本】（2026-08-12 无感重建）：新导线可能【不在
            // 网络 wires】（PowerGrid addWire/deferredRewire 延迟）→ wires 签名不变
            // → 仍需命中失效。addWire/removeWire hook 携带网络实例 → 该网络
            // netVer++ → 此处必然 miss → 强制重建 ctx（WORLD_WIRES 全量扩展收集
            // 新导线两端）→ 等电位 → 不烧线。其他网络 netVer 不变 → 缓存命中
            // （零重建零卡顿）。
            // ⚠【阶段0 世界导线定位兜底】：即使导线接入【未触发 addWire hook】
            // （变压器副边接线可能走特殊路径），WORLD_WIRES 全量表【创建即加入】
            // → 阶段0 按新增导线端点网络精确失效 → 无烧线窗口（替代原全局
            // worldWireSig 校验——后者使任何新导线 → 所有网络缓存全失效 → 卡顿）。
            Cache c = CACHES.get(net);
            long netVer = CryptandTopologyManager.get()
                    .netVersionOf(net);
            // ⚠ 自管图版本校验（2026-08-13 阶段1）：WireGraph.version 变化（导线
            // 新增/移除，含 hook 漏网/自管源）→ 该网络可能受影响 → 强制 miss。
            // 自管图为唯一拓扑真相，版本变化 = 拓扑变化。
            long graphVer = WireNetworkManager.get().version();
            if (!force && c != null && c.freq == freq
                    && net.getNodes().size() == c.pgNodes
                    && c.netVer == netVer
                    && c.graphVer == graphVer
                    && NetworkSignatures
                            .nodeSignature(net) == c.nodeSig
                    && NetworkSignatures
                            .wiresSignature(net) == c.wireSig) {
                // 无闭合回路 → 恒跳过计算（不求解）：单独记录 + 参数重置。
                // 回写全 0 结果（真实清零，非等电位到接线端）→ 清除 PowerGrid
                // 节点残留（万用表测悬空端应为 0）。接入回路时 topoVer++ →
                // 缓存失效 → 重建合并。
                // ⚠ 也必须刷新参数：灯泡装入/开关切换不触发 topoVer++（SwitchedWire
                //   已过滤），但可能使网络从无回路变有回路（灯座开路→闭合）→
                //   参数变了 → 强制重建重新判定回路（否则灯永远不亮）。
                if (c.ctx.loopless) {
                    resetComputation(level, c.ctx);
                    try { c.ctx.refreshParams(); } catch (Throwable ignored) { }
                    if (c.ctx.paramVersion.get() != c.paramVersion) {
                        CACHES.remove(net);
                        // 参数变化（灯泡/开关）→ 可能形成回路 → 重建重新判定
                        PhasorNetworkContext ctx2 =
                                PhasorNetworkBuilder.buildContextFromNetwork(level, net, freq);
                        if (ctx2.network.elements().isEmpty() || ctx2.nodeToEngine.isEmpty()) {
                            CACHES.remove(net);
                            return null;
                        }
                        if (ctx2.loopless) {
                            resetComputation(level, ctx2);
                            SolveResult zero2 = zeroResult(ctx2);
                            CACHES.put(net, new Cache(freq, gt, ctx2, zero2,
                                    net.getNodes().size(),
                                    NetworkSignatures
                                            .wiresSignature(net),
                                    NetworkSignatures
                                            .nodeSignature(net),
                                    netVer, ctx2.paramVersion.get()));
                            return new Pending(net, freq, ctx2, zero2, gt);
                        }
                        return new Pending(net, freq, ctx2, null, gt);
                    }
                    return new Pending(net, freq, c.ctx, c.result, gt);
                }
                // 结构复用（不重建网络）：刷新可调参数（同一线程）。
                // 同一 tick 同一网络多个元件变化 → paramVersion 只判“变没变” → 合并一次重解。
                try { c.ctx.refreshParams(); } catch (Throwable ignored) { }
                // 稳态跳过（2026-08-20 用户要求：稳态电路跳过，加强实时能力）：
                // 网络状态已稳定（温度到稳态/转速到稳态，stateSettled=true）+
                // 参数未变 → 完全跳过（复用缓存，不求解不写回）。
                // ⚠ 线性无状态网络（nonlinearCount=0）stateSettled 恒 true → 恒跳过
                // （本来参数未变也复用，行为不变）；非线性网络状态未稳定才继续求解。
                boolean nonlinear = c.ctx.network != null
                        && c.ctx.network.nonlinearCount() > 0;
                boolean settled = c.ctx.network == null
                        || c.ctx.network.stateSettled;
                if (c.ctx.paramVersion.get() == c.paramVersion && (!nonlinear || settled)) {
                    return new Pending(net, freq, c.ctx, c.result, gt); // 参数未变+稳态 → 复用
                }
                return new Pending(net, freq, c.ctx, null, gt); // 非线性未稳定/参数变化 → 重解（不重建）
            }

            // 重新构建
            PhasorNetworkContext ctx = PhasorNetworkBuilder.buildContextFromNetwork(level, net, freq);
            if (ctx.network.elements().isEmpty() || ctx.nodeToEngine.isEmpty()) {
                CACHES.remove(net);
                // 诊断（节流 2s）：构建结果为空 → solved 恒 0 → 设备不工作。
                // 后台线程读 Level/网络与主线程竞争可能抛异常被吞 → 空 ctx。
                long emptyNow = System.currentTimeMillis();
                if (emptyNow - BUILD_EMPTY_LAST >= 2000) {
                    BUILD_EMPTY_LAST = emptyNow;
                    try {
                        StringBuilder esb = new StringBuilder();
                        for (org.patryk3211.powergrid.electricity.sim.node.INode in : net.getNodes()) {
                            if (in instanceof OwnedFloatingNode ofn && ofn.endpoint != null) {
                                esb.append(ofn.endpoint).append(' ');
                                if (esb.length() > 300) { esb.append("..."); break; }
                            }
                        }
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[BuildEmpty] netNodes={} elements={} nodeMap={} nodes=[{}]",
                                net.getNodes().size(), ctx.network.elements().size(),
                                ctx.nodeToEngine.size(), esb);
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }
            // 无闭合回路 → 单独记录（缓存 ctx：接入回路时结构可复用，免平时反复
            // 构建）+ 不运算（不求解）+ 参数重置（断开后计算数据归零）。
            // 回写全 0 结果（真实清零，非等电位到接线端）→ 清除 PowerGrid 节点
            // 残留（万用表测悬空端应为 0）。
            if (ctx.loopless) {
                resetComputation(level, ctx);
                SolveResult zero = zeroResult(ctx);
                CACHES.put(net, new Cache(freq, gt, ctx, zero,
                        net.getNodes().size(),
                        NetworkSignatures
                                .wiresSignature(net),
                        NetworkSignatures
                                .nodeSignature(net),
                        netVer, ctx.paramVersion.get()));
                return new Pending(net, freq, ctx, zero, gt);
            }
            return new Pending(net, freq, ctx, null, gt);
        } catch (Throwable t) {
            CACHES.remove(net);
            return null;
        }
    }

    /** 无回路网络的全 0 结果（所有节点 0V，跳过求解）。 */
    private static SolveResult zeroResult(PhasorNetworkContext ctx) {
        int nc = 0;
        for (Integer id : ctx.nodeToEngine.values()) nc = Math.max(nc, id + 1);
        return new SolveResult(new double[nc], true, 0, 0,
                com.hdf.cryptand.circuitsimulation.solver.SolveMode.REAL_DC);
    }

    /**
     * 无回路/断开 → 电气状态重置 + 温度【自然冷却】（2026-08-12 v2）：
     * 设备/变压器电流、损耗等电气状态清零（静音、停止发热），但温度不瞬间归环境
     * （原 reset 直接归环境 → 空载/断开瞬间温度突变）。改为以 0 功率推进 →
     * 指数冷却到环境（保留热惯性），重新闭合从当前温度继续。
     */
    public static void resetComputation(Level level, PhasorNetworkContext ctx) {
        if (ctx == null) return;
        long now = System.nanoTime();
        for (ThermalDevice td : ctx.deviceThermals.values()) {
            try {
                ThermalModel th = td.thermal();
                if (th != null) th.advance(0, now); // 0 功率 → 自然冷却
            } catch (Throwable ignored) {
            }
        }
        for (BlockPos p : ctx.transformerModels.keySet()) {
            try {
                TransformerHeatStore.resetElectrical(p); // 清电气状态（静音/停热）
                ThermalModel th = TransformerHeatStore.getThermal(p);
                if (th != null) th.advance(0, now);     // 温度自然冷却
            } catch (Throwable ignored) {
            }
        }
    }


    /** 节点 → 位置Key（BlockWireEndpoint: "B" + pos#term；Junction: "J" + block pos）。 */
    public static String posKeyOf(OwnedFloatingNode node) {
        if (node == null || node.endpoint == null) return null;
        if (node.endpoint instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bep) {
            return "B" + bep.getPos() + "#" + bep.getTerminal();
        }
        if (node.endpoint instanceof org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint jep) {
            try {
                Level lv = CRYPTAND_LAST_LEVEL;
                if (lv == null) return null;
                return "J" + net.minecraft.core.BlockPos.containing(jep.getExactPosition(lv));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
