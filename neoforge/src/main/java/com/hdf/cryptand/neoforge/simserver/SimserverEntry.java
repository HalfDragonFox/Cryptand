package com.hdf.cryptand.neoforge.simserver;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * SimServer（本地内核仿真 HTTP 接口）子包入口 —— 2026-09-07 子包隔离迁移：
 * 原由 CryptandNeoForge 构造器直持（import SimHttpServer + ServerStarted/Stopping
 * 监听）→ 改为本 Entry（core 经 SubpackageLoader 反射加载；删除本子包目录 →
 * Entry 缺失 → 自动跳过，core 零编译引用）。
 */
@CryptandSubpackage(id = "simserver", order = 90)
public final class SimserverEntry implements SubpackageEntry {

    public static final SimserverEntry INSTANCE = new SimserverEntry();

    private SimserverEntry() {
    }

    @Override
    public void registerConfigs() {
        // ⚠ 2026-08-30 用户架构：所有配置放到相关子包（原 core 域键迁入本子包）
        com.hdf.cryptand.neoforge.simserver.config.ConfigSimserver.register();
    }

    @Override
    public boolean enabled() {
        // 子包开关（引擎对外 HTTP 接口）：false → 不注册启停监听（接口不启动）
        try {
            return com.hdf.cryptand.neoforge.simserver.config.ConfigSimserver.SPEC.isLoaded()
                    ? com.hdf.cryptand.neoforge.simserver.config.ConfigSimserver
                            .ENABLE_SIM_SERVER.get()
                    : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("simserver", "enableSimServer", true);
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "common.toml#enableSimServer";
    }

    @Override
    public void init(IEventBus bus) {
        // 服务随服务器启停（enabled=false → 本方法不调用）
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStartedEvent ev) -> {
                    try {
                        SimHttpServer.get().start();
                    } catch (final Throwable ignored) {
                    }
                });
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppingEvent ev) -> {
                    try {
                        SimHttpServer.get().stop();
                    } catch (final Throwable ignored) {
                    }
                });
    }
}
