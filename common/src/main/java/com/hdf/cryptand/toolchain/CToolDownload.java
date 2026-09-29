package com.hdf.cryptand.toolchain;

/**
 * ===== 工具下载描述（2026-09-15 toolchain 子库，纯 Java 零 MC）=====
 *
 * @param toolId     工具 id（对应 {@link CToolSpec#id()}，如 riscv-gcc）
 * @param platform   目标平台（windows-x64 / linux-x64 / linux-arm64 / macos-x64 / macos-arm64）
 * @param url        下载地址（可被配置的镜像前缀覆盖）
 * @param sizeBytes  文件大小（字节；0 = 未知）
 * @param archive    归档格式（zip / tar.gz）
 */
public record CToolDownload(String toolId, String platform, String url,
                            long sizeBytes, String archive) {

    public String sizeText() {
        return humanSize(sizeBytes);
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1048576.0);
        }
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    /** 覆盖下载源前缀（镜像加速；空 = 原样） */
    public CToolDownload withMirrorPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return this;
        }
        final String base = url.substring(url.lastIndexOf('/') + 1);
        return new CToolDownload(toolId, platform, prefix.endsWith("/") ? prefix + base : prefix + "/" + base,
                sizeBytes, archive);
    }

    public String fileName() {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** UI 一行 */
    public String displayLine() {
        return toolId + " · " + platform + " · " + sizeText() + " · " + archive;
    }
}
