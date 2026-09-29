package com.hdf.cryptand.neoforge.soc.download;

import com.hdf.cryptand.neoforge.soc.net.SocToolDownloadPayload;
import com.hdf.cryptand.toolchain.CToolDownload;

/**
 * ===== 服务端下载 · 客户端代理（2026-09-15）=====
 *
 * <p>UI 选择"服务端"时，经本类发请求；服务端执行下载并回报状态行。</p>
 */
public final class ServerDownloadClient {

    private static volatile String stateLine = "未请求（服务端下载需 OP + soc.toml#enableServerDownload=true）";

    private ServerDownloadClient() {
    }

    public static void request(CToolDownload download) {
        stateLine = "已请求服务端下载…";
        SocToolDownloadPayload.send(SocToolDownloadPayload.ACTION_START, download.toolId(), download.platform());
    }

    public static void cancel() {
        stateLine = "已请求服务端中断…";
        SocToolDownloadPayload.send(SocToolDownloadPayload.ACTION_CANCEL, "", "");
    }

    public static void query() {
        SocToolDownloadPayload.send(SocToolDownloadPayload.ACTION_QUERY, "", "");
    }

    public static String stateLine() {
        return stateLine;
    }

    /** 服务端状态回报（payload handler 在客户端主线程调用） */
    public static void update(String line) {
        stateLine = line == null ? "" : line;
    }
}
