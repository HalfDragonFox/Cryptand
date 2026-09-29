package com.hdf.cryptand.neoforge.cee;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import com.hdf.cryptand.neoforge.cee.config.ConfigCee;

/** CEE 模块入口（含 CEE×sable 联动观察者）（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "cee", order = 30)
public final class CeeEntry implements SubpackageEntry {
    public static final CeeEntry INSTANCE = new CeeEntry();
    private CeeEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigCee.register();
    }

    @Override
    public boolean enabled() {
        return CeeModule.shouldLoad();
    }

    @Override
    public String conditionDesc() {
        return "cee support && cee mod loaded";
    }

    @Override
    public void init(IEventBus bus) {
        CeeModule.register(bus);
    }

    @Override
    public void tick(ServerLevel level) {
        CeeModule.tick(level);
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：内容 + mixin 一起注册；
        // 统一由 SubpackageLoader.loadableMixinConfigs() 按 enabled 返回）
        return java.util.List.of("cryptand.cee.mixins.json", "cryptand.sable.cee.mixins.json");
    }
}
