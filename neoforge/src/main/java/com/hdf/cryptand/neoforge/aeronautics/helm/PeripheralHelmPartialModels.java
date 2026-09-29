/**
 * ===== 外设船舵局部模型（客户端，2026-09-13） =====
 *
 * 舵轮（wheel.obj）不参与 blockstate——方块模型只有底座（block / block_up），
 * 舵轮由 {@link PeripheralHelmRenderer} 单独渲染并按舵角旋转。
 * 模型文件：assets/cryptand/models/block/peripheral_helm/wheel.json（neoforge:obj）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

public final class PeripheralHelmPartialModels {

    /** 舵轮模型 id（额外模型注册用；见 PeripheralHelmClient#onRegisterAdditional） */
    public static final ResourceLocation WHEEL_ID =
            ResourceLocation.fromNamespaceAndPath("cryptand", "block/peripheral_helm/wheel");

    /** 舵轮局部模型（旋转渲染用） */
    public static final PartialModel WHEEL = PartialModel.of(WHEEL_ID);

    private PeripheralHelmPartialModels() {
    }
}
