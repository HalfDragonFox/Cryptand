package com.hdf.cryptand.neoforge.pipez;

import com.hdf.cryptand.neoforge.pipez.config.ConfigPipez;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

/**
 * Pipez（管道）模块（2026-08-30）：shouldLoad() = 配置开关 && pipez mod 已加载。
 * 开关 false → 本子包全部内容不加载（Mixin 由 PipezMixinPlugin 检查 mod；
 * 运行时逻辑由各 Mixin/Takeover 入口检查本 shouldLoad()）。
 */
public final class PipezModule {

    private PipezModule() {
    }

    /** 是否加载本子包（配置开关 && pipez mod 加载）。
     *  ⚠ 构造期（spec 未加载）→ 用 ConfigLoad.preloadBoolean 预读配置文件，
     *  使 enablePipezSupport=false 在内容注册前即生效。 */
    public static boolean shouldLoad() {
        try {
            final boolean enabled = ConfigPipez.SPEC.isLoaded()
                    ? ConfigPipez.ENABLE_PIPEZ_SUPPORT.get()
                    : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("pipez", "enablePipezSupport", true);
            if (!enabled) return false; // 配置关闭 → 联动关闭
            return ModList.get() != null && ModList.get().getModContainerById("pipez").isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 注册（无独立注册内容——管道接管由 Mixin 懒注册） */
    public static void register(IEventBus bus) {
        // 本子包无方块/物品注册（接管式集成）——保留入口供未来扩展
    }

    /** 服务端 tick（无周期任务——保留入口） */
    public static void tick(ServerLevel level) {
        // 保留入口（接管由 Mixin 触发；无周期任务）
    }
}
