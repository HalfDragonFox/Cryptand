package com.hdf.cryptand.neoforge.powergrid.element;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit;
import com.hdf.cryptand.circuitsimulation.lib.SpiceInstance;
import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.IdealTransformerModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MeterModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.elements.*;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.core.library.ComponentLibrary;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.block.AcCreativeSourceBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.block.CapacitorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.block.InductorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.block.ProgrammableComponentBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import com.hdf.cryptand.neoforge.powergrid.device.Assemblers;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import com.hdf.cryptand.neoforge.powergrid.device.BeEventSink;
import com.hdf.cryptand.neoforge.powergrid.device.wire.WireAssembler;
import com.hdf.cryptand.neoforge.powergrid.engine.AdapterDiag;
import com.hdf.cryptand.neoforge.powergrid.mixin.customcore.ElectricalNetworkAccessor;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
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
import java.util.*;

/** 兜底：原版/PowerGrid 设备 → 组装器（Assemblers 注册表）装配或 stamp。 */
public final class AssemblerDeviceMapper extends ElementMapper {

    @Override
    String name() {
        return "AssemblerDeviceMapper";
    }

    @Override
    boolean supports(BlockEntity be) {
        return true;
    }

    @Override
    protected void doMap(ElementMapContext ctx, BlockEntity be) {
        final Network net = ctx.net;
        final int a = ctx.a;
        final int b = ctx.b;
        // 原版设备：按 PowerGrid 包结构分发的设备模型（battery/heater/switch...）
        Assembler model = Assemblers.get(be);
        // ⚠ 2026-08-30 网络内组装器表（用户：取消全局——组装器按网络存储）：
        // 注册进本网络 assemblers（构建后填充 ctx.assemblers）——处理
        // （温度/能量/策略）按网络遍历本表。
        ctx.reg().assembler(be, model);
        if (model != null) {
            CompositeModel cm = null;
            // 2026-08-15 组装器缓存化：优先走缓存版（assembleFromCache 读
            // 原子缓存输入槽，后台可纯数据构建）；缓存未注册/返回 null →
            // 回退原 assemble(BE)（过渡兼容）。
            if (model instanceof SourceCacheAssembler sca) {
                try {
                    DeviceCache c =
                            DeviceCacheRegistry
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
                    ctx.reg().thermal(be, td);
                }
                // 储能设备（电容/电池/机电能量模型）→ 记录到 ctx（求解后
                // 统一同步储能状态——时间相关变量绑定，2026-08-12）
                if (cm instanceof com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice ed) {
                    ctx.reg().energy(be, ed);
                }
                // 绑定实际模型事件接收器：温度超限 → 通知实际模型爆炸
                // （完全解耦：引擎不知道 BE 细节，BE 侧只接收事件）
                try {
                    cm.bindEvents(BeEventSink.of(be));
                } catch (Throwable ignored) {
                }
                // 组装器统一绑定：实际模型 ↔ 复合元件（破坏感知/移除通知）
                ctx.reg().bindDevice(model, be, cm);
                // ⚠ 2026-08-30 网络内绑定器表（引擎消息：求解完成 → 绑定器）：
                // 注册 DeviceBinding（平台实现 common Binding——onMessage 平台处理）
                ctx.reg().binding(be);
                // 复合模型可调参数源（碳堆 trim/变阻器滑片 → setResistance）
                model.registerParams(be.getBlockPos(), cm, ctx.paramSources);
            } else {
                model.stamp(be, a, b, net);
            }
        } else {
            AdapterDiag.pos(be.getBlockPos(),
                    "[BuildDbg] block UNRECOGNIZED pos={} class={} (no element, may become short)",
                    be.getBlockPos(), be.getClass().getSimpleName());
        }
    }
}