package com.hdf.cryptand.neoforge.soc.download;

import com.hdf.cryptand.neoforge.soc.config.ConfigSoc;
import com.hdf.cryptand.neoforge.soc.net.SocToolDownloadPayload;
import com.hdf.cryptand.neoforge.soc.net.SocToolDownloadStatusPayload;
import com.hdf.cryptand.toolchain.CToolDownload;
import com.hdf.cryptand.toolchain.CToolDownloader;
import com.hdf.cryptand.toolchain.CToolDownloads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * ===== 服务端工具链下载服务（2026-09-15）=====
 *
 * <p>用户定稿：<b>默认仅客户端；服务端仅管理"允许操作"</b>。因此本服务：
 * 校验 OP 权限 + `soc.toml#enableServerDownload`（默认 false），通过后在
 * <b>独立异步线程</b>下载到服务端自己的 `cryptand/tool/&lt;平台&gt;/`，并把状态行回报给请求者。</p>
 */
public final class ServerToolDownloadService {

    private static volatile CToolDownloader downloader;
    private static volatile String lastLine = "空闲";

    private ServerToolDownloadService() {
    }

    public static void handle(ServerPlayer player, SocToolDownloadPayload payload) {
        // 权限 + 开关（服务端只做"允许操作"管理）
        if (!player.hasPermissions(2)) {
            reply(player, "权限不足：服务端下载需要 OP（等级 2）");
            return;
        }
        if (!ConfigSoc.enableServerDownload()) {
            reply(player, "服务端未开启下载（soc.toml#enableServerDownload=false）——请在服务端配置后重试");
            return;
        }
        switch (payload.action()) {
            case SocToolDownloadPayload.ACTION_START -> start(player, payload);
            case SocToolDownloadPayload.ACTION_CANCEL -> cancel(player);
            default -> reply(player, statusLine());
        }
    }

    private static void start(ServerPlayer player, SocToolDownloadPayload payload) {
        final CToolDownloader running = downloader;
        if (running != null && running.isRunning()) {
            reply(player, "已有下载在进行：" + running.progress().summary() + "（可先中断）");
            return;
        }
        final String platform = payload.platform() == null || payload.platform().isBlank()
                ? CToolDownloads.currentPlatform() : payload.platform();
        final String toolId = payload.toolId() == null || payload.toolId().isBlank()
                ? "riscv-gcc" : payload.toolId();
        CToolDownload download = CToolDownloads.forPlatform(toolId, platform);
        if (download == null) {
            reply(player, "无内置地址：" + toolId + " / " + platform);
            return;
        }
        final String mirror = ConfigSoc.downloadUrlOverride();
        if (mirror != null && !mirror.isBlank()) {
            download = download.withMirrorPrefix(mirror);
        }
        final java.nio.file.Path dir = ConfigSoc.serverDownloadTargetDir().isBlank()
                ? FMLPaths.GAMEDIR.get().resolve("cryptand").resolve("tool").resolve(platform)
                : java.nio.file.Path.of(ConfigSoc.serverDownloadTargetDir());
        final CToolDownloader dl = new CToolDownloader(download, dir, false);
        downloader = dl;
        dl.start();
        lastLine = "服务端下载中：" + download.toolId() + " / " + platform + " → " + dir
                + "（" + download.sizeText() + "）";
        reply(player, lastLine);
    }

    private static void cancel(ServerPlayer player) {
        final CToolDownloader dl = downloader;
        if (dl == null || !dl.isRunning()) {
            reply(player, "当前没有进行中的服务端下载");
            return;
        }
        dl.cancel();
        lastLine = "服务端下载已中断";
        reply(player, lastLine);
    }

    /** 状态行（含进度；UI/命令均可用） */
    public static String statusLine() {
        final CToolDownloader dl = downloader;
        if (dl == null) {
            return lastLine;
        }
        return dl.progress().summary() + "  ·  目标 " + dl.targetDir();
    }

    private static void reply(ServerPlayer player, String line) {
        lastLine = line;
        try {
            PacketDistributor.sendToPlayer(player, new SocToolDownloadStatusPayload(line));
        } catch (Throwable ignored) {
        }
    }
}
