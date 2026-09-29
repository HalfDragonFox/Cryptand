package com.hdf.cryptand.neoforge.threading.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigThreading —— 线程调度/分配核心配置（2026-08-30 用户架构：所有配置放到
 * 相关子包——原 debug 域的线程调试键迁入）。
 */
public final class ConfigThreading implements CryptandConfigSpec {

    public static final String DOMAIN = "threading";

    public static final ConfigThreading INSTANCE = new ConfigThreading();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "threading.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "threading.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /** 线程分配调试日志开关。 */
    public static final ModConfigSpec.BooleanValue ENABLE_THREAD_DISPATCH_DEBUG = CK
            .comment("线程分配调试日志",
                     "true: 打印任务分配/执行日志(诊断用)")
            .define("enableThreadDispatchDebug", false);

    /** 线程池 HUD 开关（客户端）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_THREAD_POOL_HUD = CK
            .comment("线程池HUD",
                     "true: 屏幕显示线程池状态(调试用)")
            .define("enableThreadPoolHud", false);

    /** 线程平衡检查间隔（ms）。 */
    public static final ModConfigSpec.IntValue THREAD_BALANCE_INTERVAL_MS = CK
            .comment("线程平衡检查间隔(ms)",
                     "分配核心负载均衡检查节拍",
                     "默认100")
            .defineInRange("threadBalanceIntervalMs", 100, 10, 10000);

    public static final ModConfigSpec SPEC = CK.build();
}
