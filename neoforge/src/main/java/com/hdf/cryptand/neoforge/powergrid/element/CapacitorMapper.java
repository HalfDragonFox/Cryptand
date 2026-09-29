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
import com.hdf.cryptand.neoforge.powergrid.device.AssemblyRegistrar;
import com.hdf.cryptand.neoforge.powergrid.device.wire.WireAssembler;
import com.hdf.cryptand.neoforge.powergrid.mixin.customcore.ElectricalNetworkAccessor;
import com.hdf.cryptand.neoforge.powergrid.state.CapacitorStateStore;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
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

/** 电容：复合模型（基础电容 + ESR 发热 + 储能），跨重建恢复充电状态 vPrev。 */
public final class CapacitorMapper extends ElementMapper {

    @Override
    String name() {
        return "CapacitorMapper";
    }

    @Override
    boolean supports(BlockEntity be) {
        return be instanceof CapacitorBlockEntity;
    }

    @Override
    protected void doMap(ElementMapContext c, BlockEntity be) {
        final Network net = c.net;
        final CapacitorBlockEntity cap = (CapacitorBlockEntity) be;
        final int a = c.a;
        final int b = c.b;
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
        // ⚠ 2026-08-30 审计 U17 根因：跨网络重建/测量构建必须恢复电容充电
        // 状态（vPrev）——CapacitorStateStore 此前只 save（PhasorEngine.
        // saveCapacitorStates）从不 restore → 每次重建 vPrev=0 → DC 电容
        // 永远停在第一拍充电电流（"DC 电容测量恒 163A"根因；2026-08-21
        // 修复只做了一半）。恢复后充电状态连续，DC 逐节拍演化正确。
        try {
            double vPrev = CapacitorStateStore.get("C" + be.getBlockPos());
            if (vPrev != 0) capModel.setCapVPrev(vPrev);
        } catch (Throwable ignored) {
        }
        net.addComposite(capModel);
        // 储能 + 温度收集（求解后统一同步电荷/推进温度）
        final AssemblyRegistrar reg = c.reg();
        reg.energy(be, capModel);
        reg.thermal(be, capModel);
        reg.bindAll(be, capModel);
        final com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel cmRef = capModel;
        reg.paramSource(be.getBlockPos(), DeviceParamCache.Kind.CAPACITOR,
                e -> cmRef.setCapacitance(e.capacitance));
    }
}
