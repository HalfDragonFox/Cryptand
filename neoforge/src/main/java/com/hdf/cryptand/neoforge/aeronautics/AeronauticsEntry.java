package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;

/** Aeronautics 模块入口（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "aeronautics", order = 50)
public final class AeronauticsEntry implements SubpackageEntry {
    public static final AeronauticsEntry INSTANCE = new AeronauticsEntry();
    private AeronauticsEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigAero.register();
    }

    @Override
    public boolean enabled() {
        return AeronauticsModule.shouldLoad();
    }

    @Override
    public String conditionDesc() {
        return "aeronautics mod loaded";
    }

    @Override
    public void init(IEventBus bus) {
        AeronauticsModule.register(bus);
    }

    @Override
    public void tick(ServerLevel level) {
        AeronauticsModule.tick(level);
    }

    @Override
    public void commonSetup() {
        try {
            AeronauticsCompat.init();
        } catch (Throwable t) {
            // 未装自动跳过
        }
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：内容 + mixin 一起注册；
        // 统一由 SubpackageLoader.loadableMixinConfigs() 按 enabled 返回）
        return java.util.List.of("cryptand.aeronautics.mixins.json", "cryptand.sable.aeronautics.mixins.json");
    }
}
