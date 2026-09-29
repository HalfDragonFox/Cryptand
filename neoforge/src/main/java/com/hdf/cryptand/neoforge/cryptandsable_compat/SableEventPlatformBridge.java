package com.hdf.cryptand.neoforge.cryptandsable_compat;


import dev.ryanhcode.sable.api.event.SablePostPhysicsTickEvent;
import dev.ryanhcode.sable.api.event.SablePrePhysicsTickEvent;
import dev.ryanhcode.sable.api.event.SableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.platform.SableEventPlatform;

/**
 * {@link SableEventPlatform} 的 cryptand 实现（空桥）。
 *
 * <p>经 {@code META-INF/services/dev.ryanhcode.sable.platform.SableEventPlatform} 注册，
 * 使 {@code SableEventPlatform.INSTANCE}（Offroad 等依赖）能 resolve。当前无亚层系统
 * 事件消费方，全部空实现；后续桥 CryptandSable 核心 tick 即可。
 */
public final class SableEventPlatformBridge implements SableEventPlatform {
    public static final SableEventPlatformBridge INSTANCE = new SableEventPlatformBridge();

    // ServiceLoader 需要 public 无参构造器
    public SableEventPlatformBridge() {
    }

    @Override
    public void onSubLevelContainerReady(SableSubLevelContainerReadyEvent event) {
    }

    @Override
    public void onPhysicsTick(SablePrePhysicsTickEvent event) {
    }

    @Override
    public void onPostPhysicsTick(SablePostPhysicsTickEvent event) {
    }
}