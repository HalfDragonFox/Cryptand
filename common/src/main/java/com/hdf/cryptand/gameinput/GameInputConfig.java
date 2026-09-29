package com.hdf.cryptand.gameinput;

/**
 * 游戏输入子包运行门控（2026-09-08，平台配置注入）。
 * <p>
 * 总开关由平台层（neoforge: config/cryptand/common.toml 核心域）在配置加载后注入：
 * {@link #isInputEnabled()} 关闭时枚举返回空、打开抛 {@link IllegalStateException}
 * （不加载任何后端）。当前唯一后端为 SDL2（2026-09-08 用户定稿：其余后端废弃）。
 */
public final class GameInputConfig {

    private static volatile boolean inputEnabled = true;

    private GameInputConfig() {
    }

    /** 平台层注入（neoforge 主类 commonSetup 读 ConfigCore 后调用；fabric 默认注入） */
    public static void configure(boolean inputEnabled) {
        GameInputConfig.inputEnabled = inputEnabled;
    }

    /** 游戏输入子包是否启用（false = 枚举空/open 拒绝） */
    public static boolean isInputEnabled() {
        return inputEnabled;
    }
}
