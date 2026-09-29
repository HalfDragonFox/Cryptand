package com.hdf.cryptand.neoforge.soc.download;

import com.hdf.cryptand.toolchain.CToolDownload;
import com.hdf.cryptand.toolchain.CToolDownloader;
import com.hdf.cryptand.toolchain.CToolMirror;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== 客户端下载管理器（2026-09-15，多工具并行）=====
 *
 * <p>用户要求：① 关闭页面后下载继续、再次打开记住进度；② <b>多个不同工具并行多线程下载</b>。</p>
 *
 * <p>实现：每个工具一个独立的 {@link CToolDownloader}（各自专属 daemon 线程），本管理器按
 * <b>toolId</b> 持有它们；UI 只是视图，随时关闭/重开都能看到同一批任务的实时进度。
 * 已结束的任务会保留（供查看结果），可由 {@link #clearFinished()} 清理。</p>
 */
public final class SocDownloadManager {

    private static final Map<String, CToolDownloader> TASKS = new ConcurrentHashMap<>();

    /**
     * 当前镜像站（UI 里"镜像站"一栏切换，全局生效）。
     *
     * <p>GitHub 直连卡顿是常态，用户切一次镜像后，<b>之后启动的所有任务</b>都走该镜像；
     * 正在运行的任务不打断（URL 已连接）。</p>
     */
    private static volatile CToolMirror MIRROR = CToolMirror.DIRECT;

    private SocDownloadManager() {
    }

    /** 当前镜像站 */
    public static CToolMirror mirror() {
        return MIRROR;
    }

    /** 切换镜像站（null 视为官方直连） */
    public static void setMirror(CToolMirror mirror) {
        MIRROR = mirror == null ? CToolMirror.DIRECT : mirror;
    }

    /**
     * 启动/复用某工具的下载。
     *
     * @return 该工具的下载器（已在进行中则直接返回，不重复下载）
     */
    public static CToolDownloader start(String toolId, CToolDownload download, Path dir) {
        final CToolDownloader existing = TASKS.get(toolId);
        if (existing != null && existing.isRunning()) {
            return existing;
        }
        final CToolDownloader dl = new CToolDownloader(download, dir, true).setMirror(MIRROR);
        TASKS.put(toolId, dl);
        dl.start();
        return dl;
    }

    /** 当前全部任务（含已结束；按工具 id） */
    public static Map<String, CToolDownloader> tasks() {
        return new LinkedHashMap<>(TASKS);
    }

    public static CToolDownloader task(String toolId) {
        return TASKS.get(toolId);
    }

    /** 是否有任一任务在进行 */
    public static boolean anyRunning() {
        for (CToolDownloader dl : TASKS.values()) {
            if (dl.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /** 进行中的任务数 */
    public static int runningCount() {
        int n = 0;
        for (CToolDownloader dl : TASKS.values()) {
            if (dl.isRunning()) {
                n++;
            }
        }
        return n;
    }

    /** 中断单个工具 */
    public static void cancel(String toolId) {
        final CToolDownloader dl = TASKS.get(toolId);
        if (dl != null) {
            dl.cancel();
        }
    }

    /** 中断全部 */
    public static void cancelAll() {
        for (CToolDownloader dl : TASKS.values()) {
            dl.cancel();
        }
    }

    /** 清理已结束任务（UI"刷新"或关闭时用） */
    public static void clearFinished() {
        TASKS.entrySet().removeIf(e -> !e.getValue().isRunning());
    }

    /**
     * 状态行列表（UI 的进度文本框直接逐行显示）。
     *
     * @return 每个工具一行：{@code [状态] 工具名  进度条  详情}
     */
    public static List<String> statusLines() {
        final List<String> out = new ArrayList<>();
        if (TASKS.isEmpty()) {
            out.add("（无下载任务）选择工具后点击【开始下载】，可多选并行。");
            return out;
        }
        for (Map.Entry<String, CToolDownloader> e : tasks().entrySet()) {
            final CToolDownloader.Progress p = e.getValue().progress();
            out.add(String.format("%-12s %s  %s", e.getKey(), p.progressBar(16), p.summary()));
        }
        return out;
    }
}
