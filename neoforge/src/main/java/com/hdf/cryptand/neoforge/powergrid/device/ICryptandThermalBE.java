/**
 * ===== 带温度设备的通用 BE 层（2026-09-13 用户：其他元件类似）=====
 *
 *   ICryptandCircuitBe（BE 基类）
 *     └ ICryptandThermalBE（通用热设备：温度查询 / 过热判定 / 热量注入）
 *
 * 约定（2026-09-12 定稿）：温度一律走自管模型（DeviceThermalStore），
 * 原版 ThermalBehaviour 已退役（工厂返回 null、tick 停用、热量改注入自管模型）。
 */
package com.hdf.cryptand.neoforge.powergrid.device;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;

public interface ICryptandThermalBE extends ICryptandCircuitBe {

    /** 当前温度（°C）：默认查 DeviceThermalStore（组装器建立的那一份） */
    default double cryptandTempC() {
        try {
            if (cryptandSelf() instanceof net.minecraft.world.level.block.entity
                    .BlockEntity be) {
                return com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore
                        .thermalFor(be.getBlockPos()).tempCelsius();
            }
        } catch (Throwable ignored) {
        }
        return 25.0;
    }

    /** 最高安全温度（°C）：默认 200；高耐温设备（加热器等）覆写 400 */
    default double cryptandMaxTempC() {
        return 200.0;
    }

    /** 过热判定 */
    default boolean cryptandOverheated() {
        return cryptandTempC() >= cryptandMaxTempC();
    }

    /** 注入热量（焦耳）到自管温度模型（原版 applyTickPower 的等价换算入口） */
    default void cryptandAddHeat(double joules) {
        try {
            if (cryptandSelf() instanceof net.minecraft.world.level.block.entity
                    .BlockEntity be) {
                com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore
                        .thermalFor(be.getBlockPos()).addHeat(joules);
            }
        } catch (Throwable ignored) {
        }
    }
}
