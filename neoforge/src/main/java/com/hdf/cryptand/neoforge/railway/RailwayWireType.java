package com.hdf.cryptand.neoforge.railway;

import com.hdf.cryptand.neoforge.cee.CeeTerminalSupport;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;

/**
 * 接触网导线类型注册（2026-08-22 "CEE 全自管电路"）。
 * <p>
 * 接触网段 = holder↔holder 间的一条 Cryptand 自管导线，rendererId = "catenary"：
 *   - 无下垂（sag=0，接触网绷紧直线）
 *   - 纯色银白渲染（可后续换专用贴图）
 *   - 低电阻（0.001Ω/m）+ 高额定电流（200A）
 *   - 无实际物品绑定（接触网不是可捡导线；由 holder 交互产生）
 */
public final class RailwayWireType {

    private static volatile boolean registered = false;

    public static void ensureRegistered() {
        if (registered) return;
        registered = true;
        try {
            SaggingWireType catenary = SaggingWireType.builder(CeeTerminalSupport.CATENARY_RENDERER_ID)
                    .sag(0f)                 // 绷紧，无下垂
                    .thickness(0.03125f)     // 1/32 格，细
                    .color(0xFFB8B8C0)       // 银白色
                    .resistancePerMeter(0.001)
                    .itemsPerMeter(0f)
                    .maximumLength(96f)
                    .maximumCurrent(200f)
                    .build();
            SaggingWireRegistry.register(catenary);
        } catch (Throwable ignored) {
        }
    }

    private RailwayWireType() {
    }
}