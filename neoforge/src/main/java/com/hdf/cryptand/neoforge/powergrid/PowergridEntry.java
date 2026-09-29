package com.hdf.cryptand.neoforge.powergrid;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;

/** PowerGrid 模块入口（子包隔离入口：随本子包删除而消失；core 经 SubpackageLoader 反射加载）。 */
@CryptandSubpackage(id = "powergrid", order = 10)
public final class PowergridEntry implements SubpackageEntry {
    public static final PowergridEntry INSTANCE = new PowergridEntry();
    private PowergridEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigPowerGrid.register();
    }

    @Override
    public boolean enabled() {
        return PowergridModule.shouldLoad();
    }

    @Override
    public String conditionDesc() {
        return "powergrid.toml#enablePowergridSupport";
    }

    @Override
    public void init(IEventBus bus) {
        PowergridModule.register(bus);
    }

    @Override
    public void tick(ServerLevel level) {
        PowergridModule.tick(level);
    }

    @Override
    public void commands() {
        // /cryptand transformer|device|...（子命令经 CryptandRegistries 挂根；
        // ModConfigEvent 后 config 已加载才执行——命令可读 spec）
        PowergridCommands.register();
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        // ⚠ 2026-08-30 子包主类声明本子包 Mixin（类 mod 化：内容 + mixin 一起注册；
        // 统一由 SubpackageLoader.loadableMixinConfigs() 按 enabled 返回）
        return java.util.List.of("cryptand.powergrid.mixins.json", "cryptand.sable.powergrid.mixins.json");
    }
}
