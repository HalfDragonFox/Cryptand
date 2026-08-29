/**
 * ===== CEE 联动模块主类（2026-08-24，模块化） =====
 *
 * shouldLoad() = CEE 支持开启 && CEE mod 已加载；
 * tick：主世界 Sable 亚层观察者注册重试（物理化端点保护）。
 * Mixin 由 cee.mixin（CeeMixinPlugin / SableMixinPlugin）子管理。
 */

package com.hdf.cryptand.neoforge.cee;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

public final class CeeModule {

    private CeeModule() {
    }

    public static boolean shouldLoad() {
        return CeeTerminalSupport.ceeEnabled() && CeeTerminalSupport.ceeModLoaded();
    }

    public static void register(IEventBus bus) {
    }

    /** 服务端 tick：Sable 亚层移除观察者注册重试（仅主世界） */
    public static void tick(ServerLevel level) {
        if (!shouldLoad() || level == null
                || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return;
        }
        try {
            SableSubLevelObserver.ensure(level);
        } catch (Throwable ignored) {
        }
    }
}
