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
import com.hdf.cryptand.neoforge.powergrid.device.wire.WireAssembler;
import com.hdf.cryptand.neoforge.powergrid.mixin.customcore.ElectricalNetworkAccessor;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
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

/** 创造模式 AC 源（电压源 / 电流源两型）：装源元件 + 注册参数刷新源。 */
public final class AcSourceMapper extends ElementMapper {

    @Override
    String name() {
        return "AcSourceMapper";
    }

    @Override
    boolean supports(BlockEntity be) {
        return be instanceof AcCreativeSourceBlockEntity;
    }

    @Override
    protected void doMap(ElementMapContext c, BlockEntity be) {
        final Network net = c.net;
        final AcCreativeSourceBlockEntity src = (AcCreativeSourceBlockEntity) be;
        final int a = c.a;
        final int b = c.b;
        final BlockPos sp = be.getBlockPos();
        if (src.isVoltageSourceType()) {
            // 2026-08-15 多 AC 源修复：传源频率（多频网络 MultiToneSolver 按
            // 频率选择性注入；0=跟随网络主导频率）
            AcVoltageSource vs = new AcVoltageSource(a, b, src.getAmplitude(),
                    src.getPhaseDegrees(), 1e-4, src.getFrequencyHz());
            net.addElement(vs);
            // 参数刷新源：读缓存当前幅值/相位 → setter；值变化自动发送参数变化消息
            // （2026-08-15 去 level：读 DeviceParamCache，不碰 level/BE）
            c.reg().paramSource(sp, DeviceParamCache.Kind.AC_VOLTAGE_SRC, e -> {
                vs.setAmplitude(e.amplitude);
                vs.setPhaseDeg(e.phaseDeg);
            });
        } else {
            CurrentSource cs = new CurrentSource(a, b, src.getAmplitude(),
                    src.getFrequencyHz());
            net.addElement(cs);
            c.reg().paramSource(sp, DeviceParamCache.Kind.AC_CURRENT_SRC,
                    e -> cs.setCurrent(e.amplitude));
        }
    }
}
