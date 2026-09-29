/**
 * ===== 外设船舵绑定更新包（C2S，2026-09-13） =====
 *
 * LDLib2 界面点"保存绑定"后发送：目标方块坐标 + 完整绑定（设备 id / 轴映射 / 手感 /
 * 力反馈加速度阈值）。服务端写入 BE 并 {@code sendData()} 同步给全部追踪玩家。
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

public record PeripheralHelmBindPayload(BlockPos pos, PeripheralHelmBinding binding)
        implements CustomPacketPayload {

    public static final Type<PeripheralHelmBindPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_helm_bind"));

    public static final StreamCodec<ByteBuf, PeripheralHelmBindPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PeripheralHelmBindPayload decode(ByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    String device = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int steerAxis = ByteBufCodecs.VAR_INT.decode(buf);
                    boolean invert = ByteBufCodecs.BOOL.decode(buf);
                    float maxAngle = ByteBufCodecs.FLOAT.decode(buf);
                    float deadzone = ByteBufCodecs.FLOAT.decode(buf);
                    boolean ffb = ByteBufCodecs.BOOL.decode(buf);
                    float ffbStrength = ByteBufCodecs.FLOAT.decode(buf);
                    float ffbMinAccel = ByteBufCodecs.FLOAT.decode(buf);
                    float ffbFullAccel = ByteBufCodecs.FLOAT.decode(buf);
                    int forceKind = ByteBufCodecs.VAR_INT.decode(buf);
                    int feelPreset = ByteBufCodecs.VAR_INT.decode(buf);
                    return new PeripheralHelmBindPayload(pos, new PeripheralHelmBinding(
                            device, steerAxis, invert, maxAngle, deadzone,
                            ffb, ffbStrength, ffbMinAccel, ffbFullAccel, forceKind, feelPreset));
                }

                @Override
                public void encode(ByteBuf buf, PeripheralHelmBindPayload payload) {
                    PeripheralHelmBinding b = payload.binding();
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    ByteBufCodecs.STRING_UTF8.encode(buf, b.deviceId() == null ? "" : b.deviceId());
                    ByteBufCodecs.VAR_INT.encode(buf, b.steerAxis());
                    ByteBufCodecs.BOOL.encode(buf, b.invertSteer());
                    ByteBufCodecs.FLOAT.encode(buf, b.maxAngleDeg());
                    ByteBufCodecs.FLOAT.encode(buf, b.deadzone());
                    ByteBufCodecs.BOOL.encode(buf, b.forceFeedback());
                    ByteBufCodecs.FLOAT.encode(buf, b.ffbStrength());
                    ByteBufCodecs.FLOAT.encode(buf, b.ffbMinAccel());
                    ByteBufCodecs.FLOAT.encode(buf, b.ffbFullAccel());
                    ByteBufCodecs.VAR_INT.encode(buf, b.forceKind());
                    ByteBufCodecs.VAR_INT.encode(buf, b.feelPreset());
                }
            };

    @Override
    public Type<PeripheralHelmBindPayload> type() {
        return TYPE;
    }

    public static void handle(PeripheralHelmBindPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (serverPlayer.level().getBlockEntity(payload.pos())
                    instanceof PeripheralHelmBlockEntity be) {
                be.setBinding(payload.binding());
                // 绑定归属 = 提交者：之后只有他的客户端采样/上发舵角（其它客户端零参与）
                be.setOwner(serverPlayer.getUUID());
            }
        });
    }

    public static void sendToServer(BlockPos pos, PeripheralHelmBinding binding) {
        PacketDistributor.sendToServer(new PeripheralHelmBindPayload(pos, binding));
    }
}
