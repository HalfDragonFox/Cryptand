package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ===== 换向器火花粒子门控（2026-09-13 用户）=====
 *
 * 用户原话：「电机旋转时会有一圈紫色粒子效果，改成从0开始转有速度时触发一次，
 *          每次到0就重置能够再次触发」。
 *
 * PowerGrid 原版行为（CommutatorBlockEntity.tick() 的客户端分支）：
 *   angular = rotorBehaviour.getAngularVelocityRadians()
 *   current = getCurrent()
 *   chance  = min(|angular/32 × current/4|, 5)     ← 每 tick 最多 5 个
 *   在两个电刷位置（brushOffset 正/负）随机 addParticle(SparkParticleData)
 *   ⇒ 只要在转就【持续】喷火花 —— 就是玩家看到的那「一圈紫色粒子」。
 *
 * 新行为：
 *   · 转速为 0（停转）→ 不喷，并【重置】状态（下次起步可再次触发）；
 *   · 0 → 非 0 的起步瞬间 → 打开极短爆发窗口（~80ms ≈ 1~2 tick），窗口内的
 *     粒子全部放行（一 tick 最多 5 个 + 双电刷 ≈ 正好一圈）；
 *   · 窗口之外（持续旋转中）→ 拦掉。
 *
 * 实现：@Redirect 精确替换 tick 内【唯一】的 Level.addParticle(ParticleOptions,
 *   double×6) 调用 —— 换向器的装配/电流/装配变更等原版逻辑一律不动。
 *
 * ⚠ 纯视觉，不影响任何物理量：该分支本就只在客户端跑，两个 @Unique 字段挂在
 *   目标 BE 实例上，不写 NBT、不参与同步。
 */
@Mixin(targets = {
        "org.patryk3211.powergrid.kinetics.generator.inductionrotor.CommutatorBlockEntity"
}, remap = false)
public abstract class CommutatorSparkGateMixin {

    /*
     * ⚠⚠ 2026-09-13 启动崩溃根因（实测，勿重犯）：
     *   原实现用 `@Shadow(remap = false) protected RotorBehaviour rotorBehaviour;`
     *   指向【父类 RotorBlockEntity】的字段 —— 崩溃报告原文：
     *     InvalidMixinException: @Shadow field rotorBehaviour was not located in
     *     the target class ...CommutatorBlockEntity. No refMap loaded.
     *   ⇒ **Sponge 的 @Shadow 只查目标类自身，不查父类**（attachFields 阶段直接
     *     抛错 → MixinApplyError → 模组加载失败）。
     *   改用反射读该字段（MotorTakeoverBase 带解析缓存，稳态零额外查找）。
     */

    /** 上一 tick 是否处于「在转」状态（用于检测 0 → 非 0 的起步瞬间） */
    @Unique private boolean cryptand$wasSpinning;
    /** 爆发窗口截止时刻（墙钟 ms）；旋转中的其余时间恒为过去 → 拦掉 */
    @Unique private long cryptand$burstUntilMs;

    /** 起步爆发窗口长度（ms）：覆盖 1~2 个 tick，让「一圈」火花一次性喷完 */
    @Unique private static final long CRYPTAND_BURST_MS = 80;

    @Redirect(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"),
            remap = false)
    private void cryptand$gateSpark(Level level, ParticleOptions type,
                                    double x, double y, double z,
                                    double vx, double vy, double vz) {
        try {
            boolean spinning = cryptand$spinning();
            long now = System.currentTimeMillis();
            if (!spinning) {
                // 停转（回到 0）→ 重置状态，起步时可再次触发
                cryptand$wasSpinning = false;
                cryptand$burstUntilMs = 0;
                return;
            }
            if (!cryptand$wasSpinning) {
                // 0 → 非 0：起步瞬间，打开爆发窗口
                cryptand$wasSpinning = true;
                cryptand$burstUntilMs = now + CRYPTAND_BURST_MS;
            }
            if (now > cryptand$burstUntilMs) return; // 持续旋转中：不再喷
            level.addParticle(type, x, y, z, vx, vy, vz);
        } catch (Throwable ignored) {
        }
    }

    /** 是否在转（角速度非零；异常/未装配 → false）。反射读父类字段（见上方注释）。 */
    @Unique
    private boolean cryptand$spinning() {
        try {
            Object rb = com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                    .getField(this, "rotorBehaviour");
            if (rb == null) return false;
            java.lang.reflect.Method m = com.hdf.cryptand.neoforge.powergrid.device.motor
                    .MotorTakeoverBase.findMethodCached(rb.getClass(),
                            "getAngularVelocityRadians");
            if (m == null) return false;
            Object v = m.invoke(rb);
            return v instanceof Number n && Math.abs(n.doubleValue()) > 1e-3;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
