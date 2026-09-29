/**
 * ===== 加热器（PowerGrid electricity.heater） =====
 * 电阻负载：wire（ElectricWire/LRSeriesWire）→ R(+L)。
 * 复合模型：通过【包含】MotorModel（R-L 绕组，基础元件组合）做电路解析 +
 * 温度模型（DeviceThermalStore/ThermalModel：散热/热容/过热，风扇冷却提高散热）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.heater;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class HeaterAssembler implements com.hdf.cryptand.neoforge.powergrid.device.ProxiableAssembler {

    public static final HeaterAssembler INSTANCE = new HeaterAssembler();

    private HeaterAssembler() {}

    /** 原版直流设备：只支持 DC，超频温度惩罚（dcDeviceMaxFrequencyHz） */
    @Override public boolean dcOnly() { return true; }

    /** 加热器是【发热设备】：实现【仅输出】温度扩散模型——主动向【前后方向】
     *  扩散热量（按方块朝向 + 反方向，热风直线传播），基础距离 2 格、衰减 0.2；
     *  被鼓风机/风机吹时扩散距离 +2（热风传播更远）。不接受外部输入（不会被
     *  周围高温反向加热）。 */
    @Override
    public ThermalDiffusionConfig thermalDiffusion() {
        return ThermalDiffusionConfig.output(2.0, 2, 0.2)
                .forwardBackward(true)
                .blownBonus(2);
    }

    /** 读加热器 wire（R/L + 开关态；BE→组装器 Source 方向） */
    private static DeviceWire wireOf(BlockEntity be) {
        return DeviceWire.of(DeviceWire.field(be, "wire"));
    }

    /** ⚠ 2026-08-30 适配【设备接线柱连接】（用户：加热器改经设备接线柱连接）：
     *  wire 字段可能未建立（原版 buildCircuit 经接线柱/端子建 wire，被接管后
     *  可能不触发）→ 回退读 resistance() 方法（原版 buildCircuit 同源），
     *  保证加热器电阻总能取到。 */
    private static double resistanceOf(BlockEntity be) {
        DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "wire"));
        if (dw.hasResistance() && dw.resistance > 0) return dw.resistance;
        try {
            Object v = DeviceWire.call(be, "resistance");
            if (v instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 主线程每 tick：读 wire → 原子写输入槽（电阻/电感/开关态） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            double r = resistanceOf(be);
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, true, old.version + 1));
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
                // 加热器是发热设备：高耐温模型（maxTemp 400°C）
                return new MotorModel(a, b, x, d.resistance, d.inductance,
                        DeviceThermalStore.thermalForHighTemp(pos));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            double r = resistanceOf(be);
            if (r > 0) {
                int x = net.addNode().id;
                // 加热器是发热设备：高耐温模型（maxTemp 400°C）
                return new MotorModel(a, b, x, r, 0,
                        DeviceThermalStore.thermalForHighTemp(be.getBlockPos()));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
