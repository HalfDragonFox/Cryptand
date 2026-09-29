/**
 * ===== 电风扇（PowerGrid electricity.fan） =====
 * 电机负载：motor（ElectricWire）→ R(+L)。
 * 复合模型：通过【包含】MotorModel（R-L 绕组）做电路解析 + 温度模型。
 */

package com.hdf.cryptand.neoforge.powergrid.device.fan;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class FanAssembler implements SourceCacheAssembler {

    public static final FanAssembler INSTANCE = new FanAssembler();

    private FanAssembler() {}

    /** 原版直流设备：只支持 DC，超频温度惩罚（dcDeviceMaxFrequencyHz） */
    @Override public boolean dcOnly() { return true; }

    /** 读风扇 motor wire（R/L + 开关态；BE→组装器 Source 方向） */
    private static DeviceWire motorOf(BlockEntity be) {
        return DeviceWire.of(DeviceWire.field(be, "motor"));
    }

    /** 主线程每 tick：读 motor wire → 原子写输入槽（电阻/电感/开关态） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = motorOf(be);
            double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, Math.max(dw.inductance, 0),
                    0, 0, 0, dw.enabled, old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽快照构建 MotorModel（原子读，不碰 BE） */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            if (d.enabled && d.resistance > 0) {
                int x = net.addNode().id;
                return new MotorModel(a, b, x, d.resistance, d.inductance,
                        DeviceThermalStore.thermalFor(pos));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            DeviceWire dw = motorOf(be);
            if (dw.enabled && dw.hasResistance() && dw.resistance > 0) {
                int x = net.addNode().id;
                return new MotorModel(a, b, x, dw.resistance,
                        Math.max(dw.inductance, 0),
                        DeviceThermalStore.thermalFor(be.getBlockPos()));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
