package com.hdf.cryptand.neoforge.railway;

import com.hdf.cryptand.neoforge.cee.CeeTerminalSupport;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * 受电弓 / 接触网 局部模型（移植自 CEE CEEPartialModels，命名空间切到 cryptand）。
 * 模型文件在 assets/cryptand/models/block/cee_pantograph、cee_catenary_holder。
 * 仅客户端引用（渲染器 / contraption 渲染），服务端不得加载本类。
 */
public final class RailwayPartialModels {

    public static final PartialModel PANTOGRAPH_BASE = PartialModel.of(mcLoc("cee_pantograph/base"));
    public static final PartialModel PANTOGRAPH_UPPER_ARM = PartialModel.of(mcLoc("cee_pantograph/upper_arm"));
    public static final PartialModel PANTOGRAPH_UPPER_ARM_ARM = PartialModel.of(mcLoc("cee_pantograph/upper_arm_arm"));
    public static final PartialModel PANTOGRAPH_LOWER_ARM = PartialModel.of(mcLoc("cee_pantograph/lower_arm"));
    public static final PartialModel PANTOGRAPH_BASE_DOUBLE = PartialModel.of(mcLoc("cee_pantograph/base_double"));
    public static final PartialModel PANTOGRAPH_UPPER_ARMS_DOUBLE = PartialModel.of(mcLoc("cee_pantograph/upper_arms_double"));
    public static final PartialModel PANTOGRAPH_LOWER_ARMS_DOUBLE = PartialModel.of(mcLoc("cee_pantograph/lower_arms_double"));
    public static final PartialModel PANTOGRAPH_CONNECTING_SURFACE = PartialModel.of(mcLoc("cee_pantograph/connecting_surface"));
    public static final PartialModel PANTOGRAPH_CONNECTING_SURFACE_DOUBLE = PartialModel.of(mcLoc("cee_pantograph/connecting_surface_double"));
    public static final PartialModel PANTOGRAPH_CONNECTING_ROD = PartialModel.of(mcLoc("cee_pantograph/connecting_rod"));
    public static final PartialModel PANTOGRAPH_SPRINGS = PartialModel.of(mcLoc("cee_pantograph/springs"));
    public static final PartialModel PANTOGRAPH_SPRINGS_DOUBLE = PartialModel.of(mcLoc("cee_pantograph/springs_double"));

    public static final PartialModel CATENARY_HOLDER_INSULATOR = PartialModel.of(mcLoc("cee_catenary_holder/insulator"));
    public static final PartialModel CATENARY_HOLDER_LONG_ROD = PartialModel.of(mcLoc("cee_catenary_holder/long_rod"));
    public static final PartialModel CATENARY_HOLDER_SHORT_ROD = PartialModel.of(mcLoc("cee_catenary_holder/short_rod"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_4 = PartialModel.of(mcLoc("cee_catenary_holder/mount_4"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_6 = PartialModel.of(mcLoc("cee_catenary_holder/mount_6"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_8 = PartialModel.of(mcLoc("cee_catenary_holder/mount_8"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_10 = PartialModel.of(mcLoc("cee_catenary_holder/mount_10"));
    public static final PartialModel CATENARY_HOLDER_CONNECTOR = PartialModel.of(mcLoc("cee_catenary_holder/connector"));
    public static final PartialModel CATENARY_HOLDER_INSULATOR_WEATHERED = PartialModel.of(mcLoc("cee_catenary_holder/weathered_insulator"));
    public static final PartialModel CATENARY_HOLDER_LONG_ROD_WEATHERED = PartialModel.of(mcLoc("cee_catenary_holder/weathered_long_rod"));
    public static final PartialModel CATENARY_HOLDER_SHORT_ROD_WEATHERED = PartialModel.of(mcLoc("cee_catenary_holder/weathered_short_rod"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_4_WEATHERED = PartialModel.of(mcLoc("cee_catenary_holder/weathered_mount_4"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_6_WEATHERED = PartialModel.of(mcLoc("cee_catenary_holder/weathered_mount_6"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_8_WEATHERED = PartialModel.of(mcLoc("cee_catenary_holder/weathered_mount_8"));
    public static final PartialModel CATENARY_HOLDER_MOUNT_10_WEATHERED = PartialModel.of(mcLoc("cee_catenary_holder/weathered_mount_10"));

    private RailwayPartialModels() {
    }

    /**
     * 模型源（2026-08-23 用户：直接搬 CEE 渲染方式）——CEE mod 已加载时
     * PartialModel 直接用【CEE 原版】模型/贴图（electroenergetics:block/
     * pantograph|/catenary_holder/*），渲染 100% 与 CEE 一致；CEE 未装 → 回退
     * 自研（assets/cryptand/...）。
     */
    private static ResourceLocation mcLoc(String path) {
        try {
            if (CeeTerminalSupport.ceeModLoaded()) {
                if (path.startsWith("cee_pantograph/"))
                    return ResourceLocation.fromNamespaceAndPath("electroenergetics",
                            "block/pantograph/" + path.substring("cee_pantograph/".length()));
                if (path.startsWith("cee_catenary_holder/"))
                    return ResourceLocation.fromNamespaceAndPath("electroenergetics",
                            "block/catenary_holder/" + path.substring("cee_catenary_holder/".length()));
            }
        } catch (Throwable ignored) {
        }
        return ResourceLocation.fromNamespaceAndPath("cryptand", "block/" + path);
    }
}