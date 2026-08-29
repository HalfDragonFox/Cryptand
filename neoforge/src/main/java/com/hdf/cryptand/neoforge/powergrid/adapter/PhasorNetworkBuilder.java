/**
 * ===== 相量网络构建器：游戏世界 → 引擎 Network =====
 *
 * 从一组 PowerGrid 导线实体构建 Cryptand 引擎的相量网络（common 模块）：
 *   - 导线连通 = 同一电气节点（短导线电阻忽略，合并；后续可加导线电阻）
 *   - 方块端子（源/电阻/电容/电感）→ 两端口元件
 *   - 交流频率由调用方提供（网络级频率查询）
 *
 * 端点坐标约定：导线端点 BlockWireEndpoint.getPos() = 方块 pos，
 * 方块端子 = (方块pos, t) → 导线端点 key = (方块pos, t)。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.IdealTransformerModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MeterModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.MutualInductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit;
import com.hdf.cryptand.circuitsimulation.lib.SpiceInstance;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.creative.AcCreativeSourceBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.creative.CapacitorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.creative.InductorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.creative.ProgrammableComponentBlockEntity;
import com.hdf.cryptand.neoforge.core.library.ComponentLibrary;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import com.hdf.cryptand.neoforge.powergrid.device.Assemblers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.resistor.ResistorBlockEntity;
import org.patryk3211.powergrid.electricity.resistor.ResistorValueBehaviour;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PhasorNetworkBuilder {

    private PhasorNetworkBuilder() {}

    /** 构建期诊断日志节流（每 5 秒每个位置最多一条） */
    private static final java.util.Map<BlockPos, Long> DBG_LAST = new java.util.concurrent.ConcurrentHashMap<>();

    /** 孤立引擎节点诊断节流 */
    private static volatile long isoDbgLast;

    /** 合并覆盖率诊断节流 */
    private static volatile long mergeDbgLast;

    /** terminals 收集诊断节流 */
    private static volatile long termDbgLast;

    /** 变压器端子补收集诊断节流 */
    private static volatile long xfrmTermDbgLast;

    /** 合并1（网络 TL）逐条诊断节流 */
    private static volatile long merge1DbgLast;

    /** 变压器 arr 诊断节流 */
    private static volatile long arrDbgLast;

    /** 导线段端点诊断节流 */
    private static volatile long segDbgLast;

    /** 构建异常诊断节流 + 计数（2026-08-12） */
    private static volatile long buildErrLast;
    private static int buildErrCount;

    /** null BE 诊断节流（2026-08-12）：后台线程 getBlockEntity 返回 null 的设备 */
    private static volatile long nullBeDbgLast;

    /** 端子缺失触发 rebuildCircuit 节流（2026-08-12）：buildCircuit 延迟/重进世界 */
    private static volatile long rebuildDbgLast;

    /** 合并3.5（导线实体）逐条诊断节流 */
    private static volatile long merge35DbgLast;

    /** 合并3.5 失败（端点 resolve 不到）已打印的导线（去重，防刷屏） */
    private static final java.util.Set<String> M35_FAIL_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 合并3.5 跳过（稳定节点不可得：虚拟/断开导线 getWire()==null）已打印的导线（去重） */
    private static final java.util.Set<String> M35_SKIP_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** buildContextFromGraph 构建诊断节流（2026-08-15 定位"线路计算异常"） */
    private static volatile long GRAPH_BUILD_DBG_LAST;

    private static void dbgLog(BlockPos pos, String fmt, Object... args) {
        long now = System.currentTimeMillis();
        Long last = DBG_LAST.get(pos);
        if (last != null && now - last < 5000) return;
        DBG_LAST.put(pos, now);
        try {
            CryptandNeoForge.WAF_LOGGER.info(fmt, args);
        } catch (Throwable ignored) {
        }
    }

    // ========== 网络内导线收集（仅客户端频率搜索用，服务端不走此路径） ==========

    /** 端点附近查找与该端点相连的导线实体 */
    static Set<BaseWireEntity> wiresAtEndpoint(Level level, IWireEndpoint e) {
        Set<BaseWireEntity> wires = new HashSet<>();
        net.minecraft.world.phys.Vec3 pos = e.getExactPosition(level);
        for (BaseWireEntity wire : level.getEntitiesOfClass(BaseWireEntity.class,
                new net.minecraft.world.phys.AABB(pos, pos).inflate(2.0))) {
            IWireEndpoint ep1 = wire.getEndpoint1();
            IWireEndpoint ep2 = wire.getEndpoint2();
            if ((ep1 != null && ep1.equals(e)) || (ep2 != null && ep2.equals(e))) {
                wires.add(wire);
            }
        }
        return wires;
    }

    /** 电阻 BE 滚动值（反射读 protected value 字段 → getResistance） */
    private static float getResistorValue(BlockEntity be) {
        try {
            java.lang.reflect.Field f = ResistorBlockEntity.class.getDeclaredField("value");
            f.setAccessible(true);
            Object v = f.get(be);
            if (v instanceof ResistorValueBehaviour rvb) {
                float r = rvb.getResistance();
                if (r > 0) return r;
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.debug(
                    "[BuildDbg] getResistorValue value-field failed for {}: {}",
                    be == null ? "?" : be.getClass().getSimpleName(), t.toString());
        }
        // 兜底：直接读电阻块内部 ElectricWire 的电阻（read()/electricalTick() 会 setResistance）
        try {
            Object wire = DeviceWire.field(be, "wire");
            if (wire instanceof org.patryk3211.powergrid.electricity.sim.ElectricWire ew) {
                double r = ew.getResistance();
                if (Double.isFinite(r) && r > 0) return (float) r;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 方块 → 引擎元件（两端口）。不认识的方块忽略。 */
    /**
     * 绑定统一走组装器（2026-08-13 用户架构：所有实际模型经组装器组建实际
     * 元件）——{@link Assembler#bindAll} 提供通用绑定（DeviceBinding/ModelLink
     * 双向 + 虚拟快照 + 虚拟过热爆炸），设备组装器可覆写 bind 自定义。
     */
    private static void addElement(Level level, BlockEntity be, int a, int b, Network net,
                                   List<PhasorNetworkContext.ParamSource> paramSources,
                                   Map<BlockPos, ThermalDevice> deviceThermals,
                                   java.util.Map<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice> energyDevices,
                                   Set<ElectricalNetwork> pgNets) {
        // 防御（2026-08-12）：后台线程读 Level，getBlockEntity 可能返回 null
        // （设备被拆除节点残留/区块未加载）→ 该设备确实不存在 → 跳过建模即可，
        // 绝不能让 NPE 崩掉整个构建（否则网络永不重建 → solved=0 → 万用表 0V）。
        if (be == null) return;
        if (be instanceof AcCreativeSourceBlockEntity src) {
            final BlockPos sp = be.getBlockPos();
            if (src.isVoltageSourceType()) {
                // 2026-08-15 多 AC 源修复：传源频率（多频网络 MultiToneSolver 按
                // 频率选择性注入；0=跟随网络主导频率）
                AcVoltageSource vs = new AcVoltageSource(a, b, src.getAmplitude(),
                        src.getPhaseDegrees(), 1e-4, src.getFrequencyHz());
                net.addElement(vs);
                // 参数刷新源：读缓存当前幅值/相位 → setter；值变化自动发送参数变化消息
                // （2026-08-15 去 level：读 DeviceParamCache，不碰 level/BE）
                paramSources.add(() -> {
                    DeviceParamCache.Entry e = DeviceParamCache.get(sp);
                    if (e != null && e.kind == DeviceParamCache.Kind.AC_VOLTAGE_SRC) {
                        vs.setAmplitude(e.amplitude);
                        vs.setPhaseDeg(e.phaseDeg);
                    }
                });
            } else {
                CurrentSource cs = new CurrentSource(a, b, src.getAmplitude(),
                        src.getFrequencyHz());
                net.addElement(cs);
                paramSources.add(() -> {
                    DeviceParamCache.Entry e = DeviceParamCache.get(sp);
                    if (e != null && e.kind == DeviceParamCache.Kind.AC_CURRENT_SRC) {
                        cs.setCurrent(e.amplitude);
                    }
                });
            }
        } else if (be instanceof CapacitorBlockEntity cap) {
            // 电容复合模型（2026-08-12 用户要求：电容是复合模型）：
            // 组合基础电容（相量 jωC 隔直通交 + 时域伴随）+ ESR（温度发热）
            // + 能量模型（储能/类似电池）
            int cx = net.addNode().id;
            com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel capModel =
                    new com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel(
                            a, b, cx, cap.getCapacitance(), 0.5, // ESR=0.5Ω（魔法数字）
                            null, DeviceThermalStore.thermalFor(be.getBlockPos()));
            try {
                capModel.setCompositeKey("C" + be.getBlockPos());
            } catch (Throwable ignored) {
            }
            net.addComposite(capModel);
            // 储能 + 温度收集（求解后统一同步电荷/推进温度）
            energyDevices.put(be.getBlockPos(), capModel);
            deviceThermals.put(be.getBlockPos(), capModel);
            // 组装器统一绑定：实际模型 ↔ 复合元件（破坏感知/移除通知）
            Assembler.bindAll(be, capModel, pgNets);
            final BlockPos cp = be.getBlockPos();
            final com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel cmRef = capModel;
            paramSources.add(() -> {
                DeviceParamCache.Entry e = DeviceParamCache.get(cp);
                if (e != null && e.kind == DeviceParamCache.Kind.CAPACITOR) {
                    cmRef.setCapacitance(e.capacitance);
                }
            });
        } else if (be instanceof InductorBlockEntity ind) {
            // 电感复合模型（2026-08-12 用户要求：电感带储能效果，组合为复合元件）：
            // 基础电感（Backward Euler 电流记忆 = 储能）+ DCR 铜阻（温度发热）
            // + 能量模型（磁链/电流储能状态）
            int ix = net.addNode().id;
            com.hdf.cryptand.circuitsimulation.model.composite.InductorModel indModel =
                    new com.hdf.cryptand.circuitsimulation.model.composite.InductorModel(
                            a, b, ix, ind.getInductance(), 0.1, // DCR=0.1Ω（魔法数字）
                            null, DeviceThermalStore.thermalFor(be.getBlockPos()));
            try {
                indModel.setCompositeKey("L" + be.getBlockPos());
            } catch (Throwable ignored) {
            }
            net.addComposite(indModel);
            // 储能 + 温度收集
            energyDevices.put(be.getBlockPos(), indModel);
            deviceThermals.put(be.getBlockPos(), indModel);
            // 组装器统一绑定：实际模型 ↔ 复合元件（破坏感知/移除通知）
            Assembler.bindAll(be, indModel, pgNets);
            final BlockPos ip = be.getBlockPos();
            final com.hdf.cryptand.circuitsimulation.model.composite.InductorModel imRef = indModel;
            paramSources.add(() -> {
                DeviceParamCache.Entry e = DeviceParamCache.get(ip);
                if (e != null && e.kind == DeviceParamCache.Kind.INDUCTOR) {
                    imRef.setInductance(e.inductance);
                }
            });
        } else if (be instanceof ResistorBlockEntity) {
            float r = getResistorValue(be);
            if (r > 0) {
                // 电阻复合模型（2026-08-12 用户要求：电阻带温度模型）
                com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel resModel =
                        new com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel(
                                a, b, r, DeviceThermalStore.thermalFor(be.getBlockPos()));
                try {
                    resModel.setCompositeKey("R" + be.getBlockPos());
                } catch (Throwable ignored) {
                }
                net.addComposite(resModel);
                // 温度收集（统一推进 I²R 发热）
                deviceThermals.put(be.getBlockPos(), resModel);
                // 组装器统一绑定：实际模型 ↔ 复合元件（破坏感知/移除通知）
                Assembler.bindAll(be, resModel, pgNets);
                final BlockPos rp = be.getBlockPos();
                final com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel rrRef = resModel;
                paramSources.add(() -> {
                    DeviceParamCache.Entry e = DeviceParamCache.get(rp);
                    if (e != null && e.kind == DeviceParamCache.Kind.RESISTOR
                            && e.resistance > 0) {
                        rrRef.setResistance(e.resistance);
                    }
                });
            } else {
                dbgLog(be.getBlockPos(),
                        "[BuildDbg] resistor SKIPPED pos={} class={} r=0 (value field / wire both failed)",
                        be.getBlockPos(), be.getClass().getSimpleName());
            }
        } else if (isWinding(be) && isWindingMain(be)) {
            // 励磁绕组（Winding）：只有主方块持有 coilWire。整条线圈 = Rdc 串联 L
            // （物理正确：Z = R + jωL。注意【不能】两个元件跨同一对节点——那是
            //  并联，阻抗变成 R∥jωL 错误）。用内部节点做串联支路。
            // 代理方块不重复加元件（它的端子在网络上仍作为导线分支点）。
            double rdc = ConfigLoad.COIL_DC_RESISTANCE_OHM.get();
            double lphys = ConfigLoad.COIL_EFFECTIVE_INDUCTANCE_H.get();
            if (rdc > 0 || lphys > 0) {
                int x = net.addNode().id; // R-L 串联内部节点
                if (rdc > 0) net.addElement(new Resistor(a, x, rdc));
                if (lphys > 0) net.addElement(new Inductor(x, b, lphys));
            }
        } else {
            // 原版设备：按 PowerGrid 包结构分发的设备模型（battery/heater/switch...）
            Assembler model = Assemblers.get(be);
            if (model != null) {
                CompositeModel cm = null;
                // 2026-08-15 组装器缓存化：优先走缓存版（assembleFromCache 读
                // 原子缓存输入槽，后台可纯数据构建）；缓存未注册/返回 null →
                // 回退原 assemble(BE)（过渡兼容）。
                if (model instanceof com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler sca) {
                    try {
                        com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache c =
                                com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCacheRegistry
                                        .get(be.getBlockPos());
                        if (c == null) c = sca.cacheFor(be.getBlockPos()); // 首次注册到主控
                        if (c != null) {
                            cm = sca.assembleFromCache(be.getBlockPos(), c, a, b, net);
                            if (cm == null) {
                                // stamp 模式设备（仪表/创造源/连接器/电池 DC 源）
                                sca.stampFromCache(be.getBlockPos(), c, a, b, net);
                            }
                        }
                    } catch (Throwable ignored) {
                        cm = null;
                    }
                }
                if (cm == null) {
                    cm = model.assemble(be, a, b, net);
                }
                if (cm != null) {
                    // 设备复合元件的去重 key（同设备多网络 ctx 只更新一次温度）
                    try {
                        cm.setCompositeKey("D" + be.getBlockPos());
                    } catch (Throwable ignored) {
                    }
                    net.addComposite(cm);
                    // 带温度模型 → 记录到 ctx（求解后统一 update 推进设备温度）
                    if (cm instanceof ThermalDevice td) {
                        deviceThermals.put(be.getBlockPos(), td);
                    }
                    // 储能设备（电容/电池/机电能量模型）→ 记录到 ctx（求解后
                    // 统一同步储能状态——时间相关变量绑定，2026-08-12）
                    if (cm instanceof com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice ed) {
                        energyDevices.put(be.getBlockPos(), ed);
                    }
                    // 绑定实际模型事件接收器：温度超限 → 通知实际模型爆炸
                    // （完全解耦：引擎不知道 BE 细节，BE 侧只接收事件）
                    try {
                        cm.bindEvents(BeEventSink.of(be));
                    } catch (Throwable ignored) {
                    }
                    // 组装器统一绑定：实际模型 ↔ 复合元件（破坏感知/移除通知）
                    model.bind(be, cm, pgNets);
                    // 复合模型可调参数源（碳堆 trim/变阻器滑片 → setResistance）
                    model.registerParams(be.getBlockPos(), cm, paramSources);
                } else {
                    model.stamp(be, a, b, net);
                }
            } else {
                dbgLog(be.getBlockPos(),
                        "[BuildDbg] block UNRECOGNIZED pos={} class={} (no element, may become short)",
                        be.getBlockPos(), be.getClass().getSimpleName());
            }
        }
    }

    /** 收集分量内 AC 源频率（2026-08-15 多 AC 源修复）：≥2 个异频 → 设置网络
     *  WaveformGroup（触发 MultiToneSolver 每频率独立求解 + 合成 RMS）。
     *  源元件已带各自 frequency（见 addElement/addElementFromCache），MultiToneSolver
     *  按 activeAt 频率选择性注入——异频源互不干扰。 */
    private static void collectMultiTone(Network engine,
            java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp) {
        try {
            java.util.Map<Double, com.hdf.cryptand.circuitsimulation.model.WaveformGroup.Component> byFreq =
                    new java.util.LinkedHashMap<>();
            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                if (!WireKeyUtil.isBlock(p.key)) continue;
                BlockPos bp = pointPosOf(p.key);
                if (bp == null) continue;
                DeviceParamCache.Entry e = DeviceParamCache.get(bp);
                if (e == null || e.frequencyHz <= 0) continue;
                if (e.kind != DeviceParamCache.Kind.AC_VOLTAGE_SRC
                        && e.kind != DeviceParamCache.Kind.AC_CURRENT_SRC) continue;
                byFreq.putIfAbsent(e.frequencyHz,
                        new com.hdf.cryptand.circuitsimulation.model.WaveformGroup.Component(
                                e.frequencyHz, e.amplitude, e.phaseDeg,
                                com.hdf.cryptand.circuitsimulation.model.WaveformType.SINE));
            }
            if (byFreq.size() >= 2) {
                com.hdf.cryptand.circuitsimulation.model.WaveformGroup wg =
                        new com.hdf.cryptand.circuitsimulation.model.WaveformGroup();
                for (com.hdf.cryptand.circuitsimulation.model.WaveformGroup.Component c : byFreq.values()) {
                    wg.add(c);
                }
                engine.setWaveforms(wg);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 设备建模缓存驱动版（2026-08-15 完全异步：后台构建无 level/BE）。
     * 从 DeviceParamCache.Entry 读参数 + DeviceCache 组装器缓存构建 +
     * pos-only 绑定（DeviceBinding.ofPos / BeEventSink.ofPos）。逻辑与
     * {@link #addElement} 一致，但参数全部来自主线程预同步缓存。
     */
    private static void addElementFromCache(BlockPos pos, DeviceParamCache.Entry pe,
            int a, int b, Network net,
            List<PhasorNetworkContext.ParamSource> paramSources,
            Map<BlockPos, ThermalDevice> deviceThermals,
            java.util.Map<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice> energyDevices,
            Set<ElectricalNetwork> pgNets) {
        if (pe == null) return;
        try {
            switch (pe.kind) {
                case AC_VOLTAGE_SRC -> {
                    // 2026-08-15 多 AC 源修复：传缓存源频率（多频按频率选择性注入）
                    AcVoltageSource vs = new AcVoltageSource(a, b, pe.amplitude,
                            pe.phaseDeg, 1e-4, pe.frequencyHz);
                    net.addElement(vs);
                    final BlockPos sp = pos;
                    paramSources.add(() -> {
                        DeviceParamCache.Entry e = DeviceParamCache.get(sp);
                        if (e != null && e.kind == DeviceParamCache.Kind.AC_VOLTAGE_SRC) {
                            vs.setAmplitude(e.amplitude);
                            vs.setPhaseDeg(e.phaseDeg);
                        }
                    });
                }
                case AC_CURRENT_SRC -> {
                    CurrentSource cs = new CurrentSource(a, b, pe.amplitude,
                            pe.frequencyHz);
                    net.addElement(cs);
                    final BlockPos sp = pos;
                    paramSources.add(() -> {
                        DeviceParamCache.Entry e = DeviceParamCache.get(sp);
                        if (e != null && e.kind == DeviceParamCache.Kind.AC_CURRENT_SRC) {
                            cs.setCurrent(e.amplitude);
                        }
                    });
                }
                case CAPACITOR -> {
                    int cx = net.addNode().id;
                    com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel capModel =
                            new com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel(
                                    a, b, cx, pe.capacitance, 0.5, null,
                                    DeviceThermalStore.thermalFor(pos));
                    try { capModel.setCompositeKey("C" + pos); } catch (Throwable ignored) {}
                    net.addComposite(capModel);
                    energyDevices.put(pos, capModel);
                    deviceThermals.put(pos, capModel);
                    Assembler.bindAllPos(pos, capModel, pgNets);
                    final BlockPos cp = pos;
                    final com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel cmRef = capModel;
                    paramSources.add(() -> {
                        DeviceParamCache.Entry e = DeviceParamCache.get(cp);
                        if (e != null && e.kind == DeviceParamCache.Kind.CAPACITOR) {
                            cmRef.setCapacitance(e.capacitance);
                        }
                    });
                }
                case INDUCTOR -> {
                    int ix = net.addNode().id;
                    com.hdf.cryptand.circuitsimulation.model.composite.InductorModel indModel =
                            new com.hdf.cryptand.circuitsimulation.model.composite.InductorModel(
                                    a, b, ix, pe.inductance, 0.1, null,
                                    DeviceThermalStore.thermalFor(pos));
                    try { indModel.setCompositeKey("L" + pos); } catch (Throwable ignored) {}
                    net.addComposite(indModel);
                    energyDevices.put(pos, indModel);
                    deviceThermals.put(pos, indModel);
                    Assembler.bindAllPos(pos, indModel, pgNets);
                    final BlockPos ip = pos;
                    final com.hdf.cryptand.circuitsimulation.model.composite.InductorModel imRef = indModel;
                    paramSources.add(() -> {
                        DeviceParamCache.Entry e = DeviceParamCache.get(ip);
                        if (e != null && e.kind == DeviceParamCache.Kind.INDUCTOR) {
                            imRef.setInductance(e.inductance);
                        }
                    });
                }
                case RESISTOR -> {
                    if (pe.resistance > 0) {
                        com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel resModel =
                                new com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel(
                                        a, b, pe.resistance, DeviceThermalStore.thermalFor(pos));
                        try { resModel.setCompositeKey("R" + pos); } catch (Throwable ignored) {}
                        net.addComposite(resModel);
                        deviceThermals.put(pos, resModel);
                        Assembler.bindAllPos(pos, resModel, pgNets);
                        final BlockPos rp = pos;
                        final com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel rrRef = resModel;
                        paramSources.add(() -> {
                            DeviceParamCache.Entry e = DeviceParamCache.get(rp);
                            if (e != null && e.kind == DeviceParamCache.Kind.RESISTOR
                                    && e.resistance > 0) {
                                rrRef.setResistance(e.resistance);
                            }
                        });
                    }
                }
                case WINDING -> {
                    // 励磁绕组：只有主方块持有 coilWire（Rdc 串联 L）
                    if (pe.windingMain) {
                        double rdc = ConfigLoad.COIL_DC_RESISTANCE_OHM.get();
                        double lphys = ConfigLoad.COIL_EFFECTIVE_INDUCTANCE_H.get();
                        if (rdc > 0 || lphys > 0) {
                            int x = net.addNode().id;
                            if (rdc > 0) net.addElement(new Resistor(a, x, rdc));
                            if (lphys > 0) net.addElement(new Inductor(x, b, lphys));
                        }
                    }
                }
                default -> {
                    // 组装器设备（OTHER 及其它）
                    String simple = null;
                    if (pe.deviceClass != null) {
                        int dot = pe.deviceClass.lastIndexOf('.');
                        simple = dot >= 0 ? pe.deviceClass.substring(dot + 1) : pe.deviceClass;
                    }
                    Assembler model = Assemblers.getByClass(simple);
                    if (model == null) {
                        dbgLog(pos, "[BuildDbg] block UNRECOGNIZED (cache) pos={} class={}",
                                pos, pe.deviceClass);
                        return;
                    }
                    CompositeModel cm = null;
                    if (model instanceof com.hdf.cryptand.neoforge.powergrid.device
                            .SourceCacheAssembler sca) {
                        try {
                            DeviceCache c = DeviceCacheRegistry.get(pos);
                            if (c == null) c = sca.cacheFor(pos);
                            if (c != null) {
                                cm = sca.assembleFromCache(pos, c, a, b, net);
                                if (cm == null) sca.stampFromCache(pos, c, a, b, net);
                            }
                        } catch (Throwable ignored) {
                            cm = null;
                        }
                    }
                    if (cm == null) return; // 缓存未就绪 → 跳过（下轮同步后建模）
                    try { cm.setCompositeKey("D" + pos); } catch (Throwable ignored) {}
                    net.addComposite(cm);
                    if (cm instanceof ThermalDevice td) deviceThermals.put(pos, td);
                    if (cm instanceof com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice ed) {
                        energyDevices.put(pos, ed);
                    }
                    try { cm.bindEvents(BeEventSink.ofPos(pos)); } catch (Throwable ignored) {}
                    Assembler.bindAllPos(pos, cm, pgNets);
                    model.registerParams(pos, cm, paramSources);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 虚拟设备建模（2026-08-12 超长线路输电：未加载区块元件用参数快照）。
     * <p>
     * 仅覆盖【无源 R-L 绕组】负载（电机/加热器/灯/电磁铁/风扇等）→
     * {@link MotorModel}。功率源/变压器不保存快照（{@link VirtualDevice#of}
     * 已排除）→ 这里也不建模 → 未加载区块的发电机等【直接不输出】，必须
     * 区块加载才能供电，杜绝虚拟源产生无限功率。
     */
    private static void addVirtualElement(BlockPos pos, VirtualDevice pd,
                                          int a, int b, Network net,
                                          java.util.Map<BlockPos, ThermalDevice> deviceThermals) {
        if (pd == null) return;
        try {
            // 双保险：任何源/有源快照都不建模（正常路径 of() 已排除）
            if (pd.isVoltageSource) return;
            if (pd.resistance <= 0) return;
            com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                    DeviceThermalStore.thermalFor(pos);
            // 虚拟设备过热：断开（不建模）+ 保留温度模型（区块加载后 onLoad
            // 检测共享 ThermalModel 超限 → 实际模型直接爆炸，完全解耦）
            if (th.overheated()) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[VirtualOverheat] pos={} 过热断开，加载后爆炸", pos);
                return;
            }
            int x = net.addNode().id;
            com.hdf.cryptand.circuitsimulation.model.composite.MotorModel mm =
                    new com.hdf.cryptand.circuitsimulation.model.composite.MotorModel(
                            a, b, x, pd.resistance, Math.max(pd.inductance, 0),
                            th);
            // 绑定快照：计算时全局可调参数（外部 setResistance 等 → 下一轮自动
            // 应用 → 重解），不依赖 MC 模型更新
            mm.bind(pd);
            net.addComposite(mm);
            deviceThermals.put(pos, mm);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 功率表（PowerGaugeBlockEntity）3 端子建模。
     * <p>
     * PowerGrid 注册值：series=0.05Ω（低阻串联测流，端子 0-1）、shunt=20MΩ
     * （高阻分流测压，端子 0-2）。buildCircuit：setTerminalCount(3)，series
     * 连接 terminalNode(0)-terminalNode(1)，shunt 连接 terminalNode(0)-terminalNode(2)。
     * <p>
     * ⚠ 两条内部 wire 都必须建模进 MNA，且电阻必须等于实际内部 wire（反射读
     * series/shunt 字段的 getResistance()）。否则未建模侧两端独立求解 → 写回
     * 电压在低阻内部 wire 上产生虚假巨大电流 → 过热爆炸（同电流表问题，
     * [WireBurn] i=354.3A 实锤）。shunt 电阻用实际值（20MΩ 高阻）→ 测压几乎
     * 不分流，物理正确。
     */
    /** 可编程元件缓存驱动版：固化电路由主线程 DeviceParamCache 预存（纯数据引用），
     *  后台只读展开，不碰 BE。逻辑与 {@link #addProgrammableElement} 一致。 */
    private static void addProgrammableElementFromCache(BlockPos pos,
            com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit circ, Integer[] arr, Network net) {
        try {
            if (!ConfigLoad.ENABLE_SPICE_LIBRARY.get()) return;
            if (circ == null || circ.elements.isEmpty()) return;
            int n = circ.pins.size();
            int[] portIds = new int[n];
            for (int i = 0; i < n; i++) {
                if (arr != null && i < arr.length && arr[i] != null) {
                    portIds[i] = arr[i];
                } else {
                    portIds[i] = net.addNode().id; // 悬空端口 → 内部浮动节点
                }
            }
            SpiceInstance inst = ComponentLibrary.get().expandResolved(net, circ, portIds);
            for (Element e : inst.elements()) {
                net.addElement(e);
            }
            if (inst.elements().isEmpty()) {
                dbgLog(pos, "[Lib] programmable expanded to 0 elements (check library)");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 可编程元件：用【固化电路】（选择模型时已 resolve 写入方块 NBT）展开为
     * Cryptand 元件——不依赖库文件。端口按引脚顺序映射到 arr 引擎节点。
     */
    private static void addProgrammableElement(BlockEntity be, Integer[] arr, Network net) {
        ProgrammableComponentBlockEntity pcbe = (ProgrammableComponentBlockEntity) be;
        // 配置开关：SPICE 元件库禁用 → 不展开（含已固化元件）
        if (!ConfigLoad.ENABLE_SPICE_LIBRARY.get()) {
            dbgLog(be.getBlockPos(), "[Lib] SPICE library disabled by config, skipped");
            return;
        }
        ResolvedCircuit circ = pcbe.getResolvedCircuit();
        if (circ == null || circ.elements.isEmpty()) {
            dbgLog(be.getBlockPos(), "[Lib] programmable component no resolved circuit, skipped");
            return;
        }
        int n = circ.pins.size();
        int[] portIds = new int[n];
        for (int i = 0; i < n; i++) {
            if (arr != null && i < arr.length && arr[i] != null) {
                portIds[i] = arr[i];
            } else {
                portIds[i] = net.addNode().id; // 悬空端口 → 内部浮动节点（GMin 兜底）
            }
        }
        SpiceInstance inst = ComponentLibrary.get().expandResolved(net, circ, portIds);
        for (Element e : inst.elements()) {
            net.addElement(e);
        }
        if (!inst.notes().isEmpty()) {
            CryptandNeoForge.WAF_LOGGER.info("[Lib] expand {} @ {}: {}",
                    pcbe.getLibraryName(), be.getBlockPos(), inst.notes());
        }
        if (inst.elements().isEmpty()) {
            dbgLog(be.getBlockPos(), "[Lib] '{}' expanded to 0 elements (check library)",
                    pcbe.getLibraryName());
        }
    }

    private static void addPowerGaugeElement(BlockEntity be, Integer[] arr, Network net) {
        if (be == null || arr == null || net == null) return;
        Integer a = arr.length > 0 ? arr[0] : null;
        Integer b = arr.length > 1 ? arr[1] : null;
        Integer c = arr.length > 2 ? arr[2] : null;
        double rs = DeviceWire.wireResistance(be, "series");
        double rsh = DeviceWire.wireResistance(be, "shunt");
        if (a != null && b != null && c != null && rs > 0 && rsh > 0) {
            // 万用表复合模型（简单元件组合）：series 低阻测流（a-b）+ shunt 高阻测压（a-c）
            // 统一 addComposite 收纳（网络收集所有元件；求解后统一生命周期）
            MeterModel mm = new MeterModel(a, b, c, rs, rsh);
            try {
                mm.setCompositeKey("M" + be.getBlockPos());
            } catch (Throwable ignored) {
            }
            net.addComposite(mm);
        } else {
            // 端子/电阻读取不全 → 逐个兜底（保持旧行为）
            if (a != null && b != null && !a.equals(b) && rs > 0) {
                net.addElement(new Resistor(a, b, rs));
            }
            if (a != null && c != null && !a.equals(c) && rsh > 0) {
                net.addElement(new Resistor(a, c, rsh));
            }
        }
    }

    /** 万用表（PowerGauge）缓存驱动版：series/shunt 电阻由主线程 DeviceParamCache
     *  预同步（Entry.resistance=series 测流低阻、amplitude=shunt 测压高阻），
     *  后台不碰 BE。 */
    private static void addPowerGaugeElementFromCache(BlockPos pos,
            DeviceParamCache.Entry pe, Integer[] arr, Network net) {
        if (pe == null || arr == null || net == null) return;
        Integer a = arr.length > 0 ? arr[0] : null;
        Integer b = arr.length > 1 ? arr[1] : null;
        Integer c = arr.length > 2 ? arr[2] : null;
        double rs = pe.resistance; // series（测流低阻 a-b）
        double rsh = pe.amplitude; // shunt（测压高阻 a-c）
        if (a != null && b != null && c != null && rs > 0 && rsh > 0) {
            // 万用表复合模型（简单元件组合）：series 低阻测流（a-b）+ shunt 高阻测压（a-c）
            MeterModel mm = new MeterModel(a, b, c, rs, rsh);
            try {
                mm.setCompositeKey("M" + pos);
            } catch (Throwable ignored) {
            }
            net.addComposite(mm);
        } else {
            // 端子/电阻读取不全 → 逐个兜底（保持旧行为）
            if (a != null && b != null && !a.equals(b) && rs > 0) {
                net.addElement(new Resistor(a, b, rs));
            }
            if (a != null && c != null && !a.equals(c) && rsh > 0) {
                net.addElement(new Resistor(a, c, rsh));
            }
        }
    }

    /** 源类元件：开路时【保持电压】（2026-08-12 用户要求）。电压源/电流源/
     *  波形源是能量来源：即使无闭合回路，端子也必须保持源电压（开路电压是
     *  物理特性），导线/下游非源元件传递电压但不计算。 */
    private static boolean isSourceElement(Element el) {
        return el instanceof AcVoltageSource
                || el instanceof DcVoltageSource
                || el instanceof CurrentSource
                || el instanceof WaveformSource;
    }

    /**
     * 变压器 → 相量元件（4 端口，现实电磁参数）。
     * <p>
     * ratio = 副边匝数/原边匝数（少匝数侧当原边，与 PowerGrid 一致，ratio ≥ 1）。
     * 电磁参数完全复刻 PowerGrid buildCircuit（互感模型，2026-08-11 重构）：
     *   - 原边自感 L1 = nP²·coreAl，副边自感 L2 = ratio²·L1
     *   - 互感 M = cf·√(L1·L2)（耦合系数 cf → 对称，原边/副边只是相对）
     *   - 铁损电阻 Rcore = M × mutualMultiplier（模拟铁芯损耗/空载损耗，并联原边）
     * 拓扑（复合模型展开为基础元件）：
     *   原边：pa1 --rCp-- a1 --(rCore ∥ 互感绕组1 L1)-- pa2
     *   副边：b1 --rCs-- pb1，b1 --(互感绕组2 L2)-- pb2
     *   互感：MutualInductor(a1, pa2, b1, pb2, L1, L2, M, x, y, k)（简式：T 型等效 +
     *         理想变压器 1:1 约束，无 1/Δ 病态；x/y/k 为内部节点）
     * 任一端子未连导线 → 用内部浮动节点代替（该侧悬空/空载）→ 变压器不跳过，
     * 另一侧仍独立建模求解（治本：不再 [XfrmSkip] 整个跳过 → 一侧 0V 一侧有电压烧线）。
     * <p>
     * 分侧独立（2026-08-12 用户要求）：变压器两边是独立网络、通过互感耦合。
     * 双线圈 → 完整互感变压器；仅一侧线圈 → 该侧单线圈电感（原版行为：当
     * 电感），另一侧暂缺/空载不阻塞——任何一侧就绪该侧就建模，绝不整体跳过
     * 导致整片线路悬空。
     */
    private static void addTransformerElement(BlockEntity be, Integer[] arr, Network net, double frequency,
                                              Map<BlockPos, PhasorNetworkContext.TransformerModel> models,
                                              Set<Element> internal) {
        try {
            // 直流/低频隔直：与 TransformerBlockEntityMixin buildCircuit 一致——
            // 频率 < 最低通过频率（默认 5Hz）→ 不做任何变压传输。
            double minHz = com.hdf.cryptand.neoforge.core.config.ConfigLoad.TRANSFORMER_MIN_FREQUENCY_HZ.get();
            if (frequency < minHz) return;
            org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity t =
                    (org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) be;
            org.patryk3211.powergrid.electricity.transformer.TransformerCoilParameters pc = t.getPrimary();
            org.patryk3211.powergrid.electricity.transformer.TransformerCoilParameters sc = t.getSecondary();
            if (pc == null || sc == null) return;
            // ===== 变压器参数模型（魔法数字参数化，2026-08-12） =====
            // 正常求解用默认魔法数字；模型套用后对固定参数更改并调基类接口
            // applyModel() 更新魔法数字（自感上限/铜阻按铁心+导线规格换算）。
            // 受配置 ENABLE_DEVICE_PARAMETER_MODELS 控制（关闭 → 忽略侧表）。
            TransformerParameters tp = ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()
                    ? TransformerParamStore.get(be.getBlockPos()) : null;
            if (tp == null) tp = TransformerParameters.defaults();
            boolean pcOk = pc.isDefined();
            boolean scOk = sc.isDefined();
            if (!pcOk && !scOk) return; // 空变压器（无线圈）→ 无电路
            // 仅一侧线圈（单线圈变压器）：该侧建模为 R-L 电感（原版行为当电感，
            // 副边悬空不阻塞），另一侧线圈就绪后 rebuildCircuit → 重建升级为完整互感
            if (!pcOk || !scOk) {
                org.patryk3211.powergrid.electricity.transformer.TransformerCoilParameters single =
                        pcOk ? pc : sc;
                int n = single.getTurns();
                if (n <= 0) return;
                int t1 = single.getTerminal1();
                int t2 = single.getTerminal2();
                int a = nodeOr(arr, t1);
                int b = nodeOr(arr, t2);
                if (a < 0 || b < 0) return;
                double l = Math.min(n * n * (tp.coreAl > 0 ? tp.coreAl : t.coreAl()),
                        tp.maxSelfInductance); // 自感上限（与双线圈一致，参数模型）
                int x = net.addNode().id;
                com.hdf.cryptand.circuitsimulation.model.composite.MotorModel mm =
                        new com.hdf.cryptand.circuitsimulation.model.composite.MotorModel(
                                a, b, x, 0.5, l,
                                DeviceThermalStore.thermalFor(be.getBlockPos()));
                try {
                    mm.setCompositeKey("T" + be.getBlockPos());
                } catch (Throwable ignored) {
                }
                net.addComposite(mm);
                return;
            }
            int n1 = pc.getTurns();
            int n2 = sc.getTurns();
            if (n1 <= 0 || n2 <= 0) return;
            int c1t1 = pc.getTerminal1(), c1t2 = pc.getTerminal2();
            int c2t1 = sc.getTerminal1(), c2t2 = sc.getTerminal2();
            double ratio;
            double nP;
            int pa1, pa2, pb1, pb2;
            if (n1 > n2) {
                // 原边 = 副线圈（少匝数侧）
                ratio = n1 / (double) n2;
                pa1 = nodeOr(arr, c2t1); pa2 = nodeOr(arr, c2t2);
                pb1 = nodeOr(arr, c1t1); pb2 = nodeOr(arr, c1t2);
                nP = n2;
            } else {
                // 原边 = 主线圈（少匝数侧）
                ratio = n2 / (double) n1;
                pa1 = nodeOr(arr, c1t1); pa2 = nodeOr(arr, c1t2);
                pb1 = nodeOr(arr, c2t1); pb2 = nodeOr(arr, c2t2);
                nP = n1;
            }
            // 魔法数字：耦合系数与自感限制到合理范围（来自参数模型，默认=原值）。
            // PowerGrid 原值可能极端（cf≈1、L1=nP²·coreAl 可达数万 H，实测
            // 64000H）→ Lm 导纳 2.5e-8 < GMin 被淹没 + 漏感感抗巨大
            // （Lls=2.56H→1608Ω）→ 带载副边电压被漏感分压塌缩。clamp 不影响
            // 匝比 → 开路/带载电压比不变（理想变压器 ratio 决定）。
            double coreAl = tp.coreAl > 0 ? tp.coreAl : t.coreAl();
            double cf = Math.min(t.couplingFactor(), tp.maxCoupling); // 耦合上限（漏感恒非零）
            double l1 = Math.min(nP * nP * coreAl, tp.maxSelfInductance); // 自感上限（魔法数字）
            double l2 = ratio * ratio * l1;                  // 副边自感（H）＝ratio²·L1
            double m = cf * Math.sqrt(l1 * l2);              // 互感（H）＝cf·√(L1·L2)
            double rCore = Math.max(m * tp.effectiveCoreLossMultiplier(), 1e-3); // 铁损电阻（Ω）
            // 铜阻：原/副边线圈直流电阻。默认 0.5Ω（魔法数字，模型套用后按导线
            // 规格换算）：过大则大电流下铜损 I²R 巨大 → 稳态高温 → 过热爆炸。
            double rCp = tp.primaryResistance;
            double rCs = tp.secondaryResistance;

            // ===== 互感模型拓扑（复合模型展开为基础元件，2026-08-11） =====
            // 原边：pa1 --rCp-- a1 --(rCore ∥ 互感绕组1 L1)-- pa2
            // 副边：b1 --rCs-- pb1；b1 --(互感绕组2 L2)-- pb2
            // 互感【简式】：MutualInductor(a1, pa2, b1, pb2, L1, L2, M, x, y, k)
            //   - 内部简式 T 型等效 + 理想变压器 1:1 约束（无 1/Δ 病态，float 稳定）
            //   - L1/L2/M 为固定参数（魔法数字），更新即改上面固定量重算
            //   - x/y/k 为内部节点（原边漏感后/副边漏感前/约束），由本处分配
            //   - 悬空侧（端子未连导线）→ 内部浮动节点（GMin 兜底）→ 空载电压正确
            //   - 一侧端子缺失不再跳过整个变压器（治本：不产生"一侧 0V 一侧有电压"）
            // 缺失端子 → 内部浮动节点（该侧悬空/空载，另一侧独立建模）
            if (pa1 < 0) pa1 = net.addNode().id;
            if (pa2 < 0) pa2 = net.addNode().id;
            if (pb1 < 0) pb1 = net.addNode().id;
            if (pb2 < 0) pb2 = net.addNode().id;
            // 原边铜阻：pa1 --rCp-- a1
            int a1 = pa1;
            if (rCp > 0) {
                a1 = net.addNode().id;
                Resistor rp = new Resistor(pa1, a1, rCp);
                net.addElement(rp);
                internal.add(rp);
            }
            // 铁损：a1 --rCore-- pa2（并联原边绕组，模拟励磁/空载损耗）
            Resistor rco = new Resistor(a1, pa2, rCore);
            net.addElement(rco);
            internal.add(rco);
            // 副边铜阻：b1 --rCs-- pb1（b1 是副边绕组1端）
            int b1 = pb1;
            if (rCs > 0) {
                b1 = net.addNode().id;
                Resistor rs = new Resistor(b1, pb1, rCs);
                net.addElement(rs);
                internal.add(rs);
            }
            // 理想变压器复合模型（由基础元件组合：Llp+Lm+Lls+IdealTransformer，
            // 无 1/Δ 病态、float 稳定）：内部节点 mx（原边漏感后）、my（副边
            // 漏感前）、mk（约束）；绕组1 (a1, pa2) L1，绕组2 (b1, pb2) L2。
            // 匝比 ratio（Lm=M/n、Llp=L1-M/n、Lls=L2-n·M，魔法数字固定量）。
            int mx = net.addNode().id;
            int my = net.addNode().id;
            int mk = net.addNode().id;
            IdealTransformerModel xfrmModel =
                    new IdealTransformerModel(a1, pa2, b1, pb2, l1, l2, m, ratio, mx, my, mk);
            // 统一 addComposite 收纳（网络收集所有元件）。4 端口 → nodeA/nodeB=-1
            // → 不参与统一 update（温度走 computeTransformerHeatUnified 特例）。
            try {
                xfrmModel.setCompositeKey("T" + be.getBlockPos());
            } catch (Throwable ignored) {
            }
            net.addComposite(xfrmModel);
            for (Element se : xfrmModel.decompose()) {
                internal.add(se); // 变压器内部基础元件 → 回路剔除时恒保留
            }

            // 记录相量模型：求解后按真实算法算铜损/铁损发热与电流
            //（TransformerModel 字段兼容：x=a1（原边铜阻后节点）、w=b1（副边铜阻后节点）、
            //  lLp/lLs=0（漏感已含在互感内）、lM=m（互感/励磁））
            // 实际变压器包含：复合模型（IdealTransformerModel 电路解析，上面已装配）
            // + 温度模型（散热值/热容——魔法数字，更新即重算）。过热仿真用。
            models.put(be.getBlockPos(),
                    new PhasorNetworkContext.TransformerModel(pa1, a1, pa2, pb1, pb2, b1,
                            rCp, rCs, rCore, m, 0, 0, ratio,
                            new com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel(
                                    tp.heatDissipation, // 散热值 W/K（越大散热越快）
                                    tp.heatCapacity,    // 热容 J/K（越大升温越慢）
                                    tp.ambientK,        // 环境温度 K
                                    tp.maxTempK         // 最高安全温度 K
                            )));
        } catch (Throwable ignored) {
        }
    }

    /** 变压器 → 相量元件（缓存驱动版：线圈参数由主线程 DeviceParamCache 预读纯值，
     *  后台不碰 BE。逻辑与 {@link #addTransformerElement} 完全一致）。 */
    private static void addTransformerElementFromCache(BlockPos pos,
            DeviceParamCache.TransformerParams tp, Integer[] arr, Network net,
            double frequency,
            Map<BlockPos, PhasorNetworkContext.TransformerModel> models,
            Set<Element> internal) {
        try {
            // 直流/低频隔直：与 buildCircuit 一致——频率 < 最低通过频率 → 不传输。
            double minHz = com.hdf.cryptand.neoforge.core.config.ConfigLoad.TRANSFORMER_MIN_FREQUENCY_HZ.get();
            if (frequency < minHz) return;
            if (tp == null) return;
            boolean pcOk = tp.pcOk();
            boolean scOk = tp.scOk();
            if (!pcOk && !scOk) return; // 空变压器（无线圈）→ 无电路
            TransformerParameters params = ConfigLoad.ENABLE_DEVICE_PARAMETER_MODELS.get()
                    ? TransformerParamStore.get(pos) : null;
            if (params == null) params = TransformerParameters.defaults();
            // 仅一侧线圈：该侧建模为 R-L 电感（原版行为当电感），另一侧就绪后重建升级
            if (!pcOk || !scOk) {
                boolean usePrimary = pcOk;
                int n = usePrimary ? tp.n1() : tp.n2();
                if (n <= 0) return;
                int t1 = usePrimary ? tp.c1t1() : tp.c2t1();
                int t2 = usePrimary ? tp.c1t2() : tp.c2t2();
                int a = nodeOr(arr, t1);
                int b = nodeOr(arr, t2);
                if (a < 0 || b < 0) return;
                double l = Math.min(n * n * (params.coreAl > 0 ? params.coreAl : tp.coreAl()),
                        params.maxSelfInductance);
                int x = net.addNode().id;
                com.hdf.cryptand.circuitsimulation.model.composite.MotorModel mm =
                        new com.hdf.cryptand.circuitsimulation.model.composite.MotorModel(
                                a, b, x, 0.5, l,
                                DeviceThermalStore.thermalFor(pos));
                try {
                    mm.setCompositeKey("T" + pos);
                } catch (Throwable ignored) {
                }
                net.addComposite(mm);
                return;
            }
            int n1 = tp.n1();
            int n2 = tp.n2();
            if (n1 <= 0 || n2 <= 0) return;
            int c1t1 = tp.c1t1(), c1t2 = tp.c1t2();
            int c2t1 = tp.c2t1(), c2t2 = tp.c2t2();
            double ratio;
            double nP;
            int pa1, pa2, pb1, pb2;
            if (n1 > n2) {
                ratio = n1 / (double) n2;
                pa1 = nodeOr(arr, c2t1); pa2 = nodeOr(arr, c2t2);
                pb1 = nodeOr(arr, c1t1); pb2 = nodeOr(arr, c1t2);
                nP = n2;
            } else {
                ratio = n2 / (double) n1;
                pa1 = nodeOr(arr, c1t1); pa2 = nodeOr(arr, c1t2);
                pb1 = nodeOr(arr, c2t1); pb2 = nodeOr(arr, c2t2);
                nP = n1;
            }
            double coreAl = params.coreAl > 0 ? params.coreAl : tp.coreAl();
            double cf = Math.min(tp.couplingFactor(), params.maxCoupling);
            double l1 = Math.min(nP * nP * coreAl, params.maxSelfInductance);
            double l2 = ratio * ratio * l1;
            double m = cf * Math.sqrt(l1 * l2);
            double rCore = Math.max(m * params.effectiveCoreLossMultiplier(), 1e-3);
            double rCp = params.primaryResistance;
            double rCs = params.secondaryResistance;
            if (pa1 < 0) pa1 = net.addNode().id;
            if (pa2 < 0) pa2 = net.addNode().id;
            if (pb1 < 0) pb1 = net.addNode().id;
            if (pb2 < 0) pb2 = net.addNode().id;
            int a1 = pa1;
            if (rCp > 0) {
                a1 = net.addNode().id;
                Resistor rp = new Resistor(pa1, a1, rCp);
                net.addElement(rp);
                internal.add(rp);
            }
            Resistor rco = new Resistor(a1, pa2, rCore);
            net.addElement(rco);
            internal.add(rco);
            int b1 = pb1;
            if (rCs > 0) {
                b1 = net.addNode().id;
                Resistor rs = new Resistor(b1, pb1, rCs);
                net.addElement(rs);
                internal.add(rs);
            }
            int mx = net.addNode().id;
            int my = net.addNode().id;
            int mk = net.addNode().id;
            IdealTransformerModel xfrmModel =
                    new IdealTransformerModel(a1, pa2, b1, pb2, l1, l2, m, ratio, mx, my, mk);
            try {
                xfrmModel.setCompositeKey("T" + pos);
            } catch (Throwable ignored) {
            }
            net.addComposite(xfrmModel);
            for (Element se : xfrmModel.decompose()) {
                internal.add(se);
            }
            models.put(pos,
                    new PhasorNetworkContext.TransformerModel(pa1, a1, pa2, pb1, pb2, b1,
                            rCp, rCs, rCore, m, 0, 0, ratio,
                            new com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel(
                                    params.heatDissipation,
                                    params.heatCapacity,
                                    params.ambientK,
                                    params.maxTempK)));
        } catch (Throwable ignored) {
        }
    }

    /** 端子索引 → 引擎节点 id；未连导线返回 -1 */
    private static int nodeOr(Integer[] arr, int term) {
        if (arr == null || term < 0 || term >= arr.length) return -1;
        Integer id = arr[term];
        return id == null ? -1 : id;
    }

    /** 是否为 PowerGrid 励磁绕组（按类名匹配，避免硬依赖内部类） */
    private static boolean isWinding(BlockEntity be) {
        return be != null && be.getClass().getName().endsWith("WindingBlockEntity");
    }

    /** Winding 是否主方块（唯一持有 coilWire 者；代理方块 mainBE 指向主方块） */
    private static boolean isWindingMain(BlockEntity be) {
        try {
            java.lang.reflect.Field f = be.getClass().getDeclaredField("mainBE");
            f.setAccessible(true);
            Object main = f.get(be);
            return main == null || main == be;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 声明式端子数（2026-08-13 完全接管端子）：Cryptand 决定每个设备几个端子，
     * 【不依赖原版 buildCircuit / getTerminal 探测】。buildCircuit 延迟/重进
     * 世界未完成时，引擎照样按声明创建端子建模 → 设备两端齐全 → 内部元件
     * （绕组 R-L）让悬空端与接入端 MNA 等电位 → 不误启动、不烧线。
     * <ul>
     *   <li>变压器：4（原/副线圈各 2 端子）</li>
     *   <li>万用表（PowerGauge）：3（series 0-1 + shunt 0-2）</li>
     *   <li>可编程元件：引脚数（固化电路 resolve 后；未 resolve → 2 兜底）</li>
     *   <li>其他设备：组装器声明（默认 2）；无组装器 → 2（两端口兜底）</li>
     * </ul>
     */
    public static int declaredTerminalCount(BlockEntity be) {
        if (be == null) return 2;
        try {
            if (be instanceof ProgrammableComponentBlockEntity pcbe) {
                com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit circ =
                        pcbe.getResolvedCircuit();
                if (circ != null && circ.pins != null && !circ.pins.isEmpty()) {
                    return circ.pins.size();
                }
                return 2;
            }
            if (be instanceof org.patryk3211.powergrid.electricity.transformer
                    .TransformerBlockEntity) {
                return 4;
            }
            if (be.getClass().getName().endsWith("PowerGaugeBlockEntity")) {
                return 3;
            }
            com.hdf.cryptand.neoforge.powergrid.device.Assembler asm =
                    com.hdf.cryptand.neoforge.powergrid.device.Assemblers.get(be);
            if (asm != null) {
                int c = asm.terminalCount();
                if (c >= 1) return c;
            }
        } catch (Throwable ignored) {
        }
        return 2;
    }

    /** 端子是否可收集：方块端子 或 接线端子（JunctionWireEndpoint） */
    private static boolean collectableEndpoint(OwnedFloatingNode n) {
        return n.endpoint instanceof BlockWireEndpoint
                || n.endpoint instanceof JunctionWireEndpoint;
    }

    /** 端点 → 方块位置（BlockWireEndpoint→getPos；JunctionWireEndpoint→getExactPosition(level)） */
    private static BlockPos endpointPos(Level level, IWireEndpoint ep) {
        if (ep instanceof BlockWireEndpoint bep) return bep.getPos();
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                return BlockPos.containing(jep.getExactPosition(level));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 端点 → 网络节点（BlockWireEndpoint / JunctionWireEndpoint） */
    private static OwnedFloatingNode endpointNodeOf(Level level, IWireEndpoint ep) {
        if (ep instanceof BlockWireEndpoint bep) {
            try { return bep.getNode(level); } catch (Throwable ignored) { }
        }
        if (ep instanceof JunctionWireEndpoint jep) {
            try { return jep.getNode(level); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** 导线实体的稳定节点（WireEntity.getWire().getNode1()/getNode2()）。
     *  PowerGrid 建线时创建的 OwnedFloatingNode，endpoint 固定——比直接用
     *  BaseWireEntity.getEndpoint1()/2（MutableBlockPos，值随共享复用漂移）
     *  可靠得多。烧线导线常常不在网络 wires 里（deferred rewire 未处理），
     *  只能靠实体扫描 → 必须用稳定节点合并，否则间歇性合并失败 → 烧线。 */
    private static OwnedFloatingNode wireNodeOf(Level level, BaseWireEntity w, boolean first) {
        try {
            if (w instanceof org.patryk3211.powergrid.electricity.wire.WireEntity we) {
                org.patryk3211.powergrid.electricity.sim.ElectricWire ew = we.getWire();
                if (ew != null) {
                    org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd =
                            first ? ew.getNode1() : ew.getNode2();
                    if (nd instanceof OwnedFloatingNode ofn) return ofn;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从 AbstractElectricWire 直接取网络级稳定节点（node1/node2，OwnedFloatingNode）。
     *  3.5b connections 反查用：WireEntity.getWire() 可能 null（导线被 dropWire 后
     *  实体残留），但网络级导线（TransmissionLine/TransmissionLinePart/ElectricWire）
     *  的 node1/node2 始终是稳定的网络节点 —— 不依赖实体状态。 */
    private static OwnedFloatingNode nodeOfWire(AbstractElectricWire w, boolean first) {
        try {
            org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd =
                    first ? w.getNode1() : w.getNode2();
            if (nd instanceof OwnedFloatingNode ofn) return ofn;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 方块位置是否为变压器主方块（端子豁免：变压器端子即使无玩家导线也有效——
     *  内部 LRSeriesWire 连接/空载电压不受"导线连接"判定影响） */
    public static boolean isTransformerBlock(Level level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 方块端子是否仍有导线连接（ElectricBehaviour.connections）。
     *  导线烧毁/剪断后 PowerGrid 的 removeConnection 只更新此 Map、不触发网络
     *  重建（后台线程已停用）→ 端子残留网络节点。用它判断端子是否应视为孤立：
     *  connections 为空 → 无导线实体 → 不再供电（回写 0 / 不合并）。
     *  ⚠ 必须【遍历按 pos+terminal 匹配】，不能用 conns.get(bep) 实例匹配：
     *  网络节点 endpoint 与 addConnection 记录的 key 可能是不同实例，get() 会
     *  误判无导线 → writeback 清零误伤正常端子 → 导线一端 0V 一端有电压烧线。 */
    public static boolean endpointHasWireConnection(Level level, BlockWireEndpoint bep) {
        try {
            org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh = bep.getElectricBehaviour(level);
            if (beh == null) return false;
            java.util.Map<?, ?> conns = beh.getConnections();
            if (conns == null) return false;
            for (java.util.Map.Entry<?, ?> en : conns.entrySet()) {
                Object k = en.getKey();
                if (k instanceof BlockWireEndpoint kb
                        && kb.getPos().equals(bep.getPos())
                        && kb.getTerminal() == bep.getTerminal()) {
                    Object set = en.getValue();
                    return set instanceof java.util.Set<?> s && !s.isEmpty();
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true; // 保守：异常时视为已连接，避免误判正常端子
        }
    }

    /** 导线两端是否仍有效连接（2026-08-13 幽灵导线过滤）：剪线后 removeConnection
     *  清空端点 connections → 两端均无导线连接 = 已物理断开（幽灵导线）→ 不应再
     *  作为连通性扩展/分段依据（否则把断开的两网络仍连起来 → 网络不分裂 → 反复
     *  构建不稳定）。⚠ 一端有连接即可算"有效"（新导线/半边断开的导线另一端可能
     *  仍连源侧）——只有【两端都无连接】才算幽灵。Junction 端点保守视为有效
     *  （接线端子汇流，connections 语义不同）。 */
    private static boolean endpointsBothConnected(Level level, TransmissionLine tl) {
        try {
            if (tl == null) return false;
            IWireEndpoint e1 = tl.getEndpoint1();
            IWireEndpoint e2 = tl.getEndpoint2();
            boolean c1 = e1 instanceof BlockWireEndpoint b1
                    ? endpointHasWireConnection(level, b1) : true;
            boolean c2 = e2 instanceof BlockWireEndpoint b2
                    ? endpointHasWireConnection(level, b2) : true;
            return c1 || c2;
        } catch (Throwable ignored) {
            return true; // 保守：异常视为有效，避免误删正常导线
        }
    }

    /**
     * 【位置匹配】在 terminals 里找端点 ep 对应的节点（绕开对象同一性）：
     *   - BlockWireEndpoint：方块 pos + 端子索引 精确匹配
     *   - JunctionWireEndpoint（接线端子）：按方块位置匹配（接线端子方块的所有
     *     接入导线汇流到同一节点）
     * 网络 wires 节点对象可能与 terminals 节点不同实例（PowerGrid 节点替换），
     * 对象匹配（parent.containsKey）会失败 → 用位置匹配保证导线两端合并。
     */
    private static OwnedFloatingNode findNodeAt(Level level, Collection<OwnedFloatingNode> terminals,
                                                IWireEndpoint ep) {
        if (ep == null || terminals == null) return null;
        if (ep instanceof BlockWireEndpoint bep) {
            BlockPos pos = bep.getPos();
            int term = bep.getTerminal();
            for (OwnedFloatingNode n : terminals) {
                if (n.endpoint instanceof BlockWireEndpoint nb
                        && nb.getPos().equals(pos) && nb.getTerminal() == term) {
                    return n;
                }
            }
            return null;
        }
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                BlockPos pos = BlockPos.containing(jep.getExactPosition(level));
                for (OwnedFloatingNode n : terminals) {
                    if (n.endpoint instanceof BlockWireEndpoint nb && nb.getPos().equals(pos)) {
                        return n;
                    }
                    if (n.endpoint instanceof JunctionWireEndpoint nj) {
                        try {
                            if (BlockPos.containing(nj.getExactPosition(level)).equals(pos)) {
                                return n;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
        return null;
    }

    /** 节点 → 权威节点：对象在 allNetNodes → 返回它；否则用 endpoint 位置在
     *  allNetNodes 里找同位置节点（PowerGrid 节点替换/包装后对象不同，但位置
     *  相同 → 解析到权威实例，保证同一方块端子只映射一个引擎节点）；都不中 →
     *  null（内部节点/不在求解集，不合并）。 */
    private static OwnedFloatingNode resolveToAll(Set<OwnedFloatingNode> allNetNodes, Level level,
                                                  IElectricNode node) {
        if (node instanceof OwnedFloatingNode ofn) {
            if (allNetNodes.contains(ofn)) return ofn;
            if (ofn.endpoint != null) {
                OwnedFloatingNode m = findNodeAt(level, allNetNodes, ofn.endpoint);
                if (m != null) return m;
            }
        }
        return null;
    }

    /** 导线端点是变压器方块 → 用 beh.getTerminal(0-3) 强制补收集 4 端子进权威集。
     *  网络节点收集可能因节点替换/collectable 失败漏掉变压器端子（[XfrmSkip]
     *  arr 副边缺失）→ 变压器跳过 → 副边孤立 0V → 烧线导线 node2（副边端子）
     *  不在 allNetNodes → resolveToAll 失败 → 不合并 → 一端 0V 一端有电压烧线。 */
    private static void addTransformerTerminals(Level level, Set<OwnedFloatingNode> allNetNodes,
                                                IWireEndpoint ep) {
        try {
            if (!(ep instanceof BlockWireEndpoint bep)) return;
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(bep.getPos());
            if (!(be instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity tb)) return;
            org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh = tb.getElectricBehaviour();
            if (beh == null) return;
            int added = 0;
            for (int t = 0; t < 4; t++) {
                OwnedFloatingNode tn = beh.getTerminal(t);
                if (tn != null && collectableEndpoint(tn)) {
                    if (allNetNodes.add(tn)) added++;
                }
            }
            // 诊断（节流）：确认变压器端子补收集是否生效
            long xNow = System.currentTimeMillis();
            if (added > 0 && xNow - xfrmTermDbgLast >= 2000) {
                xfrmTermDbgLast = xNow;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[XfrmTerm] ep={}#{} block={} added={}",
                        bep.getPos(), bep.getTerminal(),
                        be == null ? "null" : be.getClass().getSimpleName(), added);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 导线分段（2026-08-12 用户要求） ====================
    // 导线视作【电阻元件】：连续段导线电阻相加、共用同一套温度模型，不用单独
    // 收集每根导线。分叉等不连续处（设备端子/接线端子/悬空端）视作不同段。
    // 段 = 设备端子/分叉点/接线端子之间无分叉的导线链：
    //   - 段内中间点（度数 2 且非接线端子）→ 等效串联省略（不设引擎节点）
    //   - 段两端（度数≠2 或接线端子）→ 进引擎，加一个【段电阻】元件
    // 接线端子（CordJunction）是汇流点 → 其上多根导线的段端点 union（等电位）。
    /** 连续导线段的电阻元件信息。 */
    static final class WireSegment {
        String keyA, keyB;                 // 段两端端点 key
        OwnedFloatingNode nodeA, nodeB;    // 段两端端点节点
        double resistance;                 // Σ 段内导线电阻
        String pathKey;                    // 段路径签名（温度模型 key）
        WireSegment() {}
    }

    /** 端点 → key（BlockWireEndpoint: pos#term；Junction: J+pos）。 */
    private static String wireEndpointKey(Level level, IWireEndpoint ep) {
        if (ep instanceof BlockWireEndpoint bep) return "B" + bep.getPos() + "#" + bep.getTerminal();
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                return "J" + BlockPos.containing(jep.getExactPosition(level));
            } catch (Throwable ignored) {
                return "J" + System.identityHashCode(jep);
            }
        }
        return null;
    }

    /** 导线节点 key：优先【稳定节点】endpoint（PowerGrid 权威，endpoint 固定）；
     *  稳定节点不可得时退回导线 endpoint。 */
    private static String wireNodeKeyOf(Level level, IElectricNode nd, IWireEndpoint ep) {
        try {
            if (nd instanceof OwnedFloatingNode ofn && ofn.endpoint != null) {
                String k = wireEndpointKey(level, ofn.endpoint);
                if (k != null) return k;
            }
        } catch (Throwable ignored) {
        }
        return wireEndpointKey(level, ep);
    }

    /** 端点是接线端子（CordJunction 汇流点）→ 段边界（其上多导线各自成段）。 */
    private static boolean isCordJunction(Level level, IWireEndpoint ep) {
        try {
            if (!(ep instanceof BlockWireEndpoint bep)) return false;
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(bep.getPos());
            return be != null && be.getClass().getName().endsWith("CordJunctionBlockEntity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把导线集合分段（连续段电阻相加）。
     * @return 段列表；段端点（度数≠2 或接线端子）已解析到 allNetNodes 中的权威节点。
     */
    static java.util.List<WireSegment> buildWireSegments(Level level,
            java.util.Collection<TransmissionLine> allWires,
            java.util.Set<OwnedFloatingNode> allNetNodes,
            java.util.Set<String> boundaryKeys) {
        java.util.List<WireSegment> out = new java.util.ArrayList<>();
        if (allWires == null || allWires.isEmpty()) return out;
        int dbgNullKey = 0, dbgInTl = 0, dbgSkipOuter = 0, dbgSkipResolve = 0;
        // 端点 key → 节点 索引（2026-08-12 性能优化）：一次遍历 allNetNodes 建索引，
        // 段端点 resolve 从 O(段数×节点数) 降到 O(段数)。
        java.util.Map<String, OwnedFloatingNode> keyToNode = new java.util.HashMap<>();
        for (OwnedFloatingNode n : allNetNodes) {
            try {
                String k = wireEndpointKey(level, n.endpoint);
                if (k != null) keyToNode.putIfAbsent(k, n);
            } catch (Throwable ignored) {
            }
        }
        try {
            // 端点 key → 导线列表（度数）+ key → endpoint
            java.util.Map<String, java.util.List<TransmissionLine>> byEp =
                    new java.util.HashMap<>();
            java.util.Map<TransmissionLine, String[]> tlKeys = new java.util.HashMap<>();
            java.util.Map<String, IWireEndpoint> keyToEp = new java.util.HashMap<>();
            for (TransmissionLine tl : allWires) {
                // 优先用【稳定节点】endpoint 的 key（PowerGrid 权威节点，endpoint
                // 固定）——比直接读 getEndpoint1/2（可能 MutableBlockPos 漂移）可靠。
                String k1 = wireNodeKeyOf(level, tl.getNode1(), tl.getEndpoint1());
                String k2 = wireNodeKeyOf(level, tl.getNode2(), tl.getEndpoint2());
                if (k1 == null || k2 == null || k1.equals(k2)) {
                    dbgNullKey++;
                    long now0 = System.currentTimeMillis();
                    if (now0 - segDbgLast >= 2000) {
                        segDbgLast = now0;
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[SegSkip] k1={} k2={} ep1={} ep2={} n1ep={} n2ep={}",
                                k1, k2, epDbg(tl.getEndpoint1()), epDbg(tl.getEndpoint2()),
                                ndDbg(tl.getNode1()), ndDbg(tl.getNode2()));
                    }
                    continue;
                }
                dbgInTl++;
                tlKeys.put(tl, new String[]{k1, k2});
                byEp.computeIfAbsent(k1, k -> new java.util.ArrayList<>()).add(tl);
                byEp.computeIfAbsent(k2, k -> new java.util.ArrayList<>()).add(tl);
                keyToEp.putIfAbsent(k1, tl.getEndpoint1());
                keyToEp.putIfAbsent(k2, tl.getEndpoint2());
            }
            // 段边界 = 度数 != 2（1=设备端/悬空，>=3=分叉）+ 测量端点 +
            // 接线端子（CordJunction 汇流/测量点，无论度数都作段边界，保留
            // 节点可测量；多导线在接线端子汇流 → 各自成段，共享接线端子节点）
            java.util.Set<String> allBoundary = new java.util.HashSet<>();
            if (boundaryKeys != null) allBoundary.addAll(boundaryKeys);
            for (java.util.Map.Entry<String, IWireEndpoint> e : keyToEp.entrySet()) {
                if (isCordJunction(level, e.getValue())) allBoundary.add(e.getKey());
                // 所有电气设备端子（含源/变压器/设备）强制作为段边界（2026-08-12）：
                // 设备端子即使度数2（分叉）也必须保留引擎节点——否则被链当中间点
                // 省略 → 源/变压器/设备端子孤立 → 元件连不上 → 烧线。
                if (e.getValue() instanceof BlockWireEndpoint bep) {
                    try {
                        net.minecraft.world.level.block.entity.BlockEntity be =
                                level.getBlockEntity(bep.getPos());
                        if (be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity) {
                            allBoundary.add(e.getKey());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            java.util.Set<TransmissionLine> visited = new java.util.HashSet<>();
            for (TransmissionLine start : allWires) {
                if (visited.contains(start) || !tlKeys.containsKey(start)) continue;
                visited.add(start);
                String[] sk = tlKeys.get(start);
                // 左链（沿 sk[0] 外扩）+ 右链（沿 sk[1] 外扩）
                java.util.List<TransmissionLine> left = new java.util.ArrayList<>();
                java.util.List<TransmissionLine> right = new java.util.ArrayList<>();
                expandChain(start, sk[0], byEp, tlKeys, visited, allBoundary, left);
                expandChain(start, sk[1], byEp, tlKeys, visited, allBoundary, right);
                // 完整链 = 左链(逆) + start + 右链
                java.util.List<TransmissionLine> chain = new java.util.ArrayList<>();
                for (int i = left.size() - 1; i >= 0; i--) chain.add(left.get(i));
                chain.add(start);
                chain.addAll(right);
                // 段外端 key
                String keyA = chainOuterKey(chain.get(0), tlKeys, byEp, allBoundary);
                String keyB = chainOuterKey(chain.get(chain.size() - 1), tlKeys, byEp, allBoundary);
                if (keyA == null || keyB == null) {
                    dbgSkipOuter++;
                    // 诊断（节流）：打印被跳过链的端点 key —— 定位 chainOuterKey 失败
                    long now1 = System.currentTimeMillis();
                    if (now1 - segDbgLast >= 2000) {
                        segDbgLast = now1;
                        try {
                            StringBuilder cb = new StringBuilder();
                            for (TransmissionLine ctl : chain) {
                                String[] ck = tlKeys.get(ctl);
                                cb.append('[').append(ck[0]).append('|').append(ck[1]).append("] ");
                                if (cb.length() > 400) { cb.append("..."); break; }
                            }
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[SegSkipOuter] chainN={} keyA={} keyB={} chain=[{}]",
                                    chain.size(), keyA, keyB, cb);
                        } catch (Throwable ignored) {
                        }
                    }
                    continue;
                }
                // 单导线段（chainN==1）：chain.get(0)==chain.get(last) → chainOuterKey
                // 对同一根导线两次返回【同一端点】→ keyA==keyB 误判跳过
                // （[SegSkipOuter] 实锤：chainN=1 keyA==keyB → 每根设备导线都被跳过
                // → 无段电阻 → 烧线）。单导线段两端都是边界（expandChain 停在度数≠2）
                // → keyB 取另一端即可。
                if (keyA.equals(keyB) && chain.size() == 1) {
                    String[] tk0 = tlKeys.get(chain.get(0));
                    if (tk0 != null) {
                        keyB = tk0[0].equals(keyA) ? tk0[1] : tk0[0];
                    }
                }
                if (keyA == null || keyB == null || keyA.equals(keyB)) {
                    dbgSkipOuter++;
                    continue;
                }
                WireSegment seg = new WireSegment();
                seg.keyA = keyA;
                seg.keyB = keyB;
                double r = 0;
                for (TransmissionLine tl : chain) {
                    try { r += tl.getResistance(); } catch (Throwable ignored) { }
                }
                seg.resistance = Math.max(r, 0);
                seg.nodeA = keyToNode.get(keyA);
                if (seg.nodeA == null) {
                    seg.nodeA = collectEndpointNode(level, keyToEp.get(keyA), allNetNodes);
                }
                seg.nodeB = keyToNode.get(keyB);
                if (seg.nodeB == null) {
                    seg.nodeB = collectEndpointNode(level, keyToEp.get(keyB), allNetNodes);
                }
                if (seg.nodeA == null || seg.nodeB == null) {
                    dbgSkipResolve++;
                    continue;
                }
                // 段路径签名（温度模型 key）：段内所有端点 key【排序】拼接——
                // DFS 起点不同链方向可能反转，排序保证同一段每次 key 稳定
                // （温度模型跨重建持久，key 变了会重置温度）
                java.util.Set<String> uniq = new java.util.LinkedHashSet<>();
                for (TransmissionLine tl : chain) {
                    String[] k = tlKeys.get(tl);
                    uniq.add(k[0]);
                    uniq.add(k[1]);
                }
                java.util.List<String> sorted = new java.util.ArrayList<>(uniq);
                java.util.Collections.sort(sorted);
                StringBuilder sb = new StringBuilder();
                for (String k : sorted) sb.append(k).append(';');
                seg.pathKey = sb.toString();
                out.add(seg);
            }
            // 诊断（节流 ~2s）：分段统计——定位"导线不成段 → 无电阻 → 烧线"
            long segNow = System.currentTimeMillis();
            if (segNow - segDbgLast >= 2000) {
                segDbgLast = segNow;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[SegStat] allWires={} inTl={} segs={} nullKey={} skipOuter={} skipResolve={}",
                        allWires.size(), dbgInTl, out.size(), dbgNullKey,
                        dbgSkipOuter, dbgSkipResolve);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 从导线 tl 的 curKey 端向外扩展（该端度数==2 继续，否则停）。经过的导线加入 out。 */
    private static void expandChain(TransmissionLine tl, String curKey,
            java.util.Map<String, java.util.List<TransmissionLine>> byEp,
            java.util.Map<TransmissionLine, String[]> tlKeys,
            java.util.Set<TransmissionLine> visited,
            java.util.Set<String> boundaryKeys,
            java.util.List<TransmissionLine> out) {
        try {
            String[] tk = tlKeys.get(tl);
            if (tk == null) return;
            String other = tk[0].equals(curKey) ? tk[1] : tk[0];
            java.util.List<TransmissionLine> atOther =
                    byEp.getOrDefault(other, java.util.Collections.emptyList());
            // 度数≠2（设备端/分叉/悬空）或被测量端点 → 段边界
            if (atOther.size() != 2 || (boundaryKeys != null && boundaryKeys.contains(other))) {
                return;
            }
            TransmissionLine next = null;
            for (TransmissionLine t : atOther) {
                if (t != tl && !visited.contains(t)) { next = t; break; }
            }
            if (next == null) return;
            visited.add(next);
            out.add(next);
            String[] nk = tlKeys.get(next);
            String nextOther = nk[0].equals(other) ? nk[1] : nk[0];
            expandChain(next, other, byEp, tlKeys, visited, boundaryKeys, out);
        } catch (Throwable ignored) {
        }
    }

    /** 链首/链尾导线的边界外端 key：两端中【度数≠2 或边界】的那端。
     *  用 byEp 度数判断（不依赖 chain 内共享——分叉点度数≥3 直接返回，
     *  不会因分叉导线也在 chain 内误判共享而返回 null → 整条链被跳过
     *  （[SegStat] skipOuter=7 实锤：9 根导线只 1 段 → 无电阻 → 烧线）。 */
    private static String chainOuterKey(TransmissionLine tl,
            java.util.Map<TransmissionLine, String[]> tlKeys,
            java.util.Map<String, java.util.List<TransmissionLine>> byEp,
            java.util.Set<String> allBoundary) {
        try {
            String[] tk = tlKeys.get(tl);
            if (tk == null) return null;
            for (String k : tk) {
                java.util.List<TransmissionLine> atK =
                        byEp.getOrDefault(k, java.util.Collections.emptyList());
                if (atK.size() != 2 || (allBoundary != null && allBoundary.contains(k))) {
                    return k;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 端点 key → allNetNodes 中的权威节点（位置匹配）。 */
    private static OwnedFloatingNode resolveEndpointNode(Level level, String key,
            java.util.Set<OwnedFloatingNode> allNetNodes) {
        if (key == null || allNetNodes == null) return null;
        for (OwnedFloatingNode n : allNetNodes) {
            if (n.endpoint == null) continue;
            try {
                if (key.equals(wireEndpointKey(level, n.endpoint))) return n;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 兜底：端点节点不在 allNetNodes → 用 endpoint 获取并收集（保证段不跳过 → 导线必有电阻） */
    private static OwnedFloatingNode collectEndpointNode(Level level, IWireEndpoint ep,
            java.util.Set<OwnedFloatingNode> allNetNodes) {
        if (ep == null) return null;
        try {
            OwnedFloatingNode n = endpointNodeOf(level, ep);
            if (n != null && collectableEndpoint(n)) {
                allNetNodes.add(n);
                return n;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 端点 → 简写（诊断用） */
    private static String epDbg(IWireEndpoint ep) {
        if (ep == null) return "null";
        if (ep instanceof BlockWireEndpoint bep) return "B(" + bep.getPos() + "#" + bep.getTerminal() + ")";
        if (ep instanceof JunctionWireEndpoint) return "J";
        return ep.getClass().getSimpleName();
    }

    /** 节点 → 简写（诊断用） */
    private static String ndDbg(IElectricNode nd) {
        if (nd == null) return "null";
        if (nd instanceof OwnedFloatingNode ofn) return "OFN(" + epDbg(ofn.endpoint) + ")";
        return nd.getClass().getSimpleName();
    }

    /** 方块位置 → 该位置【锚定】的导线端点节点（BlockWireEndpoint.getPos()==pos 或
     *  JunctionWireEndpoint 物理位置==pos）。用于非电气方块/代理方块兜底：即使方块
     *  拿不到端子，只要该位置有导线端点，就能把测量节点纳入求解网络。 */
    private static List<OwnedFloatingNode> endpointNodesAt(Level level, BlockPos pos) {
        List<OwnedFloatingNode> out = new ArrayList<>();
        try {
            for (BaseWireEntity w : level.getEntitiesOfClass(BaseWireEntity.class,
                    new net.minecraft.world.phys.AABB(pos).inflate(3.0))) {
                addEndpointNodeIfAt(level, w.getEndpoint1(), pos, out);
                addEndpointNodeIfAt(level, w.getEndpoint2(), pos, out);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addEndpointNodeIfAt(Level level, IWireEndpoint ep, BlockPos pos,
                                            List<OwnedFloatingNode> out) {
        if (ep == null) return;
        try {
            BlockPos p = null;
            if (ep instanceof BlockWireEndpoint bep) p = bep.getPos();
            else if (ep instanceof JunctionWireEndpoint jep) p = BlockPos.containing(jep.getExactPosition(level));
            if (p != null && p.equals(pos)) {
                OwnedFloatingNode n = endpointNodeOf(level, ep);
                if (n != null && !out.contains(n)) out.add(n);
            }
        } catch (Throwable ignored) {
        }
    }

    // ========== 网络级构建（服务端，禁止 BFS） ==========

    /**
     * 从 PowerGrid ElectricalNetwork 构建引擎相量网络（纯网络级，禁止 BFS）。
     * <p>
     *   - 节点：网络内所有方块端子节点（OwnedFloatingNode.endpoint 是 BlockWireEndpoint）
     *   - 导线：TransmissionLine 两端端子合并为同一引擎节点（忽略导线电阻）
     *   - 元件：端子所属方块 → 源/电阻/电容/电感
     */
    public static Network buildFromNetwork(Level level, ElectricalNetwork net, double frequency) {
        return buildContextFromNetwork(level, net, frequency).network;
    }

    /**
     * 从【自管导线拓扑 WireGraph】构建引擎相量网络上下文（2026-08-13 阶段1：
     * 构建源切换——自管拓扑为唯一拓扑源，不再读 PowerGrid 网络对象）。
     * <p>
     * 与 buildContextFromNetwork 的区别：
     *   - 种子是 {@link WirePoint}（自管端点键"pos#term"）而非 ElectricalNetwork
     *   - 分量 = WireGraph 中与 seed 物理连通的节点集合（GraphOps DFS）
     *   - 导线 = 分量内 WireEdge（段电阻已含在边参数，直接 WireComposite）
     *   - 节点映射 = pointToEngine（WirePoint key → 引擎节点 id），不依赖
     *     OwnedFloatingNode 实例
     * <p>
     * 设备建模（端子→元件）与 buildContextFromNetwork 一致：从端点 key 解析
     * 方块位置 → BE → 组装器/直接处理类。原版节点映射（nodeToEngine）保留
     * 为空（自管写回阶段不再需要原版节点作宿主）。
     * <p>
     * @param seed 分量内任一自管端点（代表要构建的网络分量）
     * @return 构建上下文；分量无节点/异常 → 空上下文
     */
    public static PhasorNetworkContext buildContextFromGraph(Level level, String seedKey, double frequency) {
        Network engine = new Network();
        engine.frequency = frequency;
        if (level == null || seedKey == null) {
            return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
        }
        try {
            com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork net =
                    com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                            .networkOf(seedKey);
            if (net == null) {
                return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
            }
            com.hdf.cryptand.circuitsimulation.netgraph.WirePoint seed =
                    new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(seedKey);
            // 1) 分量 = seed 物理连通节点集合（DFS 现算，CEE 风格）
            //    ⚠ 跨变压器合并（2026-08-13 完整闭环）：WireGraph 只含导线拓扑，
            //    变压器原边/副边是【两个独立分量】（磁耦合、导线不连通）。但相量
            //    模型（addTransformerElement 的互感 T 型等效）必须把原边+副边收进
            //    同一引擎 Network 一起求解（副边才有源、匝数比才成立）。原版
            //    buildContextFromNetwork 用跨变压器 BFS 收集；自管图没有原版网络
            //    可循 → 这里用【变压器端子扩展】：基础分量内若含变压器方块端子，
            //    把该变压器【所有 4 端子】的自管点（经原版 ElectricBehaviour.
            //    getTerminal 反查，在 WireGraph 中存在则加入）并入，再对并入点
            //    所在导线分量重新 DFS——直到不动点（覆盖多级变压器链）。
            java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp =
                    new java.util.LinkedHashSet<>(net.connectedPoints(seed));
            if (comp.isEmpty()) {
                return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
            }
            // 设备端子簇扩展（不动点迭代：并入的设备端子可能再连设备/导线）
            // 2026-08-15 泛化（变压器特例 → 任意电气设备）：用户要求“每个元件/
            // 接线端子放下即建一个网络”（WireNetworkManager.addDevice）——同方块
            // 全部声明端子在同一单点网络对象，但与已接线端子【无边】（不导电）。
            // 构建时把同方块悬空端子并入 comp：设备元件（内部 a-b 两端）才能
            // 完整建模（灯座/电闸/变压器只接一端时，悬空端 MNA 自然等电位）。
            // 类型判断走 DeviceParamCache（主线程同步的线程安全缓存），端子数
            // 取自缓存声明（后台不读 level/BE）。
            boolean changed = true;
            int guard = 0;
            while (changed && guard++ < 32) {
                changed = false;
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p
                        : new java.util.ArrayList<>(comp)) {
                    if (!WireKeyUtil.isBlock(p.key)) continue;
                    BlockPos bp = pointPosOf(p.key);
                    if (bp == null) continue;
                    com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParamCache.Entry de =
                            com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParamCache.get(bp);
                    if (de == null) continue; // 非电气设备（纯导线连接点等）
                    int tCount = de.terminalCount > 0 ? de.terminalCount : 2;
                    for (int t = 0; t < tCount; t++) {
                        try {
                            String tKey = "B" + bp + "#" + t;
                            if (comp.contains(new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(tKey))) {
                                continue;
                            }
                            com.hdf.cryptand.circuitsimulation.netgraph.WirePoint tPoint =
                                    new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(tKey);
                            if (!com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                                    .contains(tPoint)) continue;
                            // 并入该端子 + 其所在网络（导线连通分量）
                            com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork tnet =
                                    com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get()
                                            .networkOf(tPoint);
                            if (tnet == null) continue;
                            java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> side =
                                    tnet.connectedPoints(tPoint);
                            if (comp.addAll(side)) changed = true;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            // 2.5) 多 AC 源（2026-08-15 修复）：收集分量内全部 AC 源频率 →
            //  ≥2 个异频 → 设置网络 WaveformGroup（触发 MultiToneSolver 每频率
            //  独立求解 + 合成 RMS；单频走单频相量）
            collectMultiTone(engine, comp);
            // 2) 连续段识别（IE 段语义，2026-08-14：多个连续导线统一为一段）：
            //    段端点 = 度≠2 节点 或 电气设备端子（边界）；段内导线 Σ电阻/
            //    Σ长度/统一温度 key/统一烧毁。引擎节点 = 段端点 ∪ 孤立设备端子
            //    （段中间度2 纯导线连接点不建独立节点——被段吸收，避免孤立节点
            //    MNA 奇异）。
            java.util.function.Predicate<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> boundary =
                    p -> isDeviceTerminal(level, p);
            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireSegment> segs =
                    net.segments(boundary);
            Map<String, Integer> pointToEngine = new java.util.LinkedHashMap<>();
            Map<BlockPos, Integer[]> blockTerminals = new HashMap<>();
            // 原版节点 → 引擎节点（自管构建后写回原版节点，设备 BE 宿主过渡期）
            Map<OwnedFloatingNode, Integer> nodeToEngineG = new HashMap<>();
            java.util.Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> nodePoints =
                    new java.util.LinkedHashSet<>();
            for (com.hdf.cryptand.circuitsimulation.netgraph.WireSegment s : segs) {
                nodePoints.add(s.endA);
                nodePoints.add(s.endB);
            }
            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                if (net.degree(p) == 0 && boundary.test(p)) nodePoints.add(p); // 孤立设备端子
            }
            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> sorted =
                    new java.util.ArrayList<>(nodePoints);
            java.util.Collections.sort(sorted);
            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : sorted) {
                int id = engine.addNode().id;
                pointToEngine.put(p.key, id);
                // 端点 key → 方块位置 + 端子索引
                BlockPos bp = pointPosOf(p.key);
                int term = pointTermOf(p.key);
                if (bp != null) {
                    Integer[] arr = blockTerminals.computeIfAbsent(bp, b -> new Integer[8]);
                    if (term >= 0 && term < arr.length) arr[term] = id;
                }
            }
            // 3) 连续段 → WireAssembler 组装（实际模型与虚拟元件解耦通过组装器；
            //    默认带温度+电阻，只需设电阻值；可覆写加各种模型/元件）
            int segElems = 0;
            java.util.List<com.hdf.cryptand.circuitsimulation.model.composite.WireComposite> wireComposites =
                    new java.util.ArrayList<>();
            for (com.hdf.cryptand.circuitsimulation.netgraph.WireSegment seg : segs) {
                Integer ia = pointToEngine.get(seg.endA.key);
                Integer ib = pointToEngine.get(seg.endB.key);
                if (ia == null || ib == null || ia.equals(ib)) continue;
                // 导线类型（按段首边物品反查注册器；未注册 → 组装器用段自带电阻）
                com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireType wtype =
                        seg.edges == null || seg.edges.isEmpty() ? null
                                : com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry
                                        .byItemId(seg.edges.get(0).itemId);
                com.hdf.cryptand.circuitsimulation.model.composite.WireComposite wc =
                        com.hdf.cryptand.neoforge.powergrid.device.wire.WireAssembler.DEFAULT
                                .assemble(wtype, seg, ia, ib, engine);
                if (wc != null) {
                    wireComposites.add(wc);
                    segElems++;
                }
            }
            // 4) 设备端子 → TerminalElement + 注册表（自管端点 key 即 deviceKey）
            //    ⚠ 2026-08-15 异步化：自管模式写回只写自管宿主（DEVICE_TERMINAL_V +
            //    TerminalElement 测试点），原版节点不再写回 → 无需 nodeToEngineG
            //    （OwnedFloatingNode 反查块已删除，消除 level 依赖）。
            try {
                for (Map.Entry<BlockPos, Integer[]> e : blockTerminals.entrySet()) {
                    BlockPos bpos = e.getKey();
                    Integer[] arr = e.getValue();
                    if (arr == null) continue;
                    for (int t = 0; t < arr.length; t++) {
                        Integer id = arr[t];
                        if (id == null) continue;
                        com.hdf.cryptand.circuitsimulation.model.TerminalElement te =
                                new com.hdf.cryptand.circuitsimulation.model.TerminalElement(
                                        "B" + bpos, t, id);
                        engine.addTerminal(te);
                        com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry
                                .register(bpos, t, te);
                    }
                }
            } catch (Throwable ignored) {
            }
            // 5) 设备元件建模（从端点 key 解析方块 → 组装器/直接处理类）
            // 变压器/温度/储能模型在方法外层声明 → 返回 ctx 时传给统一发热处理
            Map<BlockPos, PhasorNetworkContext.TransformerModel> tfModels = new HashMap<>();
            java.util.Map<BlockPos, ThermalDevice> deviceThermals = new java.util.HashMap<>();
            java.util.Map<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice>
                    energyDevices = new java.util.HashMap<>();
            try {
                java.util.Set<ElectricalNetwork> pgNets = java.util.Collections.emptySet();
                java.util.List<PhasorNetworkContext.ParamSource> paramSources =
                        new java.util.ArrayList<>();
                java.util.Set<Element> xfrmInternal = new java.util.HashSet<>();
                // ===== 2026-08-15 修复"变压器供电无法工作"：2x2 变压器 4 端子
                // 分散在多个 PART 方块（每格部分端子）→ 必须先按【主 BE 位置】
                // 合并成完整 4 端子数组再建模。否则每个 PART 单独建模（缺端子
                // → 兜底新建孤立节点）→ 互感模型错误 → 副边无输出。=====
                java.util.Map<BlockPos, Integer[]> tfMerged = new java.util.LinkedHashMap<>();
                java.util.Map<BlockPos, DeviceParamCache.TransformerParams> tfParams =
                        new java.util.HashMap<>();
                for (Map.Entry<BlockPos, Integer[]> e : blockTerminals.entrySet()) {
                    BlockPos bpos = e.getKey();
                    DeviceParamCache.Entry pe0 = DeviceParamCache.get(bpos);
                    if (pe0 == null || pe0.kind != DeviceParamCache.Kind.TRANSFORMER) continue;
                    DeviceParamCache.TransformerParams tp0 =
                            DeviceParamCache.transformer(bpos);
                    if (tp0 == null) continue;
                    BlockPos main = DeviceParamCache.transformerMainOf(bpos);
                    if (main == null) main = bpos;
                    Integer[] merged = tfMerged.computeIfAbsent(main, k -> new Integer[8]);
                    Integer[] src = e.getValue();
                    if (src != null) {
                        for (int t = 0; t < src.length && t < merged.length; t++) {
                            if (src[t] != null) merged[t] = src[t];
                        }
                    }
                    tfParams.putIfAbsent(main, tp0);
                }
                // 合并建模（主 BE pos 作参数/热模型 key；完整 4 端子数组）
                for (Map.Entry<BlockPos, Integer[]> g : tfMerged.entrySet()) {
                    DeviceParamCache.TransformerParams tp = tfParams.get(g.getKey());
                    if (tp == null) continue;
                    addTransformerElementFromCache(g.getKey(), tp, g.getValue(), engine,
                            frequency, tfModels, xfrmInternal);
                }
                for (Map.Entry<BlockPos, Integer[]> e : blockTerminals.entrySet()) {
                    BlockPos bpos = e.getKey();
                    Integer[] arr = e.getValue();
                    // 2026-08-15 缓存驱动：类型/参数从 DeviceParamCache 读（主线程
                    // 每 tick 预同步），后台不依赖 level.getBlockEntity 判型。
                    DeviceParamCache.Entry pe = DeviceParamCache.get(bpos);
                    if (pe == null) continue; // 非电气设备（CordJunction 等）/未同步
                    String cls = pe.deviceClass == null ? "" : pe.deviceClass;
                    if (cls.endsWith("CordJunctionBlockEntity")) continue;
                    if (pe.kind == DeviceParamCache.Kind.PROGRAMMABLE
                            || cls.contains("Programmable")) {
                        // 可编程组件：主线程预存的固化电路（纯数据引用，后台只读
                        // 展开）；缓存未同步 → 跳过（下轮同步后建模）
                        com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit circ =
                                DeviceParamCache.programmableCircuit(bpos);
                        if (circ != null) {
                            addProgrammableElementFromCache(bpos, circ, arr, engine);
                        }
                        continue;
                    }
                    if (pe.kind == DeviceParamCache.Kind.TRANSFORMER) {
                        continue; // 已在上方跨 PART 合并建模
                    }
                    if (pe.kind == DeviceParamCache.Kind.POWER_GAUGE) {
                        addPowerGaugeElementFromCache(bpos, pe, arr, engine);
                        continue;
                    }
                    Integer a = null, b = null;
                    for (Integer id : arr) {
                        if (id == null) continue;
                        if (a == null) a = id;
                        else if (b == null) { b = id; break; }
                    }
                    if (a == null || b == null) continue;
                    // 普通设备：缓存驱动（Entry 参数 + DeviceCache 组装器 + pos-only
                    // 绑定）——后台零 level/BE 依赖
                    addElementFromCache(bpos, pe, a, b, engine, paramSources,
                            deviceThermals, energyDevices, pgNets);
                }
            } catch (Throwable ignored) {
            }
            // 接地：闭合接地棒端子作参考地（2026-08-15 改走缓存：主线程预同步
            // grounded 标志，后台不碰 level/BE）
            try {
                for (Map.Entry<BlockPos, Integer[]> ge : blockTerminals.entrySet()) {
                    DeviceParamCache.Entry ge2 = DeviceParamCache.get(ge.getKey());
                    if (ge2 != null && ge2.grounded) {
                        Integer[] garr = ge.getValue();
                        for (Integer id : garr) {
                            if (id != null) { engine.groundNode = id; break; }
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            // ===== 构建诊断（2026-08-15 定位"线路计算异常"：导线不建模/设备
            // 缺失/节点合并等，5s 节流打印 seed/节点/元件/端点映射全貌） =====
            try {
                long gbNow = System.currentTimeMillis();
                if (gbNow - GRAPH_BUILD_DBG_LAST >= 5000) {
                    GRAPH_BUILD_DBG_LAST = gbNow;
                    StringBuilder gb = new StringBuilder("[GraphBuild] seed=")
                            .append(seedKey)
                            .append(" nodes=").append(engine.nodeCount())
                            .append(" elements=").append(engine.elements().size())
                            .append(" segs=").append(segElems)
                            .append(" points=").append(pointToEngine.size());
                    for (Element el : engine.elements()) {
                        gb.append(" [").append(el.type()).append('(')
                                .append(el.nodeA()).append(',').append(el.nodeB())
                                .append(")]");
                    }
                    for (Map.Entry<String, Integer> pe : pointToEngine.entrySet()) {
                        gb.append(" {").append(pe.getKey()).append("->n")
                                .append(pe.getValue()).append('}');
                    }
                    CryptandNeoForge.WAF_LOGGER.info(gb.toString());
                }
            } catch (Throwable ignored) {
            }
            // 回路检测：简化——不判 loopless（始终求解）。引擎 MNA 对无闭合回路
            // 自然等电位（开路无电流），含源/变压器网络必须求解（空载电压物理
            // 特性）。原版 5.5 段做 loopless 只是跳过计算优化；自管构建源优先
            // 保证正确性（无源无回路网络求解结果 = 0 等电位，天然正确）。
            // openTerminal 为空：自管模式引擎 MNA 自动等电位（无源设备开路端
            // 跟随接入端），无需原版 hack；变压器/温度/储能模型传给统一后处理。
            PhasorNetworkContext ret = new PhasorNetworkContext(engine, blockTerminals,
                    nodeToEngineG, pointToEngine, frequency,
                    tfModels, java.util.Collections.emptyMap(), false);
            // 温度/储能模型复制进 ctx（统一发热/储能处理用）
            try {
                ret.deviceThermals.putAll(deviceThermals);
                ret.energyDevices.putAll(energyDevices);
            } catch (Throwable ignored) {
            }
            return ret;
        } catch (Throwable ignored) {
        }
        return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
    }

    /** 端点 key → 方块位置（WireKeyUtil 兼容两种格式；失败 null） */
    private static BlockPos pointPosOf(String key) {
        return WireKeyUtil.posOf(key);
    }

    // ===== 连续段注册表（2026-08-14 用户要求：连续导线统一段处理/统一烧毁） =====

    /** 段 key → 段内导线（自管构建时注册；烧毁时按段统一移除全部边） */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge>> SEGMENT_EDGES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 注册段（构建时；重复段覆盖） */
    public static void registerSegment(String key,
            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge> edges) {
        if (key != null && edges != null && !edges.isEmpty()) {
            SEGMENT_EDGES.put(key, edges);
        }
    }

    /** 段内导线（烧毁/剪线用；无返回 null） */
    public static java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge>
            segmentEdges(String key) {
        return key == null ? null : SEGMENT_EDGES.get(key);
    }

    /** 段消失清理（拆线/烧毁后；只保留活跃段 key） */
    public static void retainSegments(java.util.Set<String> activeKeys) {
        try {
            if (activeKeys == null) {
                SEGMENT_EDGES.clear();
                return;
            }
            SEGMENT_EDGES.keySet().removeIf(k -> !activeKeys.contains(k));
        } catch (Throwable ignored) {
        }
    }

    /** 该端点是否为电气设备端子（段边界）：解析方块 → 电气设备。
     *  2026-08-15 异步化：读线程安全缓存 DeviceParamCache（主线程同步），不碰
     *  level/BE；level 参数保留仅兼容签名。 */
    private static boolean isDeviceTerminal(Level level,
            com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p) {
        try {
            if (!WireKeyUtil.isBlock(p.key)) return false;
            BlockPos bp = pointPosOf(p.key);
            if (bp == null) return false;
            return com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParamCache.get(bp) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 公开版：自管端点 key → 方块位置（供自管 round/渲染/频率反查使用）。 */
    public static BlockPos pointPosOfPublic(String key) {
        return pointPosOf(key);
    }

    /** 自管端点 key → 端子索引（WireKeyUtil；失败 -1） */
    private static int pointTermOf(String key) {
        return WireKeyUtil.termOf(key);
    }

    /**
     * 从 PowerGrid ElectricalNetwork 构建引擎相量网络上下文（纯网络级，禁止 BFS）。
     * <p>
     *   - 节点：网络内所有方块端子节点（OwnedFloatingNode.endpoint 是 BlockWireEndpoint）
     *   - 导线：TransmissionLine 两端端子合并为同一引擎节点（忽略导线电阻）
     *   - 元件：端子所属方块 → 源/电阻/电容/电感/励磁绕组
     *   - 返回块：额外携带【方块位置 → 引擎节点 id】映射，求解后可反查方块电压
     */
    public static PhasorNetworkContext buildContextFromNetwork(Level level, ElectricalNetwork seedNet, double frequency) {
        Network engine = new Network();
        engine.frequency = frequency;
        if (seedNet == null || level == null) return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
        try {
            // 1) 跨变压器 BFS 收集所有相连网络。
            //    变压器在 PowerGrid 中电气隔离两个 ElectricalNetwork（初级/次级
            //    各自独立、可多线程并行求解）——但磁通耦合。相量核心必须把两侧
            //    合并到同一 Network 一起求解（次级才有源、匝数比才成立）。
            //    注意：这里只是【读取】两侧网络构建相量模型，不触碰 PowerGrid
            //    的网络结构/求解循环 → 不影响其多线程并行。
            Set<ElectricalNetwork> nets = new HashSet<>();
            Deque<ElectricalNetwork> queue = new ArrayDeque<>();
            List<OwnedFloatingNode> direct = new ArrayList<>(); // 变压器端子无条件收集（含空载侧）
            nets.add(seedNet);
            queue.add(seedNet);
            while (!queue.isEmpty()) {
                ElectricalNetwork net = queue.poll();
                for (INode node : net.getNodes()) {
                    if (!(node instanceof OwnedFloatingNode ofn)) continue;
                    if (!(ofn.endpoint instanceof BlockWireEndpoint bep)) continue;
                    BlockEntity be = level.getBlockEntity(bep.getPos()); // getPos() = 方块 pos（服务端网络级，不 BFS）
                    // —— 跨变压器 BFS（原逻辑）：变压器 4 端子无条件收集 + 跨网络 ——
                    if (be instanceof ElectricBlockEntity ebe
                            && ebe instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
                        ElectricBehaviour beh = ebe.getElectricBehaviour();
                        if (beh != null) {
                            for (int t = 0; t < 4; t++) {
                                OwnedFloatingNode tn = beh.getTerminal(t);
                                if (tn == null) continue;
                                // 变压器端子【无条件收集】：即使某侧无导线（空载）也要纳入求解。
                                // 否则 addTransformerElement 因 nodeOr()==-1 跳过整个变压器
                                // → 空载侧无空载电压（"副边电压为 0"、无声）。
                                if (collectableEndpoint(tn)) direct.add(tn);
                                ElectricalNetwork tnNet = tn.getNetwork();
                                if (tnNet != null && tnNet != net && nets.add(tnNet)) {
                                    queue.add(tnNet);
                                }
                            }
                        }
                    }
                    // —— 跨导线 BFS（2026-08-10 新增）：PowerGrid 时域禁用下 addWire/merge
                    //    短路（ElectricalNetwork.addWire 对已 addNode 节点 return），导线两端
                    //    可能分属不同 ElectricalNetwork。若不跨导线收集网络，相量 ctx 只含
                    //    部分相连网络 → 变压器只进来部分端子（[XfrmSkip] arr=[null,null,null,2]
                    //    nodeOr=[-1,-1,-1,2] 实锤）→ 变压器跳过 → 副边无源 0V → 烧线。
                    //    从【网络节点 endpoint.connections】（PowerGrid 权威连接记录）反查
                    //    导线（AbstractElectricWire），导线另一端节点所属网络收进 nets，
                    //    保证所有相连网络在同一 ctx 完整求解（变压器 4 端子齐全）。
                    try {
                        ElectricBehaviour beh2 = bep.getElectricBehaviour(level);
                        if (beh2 != null) {
                            java.util.Map<?, ?> conns = beh2.getConnections();
                            if (conns != null) {
                                for (java.util.Map.Entry<?, ?> en : conns.entrySet()) {
                                    Object k = en.getKey();
                                    if (!(k instanceof BlockWireEndpoint kb)) continue;
                                    if (!kb.getPos().equals(bep.getPos())
                                            || kb.getTerminal() != bep.getTerminal()) continue;
                                    Object v = en.getValue();
                                    if (!(v instanceof java.util.Set<?> set)) continue;
                                    for (Object o : set) {
                                        if (!(o instanceof AbstractElectricWire aw)) continue;
                                        for (org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd :
                                                new org.patryk3211.powergrid.electricity.sim.node.IElectricNode[]{
                                                        aw.getNode1(), aw.getNode2()}) {
                                            if (nd instanceof OwnedFloatingNode on) {
                                                ElectricalNetwork onNet = on.getNetwork();
                                                if (onNet != null && onNet != net && nets.add(onNet)) {
                                                    queue.add(onNet);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 1.45) 世界导线补充收集（2026-08-12）：BFS 跨导线基于 endpoint.connections，
            //      PowerGrid 分裂/初始化瞬间 connections 可能未建立/节点未注册 → 漏收集
            //      相连网络 → 网络节点不在 allNetNodes → 未写回 0V → 虚假大电流烧线
            //      （[WireBurn] 实锤：电机端 inCtx=false v=0 vs 电阻端 85V → 14141A，
            //      "电阻到电机那段导线进世界就烧"）。用 WORLD_WIRES（全量导线表，
            //      创建即加入，不依赖 addWire）补充：导线一端节点已在已收集网络 →
            //      另一端网络也纳入并继续跨变压器/跨导线 BFS。
            try {
                boolean wwChanged = true;
                for (int wwPass = 0; wwPass < 4 && wwChanged; wwPass++) {
                    wwChanged = false;
                    for (org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl
                            : com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_WIRES) {
                        try {
                            OwnedFloatingNode a = tl.getNode1() instanceof OwnedFloatingNode o1 ? o1 : null;
                            OwnedFloatingNode b = tl.getNode2() instanceof OwnedFloatingNode o2 ? o2 : null;
                            if (a == null || b == null) continue;
                            ElectricalNetwork na = a.getNetwork();
                            ElectricalNetwork nb = b.getNetwork();
                            if (na == null || nb == null || na == nb) continue;
                            boolean aIn = nets.contains(na);
                            boolean bIn = nets.contains(nb);
                            if (aIn && !bIn && nets.add(nb)) { queue.add(nb); wwChanged = true; }
                            if (bIn && !aIn && nets.add(na)) { queue.add(na); wwChanged = true; }
                        } catch (Throwable ignored) {
                        }
                    }
                    // 补充的网络继续跨变压器/跨导线 BFS（与主 BFS 相同逻辑）
                    while (!queue.isEmpty()) {
                        ElectricalNetwork net = queue.poll();
                        for (INode node : net.getNodes()) {
                            if (!(node instanceof OwnedFloatingNode ofn)) continue;
                            if (!(ofn.endpoint instanceof BlockWireEndpoint bep)) continue;
                            BlockEntity be = level.getBlockEntity(bep.getPos());
                            if (be instanceof ElectricBlockEntity ebe
                                    && ebe instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
                                ElectricBehaviour beh = ebe.getElectricBehaviour();
                                if (beh != null) {
                                    for (int t = 0; t < 4; t++) {
                                        OwnedFloatingNode tn = beh.getTerminal(t);
                                        if (tn == null) continue;
                                        if (collectableEndpoint(tn)) direct.add(tn);
                                        ElectricalNetwork tnNet = tn.getNetwork();
                                        if (tnNet != null && tnNet != net && nets.add(tnNet)) queue.add(tnNet);
                                    }
                                }
                            }
                            try {
                                ElectricBehaviour beh2 = bep.getElectricBehaviour(level);
                                if (beh2 != null) {
                                    java.util.Map<?, ?> conns = beh2.getConnections();
                                    if (conns != null) {
                                        for (java.util.Map.Entry<?, ?> en : conns.entrySet()) {
                                            Object k = en.getKey();
                                            if (!(k instanceof BlockWireEndpoint kb)) continue;
                                            if (!kb.getPos().equals(bep.getPos()) || kb.getTerminal() != bep.getTerminal()) continue;
                                            Object v = en.getValue();
                                            if (!(v instanceof java.util.Set<?> set)) continue;
                                            for (Object o : set) {
                                                if (!(o instanceof AbstractElectricWire aw)) continue;
                                                for (org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd :
                                                        new org.patryk3211.powergrid.electricity.sim.node.IElectricNode[]{
                                                                aw.getNode1(), aw.getNode2()}) {
                                                    if (nd instanceof OwnedFloatingNode on) {
                                                        ElectricalNetwork onNet = on.getNetwork();
                                                        if (onNet != null && onNet != net && nets.add(onNet)) queue.add(onNet);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 1.5) 频率自动提升（2026-08-10）：从无源种子（如变压器次级）出发时，
            //      跨变压器合并集合内找有源侧频率（初级 50Hz → 次级也按 50Hz 求解，
            //      变压器正常耦合）。真直流（集合内无任何 AC 源）保持 0。
            if (frequency <= 0) {
                double maxF = 0;
                for (ElectricalNetwork net : nets) {
                    double f = MultimeterDebug.getNetworkFrequencyHz(net);
                    if (f > maxF) maxF = f;
                }
                if (maxF > 0) frequency = maxF;
                engine.frequency = frequency;
            }

            // 2) 收集所有网络的方块端子节点（含接线端子）+ 变压器端子（无条件，含空载侧）
            List<OwnedFloatingNode> terminals = new ArrayList<>();
            for (ElectricalNetwork net : nets) {
                for (INode n : net.getNodes()) {
                    if (n instanceof OwnedFloatingNode ofn && collectableEndpoint(ofn)) {
                        terminals.add(ofn);
                    }
                }
            }
            for (OwnedFloatingNode n : direct) {
                if (collectableEndpoint(n)) terminals.add(n);
            }
            // 诊断（节流）：writeback 路径 terminals/网络收集详情（含变压器副边端子）
            long tNowWb = System.currentTimeMillis();
            if (tNowWb - termDbgLast >= 2000) {
                termDbgLast = tNowWb;
                try {
                    StringBuilder tsb = new StringBuilder();
                    for (OwnedFloatingNode n : terminals) {
                        if (n.endpoint instanceof BlockWireEndpoint bep) tsb.append(bep.getPos()).append('#').append(bep.getTerminal()).append(' ');
                        else if (n.endpoint instanceof JunctionWireEndpoint) tsb.append("J ");
                        else tsb.append(n.endpoint == null ? "noEP " : n.endpoint.getClass().getSimpleName()).append(' ');
                    }
                    CryptandNeoForge.WAF_LOGGER.info("[WbTermDbg] term={} [{}]", terminals.size(), tsb);
                    for (ElectricalNetwork n2 : nets) {
                        StringBuilder nsb = new StringBuilder();
                        for (INode nn : n2.getNodes()) {
                            if (nn instanceof OwnedFloatingNode ofn && ofn.endpoint instanceof BlockWireEndpoint bep) {
                                nsb.append(bep.getPos()).append('#').append(bep.getTerminal()).append(' ');
                            }
                        }
                        CryptandNeoForge.WAF_LOGGER.info("  [WbNetDbg] id={} nodes={} [{}]",
                                System.identityHashCode(n2), n2.getNodes().size(), nsb);
                        // 网络 wires 完整性：确认烧线导线是否在网络 wires 反射里
                        StringBuilder wsb = new StringBuilder();
                        for (AbstractElectricWire w2 : getNetworkWires(n2)) {
                            if (w2 instanceof TransmissionLine tl2) {
                                wsb.append(epDbg(tl2.getEndpoint1())).append("<->").append(epDbg(tl2.getEndpoint2())).append(' ');
                            } else {
                                wsb.append(w2.getClass().getSimpleName()).append(' ');
                            }
                        }
                        CryptandNeoForge.WAF_LOGGER.info("    [WbWireDbg] wires=[{}]", wsb);
                    }
                } catch (Throwable ignored) {
                }
            }
            return buildFromTerminals(level, terminals, nets, frequency, null);
        } catch (Throwable ignored) {
        }
        return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
    }

    /**
     * 从【方块位置集合】构建引擎相量网络上下文（万用表专用）。
     * <p>
     * 与 buildContextFromNetwork 的区别：种子是方块而非 ElectricalNetwork——
     *   - 有网络的端子 → 跨变压器 BFS 合并收集（同上）
     *   - 无网络的端子（PowerGrid 孤立器件）→ 直接纳入（不要求 getNetwork() 非 null）
     *   - 支持跨网络导线（导线两端分属不同 ElectricalNetwork 且无变压器桥接）：
     *     两端方块都作为种子，两个网络都纳入同一相量 Network 求解
     * @param currentWires 万用表电流测量的导线（不合并，串联电阻以便测流）；可 null
     */
    public static PhasorNetworkContext buildContextFromBlocks(Level level, List<BlockPos> seedBlocks,
                                                              List<BaseWireEntity> currentWires,
                                                              double frequency) {
        Network engine = new Network();
        engine.frequency = frequency;
        if (level == null || seedBlocks == null || seedBlocks.isEmpty())
            return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
        try {
            // 1) 从种子方块端子收集：有网络 → 跨变压器合并；无网络 → 也收集（direct）。
            //    【测量点必须进求解网络】：方块端子收集不到时（非电气方块 / 代理端子 /
            //    孤立端子 / 端子全 null），从该位置【锚定的导线端点】拿节点兜底——
            //    保证测量目标节点一定在求解网络内，求解后反查必然命中（不止"反查碰运气"）。
            Set<ElectricalNetwork> nets = new HashSet<>();
            Deque<ElectricalNetwork> queue = new ArrayDeque<>();
            List<OwnedFloatingNode> direct = new ArrayList<>(); // 种子方块端子（无条件收集）
            for (BlockPos bp : seedBlocks) {
                boolean anyTerminal = false;
                if (level.getBlockEntity(bp) instanceof ElectricBlockEntity ebe) {
                    ElectricBehaviour beh = ebe.getElectricBehaviour();
                    if (beh != null) {
                        for (int t = 0; t < 4; t++) {
                            OwnedFloatingNode n = beh.getTerminal(t);
                            if (n == null) continue;
                            anyTerminal = true;
                            if (collectableEndpoint(n)) direct.add(n); // 端子（网络/孤立都收集）
                            ElectricalNetwork net = n.getNetwork();
                            if (net != null && nets.add(net)) queue.add(net);
                        }
                    }
                }
                // 兜底：方块端子一个都拿不到 → 从该位置锚定的导线端点节点
                if (!anyTerminal) {
                    for (OwnedFloatingNode n : endpointNodesAt(level, bp)) {
                        if (collectableEndpoint(n)) direct.add(n);
                        ElectricalNetwork net = n.getNetwork();
                        if (net != null && nets.add(net)) queue.add(net);
                    }
                }
            }
            // 1.2) 被测导线端点节点无条件收集（电流测量的两端点保证在求解网络内）
            if (currentWires != null) {
                for (BaseWireEntity w : currentWires) {
                    OwnedFloatingNode a = endpointNodeOf(level, w.getEndpoint1());
                    OwnedFloatingNode b = endpointNodeOf(level, w.getEndpoint2());
                    if (a != null && collectableEndpoint(a)) direct.add(a);
                    if (b != null && collectableEndpoint(b)) direct.add(b);
                }
            }
            // 跨变压器 BFS（与 buildContextFromNetwork 相同）+ 变压器端子无条件收集：
            // 变压器 4 端子（含空载/另一侧）必须纳入 direct —— 否则 addTransformerElement
            // 的 nodeOr(arr, term) 对未收集端子返回 -1 → 整个变压器被跳过 → 4 端子孤立
            // （引擎解 0/NaN → 不写回 → 0V）→ 导线一端 0V 一端有电压 → 虚假大电流烧线。
            while (!queue.isEmpty()) {
                ElectricalNetwork net = queue.poll();
                for (INode node : net.getNodes()) {
                    if (!(node instanceof OwnedFloatingNode ofn)) continue;
                    if (!(ofn.endpoint instanceof BlockWireEndpoint bep)) continue;
                    BlockEntity be = level.getBlockEntity(bep.getPos());
                    // —— 跨变压器 BFS（与 buildContextFromNetwork 相同）——
                    if (be instanceof ElectricBlockEntity ebe
                            && ebe instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
                        ElectricBehaviour beh = ebe.getElectricBehaviour();
                        if (beh != null) {
                            for (int t = 0; t < 4; t++) {
                                OwnedFloatingNode tn = beh.getTerminal(t);
                                if (tn == null) continue;
                                if (collectableEndpoint(tn)) direct.add(tn);
                                ElectricalNetwork tnNet = tn.getNetwork();
                                if (tnNet != null && tnNet != net && nets.add(tnNet)) {
                                    queue.add(tnNet);
                                }
                            }
                        }
                    }
                    // —— 跨导线网络收集（与 buildContextFromNetwork 对齐）——
                    try {
                        ElectricBehaviour beh2 = bep.getElectricBehaviour(level);
                        if (beh2 != null) {
                            java.util.Map<?, ?> conns = beh2.getConnections();
                            if (conns != null) {
                                for (java.util.Map.Entry<?, ?> en : conns.entrySet()) {
                                    Object k = en.getKey();
                                    if (!(k instanceof BlockWireEndpoint kb)) continue;
                                    if (!kb.getPos().equals(bep.getPos())
                                            || kb.getTerminal() != bep.getTerminal()) continue;
                                    Object v = en.getValue();
                                    if (!(v instanceof java.util.Set<?> set)) continue;
                                    for (Object o : set) {
                                        if (!(o instanceof AbstractElectricWire aw)) continue;
                                        for (org.patryk3211.powergrid.electricity.sim.node.IElectricNode nd :
                                                new org.patryk3211.powergrid.electricity.sim.node.IElectricNode[]{
                                                        aw.getNode1(), aw.getNode2()}) {
                                            if (nd instanceof OwnedFloatingNode on) {
                                                ElectricalNetwork onNet = on.getNetwork();
                                                if (onNet != null && onNet != net && nets.add(onNet)) {
                                                    queue.add(onNet);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 1.5) 频率自动提升
            if (frequency <= 0) {
                double maxF = 0;
                for (ElectricalNetwork net : nets) {
                    double f = MultimeterDebug.getNetworkFrequencyHz(net);
                    if (f > maxF) maxF = f;
                }
                if (maxF > 0) frequency = maxF;
                engine.frequency = frequency;
            }

            // 2) 收集端子：网络节点 + 种子方块端子（无条件，含孤立）
            List<OwnedFloatingNode> terminals = new ArrayList<>();
            for (ElectricalNetwork net : nets) {
                for (INode n : net.getNodes()) {
                    if (n instanceof OwnedFloatingNode ofn && collectableEndpoint(ofn)) {
                        terminals.add(ofn);
                    }
                }
            }
            for (OwnedFloatingNode n : direct) {
                if (collectableEndpoint(n)) terminals.add(n);
            }
            // 诊断（节流）：terminals 是否含变压器副边端子/各网络节点 —— 定位
            // [XfrmSkip]（副边端子1/3 缺失 → nodeOr=-1 → 变压器跳过 → 副边孤立 0V）
            long tNow2 = System.currentTimeMillis();
            if (tNow2 - termDbgLast >= 2000) {
                termDbgLast = tNow2;
                try {
                    StringBuilder tsb = new StringBuilder();
                    for (OwnedFloatingNode n : terminals) {
                        if (n.endpoint instanceof BlockWireEndpoint bep) tsb.append(bep.getPos()).append('#').append(bep.getTerminal()).append(' ');
                        else if (n.endpoint instanceof JunctionWireEndpoint) tsb.append("J ");
                        else tsb.append(n.endpoint == null ? "noEP " : n.endpoint.getClass().getSimpleName()).append(' ');
                    }
                    CryptandNeoForge.WAF_LOGGER.info("[TermDbg] term={} [{}]", terminals.size(), tsb);
                    for (ElectricalNetwork n2 : nets) {
                        StringBuilder nsb = new StringBuilder();
                        for (INode nn : n2.getNodes()) {
                            if (nn instanceof OwnedFloatingNode ofn) {
                                if (ofn.endpoint instanceof BlockWireEndpoint bep) {
                                    nsb.append(bep.getPos()).append('#').append(bep.getTerminal()).append(' ');
                                }
                            }
                        }
                        CryptandNeoForge.WAF_LOGGER.info("  [NetDbg] id={} nodes={} [{}]",
                                System.identityHashCode(n2), n2.getNodes().size(), nsb);
                    }
                } catch (Throwable ignored) {
                }
            }
            // 诊断：种子方块一个端子都收集不到 → 打印方块详情（帮排查非电气方块/代理端子）
            if (terminals.isEmpty()) {
                for (BlockPos bp : seedBlocks) {
                    try {
                        net.minecraft.world.level.block.entity.BlockEntity be =
                                level.getBlockEntity(bp);
                        String cls = be == null ? "null" : be.getClass().getName();
                        String electric = be instanceof ElectricBlockEntity ? "ELE" : "non-ELE";
                        String beh = "?";
                        String terms = "?";
                        if (be instanceof ElectricBlockEntity ebe) {
                            ElectricBehaviour b2 = ebe.getElectricBehaviour();
                            beh = b2 == null ? "null" : b2.getClass().getSimpleName();
                            StringBuilder sb = new StringBuilder();
                            if (b2 != null) {
                                for (int t = 0; t < 4; t++) {
                                    try {
                                        OwnedFloatingNode n = b2.getTerminal(t);
                                        sb.append(t).append(':')
                                                .append(n == null ? "null"
                                                        : (n.endpoint == null ? "noEP"
                                                                : n.endpoint.getClass().getSimpleName()))
                                                .append(' ');
                                    } catch (Throwable ex) {
                                        sb.append(t).append(":EX ").append(' ');
                                    }
                                }
                            }
                            terms = sb.toString();
                        }
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[BuildDbg] seed-empty pos={} cls={} {} beh={} terms=[{}]",
                                bp, cls, electric, beh, terms);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return buildFromTerminals(level, terminals, nets, frequency, currentWires);
        } catch (Throwable ignored) {
        }
        return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
    }

    /** 并查集/节点收集/元件/接地（网络种子与方块种子共用） */
    private static PhasorNetworkContext buildFromTerminals(Level level, List<OwnedFloatingNode> terminals,
                                                           Set<ElectricalNetwork> nets, double frequency,
                                                           List<BaseWireEntity> currentWires) {
        Network engine = new Network();
        engine.frequency = frequency;
        if (terminals.isEmpty()) return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
        try {
            // 3) 并查集：每个网络内导线（TransmissionLine）两端端子合并。
            //    万用表测量的导线【不合并】——保留两端独立节点 + 串联电阻，
            //    才能测出流过导线的电流（vDiff/r）。其余导线照常合并（忽略电阻）。
            //    【权威节点集 allNetNodes】：terminals + 网络 wires 端点 + 导线实体端点。
            //    PowerGrid 可能替换/包装节点对象（wires.getNode1()/getNode() 返回实例
            //    与 net.getNodes() 可能不同）→ 只靠 terminals 会漏节点（[MergeDbg]
            //    parentHit=5/17：12 个端点不在 terminals）→ 导线两端未合并 → 电压差
            //    ×电导 = 虚假大电流烧线。把 wires 端点纳入 allNetNodes，用对象/位置
            //    双解析（resolveToAll）保证同一根导线两端必然合并 → 等电位 → 不烧。
            Map<OwnedFloatingNode, OwnedFloatingNode> parent = new HashMap<>();
            Set<OwnedFloatingNode> allNetNodes = new LinkedHashSet<>(terminals);
            // 被测量导线端点节点（用于跳过合并 + 加电阻；不依赖 TransmissionLine 精确匹配）
            Set<OwnedFloatingNode> measuredNodes = new HashSet<>();
            Map<BaseWireEntity, OwnedFloatingNode[]> wireEnds = new HashMap<>();
            if (currentWires != null) {
                for (BaseWireEntity w : currentWires) {
                    OwnedFloatingNode a = endpointNodeOf(level, w.getEndpoint1());
                    OwnedFloatingNode b = endpointNodeOf(level, w.getEndpoint2());
                    if (a != null) measuredNodes.add(a);
                    if (b != null) measuredNodes.add(b);
                    wireEnds.put(w, new OwnedFloatingNode[]{a, b});
                }
            }
            // —— 网络 wires（TransmissionLine）端点节点加入权威集 ——
            for (ElectricalNetwork net : nets) {
                for (AbstractElectricWire w : getNetworkWires(net)) {
                    if (w instanceof TransmissionLine tl) {
                        if (tl.getNode1() instanceof OwnedFloatingNode a && collectableEndpoint(a)) {
                            allNetNodes.add(a);
                        }
                        if (tl.getNode2() instanceof OwnedFloatingNode b && collectableEndpoint(b)) {
                            allNetNodes.add(b);
                        }
                        // 兜底：导线端点是变压器方块 → 强制补收集 4 端子。
                        // 网络节点收集可能因节点替换/collectable 失败漏掉变压器副边
                        // 端子（[XfrmSkip] arr 副边缺失）→ 变压器跳过 → 副边孤立 0V
                        // → 烧线导线 node2（副边端子）不在 allNetNodes → 不合并 → 烧。
                        addTransformerTerminals(level, allNetNodes, tl.getEndpoint1());
                        addTransformerTerminals(level, allNetNodes, tl.getEndpoint2());
                    }
                }
            }
            // —— 导线实体端点节点加入权威集（优先稳定节点，endpoint 兜底） ——
            try {
                Set<BlockPos> scannedBlocks = new HashSet<>();
                for (OwnedFloatingNode n : terminals) {
                    BlockPos bp = endpointPos(level, n.endpoint);
                    if (bp == null || !scannedBlocks.add(bp)) continue;
                    for (BaseWireEntity w : level.getEntitiesOfClass(BaseWireEntity.class,
                            new net.minecraft.world.phys.AABB(bp).inflate(6.0))) {
                        OwnedFloatingNode a = wireNodeOf(level, w, true);
                        OwnedFloatingNode b = wireNodeOf(level, w, false);
                        // ⚠ 稳定节点不可得（虚拟/断开导线 getWire()==null）→ 不收集。
                        // endpoint 兜底用 MutableBlockPos（共享可变，值漂移）→ 虚拟
                        // 导线会被错误连到别的端子 → 短路/错误拓扑。
                        if (a != null && collectableEndpoint(a)) allNetNodes.add(a);
                        if (b != null && collectableEndpoint(b)) allNetNodes.add(b);
                    }
                }
            } catch (Throwable ignored) {
            }
            // —— 世界全量导线【连通性扩展】（2026-08-11，治本） ——
            // 网络 wires 可能因 PowerGrid addWire/merge 延迟不含新导线（变压器副边
            // 接设备后数秒才进 wires）→ 只从网络 wires 收集会漏设备端子 → 设备端 0V
            // vs 变压器端有电压 → 导线虚假大电流。transmissionLines 是【全量导线表】
            // （导线创建即加入，不依赖 addWire）→ 从它把【一端已在本网络集】的导线
            // 另一端也纳入（连通性扩展，多轮），保证设备端子无论何时进入求解。
            // 不做世界级全收集（避免把无关网络的孤立端子拉进 ctx 污染求解）。
            try {
                boolean wwChanged = true;
                for (int wwPass = 0; wwPass < 4 && wwChanged; wwPass++) {
                    wwChanged = false;
                    for (org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl
                            : com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_WIRES) {
                        // ⚠ 幽灵导线过滤（2026-08-13 用户方案【移除分裂】）：剪线后
                        // transmissionLines 可能仍含该导线（segments 非空、getNetwork
                        // 非 null），但其两端端点 connections 已被 removeConnection
                        // 清空 → 物理上已断开。若不过滤，连通性扩展仍把断开的两端
                        // （原本一网络）连起来 → 网络不分裂 → 反复构建不稳定
                        // （[Unstable] 节点数 15↔16 抖动实锤）。两端均无导线连接的
                        // 导线视为幽灵，跳过（不再作为连通性扩展/分段依据）。
                        if (!endpointsBothConnected(level, tl)) continue;
                        OwnedFloatingNode m1 = resolveToAll(allNetNodes, level, tl.getNode1());
                        OwnedFloatingNode m2 = resolveToAll(allNetNodes, level, tl.getNode2());
                        if (m1 != null) {
                            if (tl.getNode2() instanceof OwnedFloatingNode n2
                                    && collectableEndpoint(n2) && allNetNodes.add(n2)) {
                                wwChanged = true;
                            }
                        }
                        if (m2 != null) {
                            if (tl.getNode1() instanceof OwnedFloatingNode n1
                                    && collectableEndpoint(n1) && allNetNodes.add(n1)) {
                                wwChanged = true;
                            }
                        }
                        // 关键：导线端点是变压器方块 → 强制补收集 4 端子（含副边）。
                        // 副边端子可能因【网络分裂】不在当前收集的网络 nodes（[XfrmSkip]
                        // nodeOr=[4,3,5,-1]：arr[3]=null）→ 变压器被跳过 → 副边无电压 →
                        // 烧线 + 变压器"运行/停止"来回跳。世界导线表含副边导线 →
                        // 从端点补全 4 端子，保证变压器始终建模。
                        addTransformerTerminals(level, allNetNodes, tl.getEndpoint1());
                        addTransformerTerminals(level, allNetNodes, tl.getEndpoint2());
                    }
                }
            } catch (Throwable ignored) {
            }
            // —— 设备悬空端子无条件收集（2026-08-12，用户要求：参考原版开路求解）——
            // 原版设备 buildCircuit 把【所有端子】（含无导线悬空端）加入网络 → 原版
            // MNA 对悬空端求解除等电位。Cryptand 只收集网络/导线节点会漏悬空端子
            // → 引擎不建模该设备（缺端子）→ 悬空端 PowerGrid 节点电压恒 0（getVoltage
            // = getStateValue = 无网络 0）→ 原版电机 coil.potentialDifference() =
            // 源电压 - 0 = 大压差 → V²/R 驱动 → "开路电机直接转动"。这里把已收集
            // 节点所属设备的【全部端子】无条件收集（含悬空，多轮扩散）→ 引擎建模
            // 悬空端 → MNA/openTerminal 等电位 → 写回 → 设备两端等电位不转。
            try {
                boolean devAdded = true;
                for (int devPass = 0; devPass < 8 && devAdded; devPass++) {
                    devAdded = false;
                    for (OwnedFloatingNode n : new ArrayList<>(allNetNodes)) {
                        if (!(n.endpoint instanceof BlockWireEndpoint bep)) continue;
                        try {
                            net.minecraft.world.level.block.entity.BlockEntity be =
                                    level.getBlockEntity(bep.getPos());
                            if (be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity ebe) {
                                org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh =
                                        ebe.getElectricBehaviour();
                                if (beh == null) continue;
                                for (int t = 0; t < 4; t++) {
                                    OwnedFloatingNode tn = beh.getTerminal(t);
                                    if (tn != null && collectableEndpoint(tn)
                                            && allNetNodes.add(tn)) {
                                        devAdded = true;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // 兜底：所有网络 nodes 加入 allNetNodes（2026-08-12）——网络分裂/
            // 收集遗漏时，即使 BFS 收集了网络，其节点也可能没进 allNetNodes
            // （[WireBurn] 实锤：电机端 inCtx=false v=0 → 烧线）。强制全量加入，
            // 保证求解/写回覆盖每个网络节点。
            try {
                for (ElectricalNetwork net : nets) {
                    for (INode in : net.getNodes()) {
                        if (in instanceof OwnedFloatingNode ofn && collectableEndpoint(ofn)) {
                            allNetNodes.add(ofn);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            for (OwnedFloatingNode n : allNetNodes) parent.put(n, n);

            // 3) 导线分段（2026-08-12 用户要求）：导线视作【电阻元件】。
            //    连续段导线电阻相加、共用同一套温度模型（后续 WireThermalStore
            //    接入），分叉/接线端子/设备端子/测量处视作不同段。每段 =
            //    一个 Resistor(段两端节点, Σ电阻)。替代旧的"导线等电位 union"
            //    （合并0/合并1/3.5/3.5b）：等电位短路让导线无电阻（压降 0）→
            //    悬空大电流烧线。现在导线有电阻，MNA 自然计算压降/电流；开路
            //    无闭合路径 → 无电流 → 段两端等电位（电压沿导线等电位传递）。
            java.util.Set<org.patryk3211.powergrid.electricity.sim.special.TransmissionLine> allWires =
                    new java.util.LinkedHashSet<>();
            try {
                for (org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl
                        : com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_WIRES) {
                    allWires.add(tl);
                }
                for (ElectricalNetwork net : nets) {
                    for (AbstractElectricWire w : getNetworkWires(net)) {
                        if (w instanceof org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl) {
                            allWires.add(tl);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // 被测量导线端点 key 作为段边界（万用表测电流：两端节点保留）
            java.util.Set<String> measuredKeys = new java.util.HashSet<>();
            try {
                for (Map.Entry<BaseWireEntity, OwnedFloatingNode[]> e : wireEnds.entrySet()) {
                    OwnedFloatingNode a = e.getValue()[0];
                    OwnedFloatingNode b = e.getValue()[1];
                    if (a != null && a.endpoint != null) measuredKeys.add(wireEndpointKey(level, a.endpoint));
                    if (b != null && b.endpoint != null) measuredKeys.add(wireEndpointKey(level, b.endpoint));
                }
            } catch (Throwable ignored) {
            }
            java.util.List<WireSegment> wireSegments =
                    buildWireSegments(level, allWires, allNetNodes, measuredKeys);
            // 段端点确认在 allNetNodes + parent（段端点必须参与求解）
            for (WireSegment seg : wireSegments) {
                if (seg.nodeA != null) { allNetNodes.add(seg.nodeA); parent.put(seg.nodeA, seg.nodeA); }
                if (seg.nodeB != null) { allNetNodes.add(seg.nodeB); parent.put(seg.nodeB, seg.nodeB); }
            }
            // 同物理端子归并（2026-08-12 关键修复）：PowerGrid 中同一端子
            // （pos#term）可能有多个 OwnedFloatingNode 对象（设备端子节点 /
            // 导线稳定节点 / 网络节点 / 段端点兜底收集的节点）——它们是
            // 【同一物理点】。段重构删除了导线 union 后，这些对象若不归并 →
            // 变压器 arr 与段端点映射到不同引擎节点 → 变压器端子孤立 0V →
            // 段两端巨大压差 → 烧线（[WireBurn] i=11785A 实锤：(-1,0,9)#2
            // v=0 vs (0,0,7)#0 v=35.36）。注意：这是【同一端子】归并（物理
            // 等电位），不是【导线两端】短路（不同端子间的压降由段电阻保留）。
            {
                java.util.Map<String, OwnedFloatingNode> keyToNode = new java.util.HashMap<>();
                int sameKeyUnion = 0;
                for (OwnedFloatingNode n : allNetNodes) {
                    try {
                        String k = wireEndpointKey(level, n.endpoint);
                        if (k == null) continue;
                        OwnedFloatingNode prev = keyToNode.putIfAbsent(k, n);
                        if (prev != null && prev != n
                                && parent.containsKey(prev) && parent.containsKey(n)) {
                            union(parent, prev, n);
                            sameKeyUnion++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (sameKeyUnion > 0) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[SameKey] union={}", sameKeyUnion);
                }
            }
            // 接线端子内部汇流（2026-08-12 关键修复）：CordJunctionBlockEntity 是
            // 汇流点——多根导线接其不同端子，端子间内部连通（等电位）。段重构
            // 删除了“导线等电位 union”后接线端子各端子节点分裂 → 部分端子 0V
            // vs AC 源有电压 → 导线压差烧线（“AC 源接接线端子也会烧”）。把
            // 接线端子所有端子 union 到同一引擎节点（5 节处 continue 不建模元件）。
            {
                java.util.Map<BlockPos, OwnedFloatingNode> juncFirst =
                        new java.util.HashMap<>();
                int juncUnion = 0;
                for (OwnedFloatingNode n : allNetNodes) {
                    if (!(n.endpoint instanceof BlockWireEndpoint bep)) continue;
                    if (!isCordJunction(level, n.endpoint)) continue;
                    BlockPos jp = bep.getPos();
                    OwnedFloatingNode first = juncFirst.putIfAbsent(jp, n);
                    if (first != null && first != n
                            && parent.containsKey(first) && parent.containsKey(n)) {
                        union(parent, first, n);
                        juncUnion++;
                    }
                }
                if (juncUnion > 0) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[JuncUnion] union={}", juncUnion);
                }
            }
            // 接线端子/分叉汇流：共享端点的多段 → union（同一端子等电位）。
            // PowerGrid 中同一端子共享节点 → resolve 到同一节点 → 天然汇流；
            // 兜底 union 防止 PowerGrid 给每根导线独立节点时漏汇流。
            int junctionMerged = 0;
            try {
                java.util.Map<String, java.util.List<WireSegment>> segByKey =
                        new java.util.HashMap<>();
                for (WireSegment seg : wireSegments) {
                    segByKey.computeIfAbsent(seg.keyA, k -> new java.util.ArrayList<>()).add(seg);
                    segByKey.computeIfAbsent(seg.keyB, k -> new java.util.ArrayList<>()).add(seg);
                }
                for (java.util.Map.Entry<String, java.util.List<WireSegment>> e : segByKey.entrySet()) {
                    if (e.getValue().size() <= 1) continue;
                    WireSegment fs = e.getValue().get(0);
                    OwnedFloatingNode ref = e.getKey().equals(fs.keyA) ? fs.nodeA : fs.nodeB;
                    if (ref == null) continue;
                    for (int i = 1; i < e.getValue().size(); i++) {
                        WireSegment s = e.getValue().get(i);
                        OwnedFloatingNode on = e.getKey().equals(s.keyA) ? s.nodeA : s.nodeB;
                        if (on != null && on != ref
                                && parent.containsKey(ref) && parent.containsKey(on)) {
                            union(parent, ref, on);
                            junctionMerged++;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (junctionMerged > 0) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[SegDbg] segs={} junctionMerged={}", wireSegments.size(), junctionMerged);
            }
            // 诊断（节流 ~2s）：段端点 → 节点/引擎节点（确认段正确连接设备/变压器
            // 端子；段端点 resolve 失败 → 段缺失 → 导线无电阻 → 烧线）
            {
                long segNow = System.currentTimeMillis();
                if (segNow - segDbgLast >= 2000) {
                    segDbgLast = segNow;
                    try {
                        for (WireSegment seg : wireSegments) {
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[SegDbg] keyA={} keyB={} R={} nodeA={} nodeB={}",
                                    seg.keyA, seg.keyB,
                                    String.format("%.4f", seg.resistance),
                                    seg.nodeA == null ? "null" : epDbg(seg.nodeA.endpoint),
                                    seg.nodeB == null ? "null" : epDbg(seg.nodeB.endpoint));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 3.6) 诊断：分段/网络信息（节流 ~2s）
            long mergeNow = System.currentTimeMillis();
            if (mergeNow - mergeDbgLast >= 2000) {
                mergeDbgLast = mergeNow;
                try {
                    int wireReflect = 0, tlReflect = 0;
                    StringBuilder types = new StringBuilder();
                    for (ElectricalNetwork n2 : nets) {
                        for (AbstractElectricWire w : getNetworkWires(n2)) {
                            wireReflect++;
                            if (w instanceof TransmissionLine) tlReflect++;
                            if (types.length() < 150) {
                                types.append(w.getClass().getSimpleName()).append(' ');
                            }
                        }
                    }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[MergeDbg] nets={} reflectWires={} tl={} segs={} types=[{}] "
                            + "term={}",
                    nets.size(), wireReflect, tlReflect, wireSegments.size(), types,
                    terminals.size());
                } catch (Throwable ignored) {
                }
            }

            // 4) 根 → 引擎节点号；端子 → 所属方块；并记录每个 PowerGrid 节点
            //    → 引擎节点 id（供 PhasorWriteback 相量电压回写 network.setValue）
            Map<OwnedFloatingNode, Integer> nodeId = new HashMap<>();
            Map<BlockPos, Integer[]> blockTerminals = new HashMap<>();
            Map<OwnedFloatingNode, Integer> nodeToEngine = new HashMap<>();
            // 遍历 allNetNodes（不只 terminals）：网络 wires / 导线实体端点节点
            // 也纳入映射 → 回写全覆盖（无节点保持 0V → 悬空导线虚假大电流烧线）
            for (OwnedFloatingNode n : allNetNodes) {
                OwnedFloatingNode root = find(parent, n);
                int id = nodeId.computeIfAbsent(root, r -> engine.addNode().id);
                nodeToEngine.put(n, id);
                BlockPos bpos = endpointPos(level, n.endpoint);
                if (bpos == null) continue;
                Integer[] arr = blockTerminals.computeIfAbsent(bpos, b -> new Integer[8]);
                if (n.endpoint instanceof BlockWireEndpoint bep) {
                    arr[bep.getTerminal()] = id;
                } else {
                    arr[0] = id; // 接线端子：所有接入导线合并为同一节点
                }
            }
            // 诊断（节流）：所有设备 arr + 端子节点详情（副边端子/电阻#1 是否
            // 收集进 blockTerminals → 定位"闭合回路在电阻处断开 → 电机不转"）
            long aNow = System.currentTimeMillis();
            if (aNow - arrDbgLast >= 3000) {
                arrDbgLast = aNow;
                try {
                    for (Map.Entry<BlockPos, Integer[]> e2 : blockTerminals.entrySet()) {
                        net.minecraft.world.level.block.entity.BlockEntity be2 = level.getBlockEntity(e2.getKey());
                        StringBuilder tsb = new StringBuilder();
                        if (be2 instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity ebe2) {
                            org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh2 = ebe2.getElectricBehaviour();
                            if (beh2 != null) {
                                for (int t2 = 0; t2 < 4; t2++) {
                                    tsb.append(t2).append(':');
                                    try {
                                        org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode tn2 = beh2.getTerminal(t2);
                                        if (tn2 == null) tsb.append("null ");
                                        else {
                                            tsb.append(tn2.endpoint == null ? "noEP"
                                                    : tn2.endpoint.getClass().getSimpleName());
                                            tsb.append(tn2.getNetwork() == null ? "(noNet) " : "(net) ");
                                        }
                                    } catch (Throwable ex) {
                                        tsb.append("EX ");
                                    }
                                }
                            }
                        }
                        CryptandNeoForge.WAF_LOGGER.info("[DevArr] pos={} class={} arr={} terms=[{}]",
                                e2.getKey(),
                                be2 == null ? "null" : be2.getClass().getSimpleName(),
                                java.util.Arrays.toString(e2.getValue()), tsb);
                    }
                } catch (Throwable ignored) {
                }
            }

            // —— 设备真实端子 → TerminalElement（2026-08-12 用户要求：端子元件）——
            // 多端子模型（变压器 4 端子/三相电机 A/B/C/N/万用表 3 端子等）的真实
            // 端子逐一映射到引擎节点，解决"内部电路 ↔ 现实模型端子"接口：
            //   - 已收集端子（有导线/网络节点）→ 复用现有引擎节点（arr[t]）
            //   - 悬空端子（无导线）→ 创建引擎节点 + 补齐 arr[t]
            // 两端口设备【默认确保端子 0/1】都有引擎节点——即使 getTerminal(1)
            // 暂时 null（buildCircuit 延迟/重进世界未完成）也创建引擎节点 → 设备
            // 两端齐全建模 → 内部元件（绕组 R-L 等）让悬空端与接入端 MNA 等电位
            // → 不会因"一端接入、一端悬空"而误启动（开路电机不转）。
            // ⚠ 端子节点实例与网络节点实例可能不同（buildCircuit 端子 vs 导线
            //   端点）→ 用位置 key（blockTerminals arr[term]）统一到同一引擎节点。
            try {
                for (Map.Entry<BlockPos, Integer[]> e : blockTerminals.entrySet()) {
                    BlockPos bpos = e.getKey();
                    net.minecraft.world.level.block.entity.BlockEntity be =
                            level.getBlockEntity(bpos);
                    if (!(be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity ebe)) {
                        continue;
                    }
                    // 跳过接线端子（并查集已汇流合并，不建模设备）
                    String cls = be.getClass().getName();
                    if (cls.endsWith("CordJunctionBlockEntity")
                            || cls.endsWith("ConnectorBlockEntity")) {
                        continue;
                    }
                    // ElectricBehaviour 可能为 null（重进世界初期 buildCircuit 未
                    // 完成）→ 不跳过：仍确保端子 0/1 引擎节点 + 触发重建等待初始化。
                    org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh = null;
                    try { beh = ebe.getElectricBehaviour(); } catch (Throwable ignored) { }
                    Integer[] arr = e.getValue();
                    if (arr == null) continue;
                    // 端子数 = max(声明式端子数-1, getTerminal 非 null 最大 t,
                    //          arr 已收集最大 t)。声明式优先（2026-08-13 完全接管
                    //          端子）：Cryptand 决定设备端子数，不依赖原版探测——
                    //          buildCircuit 延迟时 getTerminal 全 null → 仍按声明
                    //          创建端子建模（不再退回 1 端子 + 等 rebuildCircuit）。
                    int maxTerminal = declaredTerminalCount(be) - 1;
                    if (maxTerminal < 1) maxTerminal = 1;
                    for (int t = 0; t < 8; t++) {
                        if (beh != null) {
                            org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode tn = null;
                            try { tn = beh.getTerminal(t); } catch (Throwable ignored) { }
                            if (tn != null) maxTerminal = Math.max(maxTerminal, t);
                        }
                        if (arr != null && t < arr.length && arr[t] != null) {
                            maxTerminal = Math.max(maxTerminal, t);
                        }
                    }
                    // 端子完整性：两端口设备应有端子节点。若缺失（buildCircuit 未
                    // 完成/重进世界）→ 触发 rebuildCircuit 补建 + 缓存失效重建。
                    boolean missing = beh == null; // beh null = 未初始化 → 待重建
                    if (!missing) {
                        for (int t = 0; t <= maxTerminal; t++) {
                            org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode tn = null;
                            try { tn = beh.getTerminal(t); } catch (Throwable ignored) { }
                            if (tn == null) missing = true;
                        }
                    }
                    if (missing) {
                        long rbNow = System.currentTimeMillis();
                        if (rbNow - rebuildDbgLast >= 2000) {
                            rebuildDbgLast = rbNow;
                            if (beh != null) {
                                try { beh.rebuildCircuit(true); } catch (Throwable ignored) { }
                            }
                            // buildCircuit 重建端子节点 → 缓存失效 → 下轮重建补齐
                            try {
                                com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager
                                        .get().markTopologyChanged();
                            } catch (Throwable ignored) { }
                        }
                    }
                    // 确保 0..maxTerminal 每个端子都有引擎节点（缺失创建）
                    for (int t = 0; t <= maxTerminal; t++) {
                        boolean hasArr = arr != null && t < arr.length && arr[t] != null;
                        int id;
                        if (hasArr) {
                            id = arr[t];          // 已有引擎节点（导线/网络收集）
                        } else {
                            id = engine.addNode().id; // 悬空端子：创建引擎节点
                            if (arr != null && t < arr.length) arr[t] = id;
                        }
                        org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode tn = null;
                        try { tn = beh.getTerminal(t); } catch (Throwable ignored) { }
                        if (tn != null) nodeToEngine.put(tn, id); // 端子节点 → 引擎节点
                        com.hdf.cryptand.circuitsimulation.model.TerminalElement te =
                                new com.hdf.cryptand.circuitsimulation.model.TerminalElement(
                                        "B" + bpos, t, id);
                        engine.addTerminal(te);
                        // 端子注册表（2026-08-13 完全接管端子）：按位置 pos#term
                        // 稳定绑定。每次构建覆盖（engineNode 随本 ctx 更新），
                        // 设备拆除/网络分裂后注册表 key 仍稳定（位置不变）。
                        com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry
                                .register(bpos, t, te);
                    }
                }
            } catch (Throwable ignored) {
            }

            // 4.6) 段电阻元件：导线连续段 → WireComposite（复合元件：Resistor +
            //     温度模型）。段两端节点独立（不 union）→ MNA 计算压降/电流；
            //     开路无闭合路径 → 无电流 → 两端等电位（电压沿导线等电位传递）。
            int segElems = 0;
            java.util.List<com.hdf.cryptand.circuitsimulation.model.composite.WireComposite> wireComposites =
                    new java.util.ArrayList<>();
            for (WireSegment seg : wireSegments) {
                Integer ia = nodeToEngine.get(seg.nodeA);
                Integer ib = nodeToEngine.get(seg.nodeB);
                if (ia != null && ib != null && !ia.equals(ib) && seg.resistance > 1e-9) {
                    // 温度模型从 WireThermalStore 取（跨网络重建持久）；key = 段路径签名
                    com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                            WireThermalStore.thermalFor(seg.pathKey);
                    com.hdf.cryptand.circuitsimulation.model.composite.WireComposite wc =
                            new com.hdf.cryptand.circuitsimulation.model.composite.WireComposite(
                                    ia, ib, seg.resistance, th, seg.pathKey);
                    engine.addComposite(wc); // 自动展开 Resistor 进 elements + 记录复合
                    wireComposites.add(wc);
                    segElems++;
                }
            }
            if (segElems > 0) {
                CryptandNeoForge.WAF_LOGGER.info("[SegElem] added={}/{} composites={}",
                        segElems, wireSegments.size(), engine.composites().size());
            }

            // 5) 方块 → 元件（变压器 4 端口；功率表 3 端口；其他两端口）
            //    可调元件（电阻/源/电容/电感）记录参数刷新源；构建完成后统一给
            //    所有元件挂参数变化消息监听（→ ctx.paramVersion++ → 重解，
            //    不重建网络，结构复用；同一网络多变化合并为一次重解）。
            Map<BlockPos, PhasorNetworkContext.TransformerModel> transformerModels = new HashMap<>();
            java.util.List<PhasorNetworkContext.ParamSource> paramSources = new java.util.ArrayList<>();
            java.util.Map<BlockPos, ThermalDevice> deviceThermals = new java.util.HashMap<>();
            // 储能设备（电容/电池复合模型：求解后统一同步储能电荷）
            java.util.Map<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice>
                    energyDevices = new java.util.HashMap<>();
            // 变压器内部基础元件（铜阻/铁损/互感展开）：回路剔除时恒保留
            // （开路保持感应电压、端子传递电压）
            java.util.Set<Element> xfrmInternal = new java.util.HashSet<>();
            for (Map.Entry<BlockPos, Integer[]> e : blockTerminals.entrySet()) {
                BlockEntity be = level.getBlockEntity(e.getKey());
                Integer[] arr = e.getValue();
                if (be == null) {
                    // 虚拟设备（2026-08-12 超长线路输电）：未加载区块元件用参数
                    // 快照建模（已加载导线持有其网络节点 → 位置在 blockTerminals）。
                    // 区分：区块未加载（isLoaded=false）→ 虚拟建模；区块已加载但
                    // BE null → 设备被拆除/不存在 → 清理快照（防虚拟错误建模）。
                    boolean chunkLoaded;
                    try {
                        chunkLoaded = level.isLoaded(e.getKey());
                    } catch (Throwable ignored) {
                        chunkLoaded = true;
                    }
                    if (!chunkLoaded) {
                        Integer a = null, b = null;
                        for (Integer id : arr) {
                            if (id == null) continue;
                            if (a == null) a = id;
                            else if (b == null) { b = id; break; }
                        }
                        if (a != null && b != null) {
                            VirtualDevice pd = VirtualDeviceStore.get(e.getKey());
                            if (pd != null) {
                                addVirtualElement(e.getKey(), pd, a, b, engine, deviceThermals);
                            }
                        }
                    } else {
                        VirtualDeviceStore.remove(e.getKey());
                    }
                    // 诊断（节流）：BE null 可能是设备拆除残留或区块未加载
                    long nbNow = System.currentTimeMillis();
                    if (nbNow - nullBeDbgLast >= 5000) {
                        nullBeDbgLast = nbNow;
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[NullBe] pos={} arr={} state={} loaded={} virtual={}",
                                e.getKey(), java.util.Arrays.toString(arr),
                                level.getBlockState(e.getKey()),
                                chunkLoaded,
                                VirtualDeviceStore.get(e.getKey()) != null);
                    }
                    continue;
                }
                if (be.getClass().getName().endsWith("CordJunctionBlockEntity")) {
                    continue; // 接线端子：并查集已把接入导线合并为同一节点，无需元件
                }
                if (be instanceof ProgrammableComponentBlockEntity) {
                    // 可编程元件：按元件库条目展开（SPICE 子电路 → Cryptand 元件）
                    addProgrammableElement(be, arr, engine);
                    continue;
                }
                if (be instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
                    addTransformerElement(be, arr, engine, frequency, transformerModels, xfrmInternal);
                    continue;
                }
                if (be != null && be.getClass().getName().endsWith("PowerGaugeBlockEntity")) {
                    // 功率表 3 端子：series（0-1，低阻串联测流）+ shunt（0-2，高阻分流测压）。
                    // 必须两条内部 wire 都建模，否则未建模侧两端独立求解 → 虚假巨大电流爆炸。
                    addPowerGaugeElement(be, arr, engine);
                    continue;
                }
                Integer a = null, b = null;
                for (Integer id : arr) {
                    if (id == null) continue;
                    if (a == null) a = id;
                    else if (b == null) { b = id; break; }
                }
                if (a == null || b == null) continue;
                addElement(level, be, a, b, engine, paramSources, deviceThermals,
                        energyDevices, nets);
            }

            // 接地：闭合接地棒的端子节点作为参考地（V=0，覆盖默认 groundNode=0）。
            // 2026-08-15 改走缓存（主线程预同步 grounded，后台不碰 level/BE）。
            for (Map.Entry<BlockPos, Integer[]> ge : blockTerminals.entrySet()) {
                DeviceParamCache.Entry ge2 = DeviceParamCache.get(ge.getKey());
                if (ge2 != null && ge2.grounded) {
                    Integer[] garr = ge.getValue();
                    for (Integer id : garr) {
                        if (id != null) { engine.groundNode = id; break; }
                    }
                    break;
                }
            }

            // 5.5) 回路检测（叶子修剪 2-core）——仅用于【loopless 判定】：
            //     该网络是否有真实闭合回路（无源无回路 → 跳过计算全 0）。
            //     ⚠ 不再剔除元件（2026-08-12 用户要求）：开路回路元件【保留在
            //       网络中】，由求解器（MNA）做现实电位计算——开路无闭合路径 →
            //       无电流 → 串联元件两端自然等电位（"开路串电阻 → 两端等电位"），
            //       电压沿导线等电位传递；只有真实闭合回路才有电流/功率。
            //       这样"接开路导线到绘图仪"不会让电机误转（开路分支电流=0）。
            //       剔除会切断电位传播（悬空端 0V → 原版设备误读压差 → 误转），
            //       故去掉剔除；openTerminal 等电位仅作兜底（防 MNA 悬空端 GMIN
            //       下拉到 0V）。
            //     ⚠ 变压器端口/源端口保护（core）：空载/单线绕组仍有感应电压
            //       （匝比），源开路保持电压——含源/变压器网络永不 loopless。
            Map<Integer, Integer> openTerminal = new HashMap<>();
            boolean loopless = true; // 无任何闭合回路 → 跳过该网络计算（2026-08-12）
            boolean hasTransformer = false; // 含变压器（独立回路/感应源）→ 永不 loopless
            boolean hasSource = false; // 含源类元件（电压源/电流源）→ 开路保持电压
            {
                java.util.Map<Integer, java.util.Set<Integer>> adj =
                        new java.util.HashMap<>();
                java.util.Set<Integer> core = new java.util.HashSet<>(); // 变压器端口
                for (Element el : engine.elements()) {
                    if (el instanceof IdealTransformer it) {
                        // 端口保护（不修剪）+ 参与回路判定（作为边）：变压器原/副边
                        // 通过互感/理想变压器闭合，必须进 adj 否则原边/副边回路被
                        // 误判为"不闭合" → 正常变压器网络被判 loopless → 不工作。
                        hasTransformer = true;
                        core.add(it.a1); core.add(it.a2);
                        core.add(it.b1); core.add(it.b2);
                        adj.computeIfAbsent(it.a1, k -> new java.util.HashSet<>()).add(it.a2);
                        adj.computeIfAbsent(it.a2, k -> new java.util.HashSet<>()).add(it.a1);
                        adj.computeIfAbsent(it.b1, k -> new java.util.HashSet<>()).add(it.b2);
                        adj.computeIfAbsent(it.b2, k -> new java.util.HashSet<>()).add(it.b1);
                        continue;
                    }
                    if (el instanceof MutualInductor mi) {
                        hasTransformer = true;
                        core.add(mi.a1); core.add(mi.a2);
                        core.add(mi.b1); core.add(mi.b2);
                        adj.computeIfAbsent(mi.a1, k -> new java.util.HashSet<>()).add(mi.a2);
                        adj.computeIfAbsent(mi.a2, k -> new java.util.HashSet<>()).add(mi.a1);
                        adj.computeIfAbsent(mi.b1, k -> new java.util.HashSet<>()).add(mi.b2);
                        adj.computeIfAbsent(mi.b2, k -> new java.util.HashSet<>()).add(mi.b1);
                        continue;
                    }
                    if (isSourceElement(el)) {
                        // 源类元件（电压源/电流源/波形源）端口保护（不修剪）：
                        // 开路时【保持电压】（源端子不因无回路被剔除/抹平），
                        // 导线/下游非源元件传递电压但不计算。
                        hasSource = true;
                        int sa = el.nodeA(), sb = el.nodeB();
                        core.add(sa); core.add(sb);
                        if (sa != sb) {
                            adj.computeIfAbsent(sa, k -> new java.util.HashSet<>()).add(sb);
                            adj.computeIfAbsent(sb, k -> new java.util.HashSet<>()).add(sa);
                        }
                        continue;
                    }
                    int a = el.nodeA(), b = el.nodeB();
                    if (a == b) continue;
                    adj.computeIfAbsent(a, k -> new java.util.HashSet<>()).add(b);
                    adj.computeIfAbsent(b, k -> new java.util.HashSet<>()).add(a);
                }
                java.util.Map<Integer, Integer> degree = new java.util.HashMap<>();
                for (java.util.Map.Entry<Integer, java.util.Set<Integer>> e :
                        adj.entrySet()) {
                    degree.put(e.getKey(), e.getValue().size());
                }
                java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
                for (java.util.Map.Entry<Integer, Integer> e : degree.entrySet()) {
                    if (e.getValue() <= 1 && !core.contains(e.getKey())) q.add(e.getKey());
                }
                java.util.Set<Integer> removed = new java.util.HashSet<>();
                java.util.Map<Integer, Integer> follow =
                        new java.util.HashMap<>(); // removed 节点 → 等电位跟随目标
                while (!q.isEmpty()) {
                    int node = q.poll();
                    if (removed.contains(node) || core.contains(node)) continue;
                    if (degree.getOrDefault(node, 0) > 1) continue;
                    removed.add(node);
                    // 记录等电位跟随目标：node 的邻居中任一未删除节点（电压沿
                    // 无回路分支传递 → 悬空端等电位到源端子，符合现实）。
                    for (int nb : adj.getOrDefault(node, java.util.Collections.emptySet())) {
                        if (removed.contains(nb)) continue;
                        follow.put(node, nb);
                        break;
                    }
                    for (int nb : adj.getOrDefault(node, java.util.Collections.emptySet())) {
                        if (removed.contains(nb)) continue;
                        int nd = degree.merge(nb, -1, Integer::sum);
                        if (nd <= 1 && !core.contains(nb)) q.add(nb);
                    }
                }
                // 无回路判定：没有任何【保留的非保护节点】（2-core 为空）→ 整个
                // 网络无闭合回路 → 跳过该网络计算。变压器端口（core）不算真实
                // 回路：原边接电源有回路时电源两端度数≥2 保留 → loopless=false；
                // 完全无源的悬空导线/设备 → 全被修剪 → loopless=true 跳过。
                // ⚠ 含变压器 → 永不 loopless（2026-08-12，用户要求"变压器是两个
                //   独立回路"）：变压器副边绕组（b1-b2）本身就是独立回路/感应源，
                //   副边即使只接一根线（无外部闭合回路）也必须求解——空载电压是
                //   物理特性，不能被 loopless 跳过写 0（否则副边端子/导线电压错误
                //   下降）。副边悬空负载由叶子修剪剔除（不工作），端子/导线保留
                //   感应电压（等电位 b1）。
                if (hasTransformer || hasSource) {
                    // 含源/含变压器 → 永不 loopless：源开路保持电压、变压器副边
                    // 独立回路感应电压，都必须正常求解（跳过则写 0 → 电压错误）。
                    loopless = false;
                } else {
                    loopless = true;
                    for (int node : adj.keySet()) {
                        if (!removed.contains(node) && !core.contains(node)) {
                            loopless = false;
                            break;
                        }
                    }
                }
                // 电压等电位传递（兜底，2026-08-12 用户要求）：开路分支节点等电位
                // 到保留的源/变压器端子 → 原版设备内部电流 (V-V)/R=0 → 不工作，
                // 但万用表仍读到传递的电压（导线连源 → 整条导线等电位）。
                // 跟随目标可能也是 removed（链式悬空）→ 递归解析到保留节点。
                for (Map.Entry<Integer, Integer> e : follow.entrySet()) {
                    int from = e.getKey(), to = e.getValue();
                    int guard = 0;
                    while (removed.contains(to) && follow.containsKey(to) && guard++ < 64) {
                        to = follow.get(to);
                    }
                    if (from != to && !removed.contains(to)) {
                        openTerminal.put(from, to);
                    }
                }
                // ⚠ 不剔除元件（2026-08-12 用户要求）：开路回路元件【保留在网络
                //   中】，由求解器（MNA）做现实电位计算——开路分支无闭合路径 →
                //   无电流 → 串联元件两端自然等电位（"开路串电阻 → 两端等电位"），
                //   电压沿导线等电位传递。只有真实闭合回路才有电流/功率。
                //   开路分支等电位 → 原版设备内部电流 (V-V)/R=0 → 绘图仪电流>0
                //   才记录、电机电流/功率>阈值才转 → 不会误转/误记录。
                //   openTerminal 等电位仅作兜底（防 MNA 对悬空端 GMIN 下拉 0V）。
            }
            // 5.6) 诊断：孤立引擎节点（无任何元件引用、非地）→ 求解电压必为 0。
            //      若该节点本应通过导线连接（未合并）→ 悬空导线虚假大电流烧线。
            try {
                java.util.Map<Integer, Integer> isoRef = new java.util.HashMap<>();
                for (Element el : engine.elements()) {
                    if (el instanceof IdealTransformer it) {
                        isoRef.merge(it.a1, 1, Integer::sum);
                        isoRef.merge(it.a2, 1, Integer::sum);
                        isoRef.merge(it.b1, 1, Integer::sum);
                        isoRef.merge(it.b2, 1, Integer::sum);
                    } else if (el instanceof MutualInductor mi) {
                        isoRef.merge(mi.a1, 1, Integer::sum);
                        isoRef.merge(mi.a2, 1, Integer::sum);
                        isoRef.merge(mi.b1, 1, Integer::sum);
                        isoRef.merge(mi.b2, 1, Integer::sum);
                    } else {
                        int a = el.nodeA(), b = el.nodeB();
                        isoRef.merge(a, 1, Integer::sum);
                        isoRef.merge(b, 1, Integer::sum);
                    }
                }
                int g0 = engine.groundNode;
                for (Map.Entry<OwnedFloatingNode, Integer> en : nodeToEngine.entrySet()) {
                    int id = en.getValue();
                    if (id == g0 || isoRef.getOrDefault(id, 0) > 0) continue;
                    long now = System.currentTimeMillis();
                    if (now - isoDbgLast >= 3000) {
                        isoDbgLast = now;
                        BlockPos p = endpointPos(level, en.getKey().endpoint);
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[IsoNode] engineNode={} pos={} ep={} net={}",
                                id, p, en.getKey().endpoint == null ? "noEP"
                                        : en.getKey().endpoint.getClass().getSimpleName(),
                                en.getKey().getNetwork() != null);
                    }
                    break; // 每构建只打一条
                }
            } catch (Throwable ignored) {
            }
            PhasorNetworkContext ctx = new PhasorNetworkContext(engine, blockTerminals,
                    nodeToEngine, frequency, transformerModels, openTerminal, loopless);
            // 统一挂参数变化消息：所有可调元件参数变化 → ctx.paramVersion++。
            // 消息处理与求解【同一线程】（服务端主线程 round）：refreshParams
            // 读方块 → setter → 消息 → 版本++；求解器判版本变没变 → 变了一次重解。
            for (Element el : engine.elements()) {
                if (el instanceof com.hdf.cryptand.circuitsimulation.model.ParamChangeSource pcs) {
                    pcs.addParamChangeListener(ignored -> ctx.paramVersion.incrementAndGet());
                }
            }
            ctx.paramSources.addAll(paramSources);
            ctx.deviceThermals.putAll(deviceThermals);
            ctx.energyDevices.putAll(energyDevices);
            // 导线段信息（2026-08-12）：WireComposite（复合元件：电阻+温度模型），
            // 求解后由统一复合元件生命周期 update 推进段温度
            ctx.wireSegments = wireComposites;
            return ctx;
        } catch (Throwable t) {
            // 诊断（2026-08-12）：构建异常被吞 → 返回空 nodeToEngine ctx
            // （[BuildEmpty] nodeMap=0 elements=7 实锤：元件构建后异常）→
            // buildPending 判空返回 null → solved=0 → 设备不工作（电机不转）。
            long btNow = System.currentTimeMillis();
            if (btNow - buildErrLast >= 2000) {
                buildErrLast = btNow;
                if (buildErrCount < 3) {
                    buildErrCount++;
                    CryptandNeoForge.WAF_LOGGER.error(
                            "[BuildErr] buildFromTerminals exception", t);
                } else {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[BuildErr] exception={} (repeated)", t.toString());
                }
            }
        }
        return new PhasorNetworkContext(engine, Collections.emptyMap(), frequency);
    }

    /** 反射读取 ElectricalNetwork.wires（protected Set<AbstractElectricWire>）。
     *  首选 mixin accessor（cryptand$wires）——Java 21 强封装下 setAccessible 反射
     *  可能抛 InaccessibleObjectException 恒空 → 网络导线合并失效 → 导线两端未合并
     *  → 孤立端 0V → 虚假大电流烧线。accessor 由 mixin 直接注入字段访问，可靠。 */
    @SuppressWarnings("unchecked")
    static Set<AbstractElectricWire> getNetworkWires(ElectricalNetwork net) {
        try {
            if (net instanceof com.hdf.cryptand.neoforge.powergrid.mixin.customcore.ElectricalNetworkAccessor acc) {
                Set<AbstractElectricWire> ws = acc.cryptand$wires();
                if (ws != null) return ws;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field f = ElectricalNetwork.class.getDeclaredField("wires");
            f.setAccessible(true);
            Object o = f.get(net);
            if (o instanceof Set) {
                return (Set<AbstractElectricWire>) o;
            }
        } catch (Throwable ignored) {
        }
        return java.util.Collections.emptySet();
    }

    /** 网络 wires 集合签名：wires 内容变化（addWire/removeWire/导线接入移除）→
     *  签名变化。用于 writeback 缓存失效：新导线接入【不改变节点数】，若缓存
     *  只按节点数判断 → 命中旧 context（不含新导线合并）→ 新导线两端不合并
     *  → 一端 0V 一端有电压 → 虚假大电流烧线（[WireBurn] 实锤）。
     *  wires 签名变化 → 强制重解 → context 重建 → 合并最新导线 → 等电位。 */
    public static int wiresSignature(ElectricalNetwork net) {
        int sig = 0;
        try {
            for (AbstractElectricWire w : getNetworkWires(net)) {
                sig = sig * 31 + System.identityHashCode(w);
            }
        } catch (Throwable ignored) {
        }
        return sig;
    }

    /** 网络【节点集合】签名（2026-08-13 无感重建修复）：所有节点的 endpoint
     *  位置/端子号聚合 hash。节点集合变化 = 有新设备/新端子接入 = 该网络必须
     *  重建（设备悬空端子收集/新设备建模）。配合缓存校验：
     *  <p>网络级失效（netVersions 按对象键）在 PowerGrid 网络【对象替换/分裂】
     *  时可能漏标记（markNetworkChanged 标记旧对象、查询/缓存用新对象）→ 旧
     *  ctx 被命中（不含新负载端子）→ 负载端 0V 不工作/烧线（[WireBurn] 实锤：
     *  Connector (3,0,12)#0 网络节点存在但 inCtx=false）。节点签名兜底：节点
     *  集合变化 → 必 miss → 重建。其他网络节点集合不变 → 缓存命中（无感保持）。 */
    public static int nodeSignature(ElectricalNetwork net) {
        int sig = 0;
        try {
            for (INode in : net.getNodes()) {
                if (in instanceof OwnedFloatingNode ofn) {
                    if (ofn.endpoint instanceof BlockWireEndpoint bep) {
                        sig = sig * 31 + bep.getPos().hashCode() * 7 + bep.getTerminal();
                    } else if (ofn.endpoint != null) {
                        sig = sig * 31 + System.identityHashCode(ofn.endpoint);
                    } else {
                        sig = sig * 31 + System.identityHashCode(ofn);
                    }
                } else {
                    sig = sig * 31 + System.identityHashCode(in);
                }
            }
        } catch (Throwable ignored) {
        }
        return sig;
    }

    /** 世界全量导线表签名（transmissionLines 全量：导线【创建即加入】，不依赖
     *  addWire/deferredRewire 延迟）。任何新导线（含变压器副边接线）接入 → 签名
     *  必变 → writeback 缓存失效 → 强制重建 ctx。这是烧线窗口的治本防线：
     *  新导线接入后【尚未 addWire】时网络 wires 签名不变、topoVersion 不变，
     *  但全量导线表已含它 → 用此签名兜住，杜绝"新导线一端 0V 一端有电压"。 */
    public static int worldWiresSignature() {
        int sig = 0;
        try {
            for (org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl
                    : com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_WIRES) {
                sig = sig * 31 + System.identityHashCode(tl);
            }
        } catch (Throwable ignored) {
        }
        return sig;
    }

    // ---- 并查集（OwnedFloatingNode 版） ----

    private static OwnedFloatingNode find(Map<OwnedFloatingNode, OwnedFloatingNode> parent,
                                          OwnedFloatingNode k) {
        OwnedFloatingNode p = parent.get(k);
        if (p == null || p.equals(k)) return p == null ? k : p;
        OwnedFloatingNode r = find(parent, p);
        parent.put(k, r);
        return r;
    }

    private static void union(Map<OwnedFloatingNode, OwnedFloatingNode> parent,
                              OwnedFloatingNode a, OwnedFloatingNode b) {
        OwnedFloatingNode ra = find(parent, a);
        OwnedFloatingNode rb = find(parent, b);
        if (ra != null && rb != null && !ra.equals(rb)) parent.put(ra, rb);
    }
}
