/**
 * ===== 悬垂导线渲染器（2026-08-14 注册器：渲染参数统一入口） =====
 *
 * 把 {@link SaggingWireType} 的渲染参数提供给导线渲染层：
 *   - 【材质注册】：texture 非 null → 使用注册的材质贴图
 *   - 【纯色 + 指定颜色代码注册（默认方式）】：texture 为 null 或未指定 →
 *     纯色模式（白色混凝土贴图 + 顶点/实例染色为 color 颜色代码）
 *   - 【悬垂率等参数】：sag（悬垂率）/ thickness（粗细）
 *
 * 渲染链路：注册器 → SaggingWireRenderer 解析 → CryptandWireEffect
 * （color/texture/dip）→ CryptandWireVisual / CryptandWireModel 管状网格。
 */

package com.hdf.cryptand.neoforge.powergrid.device.wire;

import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import net.minecraft.resources.ResourceLocation;

public final class SaggingWireRenderer {

    /** 纯色模式默认贴图（白色混凝土，染成导线颜色代码） */
    public static final ResourceLocation SOLID_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");

    private SaggingWireRenderer() {
    }

    /** 渲染材质：有材质用材质；无（纯色模式）→ 白色混凝土（染 color） */
    public static ResourceLocation textureOf(SaggingWireType t) {
        if (t == null || t.texture() == null) return SOLID_TEXTURE;
        return t.texture();
    }

    /** 渲染颜色（ARGB）：注册的颜色代码；未注册默认白（不染色） */
    public static int colorOf(SaggingWireType t) {
        return t == null ? 0xFFFFFFFF : t.color();
    }

    /** 悬垂率（渲染 dip） */
    public static float sagOf(SaggingWireType t) {
        return t == null ? 2.0f : t.sag();
    }

    /** 粗细（格；原版 0.0625 / 铁 0.125） */
    public static float thicknessOf(SaggingWireType t) {
        return t == null ? 0.0625f : t.thickness();
    }
}
