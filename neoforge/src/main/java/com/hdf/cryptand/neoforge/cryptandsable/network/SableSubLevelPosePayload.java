/**
 * ===== 亚层物理位姿同步包（S2C，2026-08-31） =====
 *
 * 核心物理体运动后（SableServerBridge 消费 PoseSnapshot），把最新位姿广播到
 * 客户端渲染模块（SableClientRenderModule.poseCache），亚层渲染跟随物理运动
 * （参考官方 sable 的 SubLevelSnapshotInterpolator 思路：服务端每 tick 发快照，
 * 客户端缓存最新位姿；MVP 取最新快照不插值）。
 *
 * runtimeId 与 SableSubLevelRenderPayload 的 runtimeId 一致（亚层渲染数据按它索引）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.network;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableClientRenderModule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SableSubLevelPosePayload(
        int runtimeId,
        double px, double py, double pz,
        double qx, double qy, double qz, double qw,
        double spx, double spy, double spz,   // ★ 2026-09-05 物理空间并集框 min（世界坐标，黄框）
        double shx, double shy, double shz    // ★ 2026-09-05 物理空间并集框 max（世界坐标，黄框）
) implements CustomPacketPayload {

    public static final Type<SableSubLevelPosePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "sable_sublevel_pose"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<ByteBuf, SableSubLevelPosePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SableSubLevelPosePayload decode(ByteBuf buf) {
                    return new SableSubLevelPosePayload(
                            buf.readInt(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble());
                }

                @Override
                public void encode(ByteBuf buf, SableSubLevelPosePayload p) {
                    buf.writeInt(p.runtimeId());
                    buf.writeDouble(p.px());
                    buf.writeDouble(p.py());
                    buf.writeDouble(p.pz());
                    buf.writeDouble(p.qx());
                    buf.writeDouble(p.qy());
                    buf.writeDouble(p.qz());
                    buf.writeDouble(p.qw());
                    buf.writeDouble(p.spx());
                    buf.writeDouble(p.spy());
                    buf.writeDouble(p.spz());
                    buf.writeDouble(p.shx());
                    buf.writeDouble(p.shy());
                    buf.writeDouble(p.shz());
                }
            };

    public static void handle(SableSubLevelPosePayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            // 转成 PoseSnapshot 塞进渲染位姿缓存（渲染器按 runtimeId 查询）
            SableClientRenderModule.INSTANCE
                    .onPoseSnapshot(new SableMessages.PoseSnapshot(
                            payload.runtimeId(), 0,
                            payload.px(), payload.py(), payload.pz(),
                            payload.qx(), payload.qy(), payload.qz(), payload.qw(),
                            0, 0, 0, 0, 0, 0));
            // ★ 2026-09-05 【一世界一空间】黄框显示已放弃置区：不再记录空间并集框
            //   （spx..shz 恒 0；setSpaceRect 弃置）
        });
    }
}
