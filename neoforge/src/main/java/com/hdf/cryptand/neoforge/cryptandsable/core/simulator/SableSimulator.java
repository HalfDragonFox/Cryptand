package com.hdf.cryptand.neoforge.cryptandsable.core.simulator;

import com.hdf.cryptand.neoforge.cryptandsable.core.chamber.ChamberManager;
import com.hdf.cryptand.neoforge.cryptandsable.core.collision.Contact;
import com.hdf.cryptand.neoforge.cryptandsable.core.collision.SableCollisionDetector;
import com.hdf.cryptand.neoforge.cryptandsable.core.collision.SableCollisionSolver;
import com.hdf.cryptand.neoforge.cryptandsable.core.environment.EnvironmentSnapshot;
import com.hdf.cryptand.neoforge.cryptandsable.core.worker.SableSimulationContext;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;

/**
 * 高频小步进模拟器（SableSimulator）。
 *
 * <p>核心的物理步进执行器：
 * <ul>
 *   <li>每 tick 由 worker 按心跳推进预算调用多次 {@link #step(SableSimulationContext, double, Vector3dc)}（C13）</li>
 *   <li>用高频小步（更小 dt）实现自收敛，弃用求解器迭代收敛（C14）</li>
 *   <li>应用环境重力（来自 {@code core/environment}），为气室净浮力预留夹具（C10）</li>
 *   <li>对齐 C12：支持批量 step（内部对 scene 内多个刚体一次遍历积分）</li>
 * </ul>
 */
public final class SableSimulator {

    /** 单步默认时长（20Hz 基础）。实际 dt = 1/20/fixedSteps。 */
    public static final double BASE_DT = 1.0 / 20.0;

    /** 每 tick 默认子步数（高频方案：细分抑制误差 → 自收敛）。 */
    public static final int DEFAULT_SUBSTEPS = 4;

    /**
     * 执行一个物理小步。
     *
     * @param ctx     模拟上下文（worker 独占）
     * @param dt      小步时长 [s]
     * @param gravity 该环境重力矢量（来源 DimensionEnvConfig），null 用默认
     */
    public static void step(SableSimulationContext ctx, double dt, Vector3dc gravity) {
        Vector3d g = gravity != null ? new Vector3d(gravity) : new Vector3d(GRAVITY_DEFAULT);
        EnvironmentSnapshot env = ctx.environment();
        double density = env.mediumDensity();

        for (Map.Entry<Integer, RigidBodyState> e : ctx.bodies().entrySet()) {
            RigidBodyState body = e.getValue();
            if (body.isRemoved()) continue;

            // 气室净浮力（C10）：按环境介质密度 + 重力方向施加
            ChamberManager.applyBuoyancy(body, density, g);

            body.integrate(dt, g);

            // ★ 2026-09-01 地表支撑：结构激活后不穿透地面（有 groundY 的体贴地停住）。
            //   否则物理体受重力直接穿透地板掉落 → 玩家原位站不上 + 渲染跟随位置闪黑。
            final double gy = body.groundY();
            if (!Double.isNaN(gy) && body.position.y < gy) {
                body.position.y = gy;
                if (body.linearVelocity.y < 0) {
                    body.linearVelocity.y = 0;
                }
            }
        }
    }

    /**
     * 批量 step（C12）：对整个上下文一次步进 = 对多体一次性批量积分。
     * 实际由 {@link #step} 承担；此处保留语义以便后续对接 Rust 批量 JNI（stepBatch）。
     *
     * <p>每小步之后做碰撞检测 + 接触求解（collision 模块），保证高频自收敛下不穿透。
     */
    public static void stepBatch(SableSimulationContext ctx, int substeps, Vector3dc gravity) {
        double dt = BASE_DT / Math.max(1, substeps);
        for (int i = 0; i < substeps; i++) {
            step(ctx, dt, gravity);
            // collision：检测+求解（仅在本 worker 线程内进行，纯模拟状态）
            java.util.List<Contact> contacts =
                    SableCollisionDetector.detect(ctx);
            if (!contacts.isEmpty()) {
                SableCollisionSolver.solve(ctx, contacts);
            }
        }
    }

    private static final Vector3d GRAVITY_DEFAULT = new Vector3d(0.0, -9.81, 0.0);
}