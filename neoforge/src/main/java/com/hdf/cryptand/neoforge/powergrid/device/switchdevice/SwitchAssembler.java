/**
 * ===== 开关类设备（PowerGrid electricity.electricswitch / contactor / fuse） =====
 *
 * Switch / HvSwitch / HvBreaker / Contactor / FuseHolder：开关触点。
 * 复合模型 {@link SwitchModel}：常驻【可变电阻】（闭合=触点电阻，断开=1e9Ω
 * 近似开路）→ 开关切换走【参数更新】重解（不重建网络）。配合
 * ElectricalNetworkMixin 过滤 SwitchedWire 的 add/remove → 高频开关不再
 * 每次整体重建（局部重解 + 参数更新，性能与电压稳定性双赢）。
 *
 * 注意：开关不是导线，两端靠元件连接；断开时用大电阻近似开路（不是不加
 * 元件）→ 电路结构不变 → 只重解不重建。
 */

package com.hdf.cryptand.neoforge.powergrid.device.switchdevice;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.SwitchModel;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class SwitchAssembler implements SourceCacheAssembler {

    public static final SwitchAssembler INSTANCE = new SwitchAssembler();

    private SwitchAssembler() {}

    /** 读开关当前 wire（fuseWire / switch1 / wire，按类字段顺序）。 */
    private static DeviceWire wireOf(BlockEntity be) {
        Object w = DeviceWire.field(be, "fuseWire");
        if (w == null) w = DeviceWire.field(be, "switch1");
        if (w == null) w = DeviceWire.field(be, "wire");
        return DeviceWire.of(w);
    }

    // ===== 缓存化分支（2026-08-15 用户设计：SourceCacheAssembler，BE→组装器） =====
    // 开关状态是动态量：主线程 refreshCache 读 BE → 原子写【输入槽】；后台组装器
    // assembleFromCache 原子读输入槽构建（不碰 BE）。

    /** 主线程每 tick：读开关 wire → 原子写输入槽（电阻 + 开关态） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = wireOf(be);
            double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, dw.enabled,
                    old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽快照构建开关模型（原子读，不碰 BE） */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            if (d.resistance > 0) {
                SwitchModel sm = new SwitchModel(a, b, d.resistance);
                sm.setOn(d.enabled);
                return sm;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 复合模型：常驻可变电阻（闭合=触点电阻，断开=1e9Ω 近似开路）。 */
    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            DeviceWire dw = wireOf(be);
            if (dw.hasResistance() && dw.resistance > 0) {
                SwitchModel sm = new SwitchModel(a, b, dw.resistance);
                sm.setOn(dw.enabled);
                return sm;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 参数源：每轮读开关状态 → setResistance/setOn →
     *  内部 Resistor 值变 → 参数变化消息 → ctx.paramVersion++ → 重解（不重建）。
     *  2026-08-15 去 level：读 DeviceCache 输入槽（主线程 refreshCache 写）。 */
    @Override
    public void registerParams(BlockPos pos, CompositeModel cm,
                                        List<PhasorNetworkContext.ParamSource> paramSources) {
        if (!(cm instanceof SwitchModel sm)) return;
        final BlockPos p = pos;
        paramSources.add(() -> {
            try {
                com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache c =
                        com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCacheRegistry.get(p);
                if (c == null) return;
                com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache.Data d = c.in();
                if (d.resistance > 0) {
                    sm.setResistance(d.resistance);
                    sm.setOn(d.enabled);
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
