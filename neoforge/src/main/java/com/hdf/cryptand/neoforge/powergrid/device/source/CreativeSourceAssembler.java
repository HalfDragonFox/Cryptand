/**
 * ===== 原版创造源/创造电阻（PowerGrid electricity.creative） =====
 *
 * CreativeSource：DC 电压源或电流源（getValue + voltageSource 标志）。
 * CreativeResistor 继承 ResistorBlockEntity → 已由 PhasorNetworkBuilder 的
 * ResistorBlockEntity 分支处理（本类不重复）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.source;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class CreativeSourceAssembler implements SourceCacheAssembler {

    /** 有源设备（虚拟建模需临时断开，不能产生无限功率） */
    @Override public boolean isSource() { return true; }

    public static final CreativeSourceAssembler INSTANCE = new CreativeSourceAssembler();

    private CreativeSourceAssembler() {}

    /** 主线程每 tick：读 getValue + voltageSource → 原子写输入槽
     *  （resistance=值，enabled=isVs） */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            Object val = DeviceWire.call(be, "getValue");
            double v = (val instanceof Number n) ? n.doubleValue() : 0;
            Object vsFlag = DeviceWire.field(be, "voltageSource");
            boolean isVs = vsFlag instanceof Boolean b2 && b2;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(v, 0, 0, 0, 0, isVs,
                    old.version + 1));
        } catch (Throwable ignored) {
        }
    }

    /** 后台：从缓存输入槽 stamp 源（原子读，不碰 BE） */
    @Override
    public void stampFromCache(BlockPos pos, DeviceCache cache, int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            boolean isVs = d.enabled;
            double v = d.resistance;
            if (isVs) {
                net.addElement(new DcVoltageSource(a, b, Math.abs(v), 1e-4));
            } else if (v != 0) {
                net.addElement(new CurrentSource(a, b, Math.abs(v)));
            } else {
                net.addElement(new Resistor(a, b, 1e-4));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stamp(BlockEntity be, int a, int b, Network net) {
        try {
            Object val = DeviceWire.call(be, "getValue");
            double v = (val instanceof Number n) ? n.doubleValue() : 0;
            Object vsFlag = DeviceWire.field(be, "voltageSource");
            boolean isVs = vsFlag instanceof Boolean b2 && b2;
            if (isVs) {
                // DC 电压源（AC 相量下自动退化为内阻电阻，见 DcVoltageSource.stampComplex）
                net.addElement(new DcVoltageSource(a, b, Math.abs(v), 1e-4));
            } else if (v != 0) {
                net.addElement(new CurrentSource(a, b, Math.abs(v)));
            } else {
                net.addElement(new Resistor(a, b, 1e-4)); // 零值源 → 近零电阻
            }
        } catch (Throwable ignored) {
        }
    }
}
