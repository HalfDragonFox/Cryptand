/**
 * ===== 外设拉杆绑定更新包（C2S，2026-09-13） =====
 *
 * LDLib2 界面点"保存并应用"后发送：方块坐标 + 完整绑定（设备 id / 输入模式 / 轴映射 /
 * 行程与死区 / 加减档按钮）。服务端写入 BE 并 sendData() 同步给追踪玩家。
 * 第一个绑定设备的玩家成为该拉杆的驱动者（只有他的客户端能推这个油门）。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

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

public record PeripheralLeverBindPayload(BlockPos pos, PeripheralLeverBinding binding)
        implements CustomPacketPayload {

    public static final Type<PeripheralLeverBindPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_lever_bind"));

    public static final StreamCodec<ByteBuf, PeripheralLeverBindPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PeripheralLeverBindPayload decode(ByteBuf buf) {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
            String device = ByteBufCodecs.STRING_UTF8.decode(buf);
            int mode = ByteBufCodecs.VAR_INT.decode(buf);
            int axis = ByteBufCodecs.VAR_INT.decode(buf);
            boolean invert = ByteBufCodecs.BOOL.decode(buf);
            float deadzone = ByteBufCodecs.FLOAT.decode(buf);
            float travelMin = ByteBufCodecs.FLOAT.decode(buf);
            float travelMax = ByteBufCodecs.FLOAT.decode(buf);
            int buttonUp = ByteBufCodecs.VAR_INT.decode(buf);
            int buttonDown = ByteBufCodecs.VAR_INT.decode(buf);
            int forceKind = ByteBufCodecs.VAR_INT.decode(buf);
            return new PeripheralLeverBindPayload(pos, new PeripheralLeverBinding(
                    device, mode, axis, invert, deadzone, travelMin, travelMax,
                    buttonUp, buttonDown, forceKind));
        }

        @Override
        public void encode(ByteBuf buf, PeripheralLeverBindPayload payload) {
            PeripheralLeverBinding b = payload.binding();
            BlockPos.STREAM_CODEC.encode(buf, payload.pos());
            ByteBufCodecs.STRING_UTF8.encode(buf, b.deviceId() == null ? "" : b.deviceId());
            ByteBufCodecs.VAR_INT.encode(buf, b.inputMode());
            ByteBufCodecs.VAR_INT.encode(buf, b.axisIndex());
            ByteBufCodecs.BOOL.encode(buf, b.invertAxis());
            ByteBufCodecs.FLOAT.encode(buf, b.deadzone());
            ByteBufCodecs.FLOAT.encode(buf, b.travelMin());
            ByteBufCodecs.FLOAT.encode(buf, b.travelMax());
            ByteBufCodecs.VAR_INT.encode(buf, b.buttonUp());
            ByteBufCodecs.VAR_INT.encode(buf, b.buttonDown());
            ByteBufCodecs.VAR_INT.encode(buf, b.forceKind());
        }
    };

    @Override
    public Type<PeripheralLeverBindPayload> type() {
        return TYPE;
    }

    public static void sendToServer(BlockPos pos, PeripheralLeverBinding binding) {
        PacketDistributor.sendToServer(new PeripheralLeverBindPayload(pos, binding));
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level().getBlockEntity(pos) instanceof PeripheralLeverBlockEntity lever)) {
            return;
        }
        if (player.distanceToSqr(pos.getCenter()) > 64.0 * 64.0) {
            return;
        }
        lever.setBinding(binding);
        if (lever.owner() == null) {
            lever.setOwner(player.getUUID());
        }
    }
}
