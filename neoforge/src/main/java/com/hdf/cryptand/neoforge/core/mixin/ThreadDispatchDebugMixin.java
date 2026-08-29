/**
 * ===== 线程分发调试（mixin 机制注入，2026-08-12 用户要求） =====
 *
 * 注入 PhasorEngine.solveAll（每轮求解入口）——配置 enableThreadDispatchDebug
 * 开启后每秒打印 ThreadDispatcher 每个 Worker 线程【累计处理的任务数】与
 * 【当前负载】（空闲/忙），观察线程分发是否均衡、空闲休眠是否生效。
 *
 * 线程分发是本 mod（Cryptand）专属通用能力，不绑定 PowerGrid。
 * 调试逻辑通过 mixin 注入，主代码零侵入（关闭时仅一次配置读取 + 时间比较）。
 */

package com.hdf.cryptand.neoforge.core.mixin;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = PhasorEngine.class, remap = false)
public abstract class ThreadDispatchDebugMixin {

    /** 上次打印时间戳（ms，1s 节流） */
    @Unique private static long cryptand$threadDebugLast;

    @Inject(method = "solveAll", at = @At("HEAD"))
    private static void cryptand$threadDispatchDebug(List<Network> nets,
            CallbackInfoReturnable<List<SolveResult>> cir) {
        try {
            long now = System.currentTimeMillis();
            if (now - cryptand$threadDebugLast < 1000) return;
            cryptand$threadDebugLast = now;
            if (!ConfigLoad.ENABLE_THREAD_DISPATCH_DEBUG.get()) return;
            com.hdf.cryptand.circuitsimulation.compute.ComputeEngine engine =
                    PhasorEngine.computeEngine();
            if (engine instanceof ThreadDispatcher td) {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[ThreadStats]\n{}", td.stats());
            }
        } catch (Throwable ignored) {
        }
    }
}
