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
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.mixin.customcore.ElectricalNetworkAccessor;
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

/**
 * ===== 元件映射上下文 =====
 *
 * <p>把原 {@code PhasorNetworkBuilder.addElement(...)} 的 10 个参数收成一个对象，
 * 供 {@link ElementMapper} 各子类共享：一次映射所需的「网络 + 两端节点 + 各类
 * 收集表（参数源 / 温度 / 储能 / 组装器 / 绑定）」。
 *
 * <p>字段全部 {@code final}：映射器只读不放，写只写收集表本身。
 */
public final class ElementMapContext {

    final Level level;
    final Network net;
    final int a;
    final int b;
    public final List<PhasorNetworkContext.ParamSource> paramSources;
    public final Map<BlockPos, ThermalDevice> deviceThermals;
    public final Map<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice> energyDevices;
    public final Set<ElectricalNetwork> pgNets;
    public final Map<BlockPos, com.hdf.cryptand.neoforge.powergrid.device.Assembler> assemblers;
    public final Map<BlockPos, com.hdf.cryptand.engine.Binding> bindings;

    public ElementMapContext(Level level, Network net, int a, int b,
                      List<PhasorNetworkContext.ParamSource> paramSources,
                      Map<BlockPos, ThermalDevice> deviceThermals,
                      Map<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice> energyDevices,
                      Set<ElectricalNetwork> pgNets,
                      Map<BlockPos, com.hdf.cryptand.neoforge.powergrid.device.Assembler> assemblers,
                      Map<BlockPos, com.hdf.cryptand.engine.Binding> bindings) {
        this.level = level;
        this.net = net;
        this.a = a;
        this.b = b;
        this.paramSources = paramSources;
        this.deviceThermals = deviceThermals;
        this.energyDevices = energyDevices;
        this.pgNets = pgNets;
        this.assemblers = assemblers;
        this.bindings = bindings;
    }

    /** 装配登记组合件（懒建一次，映射器全程复用）。 */
    private AssemblyRegistrar registrar;

    /** 装配登记组合件：映射器通过它表达「登记意图」，而不是直接操作各张表。 */
    AssemblyRegistrar reg() {
        if (registrar == null) registrar = new AssemblyRegistrar(this);
        return registrar;
    }

    /** 方块位置（各映射器高频使用）。 */
    BlockPos pos(BlockEntity be) {
        return be.getBlockPos();
    }
}
