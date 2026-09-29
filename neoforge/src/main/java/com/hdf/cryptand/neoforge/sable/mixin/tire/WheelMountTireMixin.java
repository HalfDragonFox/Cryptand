/**
 * ===== 轮胎拟真注入（2026-09-14） =====
 *
 * <p>目标：offroad {@code WheelMountBlockEntity.sable$physicsTick(ServerSubLevel, RigidBodyHandle, double)}
 * —— 官方在此完成"悬挂力 + 简化摩擦/驱动"并提交进结构 ForceTotal。
 *
 * <p>本 mixin 在方法 **RETURN** 处追加一条【修正冲量】（把摩擦分量按轮胎模型缩放），
 * 悬挂力完全不动。实现在 {@link com.hdf.cryptand.neoforge.sable.tire.impl.WheelTireHook}
 * （全程反射 + try/catch：任何失败 = 官方行为，绝不影响物理）。
 *
 * <p>门控：{@link TireMixinPlugin}（未装 offroad → 整个 mixin 跳过）；运行时另有配置开关。
 */
package com.hdf.cryptand.neoforge.sable.mixin.tire;

import com.hdf.cryptand.neoforge.sable.tire.impl.WheelTireHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
public class WheelMountTireMixin {

    /** 每次 sable$physicsTick 结束（官方力已提交）→ 追加轮胎修正冲量。 */
    @Inject(method = "sable$physicsTick", at = @At("RETURN"), remap = false)
    private void cryptand$tireRealism(@Coerce final Object subLevel, @Coerce final Object handle,
                                      final double timeStep, final CallbackInfo ci) {
        WheelTireHook.onPhysicsTickReturn(this, subLevel, handle, timeStep);
    }
}
