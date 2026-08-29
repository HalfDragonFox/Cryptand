/**
 * ===== 电磁铁（PowerGrid electricity.electromagnet） =====
 * 电感负载：wire（ElectricWire/LRSeriesWire）→ R(+L)。
 * 复合模型：通过【包含】MotorModel（R-L 绕组）做电路解析 + 温度模型。
 */

package com.hdf.cryptand.neoforge.powergrid.device.electromagnet;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ElectromagnetAssembler implements SourceCacheAssembler {

    public static final ElectromagnetAssembler INSTANCE = new ElectromagnetAssembler();

    private ElectromagnetAssembler() {}

    /** 主线程每 tick：读 wire → 原子写输入槽（电阻/电感/开关态） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "wire"));
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
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "wire"));
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
