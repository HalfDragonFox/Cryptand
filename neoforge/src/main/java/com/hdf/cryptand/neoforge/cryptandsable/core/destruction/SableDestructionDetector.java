package com.hdf.cryptand.neoforge.cryptandsable.core.destruction;

import com.hdf.cryptand.neoforge.cryptandsable.core.collision.Contact;
import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.RigidBodyState;
import com.hdf.cryptand.neoforge.cryptandsable.core.worker.SableSimulationContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 破坏检测器（SableDestructionDetector）。
 *
 * <p>基于碰撞接触的相对冲击强度判断破坏：超过阈值 → 产出 {@link DestructionEvent}
 * 增量列表（核心→主线程）。MVP 用相对速度 × 质量近似冲击（动能）；精确结构应力后续接入。
 *
 * <p>与气室联动：气室破损事件的 cause=CHAMBER_BREACH（见 ChamberManager.damageChamber）。
 */
public final class SableDestructionDetector {

    /** 破坏阈值：冲击动能 ≥ 该值判定破坏。 */
    private static final double IMPACT_THRESHOLD = 40.0;

    private SableDestructionDetector() {
    }

    /**
     * 从接触列表生成破坏事件（增量）。
     *
     * @return 本 tick 的新破坏事件；空列表表示无破坏
     */
    public static List<DestructionEvent> detect(SableSimulationContext ctx, List<Contact> contacts) {
        List<DestructionEvent> out = new ArrayList<>();
        for (Contact c : contacts) {
            if (c.relativeSpeed() <= 0.5) continue;
            RigidBodyState a = ctx.getBody(c.bodyA());
            double ke = c.relativeSpeed() * c.relativeSpeed()
                    * (a != null ? a.mass() : 0);
            if (ke >= IMPACT_THRESHOLD) {
                double severity = Math.min(1.0, ke / (IMPACT_THRESHOLD * 4));
                // 局部坐标近似：以接触点相对质心
                Vector3d local = c.pointA() != null
                        ? new Vector3d(c.pointA()).sub(a.position)
                        : new Vector3d();
                out.add(new DestructionEvent(
                        c.bodyA(), c.sceneA(),
                        local.x, local.y, local.z,
                        severity, DestructionEvent.Cause.IMPACT));
            }
        }
        return out;
    }
}