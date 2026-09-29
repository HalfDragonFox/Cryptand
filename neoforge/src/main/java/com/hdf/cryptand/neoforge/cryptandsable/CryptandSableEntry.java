package com.hdf.cryptand.neoforge.cryptandsable;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;

/** CryptandSable 物理核心子包入口（依赖 sable 联动层 config）（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "cryptandsable", order = 60)
public final class CryptandSableEntry implements SubpackageEntry {
    public static final CryptandSableEntry INSTANCE = new CryptandSableEntry();
    private CryptandSableEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigCryptandSable.register();
    }

    @Override
    public boolean enabled() {
        try {
            // ⚠ 2026-08-30 类 mod 依赖：sable 支持由【依赖表】处理（dependencies()
            // 声明 "sable"——sable 未启用/失败 → 加载器自动传递禁用本模块）；
            // 本方法只判定自身配置。
            return ConfigCryptandSable.SPEC.isLoaded()
                    ? ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()
                    : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("cryptandsable", "enableCryptandSableCore", true);
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "sable.toml#enableSableSupport && cryptand-sable.toml#enableCryptandSableCore";
    }

    @Override
    public void init(IEventBus bus) {
        // ⚠ 2026-08-30 主类子包内容转移（用户：主类凡子包内容全部转移到子包）：
        // Sable 网络包注册（原本在主类构造期直接 register）——现由子包自持
        // （enabled=false → init 不调用 → 内容不注册）。
        try {
            bus.register(com.hdf.cryptand.neoforge.cryptandsable.network
                    .SableNetworkRegistration.class);
        } catch (Throwable ignored) {
        }
        // ⚠ 2026-08-30 子包开关彻底生效：原 @EventBusSubscriber 自动注册的客户端
        // 渲染/tick 类改为显式注册（enabled=false → 零监听、零每帧开销）。
        if (net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            try {
                // ⚠⚠ 2026-09-11 同 PowergridModule 的总线修复：这三者监听的是
                // 【游戏总线】事件（ClientTickEvent.Post / RenderLevelStageEvent）——
                // 原 @EventBusSubscriber 默认 game bus；改显式注册时误挂 MOD 总线 →
                // 客户端 tick 驱动与亚层渲染/scope 调试渲染全部静默失效。
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                        com.hdf.cryptand.neoforge.cryptandsable.client
                                .SableClientTickDriver.class);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                        com.hdf.cryptand.neoforge.cryptandsable.client.render
                                .SableSubLevelWorldRenderer.class);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                        com.hdf.cryptand.neoforge.cryptandsable.client.render
                                .SableScopeBoxDebugRenderer.class);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void commonSetup() {
        CryptandSable.instance().install();
    }

    @Override
    public void commands() {
        CryptandSableCommands.register();
    }

    @Override
    public java.util.List<String> dependencies() {
        // 类 mod 依赖：Sable 集成总开关（sable 子包）未启用 → 本模块不加载
        return java.util.List.of("sable");
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：统一加载函数按 enabled 返回）
        return java.util.List.of("cryptand.sable.core.mixins.json", "cryptand.sable.simulated.mixins.json");
    }
}
