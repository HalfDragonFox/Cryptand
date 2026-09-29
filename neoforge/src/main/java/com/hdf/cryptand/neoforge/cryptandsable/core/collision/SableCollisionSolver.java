package com.hdf.cryptand.neoforge.cryptandsable.core.collision;

import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.RigidBodyState;
import com.hdf.cryptand.neoforge.cryptandsable.core.worker.SableSimulationContext;
import org.joml.Vector3d;

import java.util.List;

/**
 * 接触求解器（SableCollisionSolver）。
 *
 * <p>对已检测到的接触对应用冲量响应（简单的系数恢复冲量法）：
 * 沿接触法线交换动量、分离重叠位置（位置修正），使物体不再穿透。
 * 高频小步进下每次只需轻微修正即可自收敛（对齐 C14）。
 *
 * <p>求解发生在 worker 线程，只修改核心持有的模拟状态。
 */
public final class SableCollisionSolver {

    /** 恢复系数（0=完全非弹性，1=完全弹性）；MVP 固定值。 */
    private static final double RESTITUTION = 0.2;
    /** 位置修正比例（0..1，防过冲震荡）。 */
    private static final double POSITION_CORRECTION = 0.4;

    private SableCollisionSolver() {
    }

    /** 对接触列表求解（修改两体速度与位置）。 */
    public static void solve(SableSimulationContext ctx, List<Contact> contacts) {
        for (Contact c : contacts) {
            RigidBodyState a = ctx.getBody(c.bodyA());
            RigidBodyState b = ctx.getBody(c.bodyB());
            if (a == null || b == null) continue;
            if (a.isSleeping() && b.isSleeping()) continue;

            // 若已分离（相对速度向外）无需冲量
            double relSpeed = c.relativeSpeed();
            if (relSpeed >= 0) {
                positionalCorrection(ctx, a, b, c);
                continue; // 已在分离，只修正穿透
            }

            // 冲量：j = -(1+e) * v_rel·n / (invMassA + invMassB)
            double invMassA = a.isSleeping() ? 0 : a.invMass();
            double invMassB = b.isSleeping() ? 0 : b.invMass();
            double invSum = invMassA + invMassB;
            if (invSum <= 1e-9) continue;

            double j = -(1 + RESTITUTION) * relSpeed / invSum;
            Vector3d impulse = new Vector3d(c.normal()).mul(j);

            if (!a.isSleeping()) a.applyImpulse(impulse);
            if (!b.isSleeping()) b.applyImpulse(new Vector3d(impulse).negate());

            positionalCorrection(ctx, a, b, c);
        }
    }

    private static void positionalCorrection(SableSimulationContext ctx, RigidBodyState a, RigidBodyState b, Contact c) {
        double invMassA = a.invMass();
        double invMassB = b.invMass();
        double invSum = invMassA + invMassB;
        if (invSum <= 1e-9) return;
        double percent = POSITION_CORRECTION * c.penetration() / invSum;
        if (!a.isSleeping()) a.position.fma(-invMassA * percent, c.normal());
        if (!b.isSleeping()) b.position.fma(invMassB * percent, c.normal());
    }
}