/**
 * ===== 自耦变压器（PowerGrid kinetics.variac） =====
 * 与变压器同构：primaryStray + mutualInductance + TransformerCoupling(ratio)。
 * 相量模型：a --primaryStray(R)-- x --mutual(R)-- b + 理想变压器(x,b):(x,b) 自耦。
 * 简化近似：理想变压器 ratio 直接接端口（漏感/互感很小，见变压器模型）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.variac;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.VariacModel;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class VariacAssembler implements SourceCacheAssembler {

    public static final VariacAssembler INSTANCE = new VariacAssembler();

    private VariacAssembler() {}

    /** 复合模型：自耦变压器 + 绕组铜耗温度模型（composite 优先于 stamp）。 */
    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            Object coupling = DeviceWire.field(be, "coupling");
            DeviceWire dc = DeviceWire.of(coupling);
            double ratio = dc.ratio;
            if (ratio <= 0 || ratio == 1) {
                // 1:1 或未知 → 直通（小电阻，无真实绕组 → 不计温度）
                return new VariacModel(a, b, -1, -1, 0, 0, ratio,
                        DeviceThermalStore.thermalFor(be.getBlockPos()));
            }
            DeviceWire dps = DeviceWire.of(DeviceWire.field(be, "primaryStray"));
            DeviceWire dmi = DeviceWire.of(DeviceWire.field(be, "mutualInductance"));
            double rps = dps.hasResistance() && dps.resistance > 0 ? dps.resistance : 0;
            double rmi = dmi.hasResistance() && dmi.resistance > 0 ? dmi.resistance : 0;
            int x = net.addNode().id;
            int k = net.addNode().id;
            return new VariacModel(a, b, x, k, rps, rmi, ratio,
                    DeviceThermalStore.thermalFor(be.getBlockPos()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 主线程每 tick：读 ratio/铜阻 → 原子写输入槽（resistance=rps, inductance=rmi, amplitude=ratio） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dc = DeviceWire.of(DeviceWire.field(be, "coupling"));
            double ratio = dc.ratio;
            DeviceWire dps = DeviceWire.of(DeviceWire.field(be, "primaryStray"));
            DeviceWire dmi = DeviceWire.of(DeviceWire.field(be, "mutualInductance"));
            double rps = dps.hasResistance() && dps.resistance > 0 ? dps.resistance : 0;
            double rmi = dmi.hasResistance() && dmi.resistance > 0 ? dmi.resistance : 0;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(rps, rmi, ratio, 0, 0,
                    ratio > 0, old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽构建 VariacModel（原子读，不碰 BE） */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            double ratio = d.amplitude;
            if (ratio <= 0 || ratio == 1) {
                return new VariacModel(a, b, -1, -1, 0, 0, ratio,
                        DeviceThermalStore.thermalFor(pos));
            }
            int x = net.addNode().id;
            int k = net.addNode().id;
            return new VariacModel(a, b, x, k, d.resistance, d.inductance,
                    ratio, DeviceThermalStore.thermalFor(pos));
        } catch (Throwable ignored) {
        }
        return null;
    }
}
