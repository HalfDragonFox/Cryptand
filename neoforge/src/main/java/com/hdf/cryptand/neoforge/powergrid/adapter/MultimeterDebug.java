/**
 * ===== 万用表调试辅助 =====
 *
 * 从测量端点/元件端点出发，沿【真实导线拓扑】BFS 寻找相连的频率源：
 *   - AC 创造源（AcCreativeSourceBlockEntity）→ 配置频率
 *   - 发电机换向器（CommutatorBlockEntity.source → GeneratorCoupling → rotor）
 *     → 频率 = |ω| / 2π（Hz）
 *
 * 为什么不用 network.getNodes() / outerHooks：
 * 客户端把整个世界的电路都塞进同一个 DummyElectricalNetwork（newNetwork()
 * 永远返回同一个对象），所以"测量网络里有没有 AC 源"在客户端毫无意义——
 * 会导致未连接的电路也显示同频率（干扰 bug）。服务端虽然网络真实，但依赖
 * AC 源端子节点是否被加入网络节点列表也不可靠（一旦检测失败返回 0，
 * 电容/电感会走实时隔直流模式而源走等效 RMS → 电路断路、调电阻无反应）。
 * 导线实体（BaseWireEntity）及其端点是精确同步的，沿导线遍历即可得到
 * 真实的连通性，客户端/服务端一致。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.powergrid.creative.AcCreativeSourceBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.mixin.customcore.CommutatorBlockEntityAccessor;
import com.hdf.cryptand.neoforge.powergrid.mixin.customcore.GeneratorCouplingAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;
import org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.kinetics.generator.inductionrotor.CommutatorBlockEntity;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public final class MultimeterDebug {

    /**
     * AC 源端子节点 → 源实体 注册表（网络级频率查询用）。
     * AC 源 buildCircuit 时注册（Map.put 是方法调用，不受 Architectury Transformer 破坏）。
     */
    public static final java.util.concurrent.ConcurrentHashMap<INode, AcCreativeSourceBlockEntity> AC_SOURCE_NODES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 换向器/发电机端子节点 → GeneratorCoupling 注册表（网络级频率源）。
     * 换向器（发电机/电动机）输出频率 = 转子转速 |ω|/2π。buildCircuit 时注册。
     * （构建时注册的静态索引，非每 tick 状态数据——电机状态存各 BlockEntity 实例）
     */
    public static final java.util.concurrent.ConcurrentHashMap<INode, GeneratorCoupling> GEN_SOURCE_NODES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 附近（±5 格）换向器实例（状态在实例字段，本地查询）；无则 null */
    public static CommutatorStateHolder nearbyCommutator(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        try {
            for (net.minecraft.world.level.block.entity.BlockEntity be : nearbyBlockEntities(level, pos)) {
                if (be instanceof CommutatorStateHolder h) return h;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 附近（±5 格）励磁绕组实例（状态在实例字段，本地查询）；无则 null */
    public static WindingStateHolder nearbyWinding(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        try {
            for (net.minecraft.world.level.block.entity.BlockEntity be : nearbyBlockEntities(level, pos)) {
                if (be instanceof WindingStateHolder h) return h;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 遍历 ±5 格范围覆盖的所有区块内的方块实体（MC 1.21.1：Level.getBlockEntities(AABB) 不存在） */
    private static java.util.Set<net.minecraft.world.level.block.entity.BlockEntity> nearbyBlockEntities(Level level, BlockPos pos) {
        java.util.Set<net.minecraft.world.level.block.entity.BlockEntity> out = new java.util.HashSet<>();
        int r = 5;
        int minCx = (pos.getX() - r) >> 4;
        int maxCx = (pos.getX() + r) >> 4;
        int minCz = (pos.getZ() - r) >> 4;
        int maxCz = (pos.getZ() + r) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null) continue;
                out.addAll(chunk.getBlockEntities().values());
            }
        }
        return out;
    }

    /** 换向器/发电机频率 = 转子转速 |ω| / 2π（Hz）；0 = 不转/无转子 */
    public static double generatorFrequencyHz(GeneratorCoupling gen) {
        try {
            IRotor rotor = ((GeneratorCouplingAccessor) gen).cryptand$rotor();
            if (rotor != null) {
                return Math.abs(rotor.getAngularVelocityRadians()) / (2 * Math.PI);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 本机方块锚定的导线网络频率（动态声音用）。
     *
     * 只收集锚定在本机方块（pos，BlockWireEndpoint.getPos() = 方块 pos）的导线端点，
     * 从它们沿导线 BFS（不跨变压器），并检查本机方块本身是否为换向器
     * （发电机转子转速频率作兜底）。
     *
     * 与 {@link #getFrequencyHzNearBlock}（扫描附近【所有】导线）不同：
     * 本方法只认【本机实际连接的导线】——旁边未连接的通电线缆不会渗入，
     * 修复"换向器没接线、旁边有通电频率的线缆就误发嗡鸣"。
     */
    public static double getBlockNetworkFrequencyHz(Level level, BlockPos pos) {
        if (level == null || pos == null) return 0;
        try {
            double genFreq = 0;
            // 本机方块本身是换向器（发电机/电动机）→ 转子转速频率作兜底
            if (level.getBlockEntity(pos) instanceof CommutatorBlockEntity commutator) {
                try {
                    GeneratorCoupling source = ((CommutatorBlockEntityAccessor) commutator).cryptand$source();
                    double f = (source != null) ? generatorFrequencyHz(source) : 0;
                    if (f > genFreq) genFreq = f;
                } catch (Throwable ignored) {
                }
            }
            // 只收集锚定在本机方块的导线实体端点（真实连接点）
            BlockPos wireAnchor = pos;
            Set<IWireEndpoint> myTerminals = new HashSet<>();
            for (BaseWireEntity wire : level.getEntitiesOfClass(BaseWireEntity.class,
                    new AABB(pos).inflate(3.0))) {
                addAnchoredEndpoint(wire.getEndpoint1(), wireAnchor, myTerminals);
                addAnchoredEndpoint(wire.getEndpoint2(), wireAnchor, myTerminals);
            }
            for (IWireEndpoint ep : myTerminals) {
                double f = searchConnectedFrequency(level, ep, false);
                if (f > genFreq) genFreq = f;
            }
            return genFreq;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 换向器是否【发电模式】（机械→电）：源输出功率 P = I×V &gt; 0（电枢输入功率为负）。
     * 电动模式（电→机械，P &lt; 0）→ 不作为频率源，应从网络【输入】频率。
     * 注意：仅用于施力方向参考；空载发电机电流≈0 时不可靠，频率源判定用转速。
     */
    public static boolean isGenerating(GeneratorCoupling gen) {
        try {
            return gen.getCurrent() * gen.getVoltage() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 频率传递表（2026-08-12 用户要求）：副边/相连网络 → 变压器原边频率。
     *  Cryptand round 把含变压器 ctx 的频率传播到其覆盖的所有网络（副边网络
     *  无源但通过变压器继承原边 AC 频率）→ 副边电机/万用表/声音按 AC 处理
     *  （不只电压，频率/相位等交流属性也随变压器传递）。每 tick 重建。 */
    private static final java.util.Map<ElectricalNetwork, Double> NET_FREQ =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 每 tick 清空频率传递表（防止旧网络残留频率）。 */
    public static void clearNetworkFrequencies() { NET_FREQ.clear(); }

    /** 记录网络频率（round 时填充：含变压器 ctx → 覆盖的所有网络）。 */
    public static void setNetworkFrequency(ElectricalNetwork net, double f) {
        if (net != null && f > 0) NET_FREQ.put(net, f);
        // 诊断（节流 ~2s）：确认 DC 网络是否被错误设 AC 频率（"原版 DC 源被
        // 其他 AC 源干扰"）——打印网络是否有 AC 源 + 节点数
        long now = System.currentTimeMillis();
        if (now - FREQ_DBG_LAST >= 2000) {
            FREQ_DBG_LAST = now;
            try {
                if (net != null) {
                    boolean hasAc = false;
                    for (INode n : net.getNodes()) {
                        if (AC_SOURCE_NODES.get(n) != null) { hasAc = true; break; }
                    }
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[FreqSet] net={} f={} hasAc={} nodes={}",
                            System.identityHashCode(net),
                            String.format("%.1f", f), hasAc, net.getNodes().size());
                }
            } catch (Throwable ignored) {
            }
        }
    }
    private static volatile long FREQ_DBG_LAST;

    /**
     * 网络级频率查询（无 Level 版，供电机等从 ElectricalNetwork 直接查）：
     * 遍历网络节点找 AC 源（AC_SOURCE_NODES 注册表）。返回 0 = 直流/无 AC 源。
     * 规则：AC 创造源优先；换向器（转子转速>0）作为兜底——
     * 发电机空载时电流≈0，不能靠 isGenerating 判断，只要有转速就输出频率。
     * ⚠ 频率传递（2026-08-12）：先查 NET_FREQ（副边网络继承原边频率）——
     *   变压器副边无源但带载时，电机/万用表必须按 AC 处理（否则读到 DC → 不转）。
     */
    public static double getNetworkFrequencyHz(ElectricalNetwork net) {
        if (net == null) return 0;
        try {
            Double pf = NET_FREQ.get(net);
            if (pf != null && pf > 0) return pf;
            // ⚠ 自管模式（2026-08-13 完全接管）：不遍历 net.getNodes() 找频率源。
            //   从网络节点里取一个端点 pos 作查询点 → 自管图分量枚举
            //   （WireGraph 是转换后的真实拓扑；原版网络仅作查询点定位）。
            if (PowerGridWireConverter.isEnabled() && WireNetworkManager.get().nodeCount() > 0) {
                Level lvl = PhasorWriteback.CRYPTAND_LAST_LEVEL;
                if (lvl != null) {
                    for (INode n : net.getNodes()) {
                        if (n instanceof BlockWireEndpoint bwe && bwe.getPos() != null) {
                            return getFrequencyHzFromGraph(lvl, bwe.getPos());
                        }
                    }
                }
                return 0; // 无法定位查询点 → 直流
            }
            double genFreq = 0; // 换向器兜底频率（无 AC 源时用）
            for (INode n : net.getNodes()) {
                // 1) 交流创造源（配置频率）→ 绝对优先
                AcCreativeSourceBlockEntity src = AC_SOURCE_NODES.get(n);
                if (src != null) {
                    float f = src.getFrequencyHz();
                    if (f > 0) return f;
                }
                // 2) 换向器/发电机：转子转速>0 即兜底频率（空载也输出频率）
                GeneratorCoupling gen = GEN_SOURCE_NODES.get(n);
                if (gen != null) {
                    double f = generatorFrequencyHz(gen);
                    if (f > genFreq) genFreq = f;
                }
            }
            return genFreq;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 网络级频率查询：端子节点 → 所属 ElectricalNetwork → 遍历网络节点找 AC 源。
     * <p>
     * 频率是【整个网络】的参数，从网络拿比沿导线 BFS 快（O(n)，n=网络内节点数）。
     * 仅服务端可用：客户端把整个世界的电路塞进同一个 DummyElectricalNetwork
     * （newNetwork() 永远返回同一个对象），网络级查询会串扰 → 返回 -1 让调用方走 BFS。
     * <p>
     * 返回：&gt;0 频率；0 = 网络存在但无 AC 源（直流）；-1 = 客户端 / 节点未挂网络（走 BFS 兜底）。
     */
    public static double getNetworkFrequencyHz(Level level, INode node) {
        if (node == null) return -1;
        try {
            // ⚠ 自管模式（2026-08-13 完全接管）：不遍历原版网络节点找频率源
            //   ——走自管图分量枚举（WireGraph 是转换后的真实拓扑）。
            if (PowerGridWireConverter.isEnabled() && WireNetworkManager.get().nodeCount() > 0
                    && node instanceof BlockWireEndpoint bwe && bwe.getPos() != null) {
                double f = getFrequencyHzFromGraph(level, bwe.getPos());
                // 图查询失败（未接导线）→ 0（直流）；不回退原版网络扫描
                return f;
            }
            if (level != null && level.isClientSide) return -1; // 客户端 Dummy 网络不可用
            ElectricalNetwork net = node.getNetwork();
            if (net == null) return -1; // 未挂网络 → 结果不确定，走 BFS 兜底
            double genFreq = 0; // 换向器兜底频率（无 AC 源时用）
            for (INode n : net.getNodes()) {
                // 1) 交流创造源（配置频率）→ 绝对优先
                AcCreativeSourceBlockEntity src = AC_SOURCE_NODES.get(n);
                if (src != null) {
                    float f = src.getFrequencyHz();
                    if (f > 0) return f;
                }
                // 2) 换向器/发电机：转子转速>0 即兜底频率（空载也输出频率）
                GeneratorCoupling gen = GEN_SOURCE_NODES.get(n);
                if (gen != null) {
                    double f = generatorFrequencyHz(gen);
                    if (f > genFreq) genFreq = f;
                }
            }
            return genFreq; // 无 AC 源 → 发电机兜底频率；仍 0 = 直流
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 等效 / 实时仿真切换阈值（Hz），来自配置 circuitSolveFrequencyHz（默认 100）。
     *   - 频率 < 阈值 → 实时仿真（时域振荡，源正弦、电容 C/Δt、电感 Δt/L）
     *   - 频率 ≥ 阈值 → 等效仿真（稳态：源输出有效值、电容 2πfC、电感 1/(2πfL)）
     */
    public static double getFrequencyModeThresholdHz() {
        return ConfigLoad.CIRCUIT_SOLVE_FREQUENCY_HZ.get();
    }

    /**
     * 是否对该频率使用等效仿真（源/电容/电感必须一致，否则电路行为错误）。
     *
     * 交流（频率 &gt; 0）一律直接采用等效模型（稳态/相量）：源输出有效值、
     * 电容 2πfC、电感 1/(2πfL)，无历史电流——彻底规避 20Hz 采样下
     * 50/60Hz 等频率无法真实振荡的问题（见"频率当前点"示波器游标，
     * 由 AC 线程以可配频率推进）。
     * 直流（0Hz）→ 实时伴生模型（电容隔直流、电感通直流）。
     */
    public static boolean shouldUseEquivalentSimulation(double frequencyHz) {
        return frequencyHz > 0;
    }

    /**
     * AC 波形当前点分辨率（bit）。
     * 统一固定 8bit（0~255，2026-08-12 移除单独配置，线程统一分发）。
     */
    public static int getWaveformResolutionBits() {
        return 8;
    }

    private MultimeterDebug() {}

    /**
     * 服务端频率解析：从端点沿【真实导线拓扑】BFS 寻找频率源。
     *
     * 与客户端 getNetworkFrequencyHz 共用同一套 searchConnectedFrequency
     * （服务端也有 BaseWireEntity 导线实体和 BlockEntity，BFS 完全适用）。
     *
     * 为什么不用 network.getNodes() 扫描：
     *   - 依赖 AC 源端子节点是否被加入网络节点列表，链路不可靠；
     *   - 一旦检测失败返回 0，电容/电感会走实时模式（隔直流 G=C/Δt），
     *     而源走等效模式（恒定 RMS）→ 电路断路、调电阻无反应（bug）。
     *
     * 返回值：正值=频率；0=无频率源（直流/孤立）。
     */
    public static double getServerNetworkFrequencyHz(Level level, IWireEndpoint endpoint) {
        if (endpoint == null || level == null) return 0;
        try {
            double freq = searchConnectedFrequency(level, endpoint, true);
            return Double.isNaN(freq) ? 0 : freq;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 服务端频率解析（便捷版，【纯网络级，禁止 BFS】）：
     * 从方块 ElectricBehaviour 的端子节点反查其所属 ElectricalNetwork，
     * 遍历网络节点找 AC 源（频率=网络参数）。
     * 客户端 Dummy 网络不可用（网络级返回 -1）→ 直接返回 0（服务端专用）。
     */
    public static double getServerNetworkFrequencyHz(Level level, BlockPos pos) {
        if (level == null || pos == null || level.isClientSide) return 0;
        try {
            // ⚠ 自管模式（2026-08-13 完全接管）：走自管图分量枚举，
            //   不遍历原版网络节点（getNetworkFrequencyHz(Level,node) 网络扫描）。
            if (PowerGridWireConverter.isEnabled() && WireNetworkManager.get().nodeCount() > 0) {
                return getFrequencyHzFromGraph(level, pos);
            }
            if (level.getBlockEntity(pos) instanceof ElectricBlockEntity ebe) {
                ElectricBehaviour beh = ebe.getElectricBehaviour();
                if (beh != null) {
                    for (int t = 0; t < 2; t++) {
                        try {
                            double f = getNetworkFrequencyHz(level, beh.getTerminal(t));
                            if (f >= 0) return f; // 0 = 直流网络；>0 = 频率
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 从测量端点获取所在【连通电路】的频率（Hz），沿导线拓扑 BFS。
     * <p>
     * 返回值约定：
     *   - 正值  → 电路上有相连的 AC 源/发电机，返回实际频率
     *   - 0.0   → 电路存在但无频率源（直流网络）
     *   - NaN   → 无法获取（无端点 / 异常）
     */
    public static double getNetworkFrequencyHz(Level level, IWireEndpoint endpoint) {
        if (endpoint == null || level == null) return Double.NaN;
        try {
            return searchConnectedFrequency(level, endpoint, true);
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }
    /**
     * 从导线端点出发的 BFS 频率搜索（公开，供变压器频率检测使用）。
     * 返回 0 = 无频率源（直流/孤立）。
     */
    public static double getFrequencyFromEndpoint(Level level, IWireEndpoint endpoint) {
        if (endpoint == null || level == null) return 0;
        try {
            double freq = searchConnectedFrequency(level, endpoint, true);
            return Double.isNaN(freq) ? 0 : freq;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 扫描方块周围导线实体，从【导线端点】出发 BFS 找频率。
     * <p>
     * 为什么不用 BlockWireEndpoint(方块pos, t)：PowerGrid 导线实体悬挂在方块上方
     * （导线端点 pos = 方块 y + 1），与方块端子 BlockWireEndpoint(方块pos, t) 的
     * pos 不一致 → equals 永远不匹配 → 从方块端子出发的 BFS 找不到任何导线。
     * 改为直接从导线端点（真实连接点）出发。
     */
    public static double getFrequencyHzNearBlock(Level level, BlockPos center, double radius) {
        if (level == null || center == null) return 0;
        try {
            for (BaseWireEntity wire : level.getEntitiesOfClass(BaseWireEntity.class,
                    new AABB(center).inflate(radius))) {
                IWireEndpoint ep1 = wire.getEndpoint1();
                if (ep1 != null) {
                    double f = getFrequencyFromEndpoint(level, ep1);
                    if (f > 0) return f;
                }
                IWireEndpoint ep2 = wire.getEndpoint2();
                if (ep2 != null) {
                    double f = getFrequencyFromEndpoint(level, ep2);
                    if (f > 0) return f;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 自管模式频率查询（2026-08-13 完全接管）：从查询点所在【自管图分量】内
     * 枚举 AC 源/换向器方块。
     * <p>
     * 为什么不用导线 BFS / 原版网络扫描：
     *   - 导线实体已被 removeConvertedWires 删除 → getFrequencyHzNearBlock（BFS）失效；
     *   - 原版网络节点扫描（getNetworkFrequencyHz）遍历 net.getNodes() 属"读原版网络"，
     *     与"全部依靠转换功能转换"冲突；
     *   - WireGraph 分量 = 转换后的真实连通拓扑，B(pos) 方块点直接反查设备类型。
     * <p>
     * 返回：&gt;0 频率；0 = 直流/无源/查询点不在图内（未接导线）。
     */
    public static double getFrequencyHzFromGraph(Level level, BlockPos queryPos) {
        if (level == null || queryPos == null) return 0;
        try {
            if (!PowerGridWireConverter.isEnabled()) return 0; // 非自管 → 调用方走原版
            var mgr = WireNetworkManager.get();
            if (mgr == null || mgr.nodeCount() == 0) return 0;
            // 1) 定位 queryPos 在图中的种子点（pos 匹配即可，term 任一端子）
            WirePoint seed = null;
            for (WirePoint p : mgr.pointList()) {
                BlockPos pp = PhasorNetworkBuilder.pointPosOfPublic(p.key);
                if (queryPos.equals(pp)) { seed = p; break; }
            }
            if (seed == null) {
                // 2026-08-21 临时诊断：queryPos 不在图中（未接导线/端子缺失）
                try {
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[FreqDbg] pos={} seed=NOT-FOUND nodes={}",
                            queryPos, mgr.nodeCount());
                } catch (Throwable ignored) {
                }
                return 0;
            }
            // 2) 枚举该分量内 AC 源/换向器方块
            double genFreq = 0;
            for (Set<WirePoint> comp : mgr.components()) {
                if (!comp.contains(seed)) continue;
                for (WirePoint p : comp) {
                    if (!WireKeyUtil.isBlock(p.key)) continue;
                    BlockPos pos = PhasorNetworkBuilder.pointPosOfPublic(p.key);
                    if (pos == null) continue;
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                    // 2026-08-21 临时诊断：打印分量内每个方块 + 频率（定位 AC 源
                    // 50Hz 读不到 → 万用表 0Hz → 电容按 DC 充电 333A）
                    if (be instanceof AcCreativeSourceBlockEntity src) {
                        float f = src.getFrequencyHz();
                        if (f > 0) return f; // AC 源绝对优先
                        try {
                            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                    "[FreqDbg] pos={} ACsrc freq={} (0!)", pos, f);
                        } catch (Throwable ignored) {
                        }
                    } else if (be instanceof CommutatorBlockEntity comm) {
                        double f = commutatorFrequencyHz(comm);
                        if (f > genFreq) genFreq = f; // 换向器兜底
                    } else if (be != null) {
                        try {
                            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                    "[FreqDbg] pos={} be={}", pos,
                                    be.getClass().getSimpleName());
                        } catch (Throwable ignored) {
                        }
                    }
                }
                break; // 只处理 seed 所在分量
            }
            return genFreq;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 换向器方块 → 转子频率（source 字段反射 → GeneratorCoupling → |ω|/2π）；0 = 不转/无 source */
    public static double commutatorFrequencyHz(CommutatorBlockEntity comm) {
        try {
            Field f = CommutatorBlockEntity.class.getDeclaredField("source");
            f.setAccessible(true);
            Object o = f.get(comm);
            if (o instanceof GeneratorCoupling gc) return generatorFrequencyHz(gc);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 沿导线拓扑 BFS，寻找相连的频率源。crossTransformers=false 时不跨变压器（用于变压器自身模式判断） */
    private static double searchConnectedFrequency(Level level, IWireEndpoint start, boolean crossTransformers) {
        Set<IWireEndpoint> visited = new HashSet<>();
        Deque<IWireEndpoint> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        double genFreq = 0; // 换向器/发电机兜底频率（无 AC 源时用）
        while (!queue.isEmpty()) {
            IWireEndpoint e = queue.poll();
            // 1) AC 创造源：绝对优先（配置频率，直接返回）
            double acFreq = acFrequencyAtEndpoint(level, e);
            if (acFreq > 0) return acFreq;
            // 2) 换向器/发电机：转速>0 即记录为兜底（发电机空载电流≈0，不能依赖电流符号）
            double gf = generatorFrequencyAtEndpoint(level, e);
            if (gf > genFreq) genFreq = gf;
            // 3) 沿连接导线继续遍历
            for (BaseWireEntity wire : wiresAtEndpoint(level, e)) {
                IWireEndpoint ep1 = wire.getEndpoint1();
                IWireEndpoint ep2 = wire.getEndpoint2();
                IWireEndpoint other = (ep1 != null && ep1.equals(e)) ? ep2 : ep1;
                if (other != null && visited.add(other)) {
                    queue.add(other);
                }
            }
            // 4) 变压器跨越（可选）：端点所在方块是变压器 → 把全部端子加入遍历
            //    （原/副边各自独立的导线网络经变压器磁耦合，跨过去才能继承频率）
            //    变压器自身模式判断传 false：只检测直接连接本变压器的网络，
            //    避免其他网络（隔着其他变压器）的频率渗入 → 0Hz 误判为 AC。
            if (crossTransformers) {
                crossTransformer(level, e, visited, queue);
            }
        }
        return genFreq;   // 无 AC 源 → 发电机兜底频率；仍 0 = 直流
    }

    /** 变压器跨越：若端点在变压器方块上，把该变压器 4 个端子都加入 BFS（跨网络继承频率） */
    private static void crossTransformer(Level level, IWireEndpoint e,
                                         Set<IWireEndpoint> visited, Deque<IWireEndpoint> queue) {
        try {
            BlockPos pos = BlockPos.containing(e.getExactPosition(level));
            if (level.getBlockEntity(pos) instanceof TransformerBlockEntity) {
                for (int t = 0; t < 4; t++) {
                    IWireEndpoint ep = new BlockWireEndpoint(pos, t);
                    if (visited.add(ep)) queue.add(ep);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 端点附近查找与该端点相连的导线实体 */
    private static Set<BaseWireEntity> wiresAtEndpoint(Level level, IWireEndpoint e) {
        Set<BaseWireEntity> wires = new HashSet<>();
        Vec3 pos = e.getExactPosition(level);
        for (BaseWireEntity wire : level.getEntitiesOfClass(BaseWireEntity.class,
                new AABB(pos, pos).inflate(2.0))) {
            if (wireConnectsEndpoint(level, wire, e)) {
                wires.add(wire);
            }
        }
        return wires;
    }

    /** 导线是否连接端点 e：优先【稳定节点】endpoint 匹配（PowerGrid 导线实体的
     *  getEndpoint1/2 pos 是共享 MutableBlockPos，值漂移 → equals 会把附近不同
     *  电路的导线误匹配 → 频率 BFS 串扰（"原版 DC 源测到其他 AC 源频率"）。
     *  稳定节点（WireEntity.getWire().getNode1/2）的 endpoint 固定，不漂移。 */
    private static boolean wireConnectsEndpoint(Level level, BaseWireEntity wire, IWireEndpoint e) {
        try {
            if (wire instanceof org.patryk3211.powergrid.electricity.wire.WireEntity we) {
                org.patryk3211.powergrid.electricity.sim.ElectricWire ew = we.getWire();
                if (ew != null) {
                    for (org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd :
                            new org.patryk3211.powergrid.electricity.sim.node.IElectricNode[]{
                                    ew.getNode1(), ew.getNode2()}) {
                        if (nd instanceof org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode ofn
                                && ofn.endpoint != null && ofn.endpoint.equals(e)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 兜底：原始 endpoint equals（稳定节点不可得时）
        IWireEndpoint ep1 = wire.getEndpoint1();
        IWireEndpoint ep2 = wire.getEndpoint2();
        return (ep1 != null && ep1.equals(e)) || (ep2 != null && ep2.equals(e));
    }

    /**
     * 变压器模式判断专用频率检测：始终只计算【本变压器方块 4 个端子】上的导线连接。
     * <p>
     *   - 只收集锚定在本变压器方块上的导线端点（BlockWireEndpoint.getPos() = 方块 pos，
     *     用 getPos().equals(pos) 精确匹配，即方块 4 个端子上的导线）；
     *   - 从这些端点 BFS，且不跨其他变压器（crossTransformers=false）→
     *     本变压器以外任何网络（含其他变压器副边、独立网络）的频率都不会影响模式判断。
     */
    public static double getTransformerNetworkFrequencyHz(Level level, BlockPos transformerPos) {
        if (level == null || transformerPos == null) return 0;
        try {
            BlockPos wireAnchor = transformerPos;
            Set<IWireEndpoint> myTerminals = new HashSet<>();
            for (BaseWireEntity wire : level.getEntitiesOfClass(BaseWireEntity.class,
                    new AABB(transformerPos).inflate(3.0))) {
                addAnchoredEndpoint(wire.getEndpoint1(), wireAnchor, myTerminals);
                addAnchoredEndpoint(wire.getEndpoint2(), wireAnchor, myTerminals);
            }
            // 本变压器 4 个端子上的导线各自所在网络都算，任一网络有 AC 源 → AC
            for (IWireEndpoint ep : myTerminals) {
                double f = searchConnectedFrequency(level, ep, false);
                if (f > 0) return f;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 若导线端点锚定在 anchor 位置（本变压器某端子），收集去重 */
    private static void addAnchoredEndpoint(IWireEndpoint ep, BlockPos anchor, Set<IWireEndpoint> out) {
        if (ep instanceof BlockWireEndpoint b && b.getPos().equals(anchor)) {
            out.add(b);
        }
    }

    /** 端点所在方块是否为 AC 创造源；是则返回频率，否则 -1 */
    private static double acFrequencyAtEndpoint(Level level, IWireEndpoint e) {
        try {
            BlockPos pos = BlockPos.containing(e.getExactPosition(level));
            if (level.getBlockEntity(pos) instanceof AcCreativeSourceBlockEntity ac) {
                return ac.getFrequencyHz();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 端点所在方块是否为发电机换向器；是则返回转子转速频率，否则 -1 */
    private static double generatorFrequencyAtEndpoint(Level level, IWireEndpoint e) {
        try {
            BlockPos pos = BlockPos.containing(e.getExactPosition(level));
            if (level.getBlockEntity(pos) instanceof CommutatorBlockEntity commutator) {
                GeneratorCoupling source = ((CommutatorBlockEntityAccessor) commutator).cryptand$source();
                if (source != null) {
                    IRotor rotor = ((GeneratorCouplingAccessor) source).cryptand$rotor();
                    if (rotor != null) {
                        // getAngularVelocityRadians() 返回 rad/s → Hz
                        return Math.abs(rotor.getAngularVelocityRadians()) / (2 * Math.PI);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
