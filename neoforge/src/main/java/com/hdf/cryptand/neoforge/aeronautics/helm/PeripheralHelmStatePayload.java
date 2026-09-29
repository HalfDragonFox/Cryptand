/**
 * ===== 外设船舵舵角同步包（C2S，2026-09-13） =====
 *
 * 客户端采样出的舵角（度）<b>每 tick 检测、变化超过阈值即上报</b>（不做限流：红石/比较器要跟得上设备）：
 * 服务端据此输出模拟红石信号 + NBT 持久化。
 * 反向不广播——采样端（驾驶者）本地已经是最新值。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PeripheralHelmStatePayload(BlockPos pos, float angleDegrees)
        implements CustomPacketPayload {

    public static final Type<PeripheralHelmStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_helm_state"));

    public static final StreamCodec<ByteBuf, PeripheralHelmStatePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PeripheralHelmStatePayload decode(ByteBuf buf) {
                    return new PeripheralHelmStatePayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            ByteBufCodecs.FLOAT.decode(buf));
                }

                @Override
                public void encode(ByteBuf buf, PeripheralHelmStatePayload payload) {
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    ByteBufCodecs.FLOAT.encode(buf, payload.angleDegrees());
                }
            };

    @Override
    public Type<PeripheralHelmStatePayload> type() {
        return TYPE;
    }

    public static void handle(PeripheralHelmStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (serverPlayer.level().getBlockEntity(payload.pos())
                    instanceof PeripheralHelmBlockEntity be) {
                // 设备在玩家客户端上：只接受【绑定归属玩家】的舵角（未限定归属时任何人可驱动）
                java.util.UUID owner = be.getOwner();
                if (owner == null || owner.equals(serverPlayer.getUUID())) {
                    be.setClientAngle(payload.angleDegrees());
                }
            }
        });
    }

    public static void sendToServer(BlockPos pos, float angleDegrees) {
        PacketDistributor.sendToServer(new PeripheralHelmStatePayload(pos, angleDegrees));
    }
}
