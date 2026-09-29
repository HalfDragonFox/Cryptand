package com.hdf.cryptand.mcp;

import com.google.gson.JsonObject;

/**
 * 服务端主动消息出口（MCP 允许服务端随时推送通知：progress / logging / list_changed …）。
 *
 * <p>由传输层对每个会话注入实现：Streamable HTTP 下 = 往该会话的 SSE 流写一条事件
 * （没有流时静默丢弃）；stdio 下 = 直接写一行 NDJSON。</p>
 */
@FunctionalInterface
public interface McpNotifier {

    /** 推送一条通知（内容必须是完整的 JSON-RPC 通知对象） */
    void notify(JsonObject message);

    /** 空实现（无推送通道时使用） */
    McpNotifier NOOP = message -> {
    };
}
