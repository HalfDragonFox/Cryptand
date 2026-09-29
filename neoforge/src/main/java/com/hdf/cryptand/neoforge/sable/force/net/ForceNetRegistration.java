/**
 * ===== 力显示网络注册（独立于 CryptandSable 总闸，2026-09-14） =====
 *
 * <p>力学可视化用的是【官方 Sable】的数据，与 CryptandSable 自研核心无关
 * —— 因此本注册【不受】{@code enableCryptandSableCore} 总闸影响（对比
 * cryptandsable 的 SableNetworkRegistration）。
 *
 * <p>采用 {@code optional()} 注册：服务端不装 Cryptand / 未开启接口时，
 * 客户端发出的请求会被静默丢弃，不会踢人、不报错；客户端另有超时兜底。
 */
package com.hdf.cryptand.neoforge.sable.force.net;

import com.hdf.cryptand.Cryptand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ForceNetRegistration {

    private ForceNetRegistration() {
    }

    @SubscribeEvent
    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(Cryptand.MOD_ID)
                .versioned("1").optional();

        // 客户端 → 服务端：请求（主动端）
        registrar.playToServer(
                SableForceRequestPayload.TYPE,
                SableForceRequestPayload.STREAM_CODEC,
                SableForceRequestPayload::handle);

        // 服务端 → 客户端：响应（被动回复；含 supported=false 能力标记）
        registrar.playToClient(
                SableForceResponsePayload.TYPE,
                SableForceResponsePayload.STREAM_CODEC,
                SableForceResponsePayload::handle);
    }
}
