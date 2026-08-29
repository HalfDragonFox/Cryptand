/**
 * ===== 变阻器（PowerGrid kinetics.rheostat） =====
 * 电位器：half1 + half2（ElectricWire）串联。相量两端口 → 总电阻（可变）。
 * 复合模型：通过【包含】MotorModel（L=0 纯电阻，滑片可变）做电路解析 +
 * 温度模型；滑片变化经 setResistance 发参数消息 → 重解（不重建）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.rheostat;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class RheostatAssembler implements SourceCacheAssembler {

    public static final RheostatAssembler INSTANCE = new RheostatAssembler();

    private RheostatAssembler() {}

    /** 原版直流设备：只支持 DC，超频温度惩罚（dcDeviceMaxFrequencyHz） */
    @Override public boolean dcOnly() { return true; }

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        double r = rheostatResistance(be);
        if (r <= 0) return null;
        int x = net.addNode().id;
        return new MotorModel(a, b, x, r, 0,
                DeviceThermalStore.thermalFor(be.getBlockPos()));
    }

    /** 主线程每 tick：读变阻器总电阻 → 原子写输入槽 */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            double r = rheostatResistance(be);
            if (r <= 0) return;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, true,
                    old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽构建 MotorModel（原子读，不碰 BE） */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            if (d.resistance > 0) {
                int x = net.addNode().id;
                return new MotorModel(a, b, x, d.resistance, 0,
                        DeviceThermalStore.thermalFor(pos));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void registerParams(BlockPos pos, CompositeModel cm,
                                        List<PhasorNetworkContext.ParamSource> paramSources) {
        if (!(cm instanceof MotorModel mm)) return;
        paramSources.add(() -> {
            com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache c =
                    com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCacheRegistry.get(pos);
            if (c == null) return;
            com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache.Data d = c.in();
            if (d.resistance > 0) mm.setResistance(d.resistance); // 值变化 → 重解
        });
    }

    /** 读当前变阻器总电阻（half1 + half2） */
    private static double rheostatResistance(BlockEntity be) {
        try {
            if (be == null) return -1;
            DeviceWire d1 = DeviceWire.of(DeviceWire.field(be, "half1"));
            DeviceWire d2 = DeviceWire.of(DeviceWire.field(be, "half2"));
            double r = 0;
            if (d1.hasResistance()) r += d1.resistance;
            if (d2.hasResistance()) r += d2.resistance;
            return r > 0 ? r : -1;
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
