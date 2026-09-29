/**
 * ===== 相量网络上下文：Network + 方块→引擎节点映射 =====
 *
 * PhasorNetworkBuilder 构建引擎 Network 时，同时记录每个方块位置 → 该方块
 * 各端子对应的引擎节点 id。求解完成后凭此反查任意方块的端子电压
 * （复数相量），从而计算跨元件的精确电压差（|V|）。
 *
 * 线程安全：构建与求解均在服务端 tick 线程（或纯数据上），由调用方保证。
 */

package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import net.minecraft.core.BlockPos;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class PhasorNetworkContext extends com.hdf.cryptand.engine.NetworkContext<net.minecraft.core.BlockPos> {
    /** 求解时网表指纹（2026-08-24：solveAll 用【求解前】快照标记 res.networkHash；
     *  本字段在求解【前】= network.structureHash()——advancePseudoTime 推进 EMF
     *  后 network 变化不影响它；WireHeat 校验用它防旧/异源结果（消除假 mismatch） */
    public volatile long solveHash;
    /** 方块位置 → 该方块各端子引擎节点 id（未连线的端子为 null） */
    public final Map<BlockPos, Integer[]> blockTerminals;
    /** PowerGrid 网络节点 → 引擎节点 id（相量电压回写 network.setValue 用） */
    public final Map<OwnedFloatingNode, Integer> nodeToEngine;
    /** 自管导线端点 → 引擎节点 id（2026-08-13 自管拓扑：WireGraph 构建源；
     *  nodeToEngine 为原版节点映射（过渡期宿主），本映射为自管端点映射
     *  （WirePoint key）。两者都指向同一引擎节点，消费端二选一。 */
    public final Map<String, Integer> pointToEngine;
    /** 变压器方块 → 模型（求解后算相量损耗发热用） */
    public final Map<BlockPos, TransformerModel> transformerModels;
    /** 开路支路：悬空引擎节点 id → 参考节点 id。
     *  无源两端口元件（电阻/电容/电感）一端悬空（只连这一个元件、非地）时，
     *  物理开路无电流 → 求解后该端电压【跟随】另一端（两端等电位）。
     *  防止相量把悬空端判为 0V → PowerGrid 原版元件 internalWire
     *  (V-0)/R 虚假大电流 → 烧毁（变压器次级"一根导线+开路电阻"场景）。 */
    public final Map<Integer, Integer> openTerminal;
    /** 无任何闭合回路（叶子修剪后 2-core 为空）→ 跳过该网络计算：
     *  不求解，所有节点写 0（悬空设备不执行）。由 buildContextFromNetwork 判定。 */
    public final boolean loopless;

    public PhasorNetworkContext(Network network, Map<BlockPos, Integer[]> blockTerminals,
                                double frequency) {
        this(network, blockTerminals, Collections.emptyMap(), frequency, Collections.emptyMap(),
                Collections.emptyMap(), false);
    }

    public PhasorNetworkContext(Network network, Map<BlockPos, Integer[]> blockTerminals,
                                Map<OwnedFloatingNode, Integer> nodeToEngine, double frequency) {
        this(network, blockTerminals, nodeToEngine, frequency, Collections.emptyMap(),
                Collections.emptyMap(), false);
    }

    public PhasorNetworkContext(Network network, Map<BlockPos, Integer[]> blockTerminals,
                                Map<OwnedFloatingNode, Integer> nodeToEngine, double frequency,
                                Map<BlockPos, TransformerModel> transformerModels) {
        this(network, blockTerminals, nodeToEngine, frequency, transformerModels,
                Collections.emptyMap(), false);
    }

    public PhasorNetworkContext(Network network, Map<BlockPos, Integer[]> blockTerminals,
                                Map<OwnedFloatingNode, Integer> nodeToEngine, double frequency,
                                Map<BlockPos, TransformerModel> transformerModels,
                                Map<Integer, Integer> openTerminal) {
        this(network, blockTerminals, nodeToEngine, frequency, transformerModels,
                openTerminal, false);
    }

    public PhasorNetworkContext(Network network, Map<BlockPos, Integer[]> blockTerminals,
                                Map<OwnedFloatingNode, Integer> nodeToEngine, double frequency,
                                Map<BlockPos, TransformerModel> transformerModels,
                                Map<Integer, Integer> openTerminal, boolean loopless) {
        this(network, blockTerminals, nodeToEngine, Collections.emptyMap(), frequency,
                transformerModels, openTerminal, loopless);
    }

    /** 完整构造（含自管端点映射 pointToEngine，2026-08-13 自管拓扑） */
    public PhasorNetworkContext(Network network, Map<BlockPos, Integer[]> blockTerminals,
                                Map<OwnedFloatingNode, Integer> nodeToEngine,
                                Map<String, Integer> pointToEngine, double frequency,
                                Map<BlockPos, TransformerModel> transformerModels,
                                Map<Integer, Integer> openTerminal, boolean loopless) {
        super(network, frequency); // ⚠ 2026-08-30 继承引擎 NetworkContext（图+频率）
        this.blockTerminals = Collections.unmodifiableMap(blockTerminals);
        this.nodeToEngine = Collections.unmodifiableMap(nodeToEngine);
        this.pointToEngine = Collections.unmodifiableMap(pointToEngine);
        this.transformerModels = Collections.unmodifiableMap(transformerModels);
        this.openTerminal = Collections.unmodifiableMap(openTerminal);
        this.loopless = loopless;
    }

    /**
     * 参数变化消息机制【结构 vs 参数】：
     *  - 电路【结构】变化（接线/拆线/方块添加移除）→ CryptandTopologyManager
     *    .markTopologyChanged() 递增拓扑版本 → 重建电路网络。
     *  - 元件【参数】变化（电阻/电容/电感/源值）→ 元件 setter 发送参数变化消息
     *    （ParamChangeSource.notifyParamChanged）→ 本 ctx 的 paramVersion++。
     *    接收方（求解器）据此【不重建网络】只【重解】（结构复用）。
     */
    public final AtomicLong paramVersion = new AtomicLong();

    /**
     * 参数刷新源：方块位置 → 元件 setter。每次求解前刷新（读缓存当前值 → setter），
     * 值【变化】时 setter 内部自动发送参数变化消息 → paramVersion++。值不变则
     * 无消息、无版本变化 → 求解器直接复用旧结果（零开销）。
     * 2026-08-15 去 level：读 DeviceParamCache/DeviceCache（主线程预同步），
     * 不再碰 Level/BE。
     */
    public interface ParamSource {
        void refresh();
    }

    /** 可调参数源（电阻/源/电容/电感）；结构不变时每次求解前刷新 */
    public final List<ParamSource> paramSources = new ArrayList<>();

    /** 按缓存参数刷新所有可调元件（不重建网络结构；值变化自动发消息 + 版本++） */
    public void refreshParams() {
        for (ParamSource ps : paramSources) {
            try {
                ps.refresh();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 变压器相量模型（复合模型：铜阻 + 铁损 + 理想变压器复合模型）
     *  实际变压器通过【包含】本模型（电路解析节点/损耗算法）+
     *  {@link ThermalModel}（温度模型：散热值/热容，过热仿真）进行模拟仿真。
     *  求解后按真实算法算铜损/铁损发热，推进温度模型。 */
    public static final class TransformerModel {
        /** 引擎节点 id：
         *  pa1 = 原边端子1（primaryCoil terminal1）
         *  x   = 原边铜阻后节点（互感绕组1 端 + 铁损电阻端）
         *  pa2 = 原边端子2（互感绕组1 另一端）
         *  pb1 = 副边端子1（secondaryCoil terminal1）
         *  pb2 = 副边端子2（互感绕组2 另一端）
         *  w   = 副边铜阻后节点（互感绕组2 端） */
        public final int pa1, x, pa2, pb1, pb2, w;
        /** 原边/副边铜阻（Ω）、铁损电阻（Ω）、互感（H）、原/副边漏感（H，互感模型为 0）、匝比 n2/n1 */
        public final double rCp, rCs, rCore, lM, lLp, lLs, ratio;
        /** 温度模型（散热值 W/K / 热容 J/K / 环境 / 最高温，魔法数字可配置） */
        public final com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel thermal;
        /** 上次推进温度的时间戳（ns），用于按真实时间步长积分 */
        private long lastHeatNanos;

        public TransformerModel(int pa1, int x, int pa2, int pb1, int pb2, int w,
                                double rCp, double rCs, double rCore, double lM,
                                double lLp, double lLs, double ratio,
                                com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel thermal) {
            this.pa1 = pa1; this.x = x; this.pa2 = pa2;
            this.pb1 = pb1; this.pb2 = pb2; this.w = w;
            this.rCp = rCp; this.rCs = rCs; this.rCore = rCore;
            this.lM = lM; this.lLp = lLp; this.lLs = lLs; this.ratio = ratio;
            this.thermal = thermal;
        }

        /** 施加发热功率推进温度模型（按真实时间步长），返回当前温度 K
         *  ⚠ 2026-08-30 统一仿真步长：调用方改用 {@link #advanceHeatStep}（dt——
         *  0.05×倍率）；本方法保留兼容旧调用。 */
        public double advanceHeat(double power, long nowNanos) {
            double dt = lastHeatNanos == 0 ? 0.05
                    : Math.min(1.0, Math.max(0.01, (nowNanos - lastHeatNanos) / 1e9));
            lastHeatNanos = nowNanos;
            return thermal.update(power, dt);
        }

        /** ⚠ 2026-08-30 统一仿真步长推进（dt = 0.05×倍率——替代真实时间） */
        public double advanceHeatStep(double power, double dt) {
            return thermal.update(power, dt > 0 ? dt : 0.05);
        }
    }
}
