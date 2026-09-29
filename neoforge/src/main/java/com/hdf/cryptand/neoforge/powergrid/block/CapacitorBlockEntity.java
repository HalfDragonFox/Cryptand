/**
 * ===== 电容方块实体 =====
 *
 * 后向欧拉伴生模型（时间域 MNA）：
 *   i(t) = C·dv/dt ≈ C·(v_k - v_{k-1})/Δt = G·v_k - G·v_{k-1}，G = C/Δt
 * 用 CurrentSourceWire 实现：导电率 G 计入矩阵，历史电流源 = -G·v_{k-1}
 * （v_{k-1} 为上一帧节点电压，由 DC 计算线程读取 volatile 快照计算）。
 *
 * 交流（频率>0）统一等效模型：G = 2πfC、无历史电流（稳态导通）。
 * 直流（0Hz）实时伴生模型：G = C/Δt + 历史电流（隔直流）。
 *
 * 计算分工（与交流分离的 DC 线程，按统一求解频率节拍）：
 *   - DC 线程（原 dcComputeFrequencyHz 独立频率已废除）：纯计算伴生模型 G / 历史电流，
 *     写入 volatile 待应用字段（pendingConductance / pendingCurrent）
 *   - 服务端 tick（electricalTick）：把待应用字段落盘到 CurrentSourceWire
 *     （所有 PowerGrid 写入只在服务端线程，避免与求解器竞争）
 *
 * 默认电容 1.0 F（20TPS 下 G=20S）；值存 NBT。
 */

package com.hdf.cryptand.neoforge.powergrid.block;

import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.threading.SimulationThreads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;

public class CapacitorBlockEntity extends ElectricBlockEntity implements ConfigurableComponent,
        SimulationThreads.DcTask {

    /** 默认电容（法拉） */
    public static final float DEFAULT_CAPACITANCE = 1.0f;
    /** 每 tick 时间步长（秒），20TPS */
    private static final double TICK_SECONDS = 1.0 / 20.0;
    /** 电路频率解析刷新间隔（tick） */
    private static final long FREQ_RESOLVE_INTERVAL = 10;

    private CurrentSourceWire wire;
    private float capacitance = DEFAULT_CAPACITANCE;
    /** 单位索引：0=µF, 1=nF, 2=pF */
    private int unitIndex;
    /** 所在电路频率（Hz），缓存（服务端 tick 更新，DC 线程只读） */
    private volatile double circuitFrequencyHz;
    private long lastFreqResolveTick = Long.MIN_VALUE;
    /** 上一帧节点电压快照（服务端 tick 更新，DC 线程只读） */
    private volatile double lastNodeVoltage;
    /** DC 线程计算出的待应用导电率 / 历史电流 */
    private volatile double pendingConductance;
    private volatile double pendingCurrent;
    /** 是否已注册到 DC 计算线程 */
    private boolean registeredToDcThread;

    public CapacitorBlockEntity(BlockPos pos, BlockState state) {
        super(CreativeSources.CAPACITOR_BE.get(), pos, state);
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(2);
        FloatingNode node0 = builder.terminalNode(0);
        FloatingNode node1 = builder.terminalNode(1);
        // 初始按实时模式：后向欧拉伴生导电率 G = C/Δt（等效模式会在 DC 线程计算中切换）
        pendingConductance = capacitance / TICK_SECONDS;
        wire = new CurrentSourceWire(node0, node1, pendingConductance);
        builder.add(wire);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        if (!registeredToDcThread) {
            SimulationThreads.get().registerDcTask(this);
            registeredToDcThread = true;
        }
    }

    /**
     * DC 线程每周期调用：纯计算伴生模型（不触碰 PowerGrid 对象）。
     *   - 等效（交流）：G = 2πfC，无历史电流
     *   - 实时（直流）：G = C/Δt，历史电流 = -G·v_{last}
     */
    @Override
    public void runDcCompute() {
        double freq = circuitFrequencyHz;
        if (MultimeterDebug.shouldUseEquivalentSimulation(freq)) {
            pendingConductance = 2 * Math.PI * freq * capacitance;
            pendingCurrent = 0;
        } else {
            double g = capacitance / TICK_SECONDS;
            pendingConductance = g;
            pendingCurrent = -g * lastNodeVoltage;
        }
    }

    @Override
    public void electricalTick() {
        super.electricalTick();
        // Cryptand 相量求解接管（2026-08-12 用户要求：禁用原版算法）：电容由
        // Cryptand 引擎的 Capacitor 元件建模（addElement），原版 CurrentSourceWire
        // 的导电率/电流写入会与求解/回写冲突（时域禁用下无意义）→ 跳过。
        if (ConfigCircuit.ENABLE_CRYPTAND_SOLVER.get()) {
            return;
        }
        if (wire == null || wire.getNetwork() == null) return;

        resolveCircuitFrequency();
        // 应用 DC 线程算好的伴生模型（写入只在服务端线程）
        if (wire.conductance() != pendingConductance) wire.setConductance(pendingConductance);
        wire.setCurrent(pendingCurrent);
        // 快照本帧电压供 DC 线程下一轮计算
        lastNodeVoltage = wire.getNode1().getVoltage() - wire.getNode2().getVoltage();
    }

    /** 解析所在电路频率（缓存，每 FREQ_RESOLVE_INTERVAL tick 刷新） */
    private double resolveCircuitFrequency() {
        long tick = level != null ? level.getGameTime() : 0;
        if (tick - lastFreqResolveTick < FREQ_RESOLVE_INTERVAL) return circuitFrequencyHz;
        lastFreqResolveTick = tick;
        try {
            // 沿真实导线拓扑 BFS 找相连 AC 源（自动尝试两个端子）
            circuitFrequencyHz = MultimeterDebug.getServerNetworkFrequencyHz(level, getBlockPos());
        } catch (Throwable t) {
            circuitFrequencyHz = 0;
        }
        return circuitFrequencyHz;
    }

    // ========== 配置（ConfigurableComponent） ==========

    @Override
    public float getValueBase() { return capacitance; }

    @Override
    public void setValueBase(float value) { setCapacitance(value); }

    @Override
    public int getUnitIndex() { return unitIndex; }

    @Override
    public void setUnitIndex(int index) {
        this.unitIndex = Math.max(0, Math.min(2, index));
        setUnsaved();
    }

    public float getCapacitance() { return capacitance; }

    public void setCapacitance(float capacitance) {
        this.capacitance = Math.max(0, capacitance);
        setUnsaved();
    }

    // ========== NBT ==========

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Capacitance")) this.capacitance = tag.getFloat("Capacitance");
        if (tag.contains("UnitIndex")) this.unitIndex = tag.getInt("UnitIndex");
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Capacitance", this.capacitance);
        tag.putInt("UnitIndex", this.unitIndex);
    }
}
