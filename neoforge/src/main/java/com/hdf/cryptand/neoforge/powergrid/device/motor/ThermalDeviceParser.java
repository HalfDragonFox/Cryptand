package com.hdf.cryptand.neoforge.powergrid.device.motor;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * PowerGrid 通用热设备桥（2026-08-24，继承 BE 基类 · 纯数据体消息）：
 * 加热器/电阻/灯泡等带 ThermalBehaviour 的设备。
 * <p>
 * 固定协议：引擎→BE {@code [powerW]}（{@link BeMessageParser#PWR_W}=0）；
 * BE→引擎 {@code [tempC]}（{@link BeMessageParser#TH_TEMP}=0）。
 * ⚠ 写入策略：第三方 thermal 由 PowerGrid 线圈功率驱动，直接写会冲突——
 * 当前 onMessage 只记录（引擎侧温度已由统一温度模型推进）。
 */
public final class ThermalDeviceParser extends BeMessageParser {

    private final org.patryk3211.powergrid.electricity.base.ThermalBehaviour thermal;

    ThermalDeviceParser(BlockEntity be, Object thermalBehaviour) {
        super(be);
        this.thermal = (org.patryk3211.powergrid.electricity
                .base.ThermalBehaviour) thermalBehaviour;
    }

    /** 热/功率协议（引擎→BE [powerW]） */
    @Override
    public Protocol protocol() { return Protocol.POWER; }

    @Override
    protected void onMessage(Object raw) {
        BeMessage m = BeMessage.of(raw);
        // 引擎→BE：[powerW]（强转；写入策略待启用——见类注释）
        double powerW = m.num(PWR_W);
        if (!Double.isNaN(powerW)) lastPowerW = powerW;
    }

    @Override
    public void serverTick(net.minecraft.world.level.Level level) {
        // BE→引擎：[tempC]（固定协议强转；组装器 pollInput 读）
        try {
            sendToEngine(new BeMessage(thermal.getTemperature()));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public double temperatureC() {
        try { return thermal.getTemperature(); } catch (Throwable ignored) { return 25; }
    }

    @Override
    public String describe() { return "PG-Thermal"; }
}
