/**
 * ===== 外设模拟传动器局部模型（客户端，2026-09-14）=====
 *
 * 齿轮不参与 blockstate（外壳才参与），由 BER 或 Flywheel 单独渲染 —— 与航空学官方
 * `SimPartialModels.ANALOG_TRANSMISSION_COG` 一致。
 * 模型文件：assets/cryptand/models/block/peripheral_transmission/gear.json（从原版复制）
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

public final class PeripheralTransmissionPartialModels {

    /** 附加动力学节点（内部齿轮）的模型 */
    public static final ResourceLocation COG_ID = id("gear");

    public static final PartialModel COG = PartialModel.of(COG_ID);

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("cryptand", "block/peripheral_transmission/" + name);
    }

    private PeripheralTransmissionPartialModels() {
    }
}
