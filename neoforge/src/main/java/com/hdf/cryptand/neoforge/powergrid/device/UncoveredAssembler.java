package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.SwitchModel;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ===== 未接管原版组件通用组装器（2026-08-30 用户：全部接管） =====
 *
 * 对 PowerGrid 未在 Assemblers 显式建模的组件按类别分发复合模型：
 *   - 灯族（FactoryLight/CeilingTileLamp）→ SwitchModel（filament 灯丝开关）
 *   - 电阻（Resistor/CreativeResistor）→ ResistorModel（wire 电阻 + 温度）
 *   - 泵（ElectricPump）→ R-L 绕组（pumpElement）
 *   - 太阳能（SolarPanel/CeilingTileSolar）→ 光伏源（DC 源简化）
 *   - 绕组（Winding/Tuned）→ R-L（MotorModel）
 *   - 机械（Rotor/GeneratorClutch/SolarPanelBearing——无电气端子）→ null（不建模）
 *   - 电子/信号/测量（CRT/FEInverter/RedstoneConverter/ModularDisplay/CircuitBoard/
 *     CircuitDesignTable/EnergyMeter/PunchCardReader/SparkGap）→ 占位直通电阻（连通）
 */
public final class UncoveredAssembler implements com.hdf.cryptand.neoforge.powergrid.device.ProxiableAssembler {

    public static final UncoveredAssembler INSTANCE = new UncoveredAssembler();

    private UncoveredAssembler() {}

    /** 主线程每 tick：空实现——本组装器 {@link #assemble} 直接读 BE（无缓存化需求） */
    @Override
    public void refreshCache(BlockEntity be, com.hdf.cryptand.neoforge.powergrid.state.DeviceCache cache) {
        // 无（assemble 走 BE 路径）
    }

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            // 2026-09-13：自有 BE 子类（XxxBE extends 原版）→ 用 beKey 解析出【原版名】，
            // 下面所有 n.equals("XxxBlockEntity") 判断才不会 MISS（否则设备不建模）
            String n = Assemblers.beKey(be);
            // ---- 灯族：灯丝开关 ----
            if (n.equals("CeilingTileLampBlockEntity") || n.equals("FactoryLightBlockEntity")) {
                return lamp(be, a, b, net);
            }
            // ---- 电阻：wire + 温度 ----
            if (n.equals("ResistorBlockEntity") || n.equals("CreativeResistorBlockEntity")) {
                return resistor(be, a, b, net);
            }
            // ---- 泵：R-L 绕组 ----
            if (n.equals("ElectricPumpBlockEntity")) {
                return pump(be, a, b, net);
            }
            // ---- 太阳能：光伏源（简化 DC） ----
            if (n.contains("SolarPanel")) {
                return solar(be, a, b, net);
            }
            // ---- 绕组：R-L ----
            if (n.contains("Winding") || n.equals("TunedBlockEntity")) {
                return winding(be, a, b, net);
            }
            // ---- 机械（无电气端子）：不建模 ----
            if (n.contains("Rotor") || n.equals("GeneratorClutchBlockEntity")
                    || n.equals("SolarPanelBearingBlockEntity")) {
                return null;
            }
            // ---- 电子/信号/测量：占位直通（安全连通） ----
            if (a != b) {
                net.addElement(new Resistor(a, b, 1e-4));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 灯：filament/getFilament → SwitchModel（装灯泡闭合） */
    private static CompositeModel lamp(BlockEntity be, int a, int b, Network net) {
        Object filament = null;
        try { filament = DeviceWire.call(be, "getFilament"); } catch (Throwable t1) { }
        if (filament == null) {
            try { filament = DeviceWire.field(be, "filament"); } catch (Throwable t2) { }
        }
        DeviceWire dw = DeviceWire.of(filament);
        if (dw.hasResistance() && dw.resistance > 0) {
            SwitchModel sm = new SwitchModel(a, b, dw.resistance);
            sm.setOn(dw.enabled);
            return sm;
        }
        return null;
    }

    /** 电阻：wire → ResistorModel（+温度） */
    private static CompositeModel resistor(BlockEntity be, int a, int b, Network net) {
        DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "wire"));
        double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
        if (r > 0) {
            ThermalModel th = DeviceThermalStore.thermalFor(be.getBlockPos());
            return new ResistorModel(a, b, r, th);
        }
        return null;
    }

    /** 泵：pumpElement/resistance → R-L 绕组 */
    private static CompositeModel pump(BlockEntity be, int a, int b, Network net) {
        DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "pumpElement"));
        double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
        if (r <= 0) {
            try {
                Object v = DeviceWire.call(be, "resistance");
                if (v instanceof Number num) r = num.doubleValue();
            } catch (Throwable ignored) { }
        }
        if (r > 0) {
            int x = net.addNode().id;
            ThermalModel th = DeviceThermalStore.thermalFor(be.getBlockPos());
            return new MotorModel(a, b, x, r, Math.max(dw.inductance, 0), th);
        }
        return null;
    }

    /** 太阳能：光伏源（简化 DC 源——阳光照度由外部/原版，这里给基础电压） */
    private static CompositeModel solar(BlockEntity be, int a, int b, Network net) {
        try {
            // 简化：DC 24V 光伏源（现实光伏板开路 ~24V；电流由负载决定）
            net.addElement(new DcVoltageSource(a, b, 24.0, 0.5));
        } catch (Throwable ignored) { }
        return null;
    }

    /** 绕组：wire → R-L（MotorModel） */
    private static CompositeModel winding(BlockEntity be, int a, int b, Network net) {
        // ⚠ 2026-09-12 字段名修正（子智能体盘点）：WindingBlockEntity 的字段是
        //   coilWire（不是 wire）——原先读 "wire" 恒为 null → r=0 → 本分支恒返回
        //   null（"已实现但不可达"）。真正的接线在 WindingMapper /
        //   PhasorNetworkBuilder 的 WINDING 分支，这里保持同样语义。
        DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "coilWire"));
        double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
        if (r > 0) {
            int x = net.addNode().id;
            ThermalModel th = DeviceThermalStore.thermalFor(be.getBlockPos());
            // 2026-09-12 用户："铁耗/铜耗/风阻等通过定义线圈、转子等复合元件然后组装器
            // 中温度模型实现相关温度计算即可，完全取消原版计算"——线圈发热（铜耗 +
            // 铁耗/磁饱和/频率封顶）整体由 WindingComposite 在引擎侧算，温度挂 th。
            // 配置值经构造参数传入（common 侧不依赖 neoforge 配置）。
            return new com.hdf.cryptand.circuitsimulation.model.composite.WindingComposite(
                    a, b, x, r, Math.max(dw.inductance, 0), th,
                    com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                            .COIL_EFFECTIVE_INDUCTANCE_H.get(),
                    com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                            .COIL_DC_RESISTANCE_OHM.get(),
                    com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                            .MOTOR_MAX_DRIVE_FREQUENCY_HZ.get());
        }
        return null;
    }
}
