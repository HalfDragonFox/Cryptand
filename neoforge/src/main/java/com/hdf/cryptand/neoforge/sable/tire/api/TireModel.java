/**
 * ===== 轮胎力学模型（轮胎拟真框架 API，2026-09-14） =====
 *
 * <p>【扩展点】实现本接口并 {@link TireRegistry#register} 即可接入自己的轮胎模型
 * （线性饱和 / 魔术公式 / 刷子模型 / 实验数据表…），无需改动注入与积分代码。
 *
 * <p>线程：服务端主线程调用（物理 tick 内）。实现必须【零分配、无 IO、无反射】，
 * 每 tick 每轮可能被调用多次（substeps）。
 */
package com.hdf.cryptand.neoforge.sable.tire.api;

public interface TireModel {

    /** 模型标识（诊断/配置选择用）。 */
    String id();

    /**
     * 求解轮胎力（轮坐标系，N）。
     *
     * <p>约定：实现内部应完成【摩擦圆裁剪】（√(fx²+fy²) ≤ μ·N），
     * 保证输出力不超出附着极限。
     */
    TireForces solve(TireInput input, TireParams params);
}
