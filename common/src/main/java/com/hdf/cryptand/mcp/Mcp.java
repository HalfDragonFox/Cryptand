package com.hdf.cryptand.mcp;

import java.util.List;

/**
 * ===== MCP（Model Context Protocol）协议常量 =====
 *
 * <p>本包是 <b>协议框架</b>，纯 Java 零 MC 依赖：JSON-RPC 2.0 编解码 + MCP 生命周期 +
 * 工具/资源注册表 + 传输抽象（Streamable HTTP / 旧版 HTTP+SSE / stdio）。</p>
 *
 * <p>具体平台（NeoForge / Fabric / 独立进程）只实现两件事：</p>
 * <ol>
 *   <li><b>执行调度</b> —— {@link McpExecutor}：把工具调用投递到宿主线程（如 MC 客户端 tick）；</li>
 *   <li><b>能力注册</b> —— 往 {@link com.hdf.cryptand.mcp.tool.McpToolRegistry} 注册工具、
 *       往 {@link com.hdf.cryptand.mcp.resource.McpResourceRegistry} 注册资源提供者。</li>
 * </ol>
 *
 * <p>规范依据：Model Context Protocol {@code 2025-06-18}（含 2025-03-26 / 2024-11-05 兼容）。</p>
 */
public final class Mcp {

    private Mcp() {
    }

    // ==================== 协议版本 ====================

    /** 最新规范版本（服务端默认回显/声明） */
    public static final String VERSION_LATEST = "2025-06-18";

    /**
     * 支持的版本，从新到旧（initialize 协商：客户端请求的版本在列表里 → 原样回显；否则回最新）。
     *
     * <p>⚠ 只声明<b>本框架完整实现</b>的版本：后续版本要么新增了未实现的特性（2025-11-25 的
     * tasks/elicitation），要么（2026-07-28，当前 latest）<b>移除了 initialize 握手与
     * {@code Mcp-Session-Id}</b>——那是另一套架构。客户端拿到自己不支持的版本时按规范断开，
     * 这比谎报支持更安全。</p>
     */
    public static final List<String> VERSIONS = List.of(
            "2025-06-18",   // Streamable HTTP、结构化工具输出、elicitation
            "2025-03-26",   // Streamable HTTP 引入、OAuth
            "2024-11-05",   // 首版：HTTP+SSE + stdio
            "2024-10-07");  // 早期草案（兼容极老客户端）

    // ==================== JSON-RPC 方法名 ====================

    public static final String M_INITIALIZE = "initialize";
    public static final String M_INITIALIZED = "notifications/initialized";
    public static final String M_PING = "ping";
    public static final String M_TOOLS_LIST = "tools/list";
    public static final String M_TOOLS_CALL = "tools/call";
    public static final String M_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    public static final String M_RESOURCES_LIST = "resources/list";
    public static final String M_RESOURCES_READ = "resources/read";
    public static final String M_RESOURCES_TEMPLATES_LIST = "resources/templates/list";
    public static final String M_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";
    public static final String M_RESOURCES_UPDATED = "notifications/resources/updated";
    public static final String M_PROMPTS_LIST = "prompts/list";
    public static final String M_PROMPTS_GET = "prompts/get";
    public static final String M_LOGGING_SET_LEVEL = "logging/setLevel";
    public static final String M_NOTIFY_MESSAGE = "notifications/message";
    public static final String M_NOTIFY_PROGRESS = "notifications/progress";
    public static final String M_NOTIFY_CANCELLED = "notifications/cancelled";

    /**
     * JSON-RPC 保留前缀：以 {@code rpc.} 开头的方法名属 JSON-RPC 内部（MCP 未另作规定）。
     *
     * <p>注意：{@code $/} 是 LSP 的约定，<b>不属于</b> JSON-RPC 2.0 与 MCP 任何版本，不要实现它。</p>
     */
    public static final String RESERVED_PREFIX_RPC = "rpc.";

    /** {@code logging/setLevel} 合法级别（RFC 5424 syslog，MCP 规范枚举） */
    public static final List<String> LOG_LEVELS = List.of(
            "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency");

    // ==================== HTTP 头 ====================

    /** 会话标识（initialize 响应下发，后续请求回传） */
    public static final String H_SESSION_ID = "Mcp-Session-Id";
    /** 协议版本头（2025-06-18 起：客户端在后续 HTTP 请求中必须带上） */
    public static final String H_PROTOCOL_VERSION = "MCP-Protocol-Version";
    /** SSE 断线重连：客户端回传最后收到的事件 id */
    public static final String H_LAST_EVENT_ID = "Last-Event-ID";

    // ==================== 内容类型 ====================

    public static final String CT_JSON = "application/json";
    public static final String CT_SSE = "text/event-stream";

    public static final String C_TEXT = "text";
    public static final String C_IMAGE = "image";
    public static final String C_AUDIO = "audio";
    public static final String C_RESOURCE = "resource";
    public static final String C_RESOURCE_LINK = "resource_link";

    // ==================== JSON-RPC / MCP 错误码 ====================

    public static final int E_PARSE = -32700;             // 解析失败
    public static final int E_INVALID_REQUEST = -32600;   // 非法请求
    public static final int E_METHOD_NOT_FOUND = -32601;  // 方法不存在
    public static final int E_INVALID_PARAMS = -32602;    // 参数非法
    public static final int E_INTERNAL = -32603;          // 内部错误
    /** MCP 扩展：资源不存在（resources/read；规范 2025-06-18 定义，2026-07-28 改为 -32602） */
    public static final int E_RESOURCE_NOT_FOUND = -32002;
    /**
     * 服务器尚未完成 initialize（初始化前的其它请求）。
     *
     * <p>⚠ 规范<b>未规定</b>此时该回什么码；本实现取 -32003 —— 落在规范预留的实现自定义区
     * {@code -32000..-32019}，不会与未来的 MCP 分配冲突。</p>
     */
    public static final int E_NOT_INITIALIZED = -32003;
}
