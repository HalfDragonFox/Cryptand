package com.hdf.cryptand.neoforge.threading;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.threading.config.ConfigThreading;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/**
 * ===== 线程调度（分配核心）子包入口 =====
 * 任务分配核心/线程池/HUD；本子包承载其配置。
 */
@CryptandSubpackage(id = "threading", order = 92)
public final class ThreadingEntry implements SubpackageEntry {

    public static final ThreadingEntry INSTANCE = new ThreadingEntry();

    private ThreadingEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigThreading.register();
    }

    @Override
    public String conditionDesc() {
        return "threading.toml (always)";
    }

    @Override
    public void init(IEventBus bus) {
    }

    @Override
    public void tick(ServerLevel level) {
    }
}
