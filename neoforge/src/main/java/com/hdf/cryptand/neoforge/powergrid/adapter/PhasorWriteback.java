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

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;

import java.util.List;
import java.util.Map;

public final class PhasorWriteback {

    private PhasorWriteback() {}

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
                    com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().version(),
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

    /**
     * 【稳态网络动力学推进，2026-08-20 用户要求】：
     * 所有网络（含矩阵求解被跳过的稳态网络）都用缓存结果推进温度等依赖时间的
     * 动力学模型——电压稳态 ≠ 温度稳态（通电设备持续发热到稳态）。
     * 主线程每 tick 调用；节流由 SimClock（tickThrottle）控制，dt=0 时跳过。
     * 返回值：实际推进了状态（dt>0）的网络数。
     */
    public static int advanceStatesOfAllCached() {
        // ⚠⚠ 2026-08-24 停用：此方法对 CACHES（测量/原版网络模型）推进状态，
        // 而 round 每轮 solveAll → advancePseudoTime 已【无条件推进全部网络】
        // （含稳态）。主线程再推 = 同一物理设备两套模型双推进（advanceState/…
        // setEmf 改 structureHash）→ 与异步 round 的指纹拍照交叉 → 每轮假
        // [WireHeatMismatch] + 状态漂移。统一由 round 单点推进（只更新最新）。
        return 0;
        /* 旧逻辑保留注释（如需恢复参考）
        // 用 lastDt（主线程最近 tick 的推进量）判断是否到节拍点
        double step = com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine.lastSimDt();
        if (step <= 0) return 0; // 节流中/主线程未推进 → 不推进（安全）
        int advanced = 0;
        for (Cache c : CACHES.values()) {
            if (c == null || c.ctx == null || c.result == null) continue;
            com.hdf.cryptand.circuitsimulation.model.Network net = c.ctx.network;
            if (net == null || net.composites().isEmpty()) continue;
            // 已由 round 求解路径推进过（solveAll → advancePseudoTime）→ 跳过
            // （round 里的 nets 收集已推进；这里只补稳态跳过未推进的）。
            // 用 stateSettled 区分：false = 状态仍在变化（round 会推进，这里
            // 必须跳过——否则两个线程用不同电压（新解 vs 缓存）双推进同一
            // 状态 → 转速来回震荡 → 电机"停转"）；
            // true = 已稳态 → round 不重解 → 这里仍需推进（温度持续发热到
            // 稳态的过程，且 EMF 变化会 paramVersion++ → 下轮重解闭环）。
            if (!net.stateSettled) continue; // round 路径已推进，跳过（防双推进）
            try {
                boolean changed = false;
                com.hdf.cryptand.circuitsimulation.solver.Complex[] ph = c.result.complex;
                if (ph == null) continue;
                int n = net.nodeCount();
                // 2026-08-21 用户要求"AC/DC 同一套系统"：统一相量 mode（DC 也是
                // 0Hz 相量，不再区分 REAL_DC）
                com.hdf.cryptand.circuitsimulation.solver.SolveMode mode =
                        com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC;
                for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce
                        : net.composites()) {
                    try {
                        // 2026-08-20 修复"5A 电流导线 300°C"（双推进温度）：
                        // 导线段温度只由 computeWireHeatOne（processPost 统一
                        // 发热）推进，这里跳过——否则稳态网络导线也被双推进
                        // （本方法一次 + computeWireHeatOne 一次）→ 温度翻倍。
                        if (ce instanceof com.hdf.cryptand.circuitsimulation.model.composite.WireComposite) {
                            continue;
                        }
                        ce.setNodeVoltages(ph);
                        int a = ce.nodeA(), b = ce.nodeB();
                        com.hdf.cryptand.circuitsimulation.solver.Complex va =
                                (a >= 0 && a < n) ? ph[a] : null;
                        com.hdf.cryptand.circuitsimulation.solver.Complex vb =
                                (b >= 0 && b < n) ? ph[b] : null;
                        if (ce instanceof com.hdf.cryptand.circuitsimulation.model.state.StateDriven sd) {
                            if (sd.advanceState(va, vb, c.freq, step, mode)) changed = true;
                        } else {
                            ce.update(va, vb, 2 * Math.PI * Math.max(c.freq, 0),
                                    System.nanoTime());
                        }
                        if (ce instanceof com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice ed) {
                            ed.syncCharge(va, vb);
                        }
                        if (ce.stateChanging()) changed = true;
                    } catch (Throwable ignored) {
                    }
                }
                net.stateSettled = !changed;
                advanced++;
            } catch (Throwable ignored) {
            }
        }
        return advanced;
        */
    }

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

    /** 最近一次成功回写的 context/result（供 [WireBurn] 诊断反查：烧线导线两端
     *  节点在 nodeToEngine 的映射 id + 求解电压 → 实证定位“合并成功但电压不同”
     *  的环节：不在 ctx（未回写→0V）/ 同 id 但电压不同（求解）/ 不同 id（合并失效）。 */
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
            int wwSig = com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                    .worldWiresSignature();
            if (wwSig == LAST_WW_SIG) return;
            java.util.List<org.patryk3211.powergrid.electricity.sim.special.TransmissionLine>
                    cur = WORLD_WIRES;
            if (!cur.isEmpty()) {
                for (org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl : cur) {
                    if (LAST_WIRES.contains(tl)) continue;
                    ElectricalNetwork n1 = networkOfNode(tl.getNode1());
                    ElectricalNetwork n2 = networkOfNode(tl.getNode2());
                    if (n1 != null) com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager
                            .get().markNetworkChanged(n1);
                    if (n2 != null) com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager
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
    private static volatile long UNSTABLE_DBG_LAST;
    /** 构建结果为空诊断节流 */
    private static volatile long BUILD_EMPTY_LAST;
    /** 进入世界初始化诊断节流（2026-08-12） */
    private static volatile long INIT_DBG_LAST;

    /** 网络是否重建完成（连续 STABLE_TICKS 结构签名不变）。 */
    private static boolean isNetworkStable(ElectricalNetwork net) {
        int pg = net.getNodes().size();
        int ws = com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder.wiresSignature(net);
        int ns = com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder.nodeSignature(net);
        long nv = com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
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
                PhasorEngine.invalidateTerminals(net);
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

    /**
     * 每 tick 主线程调用：对每个物理分量做相量求解并回写节点 RMS 电压。
     * 返回成功回写的网络数。
     * <p>
     * 2026-08-10 关键修复【cluster 去重】：buildContextFromNetwork 的 BFS 会跨
     * 变压器/跨导线把【所有相连网络】收进同一 ctx（ctx.nodeToEngine 含相连网络
     * 全部节点）。若不按物理连通集合去重，则每个 subnetwork 都独立建 ctx、独立
     * 求解、独立写回 → 同一物理节点被多个 ctx 写成不同电压（BFS 收集集合/求解
     * 顺序不同）→ 后面的覆盖前面的 → 电压每 tick 来回跳（[WbCtx] 实锤：
     * (-1,0,9)#0 在 75126645 ctx=70.71V、2050987286 ctx=-50V）→ 电机抖动/
     * 万用表测到瞬态小值/烧线。
     * 修复：每个物理连通集合只 writeback 一次；成功后把 ctx 覆盖的所有网络
     * （nodeToEngine 每个节点的 getNetwork()）标记为已处理，后续 subnetwork 跳过。
     * <p>
     * 2026-08-10 v2【按物理节点去重，而非网络对象】：PowerGrid 原版拓扑管理
     * （islandDiscovery/postTick）会把【物理同一网络】分裂成多个 ElectricalNetwork
     * 对象（[WbNetDbg] 实锤：1887634801↔14316405 同一物理节点集合、对象不同；
     * Winding 进出使节点数 12↔14 跳变）。按【网络对象】去重时，这些分裂对象
     * 各自独立 writeback → 同一物理节点被多个 ctx 覆盖 → 变压器端子电压跳变
     * （[ArrDbg] arr=[4,1,3,0]↔[0,5,2,4]↔[4,null,5,6] 每 2-3 秒变）→ 电机/
     * 变压器“运行停止来回跳动”。改用【物理节点集合】去重：OwnedFloatingNode
     * 是稳定的（endpoint 固定，网络分裂不换对象）——任何节点只要已被某 ctx
     * 写回，后续网络（即使对象不同、物理相同）一律跳过。
     * <p>
     * 2026-08-11 v3【三阶段流水线 + 网络间并行】（超大型网络多线程）：
     *   阶段1 build（主线程，读 Level）：物理去重 + 缓存检查 + buildContextFromNetwork
     *   阶段2 solve（并行，纯计算）：PhasorEngine.solveAll 把多个网络的相量求解
     *     并行提交到多线程求解器（ComputeScheduler/LocalComputeEngine 线程池）
     *   阶段3 writeback（主线程，写 Level）：应用开路 + 缓存 + 写回节点电压
     *   构建/写回因 Minecraft Level 非线程安全必须在主线程；求解是纯数据计算
     *   （NetworkSnapshot），天然多线程——网络间并行由此获得，且为后续
     *   稀疏求解（SuperLU/EJML）+ 撕裂法（变压器分块）预留统一入口。
     */
    /** 分阶段性能诊断（2026-08-12）：累计 build/solve/writeback 耗时，节流打印 */
    private static long PERF_BUILD_NS, PERF_SOLVE_NS, PERF_WRITE_NS;
    private static int PERF_ROUNDS;
    private static long PERF_LAST_MS;
    private static void perfAdd(long t0, long t1, long t2, long t3) {
        PERF_BUILD_NS += t1 - t0;
        PERF_SOLVE_NS += t2 - t1;
        PERF_WRITE_NS += t3 - t2;
        PERF_ROUNDS++;
        long now = System.currentTimeMillis();
        if (now - PERF_LAST_MS >= 5000) {
            PERF_LAST_MS = now;
            int r = Math.max(1, PERF_ROUNDS);
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[Perf] rounds={} build={}ms solve={}ms write={}ms total={}ms",
                    PERF_ROUNDS,
                    PERF_BUILD_NS / 1_000_000 / r,
                    PERF_SOLVE_NS / 1_000_000 / r,
                    PERF_WRITE_NS / 1_000_000 / r,
                    (PERF_BUILD_NS + PERF_SOLVE_NS + PERF_WRITE_NS) / 1_000_000 / r);
            PERF_BUILD_NS = PERF_SOLVE_NS = PERF_WRITE_NS = 0;
            PERF_ROUNDS = 0;
        }
    }

    public static int round(Level level, List<ElectricalNetwork> networks) {
        return round(level, networks, false);
    }

    /**
     * 求解一轮（forceInit=false 正常模式）。forceInit=true 为【进入世界初始化】
     * （2026-08-12 用户要求）：跳过稳定检测，对所有网络强制重建 ctx（忽略缓存）
     * + 刷新参数（不求解不写回，避免写入变化中的不稳定值）——进世界后先把
     * 所有网络建好/参数更新完，再开始正常（稳定检测 + 求解写回）运转。
     */
    static int round(Level level, List<ElectricalNetwork> networks, boolean forceInit) {
        if (PhasorEngine.isServerPaused()) return 0; // 暂停 → 不提交后台求解
        if (level == null || networks == null || networks.isEmpty()) {
            return 0;
        }
        CRYPTAND_LAST_LEVEL = level;
        PhasorEngine.init();
        long t0 = System.nanoTime();

        // ===== 进入世界初始化（2026-08-12 用户要求）+ 基于实际模型一致性检测
        // （2026-08-13 用户要求：防止世界变化后虚拟电路没更新） =====
        // 全量重建所有网络 ctx + 参数更新（忽略缓存/稳定检测），不求解不写回。
        // 重建的 ctx 立即缓存（result=null）→ 正常模式第一轮缓存命中、只求解，
        // 不会重复重建（初始化真正生效）。
        if (forceInit) {
            worldSynchronize(level, networks);
            return 0;
        }

        // ===== 阶段0（2026-08-12 无感重建烧线兜底）：世界新增导线 → 按端点
        // 网络精确失效（网络级）。替代原【全局 worldWireSig 缓存校验】——后者
        // 任何新导线 → 所有网络缓存全失效 → 加设备卡顿。 =====
        try {
            trackNewWorldWires();
        } catch (Throwable ignored) {
        }

        // ===== 稳定检测（2026-08-12 用户要求） =====
        // 分量内任一网络在重建（结构签名变化）→ 整个分量【清零 + 跳过】：
        // 清零使导线两端等电位 0 → 无压差 → 不烧（直接接线瞬间/进世界建立期）。
        // 稳定后（连续 3 tick 不变）才正常求解写回 → “重建完成一个网络这个网络开始求解”。
        {
            boolean stable = true;
            for (ElectricalNetwork net : networks) {
                if (!isNetworkStable(net)) {
                    stable = false;
                    break;
                }
            }
            if (!stable) {
                for (ElectricalNetwork net : networks) {
                    // 清零网络：只失效端子测试点（2026-08-20 起不再 setValue
                    // 原版节点——完全禁止写入原版 PowerGrid 节点）。失效后消费端
                    // 读 null → 不转，防重建/清零期读到旧值误启动。
                    clearNetworkVoltages(level, net);
                }
                // 诊断（节流 2s）：稳定检测失败 → 打印各网络签名（定位后台构建与
                // 主线程拓扑维护竞争 → 网络持续不稳定 → solved=0 → 设备不工作）
                long unNow = System.currentTimeMillis();
                if (unNow - UNSTABLE_DBG_LAST >= 2000) {
                    UNSTABLE_DBG_LAST = unNow;
                    try {
                        StringBuilder usb = new StringBuilder();
                        for (ElectricalNetwork net2 : networks) {
                            usb.append("[").append(net2.getNodes().size())
                                    .append("n sig=").append(PhasorNetworkBuilder.wiresSignature(net2))
                                    .append(" netVer=").append(CryptandTopologyManager.get()
                                            .netVersionOf(net2))
                                    .append("] ");
                        }
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[Unstable] nets={} topo={} [{}]", networks.size(),
                                CryptandTopologyManager.get().topoVersion(), usb);
                    } catch (Throwable ignored) {
                    }
                }
                return 0;
            }
        }

        // ===== 阶段1（主线程）：物理去重 + 缓存检查 + 构建 ctx =====
        // 构建版本快照（2026-08-12 无感重建→网络级）：round 在服务端主线程执行，
        // 但构建/求解期间主线程可能处理接线事件（addWire/removeWire → 该网络
        // netVer++）。构建/求解期间若【本网络】版本变化 → 本批结果基于【旧拓扑】
        // → 阶段2 后丢弃（不写回），下一轮自动重建。其他网络版本不变 → 结果保留
        // （不再像全局 topoVersion 那样整批丢弃 → 加设备不影响无关网络）。
        java.util.Set<OwnedFloatingNode> written = new java.util.HashSet<>();
        List<Pending> pendings = new java.util.ArrayList<>();
        for (ElectricalNetwork net : networks) {
            if (net == null || net.isEmpty()) continue;
            // 该网络所有节点都已被此前某 ctx 写回（物理同一网络被分裂成多对象
            // 时，后出现的对象全节点已写）→ 跳过，绝不再覆盖。
            boolean allWritten = true;
            for (org.patryk3211.powergrid.electricity.sim.node.INode in : net.getNodes()) {
                if (in instanceof OwnedFloatingNode ofn && !written.contains(ofn)) {
                    allWritten = false;
                    break;
                }
            }
            if (allWritten) continue;
            long netVerAtBuild = com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager
                    .get().netVersionOf(net);
            Pending p = buildPending(level, net);
            if (p == null) continue;
            p.buildNetVer = netVerAtBuild;
            pendings.add(p);
            written.addAll(p.ctx.nodeToEngine.keySet());
        }
        if (pendings.isEmpty()) return 0;
        long t1 = System.nanoTime();

        // ===== 阶段2（并行）：只对未缓存命中的网络并行求解 =====
        java.util.List<com.hdf.cryptand.circuitsimulation.model.Network> toSolve = new java.util.ArrayList<>();
        int[] solveIdx = new int[pendings.size()];
        java.util.Arrays.fill(solveIdx, -1);
        for (int i = 0; i < pendings.size(); i++) {
            if (pendings.get(i).cached == null) {
                solveIdx[i] = toSolve.size();
                toSolve.add(pendings.get(i).ctx.network);
            }
        }
        java.util.List<SolveResult> results = toSolve.isEmpty()
                ? java.util.List.of() : PhasorEngine.solveAll(toSolve);
        long t2 = System.nanoTime();

        // ===== 构建版本校验（2026-08-12 无感重建→网络级）：构建/求解期间该网络
        // 拓扑变化 → 丢弃该网络重建（防止用旧拓扑结果写回已变化世界 → 烧线）。
        // 变化来源：主线程 addWire/removeWire → markNetworkChanged → 该网络
        // netVer++（volatile map，后台线程可见）。其他网络版本不变 → 本批保留。 =====
        boolean anyNetChanged = false;
        for (Pending p2 : pendings) {
            if (com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                    .netVersionOf(p2.net) != p2.buildNetVer) {
                anyNetChanged = true;
                break;
            }
        }
        if (anyNetChanged) {
            // 诊断（节流 2s）：构建/求解期间拓扑变化 → 丢弃重建。若持续变化
            // （后台构建 vs 主线程拓扑维护竞争）→ solved 恒 0 → 设备不工作。
            long tvNow = System.currentTimeMillis();
            if (tvNow - UNSTABLE_DBG_LAST >= 2000) {
                UNSTABLE_DBG_LAST = tvNow;
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[TopoChange] netVerChanged pendings={}",
                        pendings.size());
            }
            for (Pending p2 : pendings) CACHES.remove(p2.net);
            return 0;
        }

        // ===== 阶段3（主线程）：开路跟随 + 缓存 + 写回 =====
        int solved = 0;
        java.util.List<Object[]> nets = new java.util.ArrayList<>();
        for (int i = 0; i < pendings.size(); i++) {
            Pending p = pendings.get(i);
            SolveResult res = p.cached;
            if (res == null) {
                int si = solveIdx[i];
                res = (si >= 0 && si < results.size()) ? results.get(si) : null;
                if (res == null || !res.converged) {
                    // 2026-08-20 预算耗尽修复（电机停转）：solveAll 可能因推进
                    // 预算耗尽返回空列表（非求解失败）→ 若缓存里有旧结果则复用
                    // （安全滞后，下轮预算恢复再更新），【不删缓存不丢弃】；
                    // 仅当缓存也没有旧值（真无结果）才跳过本轮。否则预算频繁
                    // 耗尽 → 缓存被删 + 结果不写回 → 电压/EMF 锁死旧值 → 电机
                    // 停转（拆线重放重建才恢复）。
                    Cache old = CACHES.get(p.net);
                    if (old != null && old.result != null && old.result.converged) {
                        res = old.result; // 预算耗尽 → 复用旧结果（滞后安全）
                    } else {
                        CACHES.remove(p.net);
                        continue;
                    }
                }
            }
            try {
                PhasorEngine.applyOpenTerminals(res, p.ctx);
            } catch (Throwable ignored) {
            }
            LAST_CTX = p.ctx;
            LAST_RES = res;
            if (p.cached == null) {
                CACHES.put(p.net, new Cache(p.freq, p.gameTime, p.ctx, res,
                        p.net.getNodes().size(),
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                .wiresSignature(p.net),
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                .nodeSignature(p.net),
                        com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                                .netVersionOf(p.net),
                        p.ctx.paramVersion.get()));
            }
            if (writebackVoltages(level, p.net, p.ctx, res)) solved++;
            // 收集（ctx, res, freq）：统一发热处理在循环后按 pos 去重执行
            nets.add(new Object[]{p.ctx, res, p.freq});
            // 频率传递（2026-08-12 用户要求）：不只电压，频率也随变压器传递——
            // 把含变压器 ctx 的求解频率传播到其覆盖的所有网络（副边网络无源
            // 但继承原边 AC 频率 → 副边电机/万用表/声音按 AC 处理，不会因读到
            // DC(0Hz) 而不转）。
            if (p.freq > 0) {
                for (OwnedFloatingNode ofn2 : p.ctx.nodeToEngine.keySet()) {
                    ElectricalNetwork nn = ofn2.getNetwork();
                    if (nn != null) MultimeterDebug.setNetworkFrequency(nn, p.freq);
                }
            }
        }
        // 统一发热处理（每 tick 每个变压器/设备 pos 只处理一次，选最高频率网络）：
        // 变压器隔开原边/副边网络 → 同一 pos 在多个网络 ctx 出现 → 只算一处。
        long t3 = System.nanoTime();
        try {
            PhasorEngine.computeTransformerHeatUnified(level, nets);
        } catch (Throwable ignored) {
        }
        try {
            PhasorEngine.computeDeviceHeatUnified(level, nets);
        } catch (Throwable ignored) {
        }
        try {
            PhasorEngine.computeWireHeatUnified(level, nets);
        } catch (Throwable ignored) {
        }
        // 统一储能推进（2026-08-12：电容/电池复合模型——求解后同步储能电荷，
        // 时间相关变量绑定；每 pos 选最高频率网络处理一次）
        try {
            PhasorEngine.computeEnergyUnified(level, nets);
        } catch (Throwable ignored) {
        }
        // 元件移除检测（2026-08-13 双向绑定：BE 已被破坏/卸载但绑定残留 → 通知
        // 复合元件移除 → 实际模型清理温度/虚拟快照 + 注销绑定）。节流每 4 tick
        // （低频清理，破坏感知由 setRemoved → DeviceBinding 即时处理）。
        try {
            if ((level.getGameTime() & 0x3) == 0) DeviceBinding.cleanupRemoved(level);
        } catch (Throwable ignored) {
        }
        // 销毁队列处理（2026-08-13 用户架构点 5）：过热组件入队 → 模型已加载则
        // 销毁（破坏+爆炸+清理）；未加载等加载；已破坏直接移出。主线程执行。
        try {
            DestructionQueue.process(level);
        } catch (Throwable ignored) {
        }
        // 引擎消息总线（2026-08-15 完全异步）：后台线程发消息（销毁/网络失效），
        // 主线程统一消费执行世界副作用。
        try {
            EngineBus.process(level);
        } catch (Throwable ignored) {
        }
        perfAdd(t0, t1, t2, t3);
        return solved;
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
                var am = com.hdf.cryptand.neoforge.powergrid.adapter
                        .MainThreadInteractionManager.get().asyncManager();
                if (am != null && am.size() > 0) {
                    long now0 = System.currentTimeMillis();
                    if (now0 - OP_WAIT_DBG_LAST >= 5000) {
                        OP_WAIT_DBG_LAST = now0;
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[SolveWaitOp] opTable busy={} (op-table first; others solve, ver-guard)",
                                am.size());
                    }
                    // ⚠ 2026-08-26：不再 return 0（其余网络照常求解）
                }
            } catch (Throwable ignored) {
            }
        }
        long t0 = System.nanoTime();
        var mgr = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get();
        // 诊断（节流 5s）：转换状态 + 自管图大小 + 世界导线数（定位"电压全 0"）
        // 2026-08-20 排查"交流源+电阻+电机 导线烧毁"：追加打印每个分量的节点
        // key + 边数——直接看用户电路是否分裂（应 1 分量含全部设备端子）。
        try {
            long now = System.currentTimeMillis();
            if (now - GRAPH_ROUND_DBG_LAST >= 5000) {
                GRAPH_ROUND_DBG_LAST = now;
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
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[GraphRound] convert={} graphNodes={} graphEdges={} graphVer={} "
                                + "worldWires={} worldNets={} comps={}",
                        com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter.isEnabled(),
                        mgr.nodeCount(), mgr.edgeCount(), mgr.version(),
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_WIRES.size(),
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_NETS.size(),
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
                    if (nowV - STABLE_DBG_LAST >= 5000) {
                        STABLE_DBG_LAST = nowV;
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
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
                PendingG p = buildPendingFromGraph(level, seedKey, freq, graphVer);
                if (p == null) {
                    try {
                        String sigR = com.hdf.cryptand.neoforge.powergrid.persistence
                                .NetworkCacheManager.signatureOf(comp, freq);
                        PhasorNetworkContext restoredCtx =
                                com.hdf.cryptand.neoforge.powergrid.persistence
                                        .NetworkCacheManager.tryRestoreStructure(sigR);
                        if (restoredCtx != null) {
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
            java.util.List<SolveResult> results = toSolve.isEmpty()
                    ? java.util.List.of() : PhasorEngine.solveAll(toSolve, solveCtxs, expectHash);
            long t2 = System.nanoTime();
            graphStageDbg("solved toSolve=" + toSolve.size());
            // 2026-08-24 配对诊断（节流 5s）：solveAll 返回顺序与 expectHash 对齐
            // 校验——若某 si 的 res.networkHash != expectHash[si] → solveAll 内部
            // out[] 被并行/顺序写错（底层根因，直接定位）。
            try {
                long nowP = System.currentTimeMillis();
                if (nowP - PAIR_DBG_LAST >= 5000) {
                    PAIR_DBG_LAST = nowP;
                    for (int i = 0; i < pendings.size(); i++) {
                        int si2 = solveIdx[i];
                        if (si2 < 0 || si2 >= results.size()) continue;
                        SolveResult rr = results.get(si2);
                        if (rr != null && rr.networkHash != expectHash[si2]) {
                            com.hdf.cryptand.neoforge.CryptandNeoForge
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
                    if (nowV2 - STABLE_DBG_LAST >= 5000) {
                        STABLE_DBG_LAST = nowV2;
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
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
                            if (nowM - SOLVE_MISMATCH_DBG_LAST >= 5000) {
                                SOLVE_MISMATCH_DBG_LAST = nowM;
                                com.hdf.cryptand.neoforge.CryptandNeoForge
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
                // 自管写回：自管宿主（TerminalRegistry 端子测试点，每次求解回填）
                if (writebackVoltages(level, null, p.ctx, res)) {
                    solved++;
                    // 记录最新结果（纯内存；世界保存时批量落库，游戏期间零 DB）
                    try {
                        com.hdf.cryptand.neoforge.powergrid.persistence.NetworkCacheManager
                                .recordSolved(p.sig, networkIdOf(p.seedKey),
                                        p.seedKey, p.freq, p.ctx, res);
                    } catch (Throwable ignored) {
                    }
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
            // 统一发热/储能后处理（2026-08-15 后台调度脱离：后台不碰 level，
            // 结果入队列 → 主线程 processPost 消费执行发热/清理/销毁/消息）
            // ⚠ 2026-08-24 根因修复：异步 100Hz 投递 > 主线程 20Hz 消费 → 旧批
            // 积压 → 主线程处理【旧 res】配【新 ctx/新网表】→ KCL 矛盾 → 假电流/
            // 温度错位（[WireHeatMismatch] res hash 逐行变、ctx 稳定实锤）。
            // 改为【只保留最新一批】：producer 覆盖旧批（丢弃过期），consumer
            // 每次处理最新一轮结果——结果滞后最多 1 消费周期，绝无跨轮交叉。
            if (!nets.isEmpty()) {
                POST_NETS.clear();
                POST_NETS.offer(nets);
            }
            perfAdd(t0, t1, t2, t3);
            return solved;
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
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
                if (!com.hdf.cryptand.neoforge.powergrid.adapter.WireKeyUtil.isBlock(p.key)) {
                    continue;
                }
                net.minecraft.core.BlockPos bp =
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                .pointPosOfPublic(p.key);
                if (bp == null) continue;
                if (com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParamCache.get(bp) != null) {
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
        CRYPTAND_LAST_LEVEL = null;
        WORLD_WIRES = java.util.Collections.emptyList();
        WORLD_NETS = java.util.Collections.emptyList();
        LAST_WW_SIG = Integer.MIN_VALUE;
        LAST_WIRES = java.util.Collections.emptySet();
    }

    /**
     * 主线程每 tick 消费后台/主线程 round 的求解结果：统一发热/储能推进 +
     * 孤儿清理 + 销毁 + 引擎消息消费（全部需要 level/BE，只能主线程）。
     */
    public static void processPost(Level level) {
        if (level == null) return;
        // 稳态网络动力学推进（2026-08-20 用户要求）：所有缓存网络（含矩阵求解
        // 跳过的稳态网络）用缓存电压结果推进温度等依赖时间的模型——电压稳态
        // ≠ 温度稳态。节流由 SimClock 控制（主线程 tick 驱动，dt=0 时跳过）。
        try {
            advanceStatesOfAllCached();
        } catch (Throwable ignored) { }
        List<Object[]> nets;
        while ((nets = POST_NETS.poll()) != null) {
            try { PhasorEngine.computeTransformerHeatUnified(level, nets); } catch (Throwable ignored) { }
            try { PhasorEngine.computeDeviceHeatUnified(level, nets); } catch (Throwable ignored) { }
            try { PhasorEngine.computeWireHeatUnified(level, nets); } catch (Throwable ignored) { }
            try { PhasorEngine.computeEnergyUnified(level, nets); } catch (Throwable ignored) { }
        }
        // 2026-08-24 设备计算消息发送（其他电气设备统一接入）：引擎算出的设备
        // 功率（后台 advancePseudoTime 每轮写 DevicePowerStore）→ 主线程按固定
        // 协议 [powerW] 发给对应 BeBridge（子类 onMessage 强转处理）。
        try {
            syncDevicePowerMessages(level);
        } catch (Throwable ignored) { }
        try {
            if ((level.getGameTime() & 0x7) == 0) checkDeviceOverheat(level);
        } catch (Throwable ignored) { }

        // 2026-08-24 主线程更新接口（活跃 BeBridge.serverTick 每 tick）
        try {
            com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser.tickAll(level);
        } catch (Throwable ignored) { }
        // 2026-08-27 独立消费 BE→引擎参数消息（每 tick，与求解轮无关）：
        // PowerGridPlus 电机 BE 每 tick 上报 [λ,networkStressSU,容量,networkRadS]
        // 或空心跳（无网=断电）。此前 pollInput 只在 advancePseudoTime
        // （solveAll 后）消费——电机网络若不在本轮 toSolve（稳态跳过）则
        // 心跳不消费 → networkStressSU 恒旧值 → 断电 1s 归零。这里独立消费：
        //   BE→引擎消息更新对应 ElectroMachineModel 的
        //   loadRatio/networkStressSU/networkRadS（空心跳 → 清零 = 负载脱离）。
        try {
            consumeBecToEngineMotorMessages(level);
        } catch (Throwable ignored) { }
        // 2026-08-27 独立消费 BE→引擎参数消息（每 tick，与求解轮无关）：
        // PowerGridPlus 电机 BE 每 tick 上报 [λ,networkStressSU,容量,networkRadS]
        // 或空心跳（无网=断电）。此前 pollInput 只在 advancePseudoTime
        // （solveAll 后）消费——电机网络若不在本轮 toSolve（稳态跳过）则
        // 心跳不消费 → networkStressSU 恒旧值 → 断电 1s 归零。这里独立消费：
        //   BE→引擎消息更新对应 ElectroMachineModel 的
        //   loadRatio/networkStressSU/networkRadS（空心跳 → 清零 = 负载脱离）。
        try {
            consumeBecToEngineMotorMessages(level);
        } catch (Throwable ignored) { }
        try {
            // 导线悬空检测（懒检测：按配置周期，默认 20 tick=1s；0=禁用）
            com.hdf.cryptand.neoforge.powergrid.adapter.WireDanglingDetector.tick(level);
        } catch (Throwable ignored) { }
        try {
            applyThermalDiffusion(level);
        } catch (Throwable ignored) { }
        try {
            if ((level.getGameTime() & 0x3) == 0) DeviceBinding.cleanupRemoved(level);
        } catch (Throwable ignored) { }
        try { DestructionQueue.process(level); } catch (Throwable ignored) { }
        try { EngineBus.process(level); } catch (Throwable ignored) { }
        // 风扇冷却应用（2026-08-20 用户要求：凡被吹到的都能冷却 + 多方块整体 +
        // 多面曲线叠加）：汇总所有风扇贡献 → 曲线合并 → 应用到 Cryptand 温度
        // 模型 + 原版 ThermalBehaviour；并重置上轮冷却但本轮未吹到的方块。
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.FanCoolingRegistry.apply(level);
        } catch (Throwable ignored) { }
    }

    /**
     * 设备计算功率 → BeBridge 消息（2026-08-24 建立；2026-08-26 用户架构修正：
     * 组装器只与【绑定的接口 BE】交互 → 唯一入口 be instanceof ICryptandCircuitBe，
     * 且桥协议必须 POWER 才发 [powerW]——绝不把功率消息发给电机桥（特化解析器
     * 按协议丢弃）。主线程；频率 = processPost 每 tick 一次。
     */
    private static void syncDevicePowerMessages(Level level) {
        try {
            java.util.List<Object[]> snap =
                    com.hdf.cryptand.neoforge.powergrid.adapter.DevicePowerStore.snapshot();
            if (snap.isEmpty()) return;
            for (Object[] e : snap) {
                net.minecraft.core.BlockPos pos = (net.minecraft.core.BlockPos) e[0];
                double powerW = (Double) e[1];
                net.minecraft.world.level.block.entity.BlockEntity be =
                        level.getBlockEntity(pos);
                if (be == null) continue;
                try {
                    // 只与绑定接口交互（2026-08-26 用户）
                    if (!(be instanceof com.hdf.cryptand.neoforge.powergrid.device
                            .ICryptandCircuitBe icc)) continue;
                    com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser bridge =
                            com.hdf.cryptand.neoforge.powergrid.device.motor
                                    .BeMessageParser.cache(be);
                    if (bridge == null
                            || bridge.protocol()
                            != com.hdf.cryptand.neoforge.powergrid.device.motor
                            .BeMessageParser.Protocol.POWER) continue;
                    icc.cryptandOnEngineMessage(
                            new com.hdf.cryptand.neoforge.powergrid.device.motor
                                    .BeMessage(powerW));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 每 tick 消费电机 BE→引擎消息（2026-08-27 断电滑行修复）。
     * <p>PowerGridPlus 电机 BE 经 PowerGridMotorParser.serverTick 每 tick 上报：
     * <ul>
     *   <li>有 Create 网络：[λ, networkStressSU, 容量SU, networkRadS]</li>
     *   <li>无网络（断电/剪线）：空心跳（size=0）</li>
     * </ul>
     * 组装器侧在此消费 → 更新 {@code ElectroMachineModel} 的网络负载参数。
     * 空心跳 → 清零（负载网络脱离，转纯空载滑行，不再按旧负载 1s 归零）。
     */
    private static void consumeBecToEngineMotorMessages(Level level) {
        if (level == null) return;
        try {
            // 自管图缓存（roundFromGraph）：seedKey → CacheG（含 ctx.network）
            for (CacheG g : GRAPH_CACHES.values()) {
                try {
                    if (g == null || g.ctx == null || g.ctx.network == null) continue;
                    consumeMotorMessagesOfNetwork(g.ctx.network);
                } catch (Throwable ignored) {
                }
            }
            // 原版网络缓存（round）：ElectricalNetwork → Cache（含 ctx.network）
            for (Cache c : CACHES.values()) {
                try {
                    if (c == null || c.ctx == null || c.ctx.network == null) continue;
                    consumeMotorMessagesOfNetwork(c.ctx.network);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 对单个引擎网络中的所有电机复合元件消费 BE→引擎消息。 */
    private static void consumeMotorMessagesOfNetwork(
            com.hdf.cryptand.circuitsimulation.model.Network net) {
        for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce
                : net.composites()) {
            if (!(ce instanceof com.hdf.cryptand.circuitsimulation.model.composite
                    .ElectroMachineModel em)) continue;
            String ck = ce.compositeKey();
            if (ck == null || !ck.startsWith("D")) continue;
            net.minecraft.core.BlockPos pos = WireKeyUtil.posOfComposite(ck);
            if (pos == null) continue;
            com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessage msg =
                    com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser
                            .pollInput(pos);
            if (msg == null) continue;
            int sz = msg.size();
            if (sz == 0) {
                // 空心跳（无网/断电）：负载脱离 → 清零（纯空载滑行 ~30s）
                em.setLoadRatio(0);
                em.setNetworkStressSU(0);
                em.setNetworkRadS(0);
                em.setNetworkConnected(false); // 断电
            } else if (sz >= 3) {
                double lambda = (msg.raw(0) instanceof Number n0) ? n0.doubleValue() : 0;
                double stress = (msg.raw(1) instanceof Number n1) ? n1.doubleValue() : 0;
                double capacity = (msg.raw(2) instanceof Number n2) ? n2.doubleValue() : 0;
                double netRadS = (sz >= 4 && msg.raw(3) instanceof Number n3)
                        ? n3.doubleValue() : 0;
                em.setLoadRatio(lambda);
                em.setNetworkStressSU(stress);
                em.setNetworkRadS(netRadS);
                // ⚠ 供电判据：网络【有源容量 capacity>0】（空网络/仅电机 → 断电）
                em.setNetworkConnected(capacity > 0);
            }
        }
    }

    /**
     * 设备过热运行时检查（2026-08-18 新增：此前只在构建时 Assembler.bindAllPos
     * 检查一次——设备升温后从不触发销毁 → "温度过高没烧线"）。每 tick 主线程
     * 检查 Cryptand 设备温度模型（DeviceThermalStore）与 ThermalBehaviour 原版
     * 方块温度，超限 → 销毁设备方块 + 烧断相连的全部导线段（同段统一烧毁）。
     * ⚠ 防反复冷却：销毁请求后 30s 内同 pos 不重复（否则设备未销毁期间每轮
     * 检查都触发 → 反复销毁/重建风暴 → 卡死）。
     */
    private static final java.util.Map<BlockPos, Long> DEVICE_BURN_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEVICE_BURN_COOLDOWN_MS = 30_000L;

    private static void checkDeviceOverheat(Level level) {
        try {
            long now = System.currentTimeMillis();
            // 收集所有过热设备：pos + 温度（DeviceThermalStore 或 ThermalBehaviour）
            java.util.List<Object[]> over = new java.util.ArrayList<>();
            for (BlockPos pos : com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore.keys()) {
                try {
                    double temp = com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore
                            .thermalFor(pos).tempCelsius();
                    boolean isOver = temp >= 200.0;
                    if (!isOver) {
                        com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour tb =
                                com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
                                        .get(level, pos,
                                                org.patryk3211.powergrid.electricity.base.ThermalBehaviour.TYPE);
                        if (tb instanceof org.patryk3211.powergrid.electricity.base.ThermalBehaviour thb) {
                            float tt = thb.getTemperature();
                            if (tt > temp) temp = tt;
                            if (tt > 200.0f) isOver = true;
                        }
                    }
                    if (isOver) over.add(new Object[]{pos, temp});
                } catch (Throwable ignored) {
                }
            }
            if (over.isEmpty()) return;
            // 只销毁【温度最高】的过热设备（先断短路 → 其余元件冷却 → 不连锁爆炸）：
            // 短路回路中最高温者（如 1939°C 的电阻）先烧 → 导线烧断 → 其他（如
            // 213°C 的加热器）自然冷却，避免"重新接线导致加热器爆炸"。
            over.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            BlockPos hottest = (BlockPos) over.get(0)[0];
            double hottestTemp = (Double) over.get(0)[1];
            Long last = DEVICE_BURN_COOLDOWN.get(hottest);
            if (last != null && now - last < DEVICE_BURN_COOLDOWN_MS) return;
            DEVICE_BURN_COOLDOWN.put(hottest, now);
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[DeviceBurn] {} 过热销毁（{}°C，over={}）",
                    hottest, String.format("%.0f", hottestTemp), over.size());
            // 先确保设备从网络移除（绑定模型销毁 → 网络重建后不再建模）
            com.hdf.cryptand.neoforge.powergrid.adapter.DeviceBinding db =
                    com.hdf.cryptand.neoforge.powergrid.adapter.DeviceBinding.forPos(hottest);
            if (db != null) {
                try { db.notifyModelDestroyed(); } catch (Throwable ignored) { }
            }
            // 标记该位置正因过热销毁（mixin setRemoved 据此跳过导线清理——
            // 2026-08-19：设备过热销毁【不再烧断相连导线】，导线保留悬垂，由
            // WireDanglingDetector 悬空检测平滑断开（懒检测可配置周期 + 爆炸
            // 强制检测），避免"20A 金导线未过热却被 50Ω 电阻爆炸连带烧断"）
            com.hdf.cryptand.neoforge.powergrid.adapter.DestructionQueue
                    .markOverheatBurning(hottest);
            // 销毁设备方块（异步队列，主线程处理）
            com.hdf.cryptand.neoforge.powergrid.adapter.DestructionQueue.request(
                    level, hottest, level.getBlockEntity(hottest), "过热销毁");
            // 不再主动烧断相连导线——设备销毁后由悬空检测自动断开
        } catch (Throwable ignored) {
        }
    }

    /**
     * 热扩散（2026-08-18 用户需求）：【只有带温度扩散模型的组装器】能向周围
     * 元件传导温度——普通设备默认不扩散，不会被周围高温设备影响。
     * 扩散分多种类型（见 {@link com.hdf.cryptand.neoforge.powergrid.device.ThermalDiffusionType}）：
     *   1. 仅输出（OUTPUT_ONLY）：只向周围传热（源更热 → 邻居），不接受外部输入
     *   2. 仅输入（INPUT_ONLY）：只接受外部传热（邻居更热 → 源），不向周围输出
     *   3. 双向（BIDIRECTIONAL）：热 → 冷双向交互
     * 方向按配置（默认全方向）；【扩散距离】按配置沿方向传播多格；
     * 【衰减度】每格按 (1−attenuation)^(d−1) 衰减——0 = 到距离上限前同样传递。
     * 相邻/沿途方块必须已有温度模型才接收热量。
     */
    private static void applyThermalDiffusion(Level level) {
        try {
            for (BlockPos pos : com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore.keys()) {
                try {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) continue;
                    com.hdf.cryptand.neoforge.powergrid.device.Assembler asm =
                            com.hdf.cryptand.neoforge.powergrid.device.Assemblers.get(be);
                    if (asm == null) continue;
                    com.hdf.cryptand.neoforge.powergrid.device.ThermalDiffusionConfig cfg =
                            asm.thermalDiffusion();
                    if (cfg == null || cfg.conductance <= 0) continue;
                    // 方向解析：前后方向（forwardBackward）→ 按方块朝向 + 反方向
                    java.util.Set<net.minecraft.core.Direction> dirs = cfg.directions;
                    if (cfg.forwardBackward) {
                        net.minecraft.core.Direction facing = readFacing(be);
                        dirs = java.util.Set.of(facing, facing.getOpposite());
                    }
                    // 风机增强：被鼓风机/风机吹时【扩散距离 = 风机吹的最大距离】
                    // （热风沿气流传播多格）；读不到风机距离 → 兜底 +blownBonus
                    int dist = cfg.distance;
                    if (cfg.blownBonus > 0) {
                        int fanDist = fanMaxDistance(level, pos);
                        if (fanDist > 0) {
                            dist = fanDist;
                        } else if (isBlownByFan(level, pos)) {
                            dist += cfg.blownBonus;
                        }
                    }
                    com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel self =
                            com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore.thermalFor(pos);
                    double tSelf = self.getTemperature();
                    double falloff = 1.0 - cfg.attenuation; // 每格保留比例（0~1）
                    for (net.minecraft.core.Direction dir : dirs) {
                        double factor = 1.0;
                        for (int d = 1; d <= dist; d++) {
                            if (factor <= 1e-6) break; // 衰减到 0 → 不再传播
                            net.minecraft.core.BlockPos np = pos.relative(dir, d);
                            if (!com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore.contains(np)) {
                                factor *= falloff; // 该格无温度模型 → 继续衰减向后传
                                continue;
                            }
                            com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel nb =
                                    com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore.thermalFor(np);
                            double tN = nb.getTemperature();
                            double dT = tSelf - tN; // 正 = 源更热
                            switch (cfg.type) {
                                case OUTPUT_ONLY:
                                    if (dT <= 0.5) { factor *= falloff; continue; }
                                    {
                                        double heat = cfg.conductance * factor * dT * 0.05; // dt=1 tick
                                        self.addHeat(-heat);
                                        nb.addHeat(heat);
                                    }
                                    break;
                                case INPUT_ONLY:
                                    if (dT >= -0.5) { factor *= falloff; continue; }
                                    {
                                        double heat = cfg.conductance * factor * (-dT) * 0.05;
                                        nb.addHeat(-heat);
                                        self.addHeat(heat);
                                    }
                                    break;
                                case BIDIRECTIONAL:
                                    if (Math.abs(dT) <= 0.5) { factor *= falloff; continue; }
                                    {
                                        double heat = cfg.conductance * factor * dT * 0.05;
                                        self.addHeat(-heat);
                                        nb.addHeat(heat);
                                    }
                                    break;
                            }
                            factor *= falloff; // 衰减度：每格衰减（0 = 到上限前同样传递）
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 方块朝向（前后扩散用；无 HORIZONTAL_FACING 属性 → 默认 NORTH） */
    private static net.minecraft.core.Direction readFacing(
            net.minecraft.world.level.block.entity.BlockEntity be) {
        try {
            net.minecraft.world.level.block.state.BlockState st = be.getBlockState();
            if (st.hasProperty(net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.HORIZONTAL_FACING)) {
                return st.getValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.HORIZONTAL_FACING);
            }
        } catch (Throwable ignored) {
        }
        return net.minecraft.core.Direction.NORTH;
    }

    /** 是否被鼓风机/风机吹（ThermalBehaviour 冷却倍率 > 1，反射同 computeDeviceHeatOne） */
    private static boolean isBlownByFan(Level level, BlockPos pos) {
        try {
            com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour tb =
                    com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
                            .get(level, pos,
                                    org.patryk3211.powergrid.electricity.base.ThermalBehaviour.TYPE);
            if (tb instanceof org.patryk3211.powergrid.electricity.base.ThermalBehaviour thb) {
                java.lang.reflect.Field f = org.patryk3211.powergrid.electricity.base.ThermalBehaviour.class
                        .getDeclaredField("totalCoolingFactorMultiplier");
                f.setAccessible(true);
                float cm = f.getFloat(thb);
                return cm > 1f;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 覆盖该方块的风机最大吹风距离（格）：反射读 ThermalBehaviour 冷却源 Map
     *  （key = AirCurrent）→ getMaxDistance()。读不到 → -1（调用方兜底）。 */
    private static int fanMaxDistance(Level level, BlockPos pos) {
        try {
            com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour tb =
                    com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
                            .get(level, pos,
                                    org.patryk3211.powergrid.electricity.base.ThermalBehaviour.TYPE);
            if (tb instanceof org.patryk3211.powergrid.electricity.base.ThermalBehaviour thb) {
                // 反射找冷却源字段（Map<AirCurrent, strength> 等）
                for (java.lang.reflect.Field f :
                        org.patryk3211.powergrid.electricity.base.ThermalBehaviour.class
                                .getDeclaredFields()) {
                    if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object m = f.get(thb);
                    if (m instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                        Object ac = map.keySet().iterator().next();
                        if (ac instanceof com.simibubi.create.content.kinetics.fan.AirCurrent air) {
                            // AirCurrent 无 getMaxDistance（在 IAirCurrentSource 上）——
                            // 反射读其 source 字段取风机最大吹风距离
                            try {
                                java.lang.reflect.Method gm = air.getClass().getMethod("getMaxDistance");
                                if (gm != null) {
                                    float md = ((Number) gm.invoke(air)).floatValue();
                                    return Math.max(1, (int) Math.ceil(md));
                                }
                            } catch (Throwable ignored2) {
                            }
                            try {
                                java.lang.reflect.Field sf =
                                        com.simibubi.create.content.kinetics.fan.AirCurrent.class
                                                .getDeclaredField("source");
                                sf.setAccessible(true);
                                Object src = sf.get(air);
                                if (src instanceof
                                        com.simibubi.create.content.kinetics.fan.IAirCurrentSource ics) {
                                    float md = ics.getMaxDistance();
                                    return Math.max(1, (int) Math.ceil(md));
                                }
                            } catch (Throwable ignored2) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

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

    /** 自管稳定检测：WireGraph 版本 + 连续稳定 tick 数。 */
    private static final class GraphStable {
        final long version;
        int ticks;
        GraphStable(long version, int ticks) { this.version = version; this.ticks = ticks; }
    }
    private static volatile GraphStable GRAPH_STABLE;
    /** [GraphBuild] 空构建/异常诊断节流（2026-08-26 定位"进世界不运行"） */
    private static volatile long GRAPH_EMPTY_DBG_LAST;
    /** 稳定检测版本变化诊断节流（2026-08-20） */
    private static volatile long STABLE_DBG_LAST;
    /** [PairDbg] 诊断节流（2026-08-24 solveAll 顺序配对校验） */
    private static volatile long PAIR_DBG_LAST;
    /** [SolveWaitOp] 诊断节流（2026-08-24） */
    private static volatile long OP_WAIT_DBG_LAST;
    /** [SolveMismatch] 诊断节流（2026-08-24） */
    private static volatile long SOLVE_MISMATCH_DBG_LAST;

    /** roundFromGraph 诊断节流 */
    private static volatile long GRAPH_ROUND_DBG_LAST;
    /** 阶段日志独立节流（2026-08-15 刷屏修复：原在阶段变化时每次都打，100Hz
     *  求解每轮 3 个阶段 → 日志 67% 都是 stage 行。改为 5s 固定节流，同时持续
     *  记录当前阶段——卡住定位仍可用（卡住时最后一条 stage 即卡点阶段）。 */
    private static volatile long GRAPH_STAGE_DBG_LAST;
    private static String GRAPH_LAST_STAGE = "";

    /** 阶段日志（节流）：世界进入卡住定位——卡住时最后 stage 显示卡点阶段 */
    private static void graphStageDbg(String stage) {
        try {
            if (!stage.equals(GRAPH_LAST_STAGE)) {
                GRAPH_LAST_STAGE = stage;
            }
            long now = System.currentTimeMillis();
            if (now - GRAPH_STAGE_DBG_LAST >= 5000) {
                GRAPH_STAGE_DBG_LAST = now;
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[GraphRound] stage={}", GRAPH_LAST_STAGE);
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
            PhasorNetworkContext ctx = com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
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
                if (nw - GRAPH_EMPTY_DBG_LAST >= 2000) {
                    GRAPH_EMPTY_DBG_LAST = nw;
                    boolean netFound = com.hdf.cryptand.neoforge.powergrid.adapter
                            .WireNetworkManager.get().networkOf(seedKey) != null;
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[GraphBuild] EMPTY(force-solve) seed={} netFound={} elems={} pts={}",
                            seedKey, netFound, elN, ptN);
                }
                return new PendingG(seedKey, freq, ctx, null); // 空也强制求解
            }
            return new PendingG(seedKey, freq, ctx, null);
        } catch (Throwable t) {
            GRAPH_CACHES.remove(seedKey);
            long ew = System.currentTimeMillis();
            if (ew - GRAPH_EMPTY_DBG_LAST >= 2000) {
                GRAPH_EMPTY_DBG_LAST = ew;
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                        "[GraphBuild] EX seed={} err={}", seedKey, String.valueOf(t));
            }
            // 异常兜底：空网络强制求解（保证每轮都有求解产出，杜绝静默不运行）
            try {
                return new PendingG(seedKey, freq,
                        new com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkContext(
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
                    com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
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
                net.minecraft.core.BlockPos pos = com.hdf.cryptand.neoforge.powergrid.adapter
                        .PhasorNetworkBuilder.pointPosOfPublic(p.key);
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
            com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry.invalidateAll();
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
                            com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                    .wiresSignature(net),
                            com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                    .nodeSignature(net),
                            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                                    .netVersionOf(net),
                            p.ctx.paramVersion.get()));
                }
                built++;
            } catch (Throwable ignored) {
            }
        }
        // 5. 同步世界导线快照（避免正常模式首轮误判新增）
        try {
            LAST_WW_SIG = com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                    .worldWiresSignature();
            LAST_WIRES = new java.util.HashSet<>(WORLD_WIRES);
        } catch (Throwable ignored) {
        }
        // 检测日志（节流）
        long inNow = System.currentTimeMillis();
        if (inNow - INIT_DBG_LAST >= 1000) {
            INIT_DBG_LAST = inNow;
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
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
            long netVer = com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                    .netVersionOf(net);
            // ⚠ 自管图版本校验（2026-08-13 阶段1）：WireGraph.version 变化（导线
            // 新增/移除，含 hook 漏网/自管源）→ 该网络可能受影响 → 强制 miss。
            // 自管图为唯一拓扑真相，版本变化 = 拓扑变化。
            long graphVer = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().version();
            if (!force && c != null && c.freq == freq
                    && net.getNodes().size() == c.pgNodes
                    && c.netVer == netVer
                    && c.graphVer == graphVer
                    && com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                            .nodeSignature(net) == c.nodeSig
                    && com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
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
                                    com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                            .wiresSignature(net),
                                    com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
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
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
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
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                                .wiresSignature(net),
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
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
    static void resetComputation(Level level, PhasorNetworkContext ctx) {
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

    /**
     * 把求解结果写回节点电压（AC 峰值→RMS；DC 实电压）。
     * <p>
     * 2026-08-11 重构【按位置写回】：原实现遍历 ctx.nodeToEngine（节点实例 →
     * id），对每个节点 `node.getNetwork().setValue(node, v)`。实测发现：
     *   [WireBurn] 电压表端子 (-2,0,12)#1 两端电压 0.18V vs 电流表 33.42V
     *   （同一根玩家导线，11000A）—— 电压表端子在网络 nodes（[WbNetDbg]
     *   nodes=9 含它）但未被写回。PowerGrid 网络重建（merge/islandDiscovery）
     *   可能【替换节点实例】或【节点 network 字段与 nodes 列表不同步】→
     *   ctx 持有的实例 getNetwork() 返回 null/旧网络 → 写回跳过或写错实例
     *   → 导线引用实例保持旧电压 → 瞬时巨大电流。
     * 修复：先建【位置Key(pos#term) → 电压】映射（求解结果），再遍历 ctx 覆盖
     *   的所有网络【所有节点】，按位置写回 —— 网络 nodes 里的【任何实例】
     *   （无论 ctx 收集的是哪个）都会被覆盖，不依赖 node.getNetwork()。
     *   - 覆盖节点实例替换：同位置不同实例都写
     *   - 覆盖 getNetwork() null：直接遍历网络 nodes，无需节点自报网络
     *   - JunctionWireEndpoint 按物理位置匹配（block 级汇流点）
     */
    /**
     * 写回求解结果到【自管宿主】端子测试点（2026-08-20 用户决策：完全禁止
     * 写入原版 PowerGrid 节点）。
     * <p>
     * 仿真核心开启时全部自管（PowerGridWireConverter.isEnabled() 恒 true，
     * CryptandTopologyManager.round 恒走 roundFromGraph）——非自管
     * （buildContextFromNetwork）模式已废弃无用；且 OwnedFloatingNodeGetVoltageMixin
     * 等 mixin 已覆盖所有原版设备电压读取（从自管宿主 TerminalRegistry 端子
     * 测试点直读）。因此：
     *   - 自管宿主写回：TerminalRecorder.record（端子测试点，每次求解回填电压+
     *     频率，消费端/风扇/仪表直读——2026-08-15 端子即接入模型）
     *   - 原版节点写回【整体删除】：vByPos 构建 + covered 网络遍历 setValue +
     *     悬空端直写 setStateValue/setSavedValue + wirePeerVoltage BFS 等电位
     *     ——原版节点不再是设备电压宿主，禁止写入。
     */
    private static boolean writebackVoltages(Level level, ElectricalNetwork net,
                                             PhasorNetworkContext ctx, SolveResult res) {
        try {
            // ===== 端子测试点回填（2026-08-13：端子 = 测试点） =====
            // 端子电压由引擎求解器自动回填到 TerminalElement（TerminalRecorder，
            // MNA 保证开路等电位 → 电流 0）。此处从测试点读取 → 天然正确：
            // 悬空端 = 接入端等电位（非 0 非强写），闭合回路 = 真实压差。不再
            // 手动从 res 算 va/vb、不强制 0V / 等电位 hack。
            // ⚠ 缓存命中路径（结构未变 → 复用旧 result 不重解）端子可能仍 invalid
            // （稳定检测清零时失效过）→ 先用当前 res 回填端子，保证与本次写回一致。
            try {
                com.hdf.cryptand.circuitsimulation.solver.TerminalRecorder.record(ctx.network, res);
            } catch (Throwable ignored) {
            }
            // 自管模式（roundFromGraph 构建，pointToEngine 非空）：电压只写自管
            // 宿主（TerminalRegistry 端子测试点，上方 TerminalRecorder 已回填），
            // 不写原版节点——原版网络仅作转换源，非设备宿主。
            if (!ctx.pointToEngine.isEmpty()) {
                return true;
            }
            // 非自管（buildContextFromNetwork）模式：已废弃（仿真核心开时全部
            // 自管）。即使误走，也【不写原版节点】——mixin 从自管宿主读电压，
            // 原版节点恒为转换源，禁止 setValue/setStateValue/setSavedValue。
            return true;
        } catch (Throwable t) {
            return false;
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
