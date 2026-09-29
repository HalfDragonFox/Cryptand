/**
 * ===== 仪表（PowerGrid electricity.gauge） =====
 * 三种表计内部都有一条 ElectricWire（真实内阻，PowerGrid 注册值）：
 *   - 电流表 CurrentGaugeBlockEntity：字段 "wire"    = 0.05Ω（安培表低阻串联）
 *   - 电压表 VoltageGaugeBlockEntity：字段 "connection" = 量程电阻（2kV 档 20MΩ）
 *   - 功率表 PowerGaugeBlockEntity：字段 "series"=0.05Ω + "shunt"=20MΩ（3 端子，
 *     shunt 由 PhasorNetworkBuilder 单独处理，本接口只有两端口）
 *
 * ⚠ 必须按方块类型读【实际内部 wire】的电阻：之前统一读 "connection"，电流表
 * （无该字段）落入 1e4Ω 兜底 → MNA 用 1e4Ω 求解，而 PowerGrid 内部 wire 实际是
 * 0.05Ω → 求解电路与实际不一致 → 写回电压在低阻内部 wire 上产生虚假巨大电流
 * （[WireBurn] i=354.3A diff=17.7V）→ 过热爆炸。
 */

package com.hdf.cryptand.neoforge.powergrid.device.gauge;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class GaugeAssembler implements SourceCacheAssembler {

    public static final GaugeAssembler INSTANCE = new GaugeAssembler();

    private GaugeAssembler() {}

    /** 按表计类型选内部 wire 字段（电流表 wire / 电压表 connection / 功率表 series） */
    private static String fieldOf(BlockEntity be) {
        String cls = com.hdf.cryptand.neoforge.powergrid.device.Assemblers.beKey(be);
        if ("CurrentGaugeBlockEntity".equals(cls)) return "wire";
        if ("VoltageGaugeBlockEntity".equals(cls)) return "connection";
        return "series";
    }

    /** 主线程每 tick：读内部 wire 电阻 → 原子写输入槽 */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            if (be == null) return;
            double r = DeviceWire.wireResistance(be, fieldOf(be));
            if (r > 0) {
                DeviceCache.Data old = cache.in();
                cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, true,
                        old.version + 1));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽 stamp Resistor（原子读，不碰 BE） */
    @Override
    public void stampFromCache(BlockPos pos, DeviceCache cache, int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            if (d.resistance > 0 && a != b) {
                net.addElement(new Resistor(a, b, d.resistance));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stamp(BlockEntity be, int a, int b, Network net) {
        if (be == null || a == b) return;
        String cls = com.hdf.cryptand.neoforge.powergrid.device.Assemblers.beKey(be);
        String field;
        if ("CurrentGaugeBlockEntity".equals(cls)) {
            field = "wire";       // 安培表：低阻串联（实测 0.05Ω）
        } else if ("VoltageGaugeBlockEntity".equals(cls)) {
            field = "connection"; // 伏特表：量程高阻（2kV 档 20MΩ）
        } else {
            field = "series";     // 功率表串联支路（shunt 由 builder 处理）
        }
        double r = DeviceWire.wireResistance(be, field);
        if (r > 0) {
            net.addElement(new Resistor(a, b, r));
        } else {
            // 内部 wire 未就绪（buildCircuit 未跑/字段异常）→ 不建模并告警，
            // 暴露真实问题（不用兜底电阻掩盖，否则求解电路与实际不符 → 虚假电流）。
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[Gauge] {} internal wire '{}' unavailable (r<=0), gauge not modeled",
                    cls, field);
        }
    }
}
