package com.hdf.cryptand.neoforge.powergrid.device.motor.parser;

import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * CEE（george_vi）通用设备桥（2026-08-24，继承 BE 基类 · 纯数据体消息）：
 * DeviceBlock / ElectricalDeviceBlock 通用设备（加热器/灯/泵…）。
 * <p>
 * 固定协议：引擎→BE {@code [powerW]}；BE→引擎 {@code [tempC, powerW]}。
 * 反射安全（CEE 未装不会类加载错误）；写入待启用。
 */
public final class CeeDeviceParser extends BeMessageParser {

    CeeDeviceParser(BlockEntity be) {
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
        // 2026-09-13 用户架构铁律："主线程永远是被动端……必须遵循主线程接收到信息后
        // 才允许发送一次，不能主动发送" —— 上报已搬到 replyAfterMessage()。
    }

    /** 被动回复：收到引擎消息后回发一次 [温度, 功率] */
    @Override
    protected void replyAfterMessage() {
        try {
            sendToEngine(new BeMessage(temperatureC(), powerConsumedW()));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public double temperatureC() {
        // 2026-09-12 用户："温度全部走自管"——不再读原版 ThermalBehaviour（已停推）
        try {
            return com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore
                    .thermalFor(be.getBlockPos()).tempCelsius();
        } catch (Throwable ignored) {
            return 25;
        }
    }

    @Override
    public double powerConsumedW() {
        try {
            Object ec = reflectField(be, "electricBehaviour");
            if (ec == null) ec = reflectField(be, "electric");
            if (ec != null) {
                java.lang.reflect.Method m = ec.getClass().getMethod("power");
                Object v = m.invoke(ec);
                if (v instanceof Number n) return n.doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return Double.NaN;
    }

    @Override
    public String describe() { return "CEE-Device"; }
}
