package com.hdf.cryptand.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具注册表（{@code tools/list} 的数据源 + {@code tools/call} 的派发目标）。
 *
 * <p>平台侧注册；框架侧只读。同名工具后注册者覆盖（便于热更新工具实现）。</p>
 */
public final class McpToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ==================== 注册 ====================

    public synchronized McpTool register(McpTool tool) {
        tools.put(tool.name(), tool);
        fireChanged();
        return tool;
    }

    public McpTool register(String name, String description, JsonObject inputSchema, McpToolHandler handler) {
        return register(new McpTool(name, null, description, inputSchema, null, null, handler));
    }

    /** 带注解的便捷注册（readOnly/destructive 等提示） */
    public McpTool register(String name, String description, JsonObject inputSchema,
                            JsonObject annotations, McpToolHandler handler) {
        return register(new McpTool(name, null, description, inputSchema, annotations, null, handler));
    }

    /** 全字段注册（含标题与分组） */
    public McpTool register(String name, String title, String description, JsonObject inputSchema,
                            JsonObject annotations, String group, McpToolHandler handler) {
        return register(new McpTool(name, title, description, inputSchema, annotations, group, handler));
    }

    /** 批量登记一组工具（平台侧按类别注册时用） */
    public synchronized void registerAll(List<McpTool> list) {
        for (McpTool t : list) {
            tools.put(t.name(), t);
        }
        fireChanged();
    }

    public synchronized boolean unregister(String name) {
        final boolean removed = tools.remove(name) != null;
        if (removed) {
            fireChanged();
        }
        return removed;
    }

    public synchronized void clear() {
        tools.clear();
        fireChanged();
    }

    // ==================== 查询 ====================

    public synchronized McpTool get(String name) {
        return tools.get(name);
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    public synchronized List<McpTool> all() {
        return new ArrayList<>(tools.values());
    }

    public synchronized List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public synchronized int size() {
        return tools.size();
    }

    /** 清单变化监听（用于广播 notifications/tools/list_changed） */
    public void onChanged(Runnable listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 分页 ====================

    /**
     * 分页列出工具（MCP 的 cursor 是不透明字符串：这里用"上一页最后一项的名字"）。
     *
     * @param cursor 上一页返回的 nextCursor（null/空 = 第一页）
     * @param size   每页条数（&lt;=0 表示不分页，全部返回）
     */
    public synchronized Page page(String cursor, int size) {
        final List<McpTool> all = new ArrayList<>(tools.values());
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).name().equals(cursor)) {
                    start = i + 1;
                    break;
                }
            }
        }
        final JsonArray arr = new JsonArray();
        if (size <= 0 || start + size >= all.size()) {
            for (int i = start; i < all.size(); i++) {
                arr.add(all.get(i).toJson());
            }
            return new Page(arr, null);
        }
        for (int i = start; i < start + size; i++) {
            arr.add(all.get(i).toJson());
        }
        return new Page(arr, all.get(start + size - 1).name());
    }

    /** 一页工具 */
    public record Page(JsonArray tools, String nextCursor) {
    }
}
