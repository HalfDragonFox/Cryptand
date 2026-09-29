/**
 * ===== 力样本（框架 API，2026-09-14） =====
 *
 * <p>一次采集（每 tick / 每力源）得到的【一条力】：力中心（世界坐标）+ 力矢量（世界坐标）。
 *
 * <p>两种粒度（对应两档显示模式）：
 * <ul>
 *   <li>{@link Kind#RESULTANT}：一个力组的【合力 + 力加权中心】——简化模式用</li>
 *   <li>{@link Kind#POINT}：一个具体力源（螺旋桨/悬浮方块簇/轮子…）的力与作用点——详细模式用</li>
 * </ul>
 *
 * <p>坐标为【世界坐标】（服务端已用 logicalPose 变换），客户端直接渲染，不再做任何坐标换算。
 *
 * @param id     力标识（ResourceLocation，如 {@code sable:lift} / {@code cryptand:gravity}）
 * @param kind   粒度
 * @param cx,cy,cz 力中心（世界坐标）
 * @param fx,fy,fz 力矢量（世界坐标；单位与官方力组一致，渲染时按标定缩放）
 */
package com.hdf.cryptand.neoforge.sable.force.api;

import net.minecraft.resources.ResourceLocation;

public record ForceSample(ResourceLocation id, Kind kind,
                          double cx, double cy, double cz,
                          double fx, double fy, double fz) {

    public enum Kind {
        /** 合力 + 力加权中心（简化模式：每个力组一个球）。 */
        RESULTANT,
        /** 具体力源的点力（详细模式：每个力源一个小球 + 箭头）。 */
        POINT
    }

    public double magnitude() {
        return Math.sqrt(this.fx * this.fx + this.fy * this.fy + this.fz * this.fz);
    }
}
