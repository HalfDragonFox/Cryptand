/**
 * ===== 碳堆电池（PowerGrid electricity.carbonpile） =====
 * CarbonPile 本体：无直接 wire（coil 是 CarbonPileCoilBlockEntity）。
 * CarbonPileCoil：pile（SwitchedWire 电阻）+ trim（可变电阻）。
 * 复合模型：通过【包含】MotorModel（L=0 纯电阻，trim 可变）做电路解析 +
 * 温度模型；trim 变化经 setResistance 发参数消息 → 重解（不重建）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.carbonpile;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class CarbonPileAssembler implements SourceCacheAssembler {

    public static final CarbonPileAssembler INSTANCE = new CarbonPileAssembler();

    private CarbonPileAssembler() {}

    /** 原版直流设备：只支持 DC，超频温度惩罚（dcDeviceMaxFrequencyHz） */
    @Override public boolean dcOnly() { return true; }

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        double r = pileResistance(be);
        if (r <= 0) return null;
        int x = net.addNode().id;
        return new MotorModel(a, b, x, r, 0,
                DeviceThermalStore.thermalFor(be.getBlockPos()));
    }

    /** 主线程每 tick：读碳堆电阻 → 原子写输入槽 */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            double r = pileResistance(be);
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
            DeviceCache c =
                    DeviceCacheRegistry.get(pos);
            if (c == null) return;
            DeviceCache.Data d = c.in();
            if (d.resistance > 0) mm.setResistance(d.resistance); // 值变化 → 重解
        });
    }

    /** 读当前碳堆电阻（pile × trim 比例） */
    private static double pileResistance(BlockEntity be) {
        try {
            if (be == null) return -1;
            Object coil = DeviceWire.field(be, "coil");
            if (coil == null) {
                // 本体可能是 CarbonPileCoilBlockEntity
                DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "pile"));
                if (dw.enabled && dw.hasResistance() && dw.resistance > 0) return dw.resistance;
                return -1;
            }
            DeviceWire dw = DeviceWire.of(DeviceWire.field(coil, "pile"));
            if (dw.enabled && dw.hasResistance() && dw.resistance > 0) {
                double r = dw.resistance;
                Object trim = DeviceWire.call(coil, "getTrim");
                if (trim instanceof Number n && n.floatValue() > 0) {
                    r *= Math.max(n.doubleValue(), 0.01); // trim 比例
                }
                return Math.max(r, 1e-4);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
