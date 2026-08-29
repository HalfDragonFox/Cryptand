/**
 * ===== 交流创造源方块实体 =====
 *
 * 基于 PowerGrid 的 ElectricBlockEntity（接线兼容），构建
 * VoltageSourceCoupling（电压源）/ CurrentSourceNode（电流源）节点。
 *
 * 交流计算统一采用【等效模型】：交流（频率>0）源值恒为有效值 RMS=amp/√2，
 * 电容/电感用 2πfC / 1/(2πfL)（见 MultimeterDebug.shouldUseEquivalentSimulation），
 * 彻底规避 20Hz 采样下 50/60Hz 无法真实振荡的问题。
 *
 * 另持有【频率当前点】（示波器式游标）：由 AC 计算线程按统一求解频率节拍
 * 推进位置（0 ~ 2^bits-1），供 UI 显示波形相位。
 *
 * 与 PowerGrid 原版创造源的区别：
 *   - 不添加 Create 的 ScrollValueBehaviour（取消右键机械动力样式滚动 UI）
 *   - 右键打开自定义 UI（AcCreativeSourceMenu），可设置频率(Hz)与幅值
 *   - 默认输出正弦波（频率默认 50Hz，幅值默认 10V / 1A）
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.neoforge.powergrid.adapter.FrequencyCurrentPoint;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.threading.SimulationThreads;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceNode;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.VoltageSourceCoupling;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import java.util.List;

public class AcCreativeSourceBlockEntity extends ElectricBlockEntity implements SimulationThreads.AcSource {

    /** 电压源默认幅值（V） */
    public static final float DEFAULT_VOLTAGE_AMPLITUDE = 10.0f;
    /** 电流源默认幅值（A） */
    public static final float DEFAULT_CURRENT_AMPLITUDE = 1.0f;
    /** 默认频率（Hz），输出正弦波 */
    public static final float DEFAULT_FREQUENCY_HZ = 50.0f;

    private VoltageSourceCoupling voltageSourceNode;
    private CurrentSourceNode currentSourceNode;

    /**
     * 源类型（true=电压源 / false=电流源）。
     * 注意：Architectury Transformer（dev 环境 Java agent）会破坏【构造器里】的字段赋值
     * （实测构造器赋值后 buildCircuit 读到 null/false），但不破坏 buildCircuit 里的赋值。
     * 因此源类型判断全部用【静态注册表 + level】，不依赖构造器赋值的字段。
     */
    private boolean voltageSource;

    /** 方块注册名（供诊断日志使用；buildCircuit 里赋值） */
    private String blockName = "?";

    /** 方块实际类名（供诊断日志使用；buildCircuit 里赋值） */
    private String blockClass = "?";

    /**
     * 静态注册表：BlockPos -> 是否电压源。
     * 构造器/放置时记录（Map.put 是方法调用，不受字段赋值破坏影响）；
     * buildCircuit 时查询。客户端/服务端同 JVM（集成服务器）共享。
     */
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.core.BlockPos, Boolean> SOURCE_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 幅值（电压 V / 电流 A） */
    private float amplitude = DEFAULT_VOLTAGE_AMPLITUDE;
    /** 频率（Hz） */
    private float frequencyHz = DEFAULT_FREQUENCY_HZ;
    /** 相位偏移（度，0~360），用于三相电设计（0°/120°/240°） */
    private float phaseDegrees = 0;
    /** 是否已注册到 AC 计算线程 */
    private boolean registeredToAcThread;

    public AcCreativeSourceBlockEntity(BlockPos pos, BlockState state) {
        super(CreativeSources.AC_CREATIVE_SOURCE_BE.get(), pos, state);
        // 构造器：只把源类型写入【静态注册表】（Map.put 是方法调用，不受字段赋值破坏影响），
        // 不缓存到实例字段（实例字段的赋值会被 Architectury Transformer 破坏）。
        try {
            net.minecraft.world.level.block.Block b = state == null ? null : state.getBlock();
            if (b instanceof AcCreativeSourceBlock sb) {
                SOURCE_TYPES.put(pos.immutable(), sb.isVoltageSource());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // 只保留基类的 ElectricBehaviour / ThermalBehaviour，
        // 不添加 Create 的 ScrollValueBehaviour —— 取消右键机械动力样式滚动 UI
        super.addBehaviours(behaviours);
    }

    /** TICK-REBUILD 防抖：上次重建 tick */
    private int lastRebuildTick = -1000;
    /** TICK-REBUILD 连续失败次数（上限后放弃，避免死循环） */
    private int rebuildFailCount;

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(2);
        FloatingNode node0 = builder.terminalNode(0);
        FloatingNode node1 = builder.terminalNode(1);
        // 网络级频率查询注册：端子节点 → 本源实体。查询时从网络 getNodes() 反查。
        MultimeterDebug.AC_SOURCE_NODES.put(node0, this);
        MultimeterDebug.AC_SOURCE_NODES.put(node1, this);

        // === 判断源类型：全部用【局部变量】（不读构造器字段——会被 Architectury Transformer 破坏） ===
        // 统一用 BlockEntity.getBlockState()（客户端/服务端都从区块状态获得一致值，
        // 不再用 level.getBlockState——客户端 BE 创建早期 level 可为 null 导致
        // 走注册表兜底而构建成错误的源类型 → 两端 internalNodes 数量不同 →
        // PowerGrid 同步包不匹配 → "Buffer read underrun (16B vs 12B)" 刷屏）。
        // 电流源分支 internalNodes=2（CurrentSourceNode + TransformerCoupling），
        // 电压源分支 internalNodes=1（VoltageSourceCoupling），两端必须一致。
        boolean vs;
        try {
            net.minecraft.world.level.block.state.BlockState bs = getBlockState();
            net.minecraft.world.level.block.Block lb = bs == null ? null : bs.getBlock();
            vs = lb instanceof AcCreativeSourceBlock b && b.isVoltageSource();
            // 同步回写注册表（供 level==null 重建兜底；两端同 JVM 共享）
            if (level != null) {
                SOURCE_TYPES.put(worldPosition.immutable(), vs);
            }
        } catch (Throwable t) {
            Boolean m = SOURCE_TYPES.get(worldPosition.immutable());
            vs = m != null ? m : true; // 兜底默认电压源，tick 会纠正
        }

        // === 构建节点（用局部变量 vs；buildCircuit 里给字段赋值是安全的，不受 transformer 影响） ===
        voltageSource = vs;
        if (vs) {
            blockClass = "com.hdf.cryptand.neoforge.powergrid.creative.AcCreativeSourceBlock";
            blockName = "cryptand:ac_creative_voltage_source";
            // 电压源（戴维南→诺顿）：内阻 1e-4Ω，与 PowerGrid 创造电压源一致
            voltageSourceNode = (VoltageSourceCoupling) builder.addInternalNode(
                    VoltageSourceCoupling.class, node0, node1, 1.0E-4f);
        } else {
            blockClass = "com.hdf.cryptand.neoforge.powergrid.creative.AcCreativeSourceBlock";
            blockName = "cryptand:ac_creative_current_source";
            // 电流源 + 耦合到两个端子
            currentSourceNode = (CurrentSourceNode) builder.addInternalNode(CurrentSourceNode.class);
            builder.couple(1f, 1.0E-4f, currentSourceNode, node0, node1);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) return;
        if (!registeredToAcThread) {
            SimulationThreads.get().registerAcSource(this);
            registeredToAcThread = true;
        }
        updateWaveform();
        // === 源类型确认 + 兜底重建 ===
        // level 非 null 时一定能判断真实类型；若与当前构建不符，触发 PowerGrid 重建网络，
        // 重建时 buildCircuit 会以 level 非 null 重新执行（此时能正确判断）。
        try {
            net.minecraft.world.level.block.Block lb = level.getBlockState(worldPosition).getBlock();
            if (lb instanceof AcCreativeSourceBlock sb) {
                boolean realVs = sb.isVoltageSource();
                SOURCE_TYPES.put(worldPosition.immutable(), realVs);
                // 通过节点实例判断当前构建类型（buildCircuit 赋值的字段，可靠）
                boolean builtAsVs = voltageSourceNode != null;
                if (realVs != builtAsVs) {
                    rebuildFailCount++;
                    // 防抖：每 40 tick 最多重建一次，连续 5 次失败后放弃（避免无限刷屏）
                    int gameTime = (int) level.getGameTime();
                    if (rebuildFailCount <= 5 && gameTime - lastRebuildTick >= 40) {
                        lastRebuildTick = gameTime;
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "AcSource TICK-REBUILD pos={} realVs={} builtAsVs={} attempt={}",
                                worldPosition, realVs, builtAsVs, rebuildFailCount);
                        if (getElectricBehaviour() != null) {
                            getElectricBehaviour().rebuildCircuit(true);
                        }
                    }
                } else {
                    rebuildFailCount = 0;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 每 tick 更新源值。交流（频率 &gt; 0）统一走等效模型：恒输出有效值 RMS=amp/√2；
     * 直流（0Hz）直接输出幅值。波形相位由 AC 线程推进的【频率当前点】表示（UI 显示）。
     */
    private void updateWaveform() {
        // Cryptand 相量求解接管时禁用原版 setVoltage（2026-08-12）：
        // 原版每 tick 写电压 vs Cryptand 网络重建/不稳定期间其他端子 0V 未写回
        // → 导线虚假大电流烧线（[WireBurn] i=833A 实锤：AC源 v=5.00 vs 变压器
        // v=0.00）→ 烧 → 重建 → 死循环（“进世界就烧”）。Cryptand 求解器用
        // AcVoltageSource/CurrentSource 元件建模源并回写端子电压，原版写电压
        // 是多余且冲突的。
        if (com.hdf.cryptand.neoforge.core.config.ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()) {
            return;
        }
        double value = frequencyHz > 0 ? amplitude / Math.sqrt(2.0) : amplitude;
        if (voltageSourceNode != null) {
            voltageSourceNode.setVoltage(value);
        }
        if (currentSourceNode != null) {
            currentSourceNode.setCurrent(value);
        }
    }

    private boolean isVoltageSource() {
        return voltageSource;
    }

    // ========== SimulationThreads.AcSource ==========

    @Override
    public double getWaveformFrequencyHz() {
        return frequencyHz;
    }

    /**
     * 2026-08-21 取消频率当前点（无时域，示波器游标无意义）：
     * 返回 null，AC 线程推进逻辑自动跳过。
     */
    @Override
    public FrequencyCurrentPoint getCurrentPoint() {
        return null;
    }

    /** 波形相位（弧度）= 本源相位偏移（三相电 0°/120°/240°；无时域游标） */
    public double getWaveformPhaseRad() {
        return Math.toRadians(phaseDegrees);
    }

    // ========== 频率 / 幅值 配置 ==========

    /** 是否已通过 UI 显式配置频率（2026-08-21 修复"50Hz 读成 0"）。
     *  Architectury Transformer 破坏构造器字段赋值（frequencyHz=DEFAULT_FREQUENCY_HZ
     *  变 0）；getFrequencyHz 需要区分【构造器破坏的 0】（应回默认 50）与【用户
     *  显式设 0Hz=直流】（应保持 0）。boolean 默认 false 不受构造器破坏影响。 */
    private boolean frequencyConfigured;

    public float getFrequencyHz() {
        return frequencyHz > 0 ? frequencyHz
                : (frequencyConfigured ? 0f : DEFAULT_FREQUENCY_HZ);
    }

    public float getAmplitude() { return amplitude; }

    /**
     * 源类型查询（2026-08-20 修复"AC 源孤立测电压 100MV"）：
     * 原实现直接返回实例字段 voltageSource——默认 false（电流源），只在
     * buildCircuit 里赋值。DeviceParamCache.readEntry（主线程每 tick 同步）
     * 可能【先于 PowerGrid 网络构建/buildCircuit】读取 → 电压源被误分类成
     * 电流源（日志 GraphBuild 显示 CURRENT_SOURCE）→ 孤立电流源 MNA 无条件
     * 注入 → 两端电压 = I/GMin = 巨大值（100MV）。
     * 改确定性判断：buildCircuit 已执行（字段 true）→ true；否则方块状态
     * 判断（客户端/服务端一致）→ 静态注册表兜底 → 字段兜底。
     */
    public boolean isVoltageSourceType() {
        try {
            if (voltageSource) return true;
            net.minecraft.world.level.block.state.BlockState bs = getBlockState();
            if (bs != null && bs.getBlock() instanceof AcCreativeSourceBlock b) {
                boolean v = b.isVoltageSource();
                if (v) {
                    // 方块是电压源但字段未赋值（buildCircuit 未执行）→ 补写字段，
                    // 后续 buildCircuit 前读取也稳定
                    voltageSource = true;
                    SOURCE_TYPES.put(worldPosition.immutable(), true);
                }
                return v;
            }
            Boolean m = SOURCE_TYPES.get(worldPosition.immutable());
            if (m != null) return m;
        } catch (Throwable ignored) {
        }
        return voltageSource;
    }

    public void setFrequencyHz(float frequencyHz) {
        this.frequencyHz = Math.max(0, Math.min(100000, frequencyHz));
        this.frequencyConfigured = true; // 用户显式配置（含 0Hz=直流）
        setUnsaved();
    }

    public void setAmplitude(float amplitude) {
        this.amplitude = Math.max(0, Math.min(100000, amplitude));
        setUnsaved();
    }

    public float getPhaseDegrees() { return phaseDegrees; }

    public void setPhaseDegrees(float phaseDegrees) {
        // 归一化到 [0, 360)
        this.phaseDegrees = ((phaseDegrees % 360f) + 360f) % 360f;
        setUnsaved();
    }

    // ========== NBT ==========

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Frequency")) this.frequencyHz = tag.getFloat("Frequency");
        if (tag.contains("Amplitude")) this.amplitude = tag.getFloat("Amplitude");
        if (tag.contains("Phase")) this.phaseDegrees = tag.getFloat("Phase");
        // 2026-08-22 修复“退出重进恢复默认值”：frequencyConfigured 未保存 →
        // 重进后 configured=false → getFrequencyHz 把用户配置的 0Hz(直流) 等
        // 兜底回默认 50Hz。随存档/网络同步一并恢复。
        if (tag.contains("FreqCfg")) this.frequencyConfigured = tag.getBoolean("FreqCfg");
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("Frequency", this.frequencyHz);
        tag.putFloat("Amplitude", this.amplitude);
        tag.putFloat("Phase", this.phaseDegrees);
        tag.putBoolean("FreqCfg", this.frequencyConfigured);
    }
}
