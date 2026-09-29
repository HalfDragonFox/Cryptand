package com.hdf.cryptand.neoforge.aiauto.mcp.server;

import com.hdf.cryptand.mcp.McpError;
import com.hdf.cryptand.mcp.resource.McpResource;
import com.hdf.cryptand.mcp.resource.McpResourceContent;
import com.hdf.cryptand.mcp.resource.McpResourceProvider;
import com.hdf.cryptand.neoforge.aiauto.AiAutomation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * ===== aiauto 产物 → MCP 资源（平台实现）=====
 *
 * <p>把 {@code <gameDir>/cryptand/ai-auto/} 里的产物暴露成标准 MCP 资源，客户端可以：</p>
 * <ul>
 *   <li>{@code resources/list} 发现"现在有哪些报告/截图"；</li>
 *   <li>{@code resources/read} 直接读内容 —— 文本走 {@code text}，
 *       <b>PNG 截图走 {@code blob}（base64）</b>，多模态模型能真的"看到画面"，
 *       不必再让 AI 去翻文件系统。</li>
 * </ul>
 *
 * <p>URI 形如 {@code cryptand://ai-auto/latest-ldlib-download.txt}（自定义 scheme，符合 RFC 3986）。
 * 每次 {@code list} 都重新扫描目录 —— 截图是随时新增的，不能缓存。</p>
 *
 * <h3>安全</h3>
 * <p>只暴露产物目录<b>顶层</b>文件：文件名里出现路径分隔符或 {@code ..} 一律拒绝，
 * 解析后还要求落在产物目录内（防目录穿越）。</p>
 */
public final class AiAutoResources implements McpResourceProvider {

    /** 资源 URI 前缀 */
    public static final String PREFIX = "cryptand://ai-auto/";

    /** 单文件读取上限（超过则报错，避免把二进制塞爆内存/上下文） */
    private static final long MAX_READ_BYTES = 24L * 1024 * 1024;

    private final AiAutomationPaths paths;

    /** 产物目录解析（便于自测注入不同目录） */
    public interface AiAutomationPaths {
        Path outDir();
    }

    public AiAutoResources() {
        this(() -> AiAutomation.outDir());
    }

    public AiAutoResources(AiAutomationPaths paths) {
        this.paths = paths;
    }

    @Override
    public String id() {
        return "ai-auto";
    }

    @Override
    public List<McpResource> list() {
        final Path dir = paths.outDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        final List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> mimeOf(p.getFileName().toString()) != null)
                    .forEach(files::add);
        } catch (Throwable ignored) {
            return List.of();
        }
        // 新的在前：AI 最关心的通常是"刚生成的那份"
        files.sort(Comparator.comparingLong(AiAutoResources::modifiedAt).reversed());

        final List<McpResource> out = new ArrayList<>(files.size());
        for (Path file : files) {
            final String name = file.getFileName().toString();
            final long size;
            try {
                size = Files.size(file);
            } catch (Throwable ignored) {
                continue;
            }
            out.add(new McpResource(uriOf(name), name, name, describe(name), mimeOf(name), size));
        }
        return out;
    }

    @Override
    public McpResourceContent read(String uri) throws Exception {
        if (uri == null || !uri.startsWith(PREFIX)) {
            return null;
        }
        final String name = decodeName(uri.substring(PREFIX.length()));
        final Path dir = paths.outDir();
        final Path file = safeResolve(dir, name);
        if (file == null || !Files.isRegularFile(file)) {
            throw McpError.resourceNotFound(uri);
        }
        final long size = Files.size(file);
        if (size > MAX_READ_BYTES) {
            throw McpError.internal("资源过大（" + size + " 字节 > 上限 " + MAX_READ_BYTES + "）：" + name);
        }
        final byte[] bytes = Files.readAllBytes(file);
        return McpResourceContent.auto(uri, mimeOf(name), bytes);
    }

    @Override
    public List<ResourceTemplate> templates() {
        return List.of(new ResourceTemplate(PREFIX + "{name}", "ai-auto-artifact",
                "aiauto 产物", "截图/布局树/报告（如 latest-ldlib-download.txt）", "text/plain"));
    }

    // ==================== 工具 ====================

    public static String uriOf(String fileName) {
        return PREFIX + encodeName(fileName);
    }

    private static String encodeName(String name) {
        return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String decodeName(String encoded) {
        return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 只允许产物目录顶层的普通文件名（防目录穿越） */
    private static Path safeResolve(Path dir, String name) {
        if (dir == null || name == null || name.isBlank()
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        final Path file = dir.resolve(name).normalize();
        return file.getParent() != null && file.getParent().equals(dir.normalize()) ? file : null;
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    /** 扩展名 → MIME（不认识的后缀不暴露为资源） */
    public static String mimeOf(String fileName) {
        final String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".csv")) {
            return "text/plain";
        }
        return null;
    }

    private static String describe(String name) {
        if (name.startsWith("latest-") && name.endsWith(".png")) {
            return "截图：" + name;
        }
        if (name.startsWith("latest-") && name.endsWith(".txt")) {
            return "报告/布局树：" + name;
        }
        if ("tools.json".equals(name)) {
            return "工具清单（legacy 文件通道的 schema 快照）";
        }
        if ("status.txt".equals(name)) {
            return "会话状态快照";
        }
        if ("console.log".equals(name)) {
            return "执行流水日志";
        }
        if ("events.log".equals(name)) {
            return "生命周期事件流";
        }
        return name;
    }
}
