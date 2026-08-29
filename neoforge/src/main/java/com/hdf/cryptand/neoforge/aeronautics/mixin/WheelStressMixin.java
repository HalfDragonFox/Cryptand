/**
 * ===== 轮子摩擦力→应力物理改造 Mixin（2026-08-28 用户） =====
 *
 * 目标：dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity
 * （extends KineticBlockEntity —— Create 动力网络成员）。
 *
 * 问题：原版 calculateStressApplied() = BlockStressValues.getImpact(block)（静态
 * SU，与重量/摩擦无关）。用户要求"和现实类似：根据实际摩擦力消耗计算应力消耗"。
 *
 * 改造：注入 calculateStressApplied() RETURN，返回【现实摩擦物理应力】：
 *   SU = P_roll / W_PER_SU = (μ·N·g·v) / 400
 *   - μ：BE.touchingFriction（physicsTick 每 tick 按 PhysicsBlockPropertyHelper
 *     .getFriction 实测 + fudgeFriction 平滑——真实接触面摩擦（雪/冰/草地/
 *     混凝土各不相同））
 *   - N：Sable MassData.getInverseNormalMass（单轮法向承载——整车质量×重力
 *     分配；含车身自重+载荷=用户期望"和整体物理结构重量有关"）
 *   - v：轮面线速度 = |网络ω|·r（getSpeed() rad/s × TireLike.radius）
 *   - r：TireLike.radius（小/大/巨型轮胎不同——大轮同转速线速更快 → 应力更高）
 *
 * 规律（公式涌现）：
 *   - 轮子悬空/抬升（N→0）→ SU→0（空转不耗应力——现实中悬空轮无摩擦负荷）
 *   - 冰面（μ→0）→ SU→0 打滑（驱动不输出）
 *   - 重载/粗面（μ·N↑）→ SU↑；快滚（v↑）→ SU↑
 *   - 整车重量 → Σ N（载重）→ 总应力随质量单调升（用户"和重量有关"）
 *
 * 门控：AeronauticsCompat.isLoaded()（create_aeronautics/offroad 未装 → 跳过）
 * + 配置 ENABLE_AERO_WHEEL_STRESS_FRICTION（false → 恢复原版静态 impact）。
 * 目标类不可见（offroad runtimeOnly + compileOnly 无 API 包）→ @Mixin(targets=)
 * + 反射。参考 SableAssemblyMoveMixin 模式。
 */
package com.hdf.cryptand.neoforge.aeronautics.mixin;

import com.hdf.cryptand.neoforge.aeronautics.WheelStressAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * @Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity")
 */
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity")
public class WheelStressMixin {

    /**
     * calculateStressApplied() —— RETURN 修改。
     * 原版返回静态 getImpact；本注入把返回值替换为现实摩擦物理应力。
     * 支撑：calculateStressApplied() 被 KineticNetwork.calculateStress 聚合调用
     * （网络总应力 = Σ 每成员 calculateStressApplied），因此返回真实值即改造
     * 整张动力网络的应力账单。
     */
    @Inject(method = "calculateStressApplied", at = @At("RETURN"),
            cancellable = true, remap = false)
    private void cryptand$onCalculateStressApplied(CallbackInfoReturnable<Float> cir) {
        try {
            Object self = this;
            // 计算真实摩擦应力
            double su = WheelStressAccess.frictionStressOf(self);
            cir.setReturnValue((float) su);
        } catch (Throwable t) {
            // 反射失败 → 不干预（返回原版值，安全回退）
        }
    }
}
