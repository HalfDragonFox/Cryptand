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

/** 励磁绕组（Winding）：整条线圈 = Rdc 串联 L，用内部节点做串联支路。 */
public final class WindingMapper extends ElementMapper {

    /** 是否为 PowerGrid 励磁绕组（按类名匹配，避免硬依赖内部类） */
    private static boolean isWinding(BlockEntity be) {
        // 2026-09-13：继承链判断（自有 BE WindingBE → 原版 WindingBlockEntity）
        return com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                .classChainHas(be, "WindingBlockEntity");
    }

    /** Winding 是否主方块（唯一持有 coilWire 者；代理方块 mainBE 指向主方块） */
    private static boolean isWindingMain(BlockEntity be) {
        try {
            // 2026-09-13：改继承链上溯（自有 BE 是原版子类，getDeclaredField 只查
            //  自身 → 原实现对子类恒 true → 代理方块被误判为主方块 → 重复建模）
            Object main = com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                    .field(be, "mainBE");
            return main == null || main == be;
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    String name() {
        return "WindingMapper";
    }

    @Override
    boolean supports(BlockEntity be) {
        return isWinding(be) && isWindingMain(be);
    }

    @Override
    protected void doMap(ElementMapContext c, BlockEntity be) {
        final Network net = c.net;
        final int a = c.a;
        final int b = c.b;
        // 励磁绕组（Winding）：只有主方块持有 coilWire。整条线圈 = Rdc 串联 L
        // （物理正确：Z = R + jωL。注意【不能】两个元件跨同一对节点——那是
        //  并联，阻抗变成 R∥jωL 错误）。用内部节点做串联支路。
        // 代理方块不重复加元件（它的端子在网络上仍作为导线分支点）。
        double rdc = ConfigPowerGrid.COIL_DC_RESISTANCE_OHM.get();
        double lphys = ConfigPowerGrid.COIL_EFFECTIVE_INDUCTANCE_H.get();
        if (rdc > 0 || lphys > 0) {
            int x = net.addNode().id; // R-L 串联内部节点
            // ⚠ 2026-09-12 接线修复（子智能体盘点 A/D1）：原先只加【裸 R+L】——
            //   既没有 composite 也没有温度模型，而 BE 侧发热已停用 →
            //   线圈无热源、温度恒环境。改为挂 WindingComposite（引擎侧算
            //   铜耗+铁耗），温度统一由 DeviceThermalStore 推进。
            com.hdf.cryptand.circuitsimulation.model.composite.WindingComposite wc =
                    new com.hdf.cryptand.circuitsimulation.model.composite.WindingComposite(
                            a, b, x, Math.max(rdc, 1e-9), Math.max(lphys, 0),
                            DeviceThermalStore.thermalFor(be.getBlockPos()),
                            Math.max(lphys, 0), Math.max(rdc, 1e-9),
                            ConfigPowerGrid.MOTOR_MAX_DRIVE_FREQUENCY_HZ.get());
            try {
                wc.setCompositeKey("W" + be.getBlockPos());
            } catch (Throwable ignored) {
            }
            net.addComposite(wc);
        }
    }
}
