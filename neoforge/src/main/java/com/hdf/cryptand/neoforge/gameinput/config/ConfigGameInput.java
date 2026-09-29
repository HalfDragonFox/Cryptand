package com.hdf.cryptand.neoforge.gameinput.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigGameInput —— 硬件 I/O（游戏输入后端）配置（2026-08-30 用户架构：所有配置
 * 放到相关子包——原 core 域 common.toml#enableGameInput 迁入 gameinput 子包）。
 *
 * 后端实现位于 common {@code com.hdf.cryptand.gameinput}（SDL2 跨平台；
 * 懒加载生命周期：使用前 load，未使用零资源占用）。
 */
public final class ConfigGameInput implements CryptandConfigSpec {

    public static final String DOMAIN = "gameinput";

    public static final ConfigGameInput INSTANCE = new ConfigGameInput();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "gameinput.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "gameinput.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /**
     * 通用游戏输入后端总开关（gameinput.toml#enableGameInput）：整个 gameinput
     * 子包门控。false 时输入设备枚举返回空、打开被拒绝（不加载任何后端/不访问硬件）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_GAME_INPUT = CK
            .comment("游戏输入支持总开关",
                     "true(默认): 启用通用游戏输入后端(SDL2跨平台,方向盘/手柄/摇杆+力反馈)",
                     "false: 整个gameinput子包禁用(枚举空/open拒绝,不加载后端)",
                     "需要重启游戏生效")
            .define("enableGameInput", true);

    public static final ModConfigSpec SPEC = CK.build();
}
