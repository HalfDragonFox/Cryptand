package com.hdf.cryptand.neoforge.powergrid.device.motor;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 自家（Cryptand）设备桥（2026-08-24，继承 BE 基类 · 纯数据体消息）：
 * DeviceThermalStore 有温度记录的自有设备（加热器/电阻/灯…）。
 * <p>
 * 固定协议：引擎→BE {@code [powerW]}；BE→引擎 {@code [tempC]}。
 * 温度读/写走 DeviceThermalStore（引擎侧统一温度模型已接管）。
 */
public final class CryptandDeviceParser extends BeMessageParser {

    CryptandDeviceParser(BlockEntity be) {
        super(be);
    }

    /** 热/功率协议（引擎→BE [powerW]） */
    @Override
    public Protocol protocol() { return Protocol.POWER; }

    @Override
    protected void onMessage(Object raw) {
        BeMessage m = BeMessage.of(raw);
        double powerW = m.num(PWR_W);
        if (!Double.isNaN(powerW)) lastPowerW = powerW;
    }

    @Override
    public void serverTick(net.minecraft.world.level.Level level) {
        try {
            sendToEngine(new BeMessage(temperatureC()));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public double temperatureC() {
        try {
            return com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore
                    .thermalFor(pos).tempCelsius();
        } catch (Throwable ignored) {
            return 25;
        }
    }

    @Override
    public void addHeat(double joules) {
        try {
            com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore
                    .thermalFor(pos).addHeat(joules);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String describe() { return "Cryptand-Device"; }
}
