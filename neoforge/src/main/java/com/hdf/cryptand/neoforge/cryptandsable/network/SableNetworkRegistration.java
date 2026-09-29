/**
 * ===== CryptandSable 网络包注册（2026-08-31） =====
 *
 * MOD 总线注册 Sable 相关的自定义 payload：
 *  - SableSubLevelRenderPayload（S2C：亚层物理化渲染同步）
 *
 * 独立于 PowergridModule.Network（self-contained，不受 powergrid 开关影响）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.network;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// ⚠ 2026-08-30：bus=Bus.MOD 在 NeoForge 21.1.231 已标记 [removal]（javac error）
// → 改编程式注册：CryptandNeoForge 构造 modEventBus.register(SableNetworkRegistration.class)
public final class SableNetworkRegistration {

    private SableNetworkRegistration() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // ★ 2026-09-06 【core 总闸】enableCryptandSableCore=false → 不注册任何 Sable
        //   payload（服务端不广播、客户端不请求；与"核心子包内容不加载"语义一致）。
        //   RegisterPayloadHandlersEvent 触发时 config 已由 NeoForge 加载 → spec 直读。
        if (!ConfigCryptandSable
                .ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }
        PayloadRegistrar registrar = event.registrar(Cryptand.MOD_ID)
                .versioned("1").optional();
        registrar.playToClient(
                SableSubLevelRenderPayload.TYPE,
                SableSubLevelRenderPayload.STREAM_CODEC,
                SableSubLevelRenderPayload::handle);
        registrar.playToClient(
                SableSubLevelPosePayload.TYPE,
                SableSubLevelPosePayload.STREAM_CODEC,
                SableSubLevelPosePayload::handle);
        // ★ 2026-09-01 pull 渲染：客户端请求 + 服务端回复（限流）
        registrar.playToServer(
                SablePoseRequestPayload.TYPE,
                SablePoseRequestPayload.STREAM_CODEC,
                SablePoseRequestPayload::handle);
        registrar.playToClient(
                SablePoseResponsePayload.TYPE,
                SablePoseResponsePayload.STREAM_CODEC,
                SablePoseResponsePayload::handle);
    }
}
