package com.hdf.cryptand.mcp.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 资源注册表（{@code resources/list} / {@code resources/read} 的数据源）。
 *
 * <p>多个提供者的资源合并成一份清单；读取时按注册顺序询问，第一个返回非 null 的胜出。</p>
 */
public final class McpResourceRegistry {

    private final List<McpResourceProvider> providers = new CopyOnWriteArrayList<>();

    public void addProvider(McpResourceProvider provider) {
        if (provider != null) {
            providers.add(provider);
        }
    }

    public List<McpResourceProvider> providers() {
        return new ArrayList<>(providers);
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    /** 全量枚举（提供者异常会被吞掉，不影响其它提供者） */
    public List<McpResource> list() {
        final List<McpResource> out = new ArrayList<>();
        for (McpResourceProvider p : providers) {
            try {
                final List<McpResource> items = p.list();
                if (items != null) {
                    out.addAll(items);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 分页列出（cursor = 上一页最后一项的 uri） */
    public Page page(String cursor, int size) {
        final List<McpResource> all = list();
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).uri().equals(cursor)) {
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
        return new Page(arr, all.get(start + size - 1).uri());
    }

    /** 全部资源模板 */
    public JsonArray templates() {
        final JsonArray arr = new JsonArray();
        for (McpResourceProvider p : providers) {
            try {
                for (McpResourceProvider.ResourceTemplate t : p.templates()) {
                    arr.add(t.toJson());
                }
            } catch (Throwable ignored) {
            }
        }
        return arr;
    }

    /**
     * 读取资源。
     *
     * @return 内容，或 {@code null}（没有任何提供者认识这个 uri）
     */
    public McpResourceContent read(String uri) throws Exception {
        for (McpResourceProvider p : providers) {
            final McpResourceContent content = p.read(uri);
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    public record Page(JsonArray resources, String nextCursor) {
    }
}
