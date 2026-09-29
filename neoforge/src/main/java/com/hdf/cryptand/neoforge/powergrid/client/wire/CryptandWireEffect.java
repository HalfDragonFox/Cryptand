/**
 * ===== 导线渲染效果（Flywheel Effect，2026-08-13 CEE 方式） =====
 *
 * 一条自管导线的渲染数据：两端点（世界坐标）+ 下垂系数 + 颜色。
 * 由 WireRenderManager 从自管 WireGraph 的 WireEdge 生成（转换而非接管——
 * 原版实体导线仍存在，本效果并行渲染替代原版视觉）。
 * <p>
 * 数据来源（转换链路）：
 *   WireGraphStore（自管图）→ WireRenderManager 每 tick 同步 → 本 Effect
 *   → CryptandWireVisual（Flywheel 实例化渲染）。
 */
package com.hdf.cryptand.neoforge.powergrid.client.wire;

import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

public final class CryptandWireEffect implements Effect {

    public final LevelAccessor level;
    /** 端点初始位置（servers 同步快照；视觉每 tick 按身份现算覆盖） */
    public final Vec3 pos1;
    public final Vec3 pos2;
    /** 端点身份（块坐标 + 端子索引）：渲染层每 tick 用它现算真实世界位置
     *  （2026-08-23 物理化跟随——位置不固定，身份固定）。 */
    public final int ax, ay, az, aTerm;
    public final int bx, by, bz, bTerm;
    /** 下垂系数（原版风格，默认 2） */
    public final float dip;
    /** 颜色（0xRRGGBB） */
    public final int color;
    /** 原版导线贴图路径（空/null = 默认） */
    public final String texture;

    public CryptandWireEffect(LevelAccessor level, Vec3 pos1, Vec3 pos2, float dip, int color) {
        this(level, pos1, pos2, dip, color, null);
    }

    public CryptandWireEffect(LevelAccessor level, Vec3 pos1, Vec3 pos2, float dip, int color,
                              String texture) {
        this(level, pos1, pos2, -1, dip, color, texture);
    }

    /** 无身份版本（jvm 编译兼容；identity 由 WireRenderManager 设置 —— 见 next ctor） */
    public CryptandWireEffect(LevelAccessor level, Vec3 pos1, Vec3 pos2,
                              int identityVersion, float dip, int color, String texture) {
        this(level, pos1, pos2, 0, 0, 0, -1, 0, 0, 0, -1, dip, color, texture);
    }

    public CryptandWireEffect(LevelAccessor level, Vec3 pos1, Vec3 pos2,
                              int ax, int ay, int az, int aTerm,
                              int bx, int by, int bz, int bTerm,
                              float dip, int color, String texture) {
        this.level = level;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.ax = ax; this.ay = ay; this.az = az; this.aTerm = aTerm;
        this.bx = bx; this.by = by; this.bz = bz; this.bTerm = bTerm;
        this.dip = dip;
        this.color = color;
        this.texture = texture;
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new CryptandWireVisual(ctx, this);
    }

    /** 与另一效果数据是否一致（用于 diff：端点身份/下垂/颜色/贴图变化 → 重建；
     *  端点【位置】由视觉每 tick 现算，不作为 diff 依据——否则物理化结构移动
     *  时每次位置变化都触发整线重建/移除。2026-08-23 修改）。 */
    public boolean matches(CryptandWireEffect o) {
        return o != null
                && dip == o.dip
                && color == o.color
                && java.util.Objects.equals(texture, o.texture)
                && ax == o.ax && ay == o.ay && az == o.az && aTerm == o.aTerm
                && bx == o.bx && by == o.by && bz == o.bz && bTerm == o.bTerm;
    }
}
