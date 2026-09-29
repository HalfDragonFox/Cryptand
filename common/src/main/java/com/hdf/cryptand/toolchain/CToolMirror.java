package com.hdf.cryptand.toolchain;

import java.util.List;
import java.util.Locale;

/**
 * 下载镜像站（前缀代理）。
 *
 * <p>GitHub 直连在国内经常卡顿/超时，这里给出若干"前缀代理"：把原始 URL 直接拼在代理前缀后面即可。
 * 例如 {@code https://ghfast.top/https://github.com/xpack-dev-tools/...}。
 * {@link #DIRECT} 表示官方直连（前缀为空，URL 原样使用）。</p>
 *
 * <p>设计约束：本类<b>零 MC 依赖、零网络</b>，仅做字符串拼装；实际下载由 {@link CToolDownloader} 执行。
 * 镜像是否可用由用户选择决定，代码不做探测（避免开 UI 时发起网络请求）。</p>
 */
public record CToolMirror(String id, String displayName, String prefix) {

    /** 官方直连 */
    public static final CToolMirror DIRECT = new CToolMirror("direct", "官方直连", "");

    /** 内置镜像表（顺序 = UI 展示顺序，官方直连排第一） */
    private static final List<CToolMirror> BUILTIN = List.of(
            DIRECT,
            new CToolMirror("ghfast", "镜像 ghfast.top", "https://ghfast.top/"),
            new CToolMirror("ghproxy", "镜像 gh-proxy.com", "https://gh-proxy.com/"),
            new CToolMirror("ghproxyNet", "镜像 ghproxy.net", "https://ghproxy.net/"),
            new CToolMirror("moeyy", "镜像 gh.moeyy.xyz", "https://gh.moeyy.xyz/https://"));

    public boolean direct() {
        return prefix == null || prefix.isEmpty();
    }

    /** 把原始下载地址套上镜像前缀 */
    public String apply(String url) {
        if (url == null || url.isEmpty() || direct()) {
            return url;
        }
        return prefix + url;
    }

    public static List<CToolMirror> builtin() {
        return BUILTIN;
    }

    /** 按 id 查内置镜像；未知 id（含自定义前缀）返回直连 */
    public static CToolMirror byId(String id) {
        if (id == null) {
            return DIRECT;
        }
        for (CToolMirror m : BUILTIN) {
            if (m.id().equalsIgnoreCase(id)) {
                return m;
            }
        }
        return DIRECT;
    }

    /**
     * 自定义镜像：前缀非空时构造一个 {@code id="custom"} 的镜像（用于配置里填写自建代理）。
     */
    public static CToolMirror custom(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DIRECT;
        }
        return new CToolMirror("custom", "自定义镜像", prefix.trim());
    }

    /** 环形切换（UI 上点一下换下一个） */
    public CToolMirror next() {
        final List<CToolMirror> all = BUILTIN;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equalsIgnoreCase(id)) {
                return all.get((i + 1) % all.size());
            }
        }
        return all.get(1 % all.size());
    }

    /** 兜底：任何异常输入都不至于让下载 URL 变 null */
    public String safeApply(String url) {
        try {
            return apply(url);
        } catch (RuntimeException ex) {
            return url;
        }
    }

    @Override
    public String toString() {
        return displayName + (direct() ? "" : "  (" + prefix + ")");
    }

    /** 归一化 id（大小写不敏感，便于配置文件手写） */
    public static String normalizeId(String raw) {
        return raw == null ? "direct" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
