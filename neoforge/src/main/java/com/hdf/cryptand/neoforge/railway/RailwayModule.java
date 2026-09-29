/**
 * ===== 铁路电气化模块主类（2026-08-24，模块化） =====
 *
 * shouldLoad() = RailwayRegistry.shouldRegister()（cryptand/cee.toml enableCeeSupport，
 * 构造期配置未加载/异常 → 默认启用）。
 */

package com.hdf.cryptand.neoforge.railway;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

public final class RailwayModule {

    private RailwayModule() {
    }

    /** 是否注册铁路电气化内容（受电弓 + 接触网） */
    public static boolean shouldLoad() {
        return RailwayRegistry.shouldRegister();
    }

    public static void register(IEventBus bus) {
        RailwayRegistry.register(bus);
        // ⚠ 2026-08-30 主类子包内容转移（用户：主类凡子包内容全部转移到子包）：
        // 铁路客户端渲染/事件（原本在主类 client 段直接注册）——现由子包自持。
        if (net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            try {
                bus.register(com.hdf.cryptand.neoforge.railway.RailwayClient.class);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void tick(ServerLevel level) {
    }
}
