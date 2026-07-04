/**
 * ===== Create 多线程化 - CommonEvents 世界 tick 注入 =====
 *
 * 目标类：com.simibubi.create.foundation.events.CommonEvents
 * 注入方法：onServerWorldTick(LevelTickEvent.Post)
 * 插入点：INVOKE GlobalLogisticsManager.tick() 之后（shift=AFTER）
 *
 * Create 的动能网络没有中心化的遍历循环。每个 KineticBlockEntity.tick()
 * 单独检查 networkDirty 标志并调用 KineticNetwork.updateNetwork()。
 *
 * 本 mixin 在 Create 的世界 tick 末尾注入一个并行化的网络遍历：
 *   1. 从 TorquePropagator 获取当前维度的所有 KineticNetwork
 *   2. 使用 parallelStream() 并行调用每个网络的 updateNetwork()
 *
 * 安全性分析：
 *   - 每个 KineticNetwork 拥有独立的 members/sources Map，
 *     网络之间不共享 KineticBlockEntity
 *   - updateNetwork() 计算 calculateStress() + calculateCapacity()，
 *     然后通过 sync() 写回到成员 KBE 的字段
 *   - 虽然 KBE 字段被工作线程写入，但这些 KBE 在同一网络内是唯一的
 *
 * 配置开关：
 *   ConfigLoad.ENABLE_CREATE_ASYNC_NETWORK 控制是否启用。
 *   默认 false，需要用户手动开启。
 */

package com.hdf.cryptand.neoforge.mixin.create;

import com.hdf.cryptand.neoforge.config.ConfigLoad;
import com.simibubi.create.content.kinetics.KineticNetwork;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = com.simibubi.create.foundation.events.CommonEvents.class, remap = false)
public abstract class CommonEventsMixin {

    /**
     * 在 CommonEvents.onServerWorldTick() 的 LOGISTICS.tick() 调用之后注入。
     *
     * 原始方法签名：
     *   public static void onServerWorldTick(LevelTickEvent.Post event)
     *
     * 并行化逻辑：
     *   1. 检查配置开关 enableCreateAsyncNetwork
     *   2. 通过 TorquePropagatorAccessor 获取当前维度的 networks Map
     *   3. 使用 parallelStream() 并行遍历所有 KineticNetwork
     *   4. 跳过空网络（members.isEmpty()）
     *   5. 每个非空网络调用 updateNetwork()
     *
     * parallelStream() 在此处安全的原因：
     *   1. 此注入点在 CommonEvents.onServerWorldTick 的 RETURN 之前，
     *      此时没有其他代码会结构性修改 networks Map。
     *      Map 的结构修改（put/remove）发生在 BE 的 attach/detach 阶段，
     *      不在 tick 循环中。
     *   2. 每个 KineticNetwork 是独立的，updateNetwork() 只读写自身字段。
     *   3. 跳过空网络避免无效的 parallelStream 开销。
     */
    @Inject(method = "onServerWorldTick", at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/logistics/packagerLink/GlobalLogisticsManager;tick(Lnet/minecraft/world/level/Level;)V",
            shift = At.Shift.AFTER
    ))
    private static void onServerWorldTickTail(LevelTickEvent.Post event, CallbackInfo ci) {
        // Guard: NeoForge config may not be loaded yet on the very first tick.
        // If the config isn't ready, skip this tick — it'll be checked again next tick.
        if (!isConfigReady()) return;

        Level level = event.getLevel();
        if (level.isClientSide) return;

        Map<Long, KineticNetwork> worldNetworks = getWorldNetworks(level);
        if (worldNetworks == null || worldNetworks.isEmpty()) return;

        long start = System.nanoTime();
        worldNetworks.values().parallelStream().forEach(network -> {
            if (network != null && !network.members.isEmpty()) {
                network.updateNetwork();
            }
        });
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        int poolSize = java.util.concurrent.ForkJoinPool.commonPool().getParallelism();
        if (elapsed > 0) {
            getLogger().info("[Cryptand] Create parallel update: {} networks in {}ms (FJ pool={}, thread={})",
                    worldNetworks.size(), elapsed, poolSize, Thread.currentThread().getName());
        }
    }

    /**
     * Safely check if the config is loaded and the feature is enabled.
     * NeoForge's ModConfigSpec.get() throws IllegalStateException
     * if called before the config file has been loaded.
     */
    @Unique
    private static boolean isConfigReady() {
        try {
            return ConfigLoad.ENABLE_CREATE_ASYNC_NETWORK.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * 世界卸载时的清理钩子。
     * 当前不需要额外操作：TorquePropagator.onUnloadWorld() 会移除整个 Map。
     * 保留此注入点以备将来需要清理线程局部资源。
     */
    @Inject(method = "onUnloadWorld", at = @At("TAIL"))
    private static void onUnloadWorldTail(net.neoforged.neoforge.event.level.LevelEvent.Unload event, CallbackInfo ci) {
        // 静态线程池保持存活，各网络的清理由 TorquePropagator 负责
    }

    /**
     * 通过 TorquePropagatorAccessor 安全获取当前维度的网络映射。
     * TorquePropagator.networks 是 private static 字段，需要通过 Accessor 访问。
     */
    @Unique
    private static Map<Long, KineticNetwork> getWorldNetworks(Level level) {
        return TorquePropagatorAccessor.getNetworks().get(level);
    }

    @Unique
    private static Logger getLogger() {
        return LoggerFactory.getLogger("Cryptand-Create");
    }
}
