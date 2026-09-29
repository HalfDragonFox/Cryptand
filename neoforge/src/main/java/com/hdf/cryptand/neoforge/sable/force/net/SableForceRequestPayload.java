/**
 * ===== 力显示请求包（C2S，2026-09-14） =====
 *
 * <p>【客户端是主动端】：客户端按【每秒一次】的节奏发本包拉取力数据；
 * 服务端【永远被动】—— 只在收到请求时采集并回复，不做任何主动广播。
 *
 * <p>会话模型：一个玩家 = 一个会话（服务端按 UUID 维护窗口计数与订阅）。
 * 请求自带 {@link #playerId()}（玩家 UUID）—— 服务端会与连接玩家比对（防伪/防串会话），
 * 同时也让"多玩家各自建立会话"变得显式。
 *
 * <p>{@code probe=true} 表示"能力探测"（客户端首次/断线重连后的握手）：
 * 服务端即使未开启力显示接口也应回复 {@link SableForceResponsePayload#unsupported()}，
 * 让客户端立刻停止请求并给出提示（而不是傻等超时）。探测同样按会话限流（1s 一次）。
 *
 * @param playerId    请求方玩家 UUID（服务端校验）
 * @param probe       是否能力探测（true 时服务端只回能力标记，不采集）
 * @param detailed    请求详细模式（点力）；服务端可按自身配置降级
 * @param maxDistance 只请求该距离内的结构（格；&lt;=0 → 服务端用默认值）
 */
package com.hdf.cryptand.neoforge.sable.force.net;

import com.hdf.cryptand.Cryptand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record SableForceRequestPayload(UUID playerId, boolean probe, boolean detailed, int maxDistance)
        implements CustomPacketPayload {

    public static final Type<SableForceRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "sable_force_request"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, SableForceRequestPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SableForceRequestPayload decode(final FriendlyByteBuf buf) {
                    return new SableForceRequestPayload(
                            buf.readUUID(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt());
                }

                @Override
                public void encode(final FriendlyByteBuf buf, final SableForceRequestPayload p) {
                    buf.writeUUID(p.playerId());
                    buf.writeBoolean(p.probe());
                    buf.writeBoolean(p.detailed());
                    buf.writeVarInt(p.maxDistance());
                }
            };

    /** 请求处理入口（服务端，主线程）。 */
    public static void handle(final SableForceRequestPayload payload,
                              final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.hdf.cryptand.neoforge.sable.force.server.SableForceService.onRequest(sp, payload);
            }
        });
    }
}
