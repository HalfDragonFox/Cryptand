package com.hdf.cryptand.neoforge.inc;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.inc.config.ConfigInc;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/**
 * ===== INC（集成网络核心）子包入口 =====
 * 虚拟网络（物品/流体/能量/气体/信号）传输平台；本子包承载其配置。
 */
@CryptandSubpackage(id = "inc", order = 85)
public final class IncEntry implements SubpackageEntry {

    public static final IncEntry INSTANCE = new IncEntry();

    private IncEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigInc.register();
    }

    @Override
    public boolean enabled() {
        // ⚠ 2026-08-30 INC 总开关（关闭所有支持时可一并关闭——否则每 tick 仍跑）
        try {
            return ConfigInc.SPEC.isLoaded()
                    ? ConfigInc.ENABLE_INC.get()
                    : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("inc", "enableInc", true);
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "inc.toml#enableInc";
    }

    @Override
    public void init(IEventBus bus) {
    }

    @Override
    public void tick(ServerLevel level) {
    }
}
