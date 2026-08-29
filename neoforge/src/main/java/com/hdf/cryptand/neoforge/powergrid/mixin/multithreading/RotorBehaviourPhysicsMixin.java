/**
 * ===== 修复：禁用主线程转子物理，角速度完全由后台线程计算 =====
 *
 * 主线程 RotorBehaviour.tick() 每 tick 执行：
 *   totalForce -= friction;
 *   angularVelocity += totalForce / 20f / inertia;   ← 物理核心
 *
 * 异步化后后台线程也在更新角速度（施加发电机耦合扭矩），两者竞争导致
 * 力被覆盖丢失。本 Mixin 通过 @Redirect 让主线程 tick 中：
 *   - 读取 totalForce 恒返回 0（angularVelocity += 0，物理禁用）
 *   - 写入 totalForce 全部丢弃（不清零、不污染后台累积的力）
 *
 * 结果：控制器角速度完全由后台线程计算（力 + 摩擦 + 超速限制）。
 * 主线程 GeneratorClutch.tick() 的 applyTickForce（电机模式刹车力）累加
 * totalForce 的操作是独立方法，不受本 Mixin 影响——刹车力保留在
 * totalForce 中，由后台线程读取应用。
 * 注意：PID 控制（forceSupplier / GeneratorClutch 发电机模式）的角速度更新
 * 直接使用 force 局部变量（不读 totalForce），因此不受影响，仍正常工作。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.multithreading;

import org.objectweb.asm.Opcodes;
import org.patryk3211.powergrid.kinetics.generator.rotor.RotorBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RotorBehaviour.class, remap = false)
public abstract class RotorBehaviourPhysicsMixin {

    /**
     * 主线程 tick 中所有读取 totalForce 的位置返回 0：
     * - angularVelocity += totalForce/20/inertia → += 0（物理禁用）
     * - prevForce = totalForce → 0（无害，PowerGrid 无人读 prevForce）
     * - totalForce -= friction（x -= y 中的读取）→ 0（配合写丢弃 = 无操作）
     * - PID totalForce += force（x += y 中的读取）→ 0（配合写丢弃 = 无操作，
     *   PID 角速度更新直接走 force 局部变量，不受影响）
     */
    @Redirect(method = "tick",
              at = @At(value = "FIELD",
                       target = "Lorg/patryk3211/powergrid/kinetics/generator/rotor/RotorBehaviour;totalForce:F",
                       opcode = Opcodes.GETFIELD))
    private float cryptand$zeroTotalForce(RotorBehaviour instance) {
        return 0f;
    }

    /**
     * 主线程 tick 中所有写入 totalForce 的位置全部丢弃：
     * - totalForce -= friction / totalForce += force / totalForce = 0
     *   （不清零、不污染后台线程累积的 totalForce）
     * GeneratorClutch 电机模式刹车力由 applyTickForce（独立方法）累加 totalForce，
     * 不经过这里，因此保留给后台线程读取。
     */
    @Redirect(method = "tick",
              at = @At(value = "FIELD",
                       target = "Lorg/patryk3211/powergrid/kinetics/generator/rotor/RotorBehaviour;totalForce:F",
                       opcode = Opcodes.PUTFIELD))
    private void cryptand$discardTotalForceWrite(RotorBehaviour instance, float value) {
        // 丢弃主线程写：不清零、不污染后台累积值
    }
}
