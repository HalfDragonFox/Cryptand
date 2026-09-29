/**
 * ===== 亚层物理位姿回复包（S2C，2026-09-01 pull 渲染） =====
 *
 * 服务端回复客户端【渲染位姿请求】：包含一组运动体的 runtimeId + 位姿。
 * 客户端收到后塞进渲染位姿缓存（SableClientRenderModule），渲染插值使用。
 *
 * 编码：int count; 随后 count×(runtimeId + 7 double)。（flat 数组更紧凑）
 */
package com.hdf.cryptand.neoforge.cryptandsable.network;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableClientRenderModule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SablePoseResponsePayload(
        int cacheVersion,           // 服务端当前缓存版本（客户端对比）
        int[] runtimeIds,
        double[] poses          // count×7（x,y,z,qx,qy,qz,qw）
) implements CustomPacketPayload {

    public static final Type<SablePoseResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "sable_pose_response"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<ByteBuf, SablePoseResponsePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SablePoseResponsePayload decode(ByteBuf buf) {
                    final int cacheVersion = buf.readInt();
                    final int count = buf.readInt();
                    final int[] ids = new int[count];
                    final double[] poses = new double[count * 7];
                    for (int i = 0; i < count; i++) {
                        ids[i] = buf.readInt();
                        for (int j = 0; j < 7; j++) {
                            poses[i * 7 + j] = buf.readDouble();
                        }
                    }
                    return new SablePoseResponsePayload(cacheVersion, ids, poses);
                }

                @Override
                public void encode(ByteBuf buf, SablePoseResponsePayload p) {
                    buf.writeInt(p.cacheVersion());
                    final int count = p.runtimeIds().length;
                    buf.writeInt(count);
                    for (int i = 0; i < count; i++) {
                        buf.writeInt(p.runtimeIds()[i]);
                        for (int j = 0; j < 7; j++) {
                            buf.writeDouble(p.poses()[i * 7 + j]);
                        }
                    }
                }
            };

    public static void handle(SablePoseResponsePayload payload,
                              net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            // 记录服务端缓存版本（客户端后续请求携带；不一致 → 服务端补发大缓存）
            SableClientRenderModule.INSTANCE
                    .setServerCacheVersion(payload.cacheVersion());
            final int count = payload.runtimeIds().length;
            for (int i = 0; i < count; i++) {
                final int rt = payload.runtimeIds()[i];
                final double[] p = payload.poses();
                final int o = i * 7;
                SableClientRenderModule.INSTANCE
                        .onPoseSnapshot(new SableMessages.PoseSnapshot(
                                rt, 0,
                                p[o], p[o + 1], p[o + 2],
                                p[o + 3], p[o + 4], p[o + 5], p[o + 6],
                                0, 0, 0, 0, 0, 0));
            }
        });
    }
}
