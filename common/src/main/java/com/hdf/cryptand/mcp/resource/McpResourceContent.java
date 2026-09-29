package com.hdf.cryptand.mcp.resource;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 资源内容（{@code resources/read} 的 contents 项）。
 *
 * <p>文本走 {@code text}，二进制走 {@code blob}（base64）—— 二者只能有一个，
 * MCP 客户端据此决定怎么交给模型（图片会当图片给多模态模型）。</p>
 */
public record McpResourceContent(String uri, String mimeType, String text, byte[] blob) {

    public static McpResourceContent text(String uri, String mimeType, String text) {
        return new McpResourceContent(uri, mimeType, text, null);
    }

    public static McpResourceContent blob(String uri, String mimeType, byte[] bytes) {
        return new McpResourceContent(uri, mimeType, null, bytes);
    }

    /** 自动判断文本/二进制（UTF-8 可解码且无 NUL 视为文本） */
    public static McpResourceContent auto(String uri, String mimeType, byte[] bytes) {
        if (bytes == null) {
            return text(uri, mimeType, "");
        }
        if (looksTextual(bytes)) {
            return text(uri, mimeType, new String(bytes, StandardCharsets.UTF_8));
        }
        return blob(uri, mimeType, bytes);
    }

    private static boolean looksTextual(byte[] bytes) {
        final int probe = Math.min(bytes.length, 4096);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isText() {
        return text != null;
    }

    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("uri", uri);
        if (mimeType != null && !mimeType.isBlank()) {
            o.addProperty("mimeType", mimeType);
        }
        if (text != null) {
            o.addProperty("text", text);
        } else if (blob != null) {
            o.addProperty("blob", Base64.getEncoder().encodeToString(blob));
        }
        return o;
    }
}
