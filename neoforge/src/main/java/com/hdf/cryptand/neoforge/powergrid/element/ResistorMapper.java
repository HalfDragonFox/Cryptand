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
import com.hdf.cryptand.neoforge.powergrid.engine.AdapterDiag;
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

/** 电阻：复合模型（温度模型 + I²R 发热）。阻值 < 0 时只打诊断不装元件。 */
public final class ResistorMapper extends ElementMapper {

    @Override
    String name() {
        return "ResistorMapper";
    }

    @Override
    boolean supports(BlockEntity be) {
        return be instanceof ResistorBlockEntity;
    }

    @Override
    protected void doMap(ElementMapContext c, BlockEntity be) {
        final Network net = c.net;
        final int a = c.a;
        final int b = c.b;
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
            final AssemblyRegistrar reg = c.reg();
            reg.thermal(be, resModel);
            reg.bindAll(be, resModel);
            final com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel rrRef = resModel;
            reg.paramSource(be.getBlockPos(), DeviceParamCache.Kind.RESISTOR, e -> {
                if (e.resistance > 0) rrRef.setResistance(e.resistance);
            });
        } else {
            AdapterDiag.pos(be.getBlockPos(),
                    "[BuildDbg] resistor SKIPPED pos={} class={} r=0 (value field / wire both failed)",
                    be.getBlockPos(), be.getClass().getSimpleName());
        }
    }

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
}
