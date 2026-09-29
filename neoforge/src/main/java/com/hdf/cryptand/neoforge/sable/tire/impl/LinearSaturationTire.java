/**
 * ===== 线性 + 饱和轮胎模型（框架内置第一版，2026-09-14） =====
 *
 * <p>模型（归一化，避免依赖不存在的真实轮胎刚度数据）：
 * <pre>
 *   曲线(x, peak) = |x| ≤ peak ? |x| : peak² / |x|        // 峰值后按 1/x 衰减（抓地饱和）
 *   Fy = −Cy · 曲线(α, peakα) · N
 *   Fx =  Cx · 曲线(κ, peakκ) · N  − Crr · sign(v_x) · N
 *   摩擦圆：√(Fx² + Fy²) > μN → 等比缩放
 * </pre>
 *
 * <p>物理直觉：小滑移线性响应（轮胎刚度）→ 峰值（最佳抓地）→ 过峰值力下降（打滑）。
 * 摩擦圆保证"纵向用掉的抓地力会挤占侧向"（推头/甩尾的物理来源）。
 */
package com.hdf.cryptand.neoforge.sable.tire.impl;

import com.hdf.cryptand.neoforge.sable.tire.api.TireForces;
import com.hdf.cryptand.neoforge.sable.tire.api.TireInput;
import com.hdf.cryptand.neoforge.sable.tire.api.TireModel;
import com.hdf.cryptand.neoforge.sable.tire.api.TireParams;

public final class LinearSaturationTire implements TireModel {

    public static final LinearSaturationTire INSTANCE = new LinearSaturationTire();

    @Override
    public String id() {
        return "cryptand:linear_saturation";
    }

    @Override
    public TireForces solve(final TireInput in, final TireParams p) {
        final double n = in.normalLoad();
        if (n <= 1.0e-6) return TireForces.ZERO;

        // 侧向：Fy = −Cy · 曲线(α) · N
        final double fy = -p.lateralStiffness() * curve(in.slipAngleRad(), p.peakSlipAngleRad()) * n;

        // 纵向：驱动/制动 Fx = Cx · 曲线(κ) · N；再叠加滚阻（与纵向速度反向）
        double fx = p.longitudinalStiffness() * curve(in.slipRatio(), p.peakSlipRatio()) * n;
        if (Math.abs(in.forwardSpeed()) > 1.0e-3) {
            fx -= Math.signum(in.forwardSpeed()) * p.rollingResistance() * n;
        }

        // 摩擦圆裁剪：√(Fx²+Fy²) ≤ μN（μ = 地面 μ × 轮胎修正）
        final double mu = Math.max(0.0, in.groundMu() * p.muScale());
        final double limit = mu * n;
        final double mag = Math.hypot(fx, fy);
        if (limit <= 1.0e-9) return TireForces.ZERO;
        if (mag > limit) {
            final double k = limit / mag;
            fx *= k;
            final double fyScaled = fy * k;
            return new TireForces(fx, fyScaled, 0.0);
        }
        return new TireForces(fx, fy, 0.0);
    }

    /** 峰值前线性、峰值后按 peak²/|x| 衰减（并在 |x|→0 时连续）。 */
    private static double curve(final double x, final double peak) {
        final double ax = Math.abs(x);
        if (ax < 1.0e-9) return 0.0;
        final double pk = Math.max(peak, 1.0e-4);
        final double f = (ax <= pk) ? ax : (pk * pk / ax);
        return (x < 0.0) ? -f : f;
    }
}
