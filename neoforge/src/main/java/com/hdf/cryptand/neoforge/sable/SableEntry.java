package com.hdf.cryptand.neoforge.sable;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;

/** sable 联动层入口（仅 sable 本体相关）（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "sable", order = 80)
public final class SableEntry implements SubpackageEntry {
    public static final SableEntry INSTANCE = new SableEntry();
    private SableEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigSable.register();
    }

    @Override
    public boolean enabled() {
        // ⚠ 2026-08-30 开关归本子包（原 core 域 common.toml → sable.toml#enableSableSupport）
        try {
            return ConfigSable.SPEC.isLoaded()
                    ? ConfigSable.ENABLE_SABLE_SUPPORT.get()
                    : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("sable", "enableSableSupport", true);
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "sable.toml#enableSableSupport";
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：统一加载函数按 enabled 返回）
        return java.util.List.of("cryptand.sable.mixins.json", "cryptand.sable.sable.mixins.json", "cryptand.sable.vanilla.mixins.json", "cryptand.sable.schematicapi.mixins.json", "cryptand.sable.offroad.mixins.json", "cryptand.sable.tire.mixins.json");
    }

    /**
     * 子包初始化（2026-09-14）：力学可视化框架接线。
     *
     * <p>⚠ 用【官方 Sable】数据，不依赖 CryptandSable 自研核心（本子包 enabled 即可用；
     * 总开关为 {@code sable.toml#enableSableSupport}）。
     * <ul>
     *   <li>MOD 总线：{@link com.hdf.cryptand.neoforge.sable.force.net.ForceNetRegistration}
     *       —— 力显示请求/响应 payload（客户端主动、服务端被动）</li>
     *   <li>双端：内置力源与默认配色 bootstrap（反射官方 ForceGroups/QueuedForceGroup）</li>
     *   <li>游戏总线（服务端）：{@link com.hdf.cryptand.neoforge.sable.force.server.SableForceService}
     *       —— 仅做订阅 TTL 清理，不主动采集/广播</li>
     *   <li>游戏总线（仅客户端）：Requester（主动请求）+ Renderer（球 + 箭头）</li>
     * </ul>
     */
    @Override
    public void init(final IEventBus bus) {
        try {
            bus.register(com.hdf.cryptand.neoforge.sable.force.net.ForceNetRegistration.class);
        } catch (final Throwable ignored) {
        }
        try {
            com.hdf.cryptand.neoforge.sable.force.impl.ForceDisplayBootstrap.init();
        } catch (final Throwable ignored) {
        }
        try {
            // 轮胎拟真：注册内置轮胎模型（注册式；其它 mod 可继续 register 追加）
            com.hdf.cryptand.neoforge.sable.tire.api.TireRegistry.register(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cryptand", "linear_saturation"),
                    com.hdf.cryptand.neoforge.sable.tire.api.TireParams.DEFAULT,
                    com.hdf.cryptand.neoforge.sable.tire.impl.LinearSaturationTire.INSTANCE,
                    true);
        } catch (final Throwable ignored) {
        }
        try {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                    com.hdf.cryptand.neoforge.sable.force.server.SableForceService.class);
        } catch (final Throwable ignored) {
        }
        if (net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            try {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                        com.hdf.cryptand.neoforge.sable.force.client.ForceDisplayRequester.class);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                        com.hdf.cryptand.neoforge.sable.force.client.ForceDisplayRenderer.class);
            } catch (final Throwable ignored) {
            }
        }
    }
}
