/**
 * ===== 灯具（PowerGrid electricity.light.fixture） =====
 * 灯座 = SwitchedWire（getFilament）：灯泡装入 → 闭合（灯丝电阻）；未装/烧断 → 开路。
 * 复合模型：{@link SwitchModel}（常驻可变电阻开关）——装灯泡=灯丝电阻闭合，
 * 未装=大电阻开路。灯泡状态变化（装入/取下/烧断）经 registerParams
 * 读 getFilament → setOn/setResistance → 参数消息 → 重解（不重建网络）。
 * ⚠ 关键：PowerGrid 灯座状态变化不触发 addWire/removeWire（已过滤 SwitchedWire）
 *   → 必须用参数更新而非重建，否则 ctx 缓存旧状态（无灯泡）→ 灯不亮。
 * 灯泡灯丝发热/烧断由 PowerGrid 原版 LightBulbState.tick 处理（filament 功率）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.light;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.SwitchModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class LightAssembler implements SourceCacheAssembler {

    public static final LightAssembler INSTANCE = new LightAssembler();

    private LightAssembler() {}

    /** 原版直流设备：只支持 DC，超频温度惩罚（dcDeviceMaxFrequencyHz） */
    @Override public boolean dcOnly() { return true; }

    /** 复合模型：常驻可变电阻（装灯泡=灯丝R闭合，未装/烧断=大电阻开路）。 */
    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            Object filament = DeviceWire.call(be, "getFilament");
            DeviceWire dw = DeviceWire.of(filament);
            if (dw.hasResistance() && dw.resistance > 0) {
                SwitchModel sm = new SwitchModel(a, b, dw.resistance);
                sm.setOn(dw.enabled); // 灯泡装入且未烧断 → 闭合
                return sm;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读灯丝（getFilament 反射调用） */
    private static DeviceWire filamentOf(BlockEntity be) {
        return DeviceWire.of(DeviceWire.call(be, "getFilament"));
    }

    /** 主线程每 tick：读灯丝 → 原子写输入槽（电阻/开关态） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = filamentOf(be);
            double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, dw.enabled,
                    old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽快照构建 SwitchModel（原子读，不碰 BE） */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            if (d.resistance > 0) {
                SwitchModel sm = new SwitchModel(a, b, d.resistance);
                sm.setOn(d.enabled); // 灯泡装入且未烧断 → 闭合
                return sm;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 参数源：每轮读灯泡状态（getFilament）→ setResistance/setOn →
     *  灯泡装入/取下/烧断 → 参数消息 → 重解（不重建网络）。
     *  2026-08-15 去 level：读 DeviceCache 输入槽（主线程 refreshCache 写）。 */
    @Override
    public void registerParams(BlockPos pos, CompositeModel cm,
                                        List<PhasorNetworkContext.ParamSource> paramSources) {
        if (!(cm instanceof SwitchModel sm)) return;
        final BlockPos p = pos;
        paramSources.add(() -> {
            try {
                DeviceCache c =
                        DeviceCacheRegistry.get(p);
                if (c == null) return;
                DeviceCache.Data d = c.in();
                if (d.resistance > 0) {
                    sm.setResistance(d.resistance); // 更新闭合电阻（灯丝 R，随温度变）
                    sm.setOn(d.enabled);            // 状态：装/未装/烧断
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
