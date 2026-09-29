package com.hdf.cryptand.mcp.tool;

import com.google.gson.JsonObject;

import java.util.function.Function;

/**
 * 工具实现（框架不假设线程：由 {@link com.hdf.cryptand.mcp.McpExecutor} 决定在哪个线程执行）。
 *
 * <p>参数校验失败抛 {@link com.hdf.cryptand.mcp.McpError#invalidParams}，业务失败返回
 * {@link McpToolResult#error}；两者客户端都能看懂。</p>
 */
@FunctionalInterface
public interface McpToolHandler {

    McpToolResult call(JsonObject args) throws Exception;

    /**
     * 适配"返回 JsonObject"的老式 handler（Cryptand 的 aiauto 工具都是这个形态）。
     *
     * <p>约定：返回对象里若有 {@code error} 字段或 {@code message} 以 {@code ERR} 开头，
     * 视为工具失败（{@code isError:true}），与文件通道的判定保持一致。</p>
     */
    static McpToolHandler ofJson(Function<JsonObject, JsonObject> handler) {
        return args -> {
            final JsonObject result = handler.apply(args == null ? new JsonObject() : args);
            if (result == null) {
                return McpToolResult.text("OK");
            }
            final boolean failed = result.has("error")
                    || (result.has("message") && result.get("message").getAsString().startsWith("ERR"));
            return failed ? McpToolResult.structured(result).asError()
                    : McpToolResult.structured(result);
        };
    }
}
