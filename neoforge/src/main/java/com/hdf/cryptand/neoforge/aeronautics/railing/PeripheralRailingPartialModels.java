/**
 * ===== 外设拉杆局部模型（客户端，2026-09-13） =====
 *
 * 拉杆的手柄 / 按钮 / 指示灯不参与 blockstate（底座才参与），由 BER 或 Flywheel 单独渲染
 * 并按档位旋转/染色 —— 与航空学官方 `SimPartialModels.THROTTLE_LEVER_{HANDLE,BUTTON,DIODE}` 一致。
 * 模型文件：assets/cryptand/models/block/peripheral_railing/{handle,button,diode}.json
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

public final class PeripheralRailingPartialModels {

    public static final ResourceLocation HANDLE_ID = id("handle");
    public static final ResourceLocation BUTTON_ID = id("button");
    public static final ResourceLocation DIODE_ID = id("diode");

    public static final PartialModel HANDLE = PartialModel.of(HANDLE_ID);
    public static final PartialModel BUTTON = PartialModel.of(BUTTON_ID);
    public static final PartialModel DIODE = PartialModel.of(DIODE_ID);

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("cryptand", "block/peripheral_railing/" + name);
    }

    private PeripheralRailingPartialModels() {
    }
}
