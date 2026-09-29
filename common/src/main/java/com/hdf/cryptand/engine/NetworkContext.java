package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ===== 引擎网络上下文（2026-08-30 引擎独立核心——纯 Java 零 MC） =====
 *
 * 一个网络的完整引擎视图：图（Network）+ 组装器表 + 温度/能量模型 + 绑定器
 * + 导线列表 + 网络锁——引擎求解/处理以本上下文为单位（网络为单位——取消全局）。
 *
 * 键（组装器/温度/绑定——Map key）：设备标识（引擎 id/网络键——平台提供，
 * 如 MC BlockPos、EDA 节点 id——引擎不关心具体类型）。
 */
public class NetworkContext<K> {

    /** 引擎相量网络（图——求解用） */
    public final Network network;

    /** 求解频率（Hz）；0 = 直流 */
    public final double frequency;

    /** 组装器表（设备键 → 组装器——完整设备虚拟模型——构建注册；键=平台设备
     *  id：MC BlockPos / EDA 节点——引擎不关心类型） */
    public final Map<K, Assembler> assemblers = new ConcurrentHashMap<>();

    /** 温度模型（设备键 → ThermalDevice——求解后推进温度） */
    public final Map<K, ThermalDevice> deviceThermals = new ConcurrentHashMap<>();

    /** 能量模型（设备键 → EnergyDevice——求解后同步储能） */
    public final Map<K, EnergyDevice> energyDevices = new ConcurrentHashMap<>();

    /** 绑定器（设备键 → Binding——平台实现绑定对象——求解完成发消息） */
    public final Map<K, Binding> bindings = new ConcurrentHashMap<>();

    /** 导线段（WireComposite——电阻+温度——求解后推进段温度）：
     *  非 final——构建可替换（平台构建完成后赋段列表） */
    public java.util.List<WireComposite> wireSegments =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** ⚠ 2026-08-30 导线组（用户：网络从存储导线改成导线组）——每个导线组 =
     *  导线组装器（多段绑定 + 总电阻 + 共享温度模型）。导线加入网络 → 拓扑
     *  运算（连通）→ 加入现有组或新建组；总电阻 = Σ 各段电阻。求解后每组建
     *  WireComposite（总 R + 共享温度）统一推进温度。 */
    public final java.util.List<WireGroup> wireGroups =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** ⚠ 2026-08-30 推进步长倍率（用户：可配置每步长倍率——不同速度下推进的
     *  真实情况；默认 1× = 20tick/s 速度（每步 0.05s）。倍率 × 0.05 = 每步推进
     *  时长——温度/能量/动力学推进统一使用）。 */
    public volatile double simSpeed = 1.0;

    /** 当前推进步长（s = 0.05 × 倍率——20tick/s × 倍率） */
    public double stepDt() { return 0.05 * simSpeed; }

    /** 网络操作锁（用户：网络带锁+bool——true=处理中；异步调度 tryBegin/end
     *  非阻塞——处理中跳过防卡） */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public NetworkContext(Network network, double frequency) {
        this.network = network;
        this.frequency = frequency;
    }

    /** 非阻塞获取网络锁（成功 = 未处理 → 标记处理中，可求解） */
    public boolean tryBegin() {
        return processing.compareAndSet(false, true);
    }

    /** 求解完成 → 释放网络锁（bool 置 false） */
    public void end() {
        processing.set(false);
    }

    /** 当前是否处理中 */
    public boolean isProcessing() {
        return processing.get();
    }
}
