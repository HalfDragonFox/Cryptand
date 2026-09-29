package com.hdf.cryptand.neoforge.powergrid.device.motor.parser;

import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
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

    /** 设备位置（2026-09-12：不再持有原版 ThermalBehaviour —— "温度全部走自管"，
     *  原版对象将不再创建，温度一律查 Cryptand 温度模型）。 */
    private final net.minecraft.core.BlockPos pos;

    ThermalDeviceParser(BlockEntity be) {
        super(be);
        this.pos = be.getBlockPos();
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
        // 2026-09-12 用户："温度全部走自管"——原先这里把【原版 ThermalBehaviour】
        // 的温度作为 BE→引擎的 [tempC] 输入；原版路径已停推（tick 被取消），
        // 继续发只会用冻结假值污染引擎输入。温度由 DeviceThermalStore 统一提供，
        // 不再需要该消息。
    }

    @Override
    public double temperatureC() {
        // 查组装器建立的自管温度模型（与温度计/过热判定同一份）
        try {
            return com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore
                    .thermalFor(pos).tempCelsius();
        } catch (Throwable ignored) {
            return 25;
        }
    }

    @Override
    public String describe() { return "PG-Thermal"; }
}
