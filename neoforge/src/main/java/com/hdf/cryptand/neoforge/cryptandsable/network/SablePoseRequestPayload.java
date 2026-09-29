/**
 * ===== 亚层物理位姿请求包（C2S，2026-09-01 pull 渲染 v2） =====
 *
 * 客户端渲染主动请求：携带客户端【已载入的缓存版本】（cacheVersion）。
 * 服务端回复：
 *  - 若 cacheVersion 一致 → 仅返回位姿（必要数据：SablePoseResponsePayload）
 *  - 若不一致（结构新增/移除/变化）→ 额外发送 SableSubLevelRenderPayload（大缓存更新）
 *
 * cacheVersion 语义：服务端全局递增（亚层 add/remove/变化时）；客户端记忆上次收到的。
 * 0 = 客户端初启动（强制全量缓存刷新）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.network;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableServerBridge;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SablePoseRequestPayload(
        int cacheVersion          // 客户端已载入的缓存版本（0 = 需全量）
) implements CustomPacketPayload {

    public static final Type<SablePoseRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "sable_pose_request"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<ByteBuf, SablePoseRequestPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SablePoseRequestPayload decode(ByteBuf buf) {
                    return new SablePoseRequestPayload(buf.readInt());
                }

                @Override
                public void encode(ByteBuf buf, SablePoseRequestPayload p) {
                    buf.writeInt(p.cacheVersion());
                }
            };

    public static void handle(SablePoseRequestPayload payload,
                              net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            SableServerBridge
                    .setLastReqCacheVersion(payload.cacheVersion());
            SableServerBridge.handlePoseRequest(context);
        });
    }
}
