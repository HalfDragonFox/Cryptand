package com.hdf.cryptand.neoforge.create;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/** Create 模块入口（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "create", order = 40)
public final class CreateEntry implements SubpackageEntry {
    public static final CreateEntry INSTANCE = new CreateEntry();
    private CreateEntry() {
    }

    @Override
    public boolean enabled() {
        return CreateModule.shouldLoad();
    }

    @Override
    public void init(IEventBus bus) {
        // 子包自治注册自己的配置（core 只提供注册接口；create.toml）
        com.hdf.cryptand.neoforge.create.config.ConfigCreate.register();
        CreateModule.register(bus);
    }

    @Override
    public void tick(ServerLevel level) {
        CreateModule.tick(level);
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：内容 + mixin 一起注册；
        // 统一由 SubpackageLoader.loadableMixinConfigs() 按 enabled 返回）
        return java.util.List.of("cryptand.create.mixins.json");
    }
}
