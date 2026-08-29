/**
 * ===== 电池类设备（PowerGrid electricity.battery / equipment.portablebattery） =====
 *
 * Battery / PotatoBattery：DC 电压源（sourceCoupling → VoltageSourceCoupling，
 * Thevenin 内阻）。DC 求解 = 电压源；AC 相量下 DC 源对交流短路 → 内阻电阻。
 * PortableBattery：SwitchedWire 电阻负载（可充放电池，相量模型 = 负载电阻）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.battery;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class BatteryAssembler implements SourceCacheAssembler {

    /** 有源设备（虚拟建模需临时断开，不能产生无限功率） */
    @Override public boolean isSource() { return true; }

    public static final BatteryAssembler INSTANCE = new BatteryAssembler();

    private BatteryAssembler() {}

    /** 原版直流设备：电池只支持 DC，超频温度惩罚（dcDeviceMaxFrequencyHz） */
    @Override public boolean dcOnly() { return true; }

    /** 主线程每 tick：读 sourceCoupling（电压/内阻）+ wire（负载 R）→ 写输入槽
     *  （resistance=负载R，inductance=内阻，amplitude=电压，enabled=isVs） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "sourceCoupling"));
            boolean isVs = dw.isVoltageSource;
            double v = isVs ? Math.abs(dw.voltage) : 0;
            double sr = (isVs && dw.sourceResistance > 0) ? dw.sourceResistance : 0;
            DeviceWire pw = DeviceWire.of(DeviceWire.field(be, "wire"));
            double wr = (pw.enabled && pw.hasResistance() && pw.resistance > 0)
                    ? pw.resistance : 0;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(wr, sr, v, 0, 0, isVs,
                    old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：PortableBattery（非源）→ 负载 MotorModel（原子读，不碰 BE） */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            if (!d.enabled && d.resistance > 0) { // PortableBattery 负载
                int x = net.addNode().id;
                return new MotorModel(a, b, x, d.resistance, 0,
                        DeviceThermalStore.thermalFor(pos));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 后台：DC 源（isVs）→ stamp DcVoltageSource（原子读，不碰 BE） */
    @Override
    public void stampFromCache(BlockPos pos, DeviceCache cache, int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            if (d.enabled) {
                double v = d.amplitude;
                double r = d.inductance > 0 ? d.inductance : 1e-4;
                if (v > 0) net.addElement(new DcVoltageSource(a, b, v, r));
                else net.addElement(new Resistor(a, b, r)); // 放空电池 → 内阻
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stamp(BlockEntity be, int a, int b, Network net) {
        try {
            // Battery / PotatoBattery：DC 电压源（Thevenin）
            Object sc = DeviceWire.field(be, "sourceCoupling");
            DeviceWire dw = DeviceWire.of(sc);
            if (dw.isVoltageSource) {
                double v = Math.abs(dw.voltage);
                double r = dw.sourceResistance > 0 ? dw.sourceResistance : 1e-4;
                if (v > 0) {
                    net.addElement(new DcVoltageSource(a, b, v, r));
                } else {
                    net.addElement(new Resistor(a, b, r)); // 放空电池 → 内阻
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            // 只有 PortableBattery（SwitchedWire 负载电阻）加温度；DC 源不计（源）
            Object sc = DeviceWire.field(be, "sourceCoupling");
            DeviceWire dw = DeviceWire.of(sc);
            if (dw.isVoltageSource) return null; // DC 源 → 交给 stamp
            Object w = DeviceWire.field(be, "wire");
            DeviceWire pw = DeviceWire.of(w);
            if (pw.enabled && pw.hasResistance() && pw.resistance > 0) {
                int x = net.addNode().id;
                return new MotorModel(a, b, x, pw.resistance, 0,
                        DeviceThermalStore.thermalFor(be.getBlockPos()));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
