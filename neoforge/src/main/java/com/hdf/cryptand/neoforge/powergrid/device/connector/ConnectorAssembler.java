/**
 * ===== 连接器/插座（PowerGrid electricity.socket / wireconnector / deviceconnector） =====
 *
 * Socket / Connector / CordJunction：纯布线（导线连通，并查集已合并）→ 无元件。
 * DeviceConnector / TFMG 兼容连接器：converterWire（ElectricWire）→ 电阻。
 */

package com.hdf.cryptand.neoforge.powergrid.device.connector;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ConnectorAssembler implements SourceCacheAssembler {

    public static final ConnectorAssembler INSTANCE = new ConnectorAssembler();

    private ConnectorAssembler() {}

    /** 主线程每 tick：读 converterWire → 原子写输入槽 */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "converterWire"));
            double r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, 0, 0, 0, 0, true, old.version + 1));
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
        try {
            Object w = DeviceWire.field(be, "converterWire");
            DeviceWire dw = DeviceWire.of(w);
            if (dw.hasResistance() && dw.resistance > 0) {
                net.addElement(new Resistor(a, b, dw.resistance));
            }
            // Socket/Connector/CordJunction：无 converterWire → 直通（导线合并已处理）
        } catch (Throwable ignored) {
        }
    }
}
