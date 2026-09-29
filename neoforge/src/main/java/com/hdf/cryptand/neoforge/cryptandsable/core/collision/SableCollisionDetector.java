package com.hdf.cryptand.neoforge.cryptandsable.core.collision;

import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.RigidBodyState;
import com.hdf.cryptand.neoforge.cryptandsable.core.worker.SableSimulationContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 碰撞检测器（SableCollisionDetector）。
 *
 * <p>对上下文内所有刚体进行两两检测（O 简化实现：中心距 vs 半尺寸和 → 粗略 AABB 判定，
 * 再产出接触点/法线/穿透）。MVP 只做球-盒近似；精确 OBB-SAT 后续接入。
 *
 * <p>检测只读模拟状态、产出 {@link Contact} 列表（纯数据）→ 主线程消费特效/破坏。
 */
public final class SableCollisionDetector {

    private SableCollisionDetector() {
    }

    /**
     * 检测所有刚体之间的碰撞。
     *
     * @return 接触列表（同一对最多产出一次）
     */
    public static List<Contact> detect(SableSimulationContext ctx) {
        List<Contact> out = new ArrayList<>();
        List<RigidBodyState> bodies = new ArrayList<>(ctx.bodies().values());
        // 简单两两检测：O(n²)，MVP 可接受；大批量后续挂 scene 空间分区（allocator 并行）
        for (int i = 0; i < bodies.size(); i++) {
            RigidBodyState a = bodies.get(i);
            if (a.isRemoved() || a.isSoft()) continue; // MVP 只刚体-刚体
            for (int j = i + 1; j < bodies.size(); j++) {
                RigidBodyState b = bodies.get(j);
                if (b.isRemoved() || b.isSoft()) continue;
                Contact c = detectPair(ctx, a, b);
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    private static Contact detectPair(SableSimulationContext ctx, RigidBodyState a, RigidBodyState b) {
        // 刚体自身存储了半尺寸？MVP 从 mass 推出"盒半径"近似：用 position 中心 + 统一默认半尺寸
        // TODO(collision): 从 BodyImport 的 bounds 存真正的半尺寸到 RigidBodyState。
        double ra = bodyRadius(a);
        double rb = bodyRadius(b);

        Vector3d delta = new Vector3d(b.position).sub(a.position);
        double dist = delta.length();
        if (dist > ra + rb || dist < 1e-9) {
            return null; // 不接触
        }

        // 法线 B→A
        Vector3d normal = new Vector3d(delta).normalize();
        if (dist < 1e-9) {
            normal.set(0, 1, 0);
        }
        double penetration = (ra + rb) - dist;

        // 接触点取两心连线上
        Vector3d pointA = new Vector3d(a.position).fma(ra, normal, new Vector3d());
        Vector3d pointB = new Vector3d(b.position).fma(-rb, normal, new Vector3d());

        // 相对速度沿法线
        Vector3d relVel = new Vector3d(b.linearVelocity).sub(a.linearVelocity);
        double relSpeed = relVel.dot(normal);

        return new Contact(a.runtimeId, b.runtimeId, a.sceneId, b.sceneId,
                pointA, pointB, normal, penetration, relSpeed);
    }

    /** MVP：用质量粗略推半径（假设约球体 ρ≈厅）。作为稳定估算。 */
    private static double bodyRadius(RigidBodyState body) {
        double mass = body.mass();
        if (mass <= 0) return 0.5;
        // 粗略：半径 = (3m / (4π·1000))^(1/3)，密度按 1000 kg/m³
        return Math.cbrt((3.0 * mass) / (4.0 * Math.PI * 1000.0));
    }
}