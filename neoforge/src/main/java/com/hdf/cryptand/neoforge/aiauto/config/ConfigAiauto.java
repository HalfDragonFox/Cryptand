package com.hdf.cryptand.neoforge.aiauto.config;

import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigAiauto —— AI 自动化配置域（aiauto.toml，2026-09-15）。
 *
 * <p>子包 {@code aiauto} 给不同 mod 提供"AI 自动化接口"（打开界面、导出布局、截图、调试器）。
 * <b>默认全关</b>：这些能力会读取并写出界面内容，属于调试/自动化用途，必须显式开启。</p>
 */
public final class ConfigAiauto implements CryptandConfigSpec {

    public static final String DOMAIN = "aiauto";

    public static final ConfigAiauto INSTANCE = new ConfigAiauto();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public String fileName() {
        return "aiauto.toml";
    }

    @Override
    public ModConfigSpec spec() {
        return SPEC;
    }

    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "aiauto.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /** 总开关 */
    public static final ModConfigSpec.BooleanValue ENABLE_AI_AUTOMATION = CK
            .comment("AI 自动化总开关（默认 false）",
                    "true : 开放 /cryptand aiauto 的全部能力——导出布局/界面描述、截图、",
                    "       程序化打开界面并在延迟若干帧后自动截图（供 AI 读取）",
                    "false(默认): 全部关闭，命令只提示开关位置；发布环境请保持 false",
                    "注意：开启后会把界面内容写入 <游戏目录>/cryptand/ai-auto/")
            .define("enableAiAutomation", false);

    /** 自动捕获 */
    public static final ModConfigSpec.BooleanValue AUTO_CAPTURE = CK
            .comment("目标就绪时自动导出（无需敲命令）")
            .define("autoCapture", true);

    /** 截图延迟帧数 */
    public static final ModConfigSpec.IntValue CAPTURE_DELAY_FRAMES = CK
            .comment("程序化打开后等待多少帧再截图（等布局/字体稳定；20 帧 ≈ 1 秒）")
            .defineInRange("captureDelayFrames", 20, 0, 600);

    /** 允许程序化打开 */
    public static final ModConfigSpec.BooleanValue ALLOW_AUTO_OPEN = CK
            .comment("允许程序化打开界面（不需要玩家手动操作）")
            .define("allowAutoOpen", true);

    // ==================== MCP（真 MCP 服务器，2026-09-16）====================

    public static final ModConfigSpec.BooleanValue MCP_ENABLED = CK
            .comment("真 MCP 服务器（Model Context Protocol）：把 aiauto 工具暴露成标准 MCP 工具",
                    "true(默认): 本地起 Streamable HTTP 服务 —— 标准 MCP 客户端（Claude Desktop /",
                    "            Cline / MCP Inspector / 自研客户端）可直接接入；同时把",
                    "            cryptand/ai-auto/ 的截图与报告暴露成 MCP 资源（PNG 走 base64）",
                    "false: 只保留文件 legacy 通道（calls/ → results/，它不是 MCP）",
                    "生效条件：enableAiAutomation 必须为 true（总开关优先）")
            .define("mcpEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> MCP_HOST = CK
            .comment("MCP 监听地址（默认仅本机回环；改成 0.0.0.0 需同时打开 mcpAllowRemote）")
            .define("mcpHost", "127.0.0.1");

    public static final ModConfigSpec.IntValue MCP_PORT = CK
            .comment("MCP 监听端口（Streamable HTTP 端点：http://<host>:<port>/mcp）")
            .defineInRange("mcpPort", 8765, 1, 65535);

    public static final ModConfigSpec.BooleanValue MCP_LEGACY_SSE = CK
            .comment("同时开放旧版 HTTP+SSE 端点（/sse + /message，2024-11-05 规范的老客户端用）")
            .define("mcpLegacySse", true);

    public static final ModConfigSpec.ConfigValue<String> MCP_TOKEN = CK
            .comment("可选 Bearer 令牌（非空时要求 Authorization: Bearer <token>）",
                    "仅本机监听时通常不需要；一旦对外监听强烈建议设置")
            .define("mcpToken", "");

    public static final ModConfigSpec.BooleanValue MCP_ALLOW_REMOTE = CK
            .comment("允许监听非回环地址（安全阀；默认 false 时非回环地址会拒绝启动）")
            .define("mcpAllowRemote", false);

    public static final ModConfigSpec.IntValue MCP_TOOL_TIMEOUT_MS = CK
            .comment("单次工具调用的等待上限（毫秒）")
            .defineInRange("mcpToolTimeoutMs", 60000, 1000, 600000);

    public static final ModConfigSpec.BooleanValue FILE_CHANNEL_ENABLED = CK
            .comment("文件 legacy 通道（calls/<id>.json → results/<id>.json）",
                    "true(默认): 保留 —— 给只能读写文件、不能发 HTTP 的调用方兜底",
                    "false: 只走 MCP；calls/ 不再被扫描（run_plan 的队列推进不受影响）")
            .define("fileChannelEnabled", true);

    public static final ModConfigSpec SPEC = CK.build();

    // ==================== 运行期读取（spec 未加载时回退默认） ====================

    /** 构造期安全读取（与其它子包一致） */
    public static boolean enabled() {
        return ConfigLoad.preloadBoolean(DOMAIN, "enableAiAutomation", false);
    }

    public static boolean enableAiAutomation() {
        return safeBool(ENABLE_AI_AUTOMATION, false);
    }

    public static boolean autoCapture() {
        return safeBool(AUTO_CAPTURE, true);
    }

    public static int captureDelayFrames() {
        return safeInt(CAPTURE_DELAY_FRAMES, 20);
    }

    public static boolean allowAutoOpen() {
        return safeBool(ALLOW_AUTO_OPEN, true);
    }

    // ==================== MCP 读取 ====================

    public static boolean mcpEnabled() {
        return safeBool(MCP_ENABLED, true);
    }

    public static String mcpHost() {
        return safeStr(MCP_HOST, "127.0.0.1");
    }

    public static int mcpPort() {
        return safeInt(MCP_PORT, 8765);
    }

    public static boolean mcpLegacySse() {
        return safeBool(MCP_LEGACY_SSE, true);
    }

    public static String mcpToken() {
        return safeStr(MCP_TOKEN, "");
    }

    public static boolean mcpAllowRemote() {
        return safeBool(MCP_ALLOW_REMOTE, false);
    }

    public static int mcpToolTimeoutMs() {
        return safeInt(MCP_TOOL_TIMEOUT_MS, 60000);
    }

    public static boolean fileChannelEnabled() {
        return safeBool(FILE_CHANNEL_ENABLED, true);
    }

    private static String safeStr(ModConfigSpec.ConfigValue<String> value, String def) {
        try {
            final String v = value.get();
            return v == null ? def : v;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean safeBool(ModConfigSpec.BooleanValue value, boolean def) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int safeInt(ModConfigSpec.IntValue value, int def) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            return def;
        }
    }
}
