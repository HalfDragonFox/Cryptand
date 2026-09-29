package com.hdf.cryptand.neoforge.railway;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/** Railway 模块入口（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "railway", order = 20)
public final class RailwayEntry implements SubpackageEntry {
    public static final RailwayEntry INSTANCE = new RailwayEntry();
    private RailwayEntry() {
    }

    @Override
    public boolean enabled() {
        return RailwayModule.shouldLoad();
    }

    @Override
    public String conditionDesc() {
        return "railway registry ready";
    }

    @Override
    public void init(IEventBus bus) {
        RailwayModule.register(bus);
    }

    @Override
    public void tick(ServerLevel level) {
        RailwayModule.tick(level);
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：内容 + mixin 一起注册；
        // 统一由 SubpackageLoader.loadableMixinConfigs() 按 enabled 返回）
        return java.util.List.of("cryptand.railway.mixins.json");
    }
}
