/**
 * ===== 警铃（PowerGrid electricity.bell） =====
 * 电阻负载：wire（ElectricWire）→ R。
 * 复合模型：通过【包含】MotorModel（L=0 纯电阻）做电路解析 + 温度模型。
 */

package com.hdf.cryptand.neoforge.powergrid.device.bell;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class BellAssembler implements SourceCacheAssembler {

    public static final BellAssembler INSTANCE = new BellAssembler();

    private BellAssembler() {}

    /** 原版直流设备：只支持 DC，超频温度惩罚（dcDeviceMaxFrequencyHz） */
    @Override public boolean dcOnly() { return true; }

    /** 主线程每 tick：读 wire → 原子写输入槽（电阻/开关态，L=0） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "wire"));
            double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, dw.enabled,
                    old.version + 1));
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
                return new MotorModel(a, b, x, d.resistance, 0,
                        DeviceThermalStore.thermalFor(pos));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "wire"));
            if (dw.enabled && dw.hasResistance() && dw.resistance > 0) {
                int x = net.addNode().id;
                return new MotorModel(a, b, x, dw.resistance, 0,
                        DeviceThermalStore.thermalFor(be.getBlockPos()));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
