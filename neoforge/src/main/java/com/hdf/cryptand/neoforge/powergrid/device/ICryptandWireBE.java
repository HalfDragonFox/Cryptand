/**
 * ===== 导线通用 BE 层（2026-09-13 用户：其他元件类似）=====
 *
 *   ICryptandCircuitBe（BE 基类）
 *     └ ICryptandWireBE（通用导线：额定电流 / 段温度 / 过热）
 *         具体导线继承本接口：额定电流与烧毁语义按材质给出。
 *
 * 与热模型的约定（2026-09-12 定稿）：发热按完整 I²R 公式（不扣减额定），
 * 散热按额定功率标定（WireThermalStore.RATED_RISE_C）——额定电流下温升恒定。
 */
package com.hdf.cryptand.neoforge.powergrid.device;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.state.WireThermalStore;

public interface ICryptandWireBE extends ICryptandCircuitBe {

    /** 额定电流（A）：默认 80（原版默认安全值）；具体导线覆写（金 160 / 铜 200…） */
    default double cryptandRatedCurrent() {
        return 80.0;
    }

    /** 导线段当前温度（°C）：默认查自管导线温度存储；无则 NaN */
    default double cryptandSegmentTempC() {
        try {
            if (cryptandSelf() instanceof net.minecraft.world.level.block.entity
                    .BlockEntity be) {
                var mgr = com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager
                        .get();
                if (mgr != null) {
                    String key = mgr.segmentKeyAtBlock(be.getBlockPos());
                    if (key != null) {
                        return com.hdf.cryptand.neoforge.powergrid.state.WireThermalStore.thermalFor(key).tempCelsius();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return Double.NaN;
    }

    /** 是否已达到烧毁阈值（默认 200°C，与 PhasorEngine.WIRE_BURN_TEMP_C 一致） */
    default boolean cryptandWireOverheated() {
        double t = cryptandSegmentTempC();
        return Double.isFinite(t) && t >= 200.0;
    }
}
