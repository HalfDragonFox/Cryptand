package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * ===== SoC 网络注册（2026-09-15）=====
 *
 * <p>子包自治注册（与 sable.force / cryptandsable 同模式）：由 {@code SocEntry} 在构造期挂上
 * MOD 总线；子包关闭（`soc.toml#enableSocSupport=false`）时不注册。</p>
 *
 * <p>采用 {@code optional()} 注册：对端未装 Cryptand 时静默丢弃，不踢人；客户端有超时兜底。</p>
 */
public final class SocNetRegistration {

    private SocNetRegistration() {
    }

    @SubscribeEvent
    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(Cryptand.MOD_ID)
                .versioned("1").optional();

        // 客户端 → 服务端：编译请求
        registrar.playToServer(
                CompileRequestPayload.TYPE,
                CompileRequestPayload.STREAM_CODEC,
                CompileRequestPayload::handle);

        // 服务端 → 客户端：编译回执
        registrar.playToClient(
                CompileResultPayload.TYPE,
                CompileResultPayload.STREAM_CODEC,
                CompileResultPayload::handle);

        // 服务端 → 客户端：芯片状态（面板数据源）
        registrar.playToClient(
                SocStatusPayload.TYPE,
                SocStatusPayload.STREAM_CODEC,
                SocStatusPayload::handle);

        // 工具链下载：客户端请求（可选目标端=服务端） → 服务端在独立线程执行并回报状态
        registrar.playToServer(
                SocToolDownloadPayload.TYPE,
                SocToolDownloadPayload.STREAM_CODEC,
                SocToolDownloadPayload::handle);
        registrar.playToClient(
                SocToolDownloadStatusPayload.TYPE,
                SocToolDownloadStatusPayload.STREAM_CODEC,
                SocToolDownloadStatusPayload::handle);

        // 真彩屏窗口的键鼠报文：任务 F-2（2026-09-27）删掉了旧的 TrueScreenInputPayload（随
        // soc/client 那套旧自研真彩屏一起下线），改由 Scala 移植件自己的通道注册
        // （truescreen/network/TrueScreenNetwork.scala）。
        // 组装台：面板按钮（组装/取出/状态）→ 服务端权威执行 → 状态回报
        registrar.playToServer(
                SocAssemblerPayload.TYPE,
                SocAssemblerPayload.STREAM_CODEC,
                SocAssemblerPayload::handle);
        registrar.playToClient(
                SocAssemblerStatusPayload.TYPE,
                SocAssemblerStatusPayload.STREAM_CODEC,
                SocAssemblerStatusPayload::handle);
    }
}
