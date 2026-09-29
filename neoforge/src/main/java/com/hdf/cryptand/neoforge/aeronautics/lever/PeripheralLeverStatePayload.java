/**
 * ===== 外设拉杆档位同步包（C2S，2026-09-13） =====
 *
 * 客户端每 tick 算出档位，只在**变化时**上报（BE 侧再兜一层变化检测）：
 * 服务端写入档位 ⇒ 红石输出 0..15、音效、邻居更新全部沿用官方 {@code setSignal} 语义。
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

public record PeripheralLeverStatePayload(BlockPos pos, int state) implements CustomPacketPayload {

    public static final Type<PeripheralLeverStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_lever_state"));

    public static final StreamCodec<ByteBuf, PeripheralLeverStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PeripheralLeverStatePayload::pos,
            ByteBufCodecs.VAR_INT, PeripheralLeverStatePayload::state,
            PeripheralLeverStatePayload::new);

    @Override
    public Type<PeripheralLeverStatePayload> type() {
        return TYPE;
    }

    public static void sendToServer(BlockPos pos, int state) {
        PacketDistributor.sendToServer(new PeripheralLeverStatePayload(pos, state));
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
        // 只有归属玩家的客户端能驱动这个拉杆（避免别人替你推油门）
        if (lever.owner() != null && !lever.owner().equals(player.getUUID())) {
            return;
        }
        lever.setSignalFromDevice(state);
    }
}
