package com.hdf.cryptand.neoforge.pipez;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.pipez.config.ConfigPipez;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/**
 * Pipez（管道）模块入口（2026-08-30 子包隔离：随本子包删除而消失；
 * core 经 SubpackageLoader 反射加载）。
 *
 * 用户架构：与 MC 有关的 mod 联动为单独子包 + 单独开关——
 * enabled=false（enablePipezSupport=false 或 pipez 未加载）→ 本子包
 * 【全部内容不加载】（init/tick/commands 跳过；Mixin 逻辑运行时短路）。
 * 核心引擎与其他子包不受影响。
 */
@CryptandSubpackage(id = "pipez", order = 35)
public final class PipezEntry implements SubpackageEntry {

    public static final PipezEntry INSTANCE = new PipezEntry();

    private PipezEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigPipez.register();
    }

    @Override
    public boolean enabled() {
        return PipezModule.shouldLoad();
    }

    @Override
    public String conditionDesc() {
        return "pipez.toml#enablePipezSupport && pipez mod loaded";
    }

    @Override
    public void init(IEventBus bus) {
        PipezModule.register(bus);
    }

    @Override
    public void tick(ServerLevel level) {
        PipezModule.tick(level);
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：内容 + mixin 一起注册；
        // 统一由 SubpackageLoader.loadableMixinConfigs() 按 enabled 返回）
        return java.util.List.of("cryptand.pipez.mixins.json");
    }
}
