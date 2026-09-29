/**
 * ===== 连接器/插座（PowerGrid electricity.socket / wireconnector / deviceconnector） =====
 *
 * Socket / Connector / CordJunction：纯布线（导线连通，并查集已合并）→ 无元件。
 * DeviceConnector / TFMG 兼容连接器：converterWire（ElectricWire）→ 电阻。
 */

package com.hdf.cryptand.neoforge.powergrid.device.connector;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ConnectorAssembler implements SourceCacheAssembler {

    public static final ConnectorAssembler INSTANCE = new ConnectorAssembler();

    private ConnectorAssembler() {}

    /** 主线程每 tick：读 converterWire → 原子写输入槽。
     *  ⚠ 2026-08-30 设备接线柱（DeviceConnector）【接入相应 BE】（用户：
     *  "BE 接口不为 null 就是有连接"）：facing 设备 BE 的 ElectricBehaviour
     *  非 null → connected（enabled 标记 true，接入设备）；无设备接口 →
     *  断开（false）。Socket/Connector 等纯布线默认连通。 */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "converterWire"));
            double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
            boolean connected = true; // Socket/Connector/CordJunction 默认连通
            if (be instanceof org.patryk3211.powergrid.electricity.deviceconnector
                    .DeviceConnectorBlockEntity) {
                try {
                    net.minecraft.core.Direction facing = be.getBlockState().getValue(
                            org.patryk3211.powergrid.electricity.deviceconnector
                                    .DeviceConnectorBlock.FACING);
                    net.minecraft.core.BlockPos facingPos = be.getBlockPos().relative(facing);
                    connected = com.simibubi.create.foundation.blockEntity.behaviour
                            .BlockEntityBehaviour.get(be.getLevel(), facingPos,
                                    org.patryk3211.powergrid.electricity.base
                                            .ElectricBehaviour.TYPE) != null;
                } catch (Throwable t) {
                    connected = false;
                }
            }
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, connected, old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽 stamp Resistor（原子读，不碰 BE）。
     *  ⚠ 2026-08-30 根因修复（"加热器需要设备连接器才行，设备连接器需要
     *  起作用"）：连接器必须【两端连通】——converterWire 缺失/电阻 0（原版
     *  被接管后未建立）时若不 stamp → 连接器两端断开 → 经它接入的设备
     *  （加热器等）不通电 → 不发热（温度计恒 25°C = 温度"没起作用"）。
     *  0 电阻 → 直通小电阻（1e-4，防 MNA 除零短路）。 */
    @Override
    public void stampFromCache(BlockPos pos, DeviceCache cache, int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            // ⚠ 2026-08-30：enabled = 有连接（DeviceConnector 按 facing BE 接口判；
            // Socket 等默认 true）——无连接 → 断开（不 stamp，悬空由 GMIN 兜底）
            if (a != b && d.enabled) {
                double r = d.resistance > 0 ? d.resistance : 1e-4; // 0 → 直通
                net.addElement(new Resistor(a, b, r));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stamp(BlockEntity be, int a, int b, Network net) {
        try {
            Object w = DeviceWire.field(be, "converterWire");
            DeviceWire dw = DeviceWire.of(w);
            double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 1e-4;
            net.addElement(new Resistor(a, b, r));
        } catch (Throwable ignored) {
        }
    }
}
