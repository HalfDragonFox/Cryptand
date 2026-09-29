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

/** 电感：复合模型（Backward Euler 电流记忆 = 储能 + DCR 铜阻发热）。 */
public final class InductorMapper extends ElementMapper {

    @Override
    String name() {
        return "InductorMapper";
    }

    @Override
    boolean supports(BlockEntity be) {
        return be instanceof InductorBlockEntity;
    }

    @Override
    protected void doMap(ElementMapContext c, BlockEntity be) {
        final Network net = c.net;
        final InductorBlockEntity ind = (InductorBlockEntity) be;
        final int a = c.a;
        final int b = c.b;
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
        final AssemblyRegistrar reg = c.reg();
        reg.energy(be, indModel);
        reg.thermal(be, indModel);
        reg.bindAll(be, indModel);
        final com.hdf.cryptand.circuitsimulation.model.composite.InductorModel imRef = indModel;
        reg.paramSource(be.getBlockPos(), DeviceParamCache.Kind.INDUCTOR,
                e -> imRef.setInductance(e.inductance));
    }
}
