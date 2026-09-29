package com.hdf.cryptand.toolchain;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ===== 工具链下载器（2026-09-15 toolchain 子库，纯 Java 零 MC）=====
 *
 * <p>用户要求：<b>异步单独线程下载（不卡 MC）· 可中断 · 显示进度与文件大小</b>。</p>
 *
 * <p>流程：HTTP 流式下载 → 校验大小 → 解压（zip / tar.gz）→ 去掉归档顶层目录 →
 * 落到目标目录（供 {@link CToolchainLocator} 直接找到）。</p>
 *
 * <p>线程：{@link #start()} 内部起一条<b>专属线程</b>（daemon），调用方零阻塞；
 * {@link #cancel()} 置中断标志，下载与解压循环都会尽快退出。</p>
 */
public final class CToolDownloader {

    /** 下载状态机 */
    public enum State {
        IDLE("等待中"), DOWNLOADING("下载中"), EXTRACTING("解压中"),
        DONE("完成"), CANCELLED("已取消"), FAILED("失败");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 进度快照（不可变；UI 轮询） */
    public record Progress(String toolId, String platform, String url, String targetDir,
                           State state, long downloadedBytes, long totalBytes,
                           long bytesPerSecond, String message, boolean clientSide) {

        /** 0..1（总大小未知返回 -1） */
        public double ratio() {
            return totalBytes <= 0 ? -1 : Math.min(1.0, (double) downloadedBytes / totalBytes);
        }

        public boolean running() {
            return state == State.DOWNLOADING || state == State.EXTRACTING;
        }

        /** 文本进度条（等宽字符，UI 直接用） */
        public String progressBar(int width) {
            final double r = ratio();
            final int filled = r < 0 ? 0 : (int) Math.round(r * width);
            final StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < width; i++) {
                sb.append(i < filled ? '#' : '.');
            }
            sb.append("] ");
            sb.append(r < 0 ? "?" : String.format("%.1f%%", r * 100));
            return sb.toString();
        }

        public String summary() {
            final StringBuilder sb = new StringBuilder();
            sb.append(state.label());
            if (totalBytes > 0) {
                sb.append("  ").append(CToolDownload.humanSize(downloadedBytes))
                        .append(" / ").append(CToolDownload.humanSize(totalBytes));
            } else if (downloadedBytes > 0) {
                sb.append("  ").append(CToolDownload.humanSize(downloadedBytes));
            }
            if (bytesPerSecond > 0 && running()) {
                sb.append("  ·  ").append(CToolDownload.humanSize(bytesPerSecond)).append("/s");
                final double remain = ratio() >= 0 ? (totalBytes - downloadedBytes) / (double) bytesPerSecond : -1;
                if (remain > 0) {
                    sb.append("  ·  剩余 ").append((long) remain).append("s");
                }
            }
            if (message != null && !message.isBlank()) {
                sb.append("  ·  ").append(message);
            }
            return sb.toString();
        }
    }

    private final CToolDownload download;

    /**
     * 镜像站（默认官方直连）。
     *
     * <p>⚠ 只影响本次下载所使用的 URL，不修改 {@link CToolDownload} 原始记录；
     * 由 {@link com.hdf.cryptand.neoforge.soc.download.SocDownloadManager} 在启动任务前注入。</p>
     */
    private volatile CToolMirror mirror = CToolMirror.DIRECT;
    private final Path targetDir;
    private final boolean clientSide;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Progress progress;
    private volatile Thread worker;
    private final CompletableFuture<Progress> future = new CompletableFuture<>();

    public CToolDownloader(CToolDownload download, Path targetDir) {
        this(download, targetDir, true);
    }

    public CToolDownloader(CToolDownload download, Path targetDir, boolean clientSide) {
        this.download = download;
        this.targetDir = targetDir;
        this.clientSide = clientSide;
        this.progress = new Progress(download.toolId(), download.platform(), download.url(),
                targetDir == null ? "?" : targetDir.toString(), State.IDLE, 0,
                download.sizeBytes(), 0, "", clientSide);
    }

    /** 当前镜像站 */
    public CToolMirror mirror() {
        return mirror;
    }

    /** 设置镜像站（仅对尚未开始的下载生效；运行中任务不会被中途改写） */
    public CToolDownloader setMirror(CToolMirror mirror) {
        this.mirror = mirror == null ? CToolMirror.DIRECT : mirror;
        return this;
    }

    public CToolDownload download() {
        return download;
    }

    public Path targetDir() {
        return targetDir;
    }

    public boolean isClientSide() {
        return clientSide;
    }

    public Progress progress() {
        return progress;
    }

    public boolean isRunning() {
        final Thread t = worker;
        return t != null && t.isAlive();
    }

    public CompletableFuture<Progress> future() {
        return future;
    }

    /** 启动下载（专属 daemon 线程；调用方零阻塞） */
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        cancelled.set(false);
        final Thread t = new Thread(this::run, "cryptand-tool-download-" + download.toolId());
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 中断（下载/解压循环尽快退出） */
    public void cancel() {
        cancelled.set(true);
        final Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    // ==================== 执行 ====================

    private void run() {
        Path tempFile = null;
        try {
            if (targetDir == null) {
                fail("目标目录未指定");
                return;
            }
            Files.createDirectories(targetDir);
            tempFile = Files.createTempFile("cryptand-tool-", "." + download.fileName());
            update(State.DOWNLOADING, 0, download.sizeBytes(), 0, "连接中…");

            downloadTo(tempFile);
            if (cancelled.get()) {
                cancelFinish();
                return;
            }
            update(State.EXTRACTING, download.sizeBytes(), download.sizeBytes(), 0, "解压中…");
            extract(tempFile);
            if (cancelled.get()) {
                cancelFinish();
                return;
            }
            final Progress done = new Progress(download.toolId(), download.platform(), download.url(),
                    targetDir.toString(), State.DONE, download.sizeBytes(), download.sizeBytes(), 0,
                    "完成（可用 /cryptand soc tools 查看）", clientSide);
            progress = done;
            future.complete(done);
        } catch (Throwable t) {
            if (cancelled.get()) {
                cancelFinish();
            } else {
                fail(t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            }
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void downloadTo(Path target) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) URI.create(mirror.apply(download.url())).toURL().openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Cryptand/1.0 (toolchain downloader)");
        conn.connect();
        final int code = conn.getResponseCode();
        if (code / 100 != 2) {
            throw new IOException("HTTP " + code + " " + conn.getResponseMessage());
        }
        final long total = conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : download.sizeBytes();
        long downloaded = 0;
        long lastReport = 0;
        long windowStart = System.nanoTime();
        long windowBytes = 0;
        final byte[] buffer = new byte[1 << 16];
        try (InputStream in = new BufferedInputStream(conn.getInputStream(), 1 << 16);
             OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (cancelled.get()) {
                    return;
                }
                out.write(buffer, 0, read);
                downloaded += read;
                windowBytes += read;
                final long now = System.nanoTime();
                if (now - windowStart >= 500_000_000L) {   // 每 0.5s 报告一次
                    final long bps = windowBytes * 1_000_000_000L / Math.max(1, now - windowStart);
                    update(State.DOWNLOADING, downloaded, total, bps, "下载中…");
                    windowStart = now;
                    windowBytes = 0;
                    lastReport = downloaded;
                } else if (downloaded - lastReport >= 8L * 1024 * 1024) {
                    update(State.DOWNLOADING, downloaded, total, 0, "下载中…");
                    lastReport = downloaded;
                }
            }
        } finally {
            conn.disconnect();
        }
        update(State.DOWNLOADING, downloaded, Math.max(total, downloaded), 0, "下载完成，准备解压");
    }

    private void extract(Path archive) throws IOException {
        if ("zip".equalsIgnoreCase(download.archive())) {
            extractZip(archive);
        } else {
            extractTarGz(archive);
        }
    }

    private void extractZip(Path archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive), 1 << 16))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (cancelled.get()) {
                    return;
                }
                final String rel = stripTopLevel(entry.getName());
                if (rel.isEmpty()) {
                    continue;
                }
                final Path out = targetDir.resolve(rel).normalize();
                if (!out.startsWith(targetDir)) {
                    continue;   // 防目录穿越
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 最小 tar.gz 解压（tar 头 512 字节 + 内容 512 对齐） */
    private void extractTarGz(Path archive) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
             GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16))) {
            final byte[] header = new byte[512];
            while (true) {
                if (cancelled.get()) {
                    return;
                }
                if (!readFully(gz, header, 512)) {
                    break;
                }
                if (header[0] == 0) {
                    break;   // 结束块
                }
                final String name = new String(header, 0, 100, StandardCharsets.UTF_8).trim();
                final long size = parseOctal(header, 124, 12);
                final byte type = header[156];
                final String rel = stripTopLevel(name);
                long remaining = size;
                if (!rel.isEmpty() && (type == '0' || type == 0 || type == '5')) {
                    final Path out = targetDir.resolve(rel).normalize();
                    if (out.startsWith(targetDir)) {
                        if (type == '5') {
                            Files.createDirectories(out);
                        } else {
                            Files.createDirectories(out.getParent());
                            try (OutputStream os = Files.newOutputStream(out)) {
                                final byte[] buf = new byte[1 << 16];
                                while (remaining > 0) {
                                    if (cancelled.get()) {
                                        return;
                                    }
                                    final int chunk = (int) Math.min(buf.length, remaining);
                                    final int read = gz.read(buf, 0, chunk);
                                    if (read <= 0) {
                                        break;
                                    }
                                    os.write(buf, 0, read);
                                    remaining -= read;
                                }
                            }
                        }
                    }
                }
                // 跳过剩余内容 + 512 对齐填充
                long skip = remaining;
                final long padding = (512 - (size % 512)) % 512;
                skip += padding;
                while (skip > 0) {
                    final long n = gz.skip(skip);
                    if (n <= 0) {
                        break;
                    }
                    skip -= n;
                }
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            final int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static long parseOctal(byte[] buffer, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            final int c = buffer[i] & 0xFF;
            if (c == 0 || c == ' ') {
                continue;
            }
            if (c < '0' || c > '7') {
                break;
            }
            value = value * 8 + (c - '0');
        }
        return value;
    }

    /** 去掉归档顶层目录（xpack 包内是 {@code xpack-.../bin/...}） */
    private static String stripTopLevel(String name) {
        if (name == null) {
            return "";
        }
        final String normalized = name.replace('\\', '/');
        final int slash = normalized.indexOf('/');
        if (slash < 0) {
            return "";
        }
        return normalized.substring(slash + 1);
    }

    // ==================== 状态 ====================

    private void update(State state, long downloaded, long total, long bps, String message) {
        progress = new Progress(download.toolId(), download.platform(), download.url(),
                targetDir == null ? "?" : targetDir.toString(), state, downloaded, total, bps, message, clientSide);
    }

    private void cancelFinish() {
        final Progress p = new Progress(download.toolId(), download.platform(), download.url(),
                targetDir == null ? "?" : targetDir.toString(), State.CANCELLED,
                progress.downloadedBytes(), progress.totalBytes(), 0, "已中断", clientSide);
        progress = p;
        future.complete(p);
    }

    private void fail(String message) {
        final Progress p = new Progress(download.toolId(), download.platform(), download.url(),
                targetDir == null ? "?" : targetDir.toString(), State.FAILED,
                progress.downloadedBytes(), progress.totalBytes(), 0, message, clientSide);
        progress = p;
        future.complete(p);
    }
}
