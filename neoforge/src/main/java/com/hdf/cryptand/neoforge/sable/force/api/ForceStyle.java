/**
 * ===== 力显示样式（框架 API，2026-09-14） =====
 *
 * <p>一条【力】的显示属性。注册式设计：任何子系统 / 其它 mod 只要
 * {@link CryptandForceDisplay#registerStyle} 一个样式 + {@link CryptandForceDisplay#registerSource}
 * 一个力源，就能把自己的力接进 KSP 风格的力学可视化（球 + 箭头），无需改动渲染代码。
 *
 * <p>颜色约定（默认表，可被注册覆盖）：重力=黄、气动=粉、浮力=蓝、升力=青、推进=紫。
 *
 * @param color        RGB（0xRRGGBB；渲染时按 alpha 上色）
 * @param sphereRadius 球半径（格；球表示该力的中心）
 * @param arrowScale   箭头长度缩放（1.0 = 默认标定）
 * @param alwaysShow   忽略"最小力阈值"强制显示（默认 false → 力过小自动淡出/不画）
 */
package com.hdf.cryptand.neoforge.sable.force.api;

public record ForceStyle(int color, float sphereRadius, float arrowScale, boolean alwaysShow) {

    /** 默认样式（未注册时的兜底：灰、普通半径、不常显）。 */
    public static final ForceStyle DEFAULT = new ForceStyle(0xAAAAAA, 0.22f, 1.0f, false);

    public static ForceStyle of(final int color) {
        return new ForceStyle(color, 0.22f, 1.0f, false);
    }

    public static ForceStyle of(final int color, final float sphereRadius, final float arrowScale) {
        return new ForceStyle(color, sphereRadius, arrowScale, false);
    }

    public ForceStyle withColor(final int newColor) {
        return new ForceStyle(newColor, this.sphereRadius, this.arrowScale, this.alwaysShow);
    }
}
