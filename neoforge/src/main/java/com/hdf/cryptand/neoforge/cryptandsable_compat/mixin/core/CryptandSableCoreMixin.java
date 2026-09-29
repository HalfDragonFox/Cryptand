package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.core;

import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CryptandSable 核心接管 mixin（mixin/core = 核心子包）。
 *
 * <p><b>职责</b>：目标官方 {@code SubasdfkzzxcvLevelPhysicsSystem.tickPipelinePhysics}（物理核心入口），
 * 当 CryptandSable 核心已接管时【在主线程取消官方物理】——官方 uuid 物理（native step/tick）
 * 不再于主线程运行，改由 CryptandSable 自己的 worker 跑自己的模拟器（纯虚拟运算，
 * 数据归属矩阵：核心即唯一权威源）。对齐 C1（主线程只同步）+ C9（完全替换官方流程）。
 *
 * <p>安全：注入点 HEAD，判断异常安全地 cancel；核心未启动则完全不管（官方照旧）。
 * 为空绕 mixin 子包规范，本类放 mixin/core；sable/aeronautics/cee/powergrid 联动各自成包。
 *
 * <p>⚠ preferred：官方 sable 是 runtimeOnly（dev 环境存在所以 mixin 能 apply）；
 * 若租除官方 sable 依赖后目标类由我们同包兼容实现提供（见 dev/ryanhcode/sable 兼容层），
 * mixin target 仍成立。
 */
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem")
public abstract class CryptandSableCoreMixin {

    /**
     * 物理核心入口：主线程当 CryptandSable 已接管 → 取消官方物理。
     * worker 线程调用（罕见）→ 放行防误杀。
     */
    @Inject(method = "tickPipelinePhysics",
            at = @At("HEAD"), remap = false, cancellable = true)
    private void cryptand$coreCancelOfficial(@org.spongepowered.asm.mixin.injection.Coerce Object container,
                                             CallbackInfo ci) {
        try {
            // worker 线程 → 放行（我们的 worker 只在自实现模拟器，理论上不会进这里；
            // 但保险起见防回环）
            if (CryptandSable.isWorkerThread(Thread.currentThread())) {
                return;
            }
            // 核心已接管 → 主线程取消官方物理
            if (CryptandSable.instance().isStarted()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // 异常安全：不确定时也 cancel，防主线程官方物理重复触碰 native
            try {
                ci.cancel();
            } catch (Throwable ignored) {
            }
        }
    }
}