package com.hdf.cryptand.neoforge.aiauto.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hdf.cryptand.mcp.McpServer;
import com.hdf.cryptand.mcp.McpServerInfo;
import com.hdf.cryptand.mcp.resource.McpResourceRegistry;
import com.hdf.cryptand.mcp.tool.McpToolRegistry;
import com.hdf.cryptand.mcp.transport.HttpMcpConfig;
import com.hdf.cryptand.mcp.transport.HttpMcpTransport;
import com.hdf.cryptand.neoforge.aiauto.AiAutomation;
import com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto;

/**
 * ===== MCP 服务器装配（平台侧）=====
 *
 * <p>把 {@code common} 的协议框架接到 NeoForge 客户端上：</p>
 * <ol>
 *   <li><b>工具</b>：{@link com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer#register} 双写进来的
 *       aiauto 工具（注册表 {@link #registry()} 常驻，协议服务器启停不影响已注册的工具）；</li>
 *   <li><b>资源</b>：{@link AiAutoResources}（截图/报告暴露成 {@code cryptand://ai-auto/…}）；</li>
 *   <li><b>执行</b>：{@link ClientTickExecutor}（工具在主线程 tick 执行，HTTP 线程等待结果）；</li>
 *   <li><b>传输</b>：{@link HttpMcpTransport}（Streamable HTTP + 旧版 HTTP+SSE）。</li>
 * </ol>
 *
 * <p>启停由 {@code aiauto.toml} 门控（{@code enableAiAutomation && mcpEnabled}），
 * 配置热重载时经 {@link #syncFromConfig()} 自动生效；也可用
 * {@code /cryptand aiauto mcp start|stop|status} 手动控制。</p>
 */
public final class AiMcpServer {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final String SERVER_NAME = "cryptand-aiauto";

    /** 服务器版本（跟 mod 版本对齐即可，仅用于 MCP serverInfo 展示） */
    private static final String SERVER_VERSION = "1.0.0";

    /** 工具注册表：常驻（工具在 AiTools.registerAll 时注册进来，与服务器启停无关） */
    private static final McpToolRegistry TOOLS = new McpToolRegistry();
    private static final McpResourceRegistry RESOURCES = new McpResourceRegistry();
    private static final ClientTickExecutor EXECUTOR = new ClientTickExecutor();

    private static volatile McpServer server;
    private static volatile HttpMcpTransport transport;
    private static volatile String lastError = "";
    private static volatile String boundHost = "";
    private static volatile int boundPort = -1;

    private AiMcpServer() {
    }

    // ==================== 访问器 ====================

    /** 工具注册表（{@code AiToolServer.register} 双写目标；始终可用） */
    public static McpToolRegistry registry() {
        return TOOLS;
    }

    public static boolean running() {
        final HttpMcpTransport t = transport;
        return t != null && t.running();
    }

    /** 接入地址（未启动时返回配置里的地址） */
    public static String endpoint() {
        final HttpMcpTransport t = transport;
        return t != null && t.running() ? t.endpoint()
                : "http://" + ConfigAiauto.mcpHost() + ":" + ConfigAiauto.mcpPort() + "/mcp（未启动）";
    }

    public static String lastError() {
        return lastError;
    }

    // ==================== 驱动 ====================

    /** 客户端每帧调用（主线程）：执行排队的 MCP 工具任务 */
    public static void tick() {
        EXECUTOR.tick();
    }

    /** 配置热重载 / 手动同步：按当前配置启停（幂等；端口或主机变了会重启） */
    public static synchronized void syncFromConfig() {
        try {
            final boolean wanted = AiAutomation.allowed() && ConfigAiauto.mcpEnabled();
            final String host = ConfigAiauto.mcpHost();
            final int port = ConfigAiauto.mcpPort();
            if (!wanted) {
                if (running()) {
                    stop();
                    LOGGER.info("[aiauto/mcp] 配置关闭 → MCP 服务器已停止");
                }
                return;
            }
            if (running() && host.equals(boundHost) && port == boundPort) {
                return;
            }
            if (running()) {
                stop();
            }
            start();
        } catch (Throwable ex) {
            lastError = String.valueOf(ex);
            LOGGER.warn("[aiauto/mcp] 同步配置失败", ex);
        }
    }

    /** 启动（幂等；失败只记录，不抛给调用方 —— MCP 不可用不能拖垮客户端） */
    public static synchronized void start() {
        if (running()) {
            return;
        }
        try {
            final McpServer created = new McpServer(info(), TOOLS, RESOURCES, EXECUTOR);
            created.toolTimeoutMs(ConfigAiauto.mcpToolTimeoutMs());
            if (RESOURCES.providers().isEmpty()) {
                RESOURCES.addProvider(new AiAutoResources());
            }
            final HttpMcpConfig config = new HttpMcpConfig()
                    .host(ConfigAiauto.mcpHost())
                    .port(ConfigAiauto.mcpPort())
                    .legacySse(ConfigAiauto.mcpLegacySse())
                    .allowRemote(ConfigAiauto.mcpAllowRemote())
                    .token(ConfigAiauto.mcpToken())
                    .requestTimeoutMs(ConfigAiauto.mcpToolTimeoutMs() + 15_000L);
            final HttpMcpTransport created2 = new HttpMcpTransport(created, config)
                    .log(message -> LOGGER.info("[aiauto/mcp] {}", message));
            created2.start();

            server = created;
            transport = created2;
            boundHost = config.host;
            boundPort = created2.port();
            lastError = "";
            LOGGER.info("[aiauto/mcp] MCP 服务器就绪：{} ｜ 工具 {} 个，资源 {} 个（协议 {}）",
                    created2.endpoint(), TOOLS.size(), RESOURCES.list().size(),
                    com.hdf.cryptand.mcp.Mcp.VERSION_LATEST);
        } catch (Throwable ex) {
            lastError = String.valueOf(ex);
            LOGGER.error("[aiauto/mcp] MCP 服务器启动失败（端口占用？）：{}", lastError, ex);
            stop();
        }
    }

    /** 停止（幂等） */
    public static synchronized void stop() {
        final HttpMcpTransport t = transport;
        transport = null;
        server = null;
        boundHost = "";
        boundPort = -1;
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 重启（改端口/改开关后手动用） */
    public static synchronized void restart() {
        stop();
        start();
    }

    // ==================== 诊断 ====================

    /** 状态快照（命令 / 诊断工具用） */
    public static JsonObject status() {
        final JsonObject o = new JsonObject();
        o.addProperty("running", running());
        o.addProperty("endpoint", endpoint());
        o.addProperty("configEnabled", ConfigAiauto.mcpEnabled());
        o.addProperty("gated", AiAutomation.allowed());
        o.addProperty("host", ConfigAiauto.mcpHost());
        o.addProperty("port", boundPort > 0 ? boundPort : ConfigAiauto.mcpPort());
        o.addProperty("legacySse", ConfigAiauto.mcpLegacySse());
        o.addProperty("tokenRequired", !ConfigAiauto.mcpToken().isEmpty());
        o.addProperty("tools", TOOLS.size());
        o.addProperty("resources", RESOURCES.list().size());
        o.addProperty("queued", EXECUTOR.pending());
        o.addProperty("hostTasksExecuted", EXECUTOR.executed());
        o.addProperty("hostTasksFailed", EXECUTOR.failed());
        if (!lastError.isEmpty()) {
            o.addProperty("lastError", lastError);
        }
        final McpServer s = server;
        if (s != null) {
            o.addProperty("sessions", s.sessions().size());
            o.addProperty("toolCalls", s.stats().get("toolCalls").getAsInt());
            o.addProperty("toolErrors", s.stats().get("toolErrors").getAsInt());
        }
        final JsonArray names = new JsonArray();
        for (String name : TOOLS.names()) {
            names.add(name);
        }
        o.add("toolNames", names);
        return o;
    }

    /** 人类可读状态（聊天栏/日志） */
    public static String statusText() {
        final JsonObject s = status();
        final StringBuilder sb = new StringBuilder();
        sb.append("MCP ").append(s.get("running").getAsBoolean() ? "运行中" : "未运行");
        sb.append("  endpoint=").append(s.get("endpoint").getAsString()).append('\n');
        sb.append("tools=").append(s.get("tools").getAsInt())
                .append(" resources=").append(s.get("resources").getAsInt())
                .append(" queued=").append(s.get("queued").getAsInt()).append('\n');
        sb.append("legacySse=").append(s.get("legacySse").getAsBoolean())
                .append(" token=").append(s.get("tokenRequired").getAsBoolean() ? "需要" : "无")
                .append(" 门控(enableAiAutomation)=").append(s.get("gated").getAsBoolean());
        if (s.has("lastError")) {
            sb.append("\n上次错误：").append(s.get("lastError").getAsString());
        }
        return sb.toString();
    }

    // ==================== 服务器自述 ====================

    private static McpServerInfo info() {
        return new McpServerInfo(SERVER_NAME, "Cryptand aiauto", SERVER_VERSION,
                """
                        本服务器操作一个正在运行的 Minecraft 客户端（NeoForge 1.21.1 + Cryptand aiauto）。

                        能力类别（工具描述里的 requires 字段）：
                        · world   —— 直接读写服务端世界（建造/扫描/查询），不需要玩家在线，也不受客户端视距限制；
                        · player  —— 需要玩家存在（替玩家敲命令、传送、物品、时间天气）；
                        · ui      —— 需要客户端界面/渲染（LDLib2 界面交互、展示方块、截图）；
                        · session —— 会话与诊断（状态、队列、日志）。

                        使用约定：
                        · 坐标支持原版相对写法 "~"；方块/物品名可写简名（stone → minecraft:stone）；
                        · run_command 是万能通道：原版命令能做的都能做；
                        · 工具失败会以 isError 返回，文本以 ERR 开头 —— 请读 message 而不是只看成功与否；
                        · 跨帧流程不要 sleep 猜时间：用 run_plan 提交步骤数组（可含 {"wait":{...}}），
                          再用 plan_status 轮询 done；
                        · 截图与报告在 resources 里（cryptand://ai-auto/…）：resources/list 发现、
                          resources/read 读取；PNG 以 base64 blob 返回，可直接看图。

                        注意：所有工具都在游戏主线程按序执行，一帧最多处理 64 个任务；批量操作请用
                        place_batch / run_plan，不要一次发起成百上千个单独调用。""");
    }
}
