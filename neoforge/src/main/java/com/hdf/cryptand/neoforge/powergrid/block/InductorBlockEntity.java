/**
 * ===== 电感方块实体 =====
 *
 * 后向欧拉伴生模型（时间域 MNA）：
 *   v = L·di/dt → i_k = (Δt/L)·v_k + i_{k-1} = G·v_k + i_{k-1}，G = Δt/L
 * 用 CurrentSourceWire 实现：导电率 G 计入矩阵，历史电流源 = i_{k-1}。
 *
 * 交流（频率>0）统一等效模型：G = 1/(2πfL)、无历史电流（稳态阻断）。
 * 直流（0Hz）实时伴生模型：G = Δt/L + 历史电流（通直流）。
 *
 * 计算分工（与交流分离的 DC 线程，按统一求解频率节拍）：
 *   - DC 线程（原 dcComputeFrequencyHz 独立频率已废除）：纯计算伴生模型 G / 历史电流，
 *     写入 volatile 待应用字段（pendingConductance / pendingCurrent）
 *   - 服务端 tick（electricalTick）：把待应用字段落盘到 CurrentSourceWire
 *     （所有 PowerGrid 写入只在服务端线程，避免与求解器竞争）
 *
 * 默认电感 1.0 H（20TPS 下 G=0.05S）；值存 NBT。
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

public class InductorBlockEntity extends ElectricBlockEntity implements ConfigurableComponent,
        SimulationThreads.DcTask {

    /** 默认电感（亨利） */
    public static final float DEFAULT_INDUCTANCE = 1.0f;
    /** 每 tick 时间步长（秒），20TPS */
    private static final double TICK_SECONDS = 1.0 / 20.0;
    /** 电路频率解析刷新间隔（tick） */
    private static final long FREQ_RESOLVE_INTERVAL = 10;

    private CurrentSourceWire wire;
    private float inductance = DEFAULT_INDUCTANCE;
    /** 单位索引：0=H, 1=mH, 2=µH */
    private int unitIndex;
    /** 所在电路频率（Hz），缓存（服务端 tick 更新，DC 线程只读） */
    private volatile double circuitFrequencyHz;
    private long lastFreqResolveTick = Long.MIN_VALUE;
    /** 上一时刻电感电流 i_{k-1}（历史电流源，仅 DC 线程写） */
    private volatile double previousCurrent;
    /** 上一帧节点电压快照（服务端 tick 更新，DC 线程只读） */
    private volatile double lastNodeVoltage;
    /** DC 线程计算出的待应用导电率 / 历史电流 */
    private volatile double pendingConductance;
    private volatile double pendingCurrent;
    /** 是否已注册到 DC 计算线程 */
    private boolean registeredToDcThread;

    public InductorBlockEntity(BlockPos pos, BlockState state) {
        super(CreativeSources.INDUCTOR_BE.get(), pos, state);
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(2);
        FloatingNode node0 = builder.terminalNode(0);
        FloatingNode node1 = builder.terminalNode(1);
        // 初始按实时模式：后向欧拉伴生导电率 G = Δt/L
        pendingConductance = inductance > 0 ? TICK_SECONDS / inductance : 0;
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
     *   - 等效（交流）：G = 1/(2πfL)，无历史电流（稳态阻断）
     *   - 实时（直流）：G = Δt/L，i_{k} = G·v_{last} + i_{k-1}
     */
    @Override
    public void runDcCompute() {
        double freq = circuitFrequencyHz;
        if (MultimeterDebug.shouldUseEquivalentSimulation(freq)) {
            pendingConductance = inductance > 0 ? 1.0 / (2 * Math.PI * freq * inductance) : 0;
            pendingCurrent = 0;
            previousCurrent = 0;
        } else {
            double g = inductance > 0 ? TICK_SECONDS / inductance : 0;
            pendingConductance = g;
            previousCurrent = g * lastNodeVoltage + previousCurrent;
            pendingCurrent = previousCurrent;
        }
    }

    @Override
    public void electricalTick() {
        super.electricalTick();
        // Cryptand 相量求解接管（2026-08-12 用户要求：禁用原版算法）：电感由
        // Cryptand 引擎的 Inductor 元件建模（addElement），原版 CurrentSourceWire
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
    public float getValueBase() { return inductance; }

    @Override
    public void setValueBase(float value) { setInductance(value); }

    @Override
    public int getUnitIndex() { return unitIndex; }

    @Override
    public void setUnitIndex(int index) {
        this.unitIndex = Math.max(0, Math.min(2, index));
        setUnsaved();
    }

    public float getInductance() { return inductance; }

    public void setInductance(float inductance) {
        this.inductance = Math.max(0, inductance);
        setUnsaved();
    }

    // ========== NBT ==========

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Inductance")) this.inductance = tag.getFloat("Inductance");
        if (tag.contains("PrevCurrent")) this.previousCurrent = tag.getDouble("PrevCurrent");
        if (tag.contains("UnitIndex")) this.unitIndex = tag.getInt("UnitIndex");
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Inductance", this.inductance);
        tag.putDouble("PrevCurrent", this.previousCurrent);
        tag.putInt("UnitIndex", this.unitIndex);
    }
}
