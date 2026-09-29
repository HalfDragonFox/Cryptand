/**
 * ===== 元件数值+单位配置更新包（C2S，电容 / 电感通用） =====
 *
 * 客户端菜单确认后发送：目标方块坐标 + 基准值 + 单位索引。
 * 服务端按方块实体类型分发（电容 F / 电感 H）更新对应 BE。
 */

package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.block.CapacitorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.block.InductorBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ValueUnitUpdatePayload(BlockPos pos, float valueBase, int unitIndex)
        implements CustomPacketPayload {

    public static final Type<ValueUnitUpdatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "value_unit_update"));

    public static final StreamCodec<ByteBuf, ValueUnitUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ValueUnitUpdatePayload::pos,
            ByteBufCodecs.FLOAT, ValueUnitUpdatePayload::valueBase,
            ByteBufCodecs.INT, ValueUnitUpdatePayload::unitIndex,
            ValueUnitUpdatePayload::new);

    @Override
    public Type<ValueUnitUpdatePayload> type() {
        return TYPE;
    }

    public static void handle(ValueUnitUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer serverPlayer) {
                var be = serverPlayer.level().getBlockEntity(payload.pos());
                if (be instanceof CapacitorBlockEntity cap) {
                    cap.setValueBase(payload.valueBase());
                    cap.setUnitIndex(payload.unitIndex());
                } else if (be instanceof InductorBlockEntity ind) {
                    ind.setValueBase(payload.valueBase());
                    ind.setUnitIndex(payload.unitIndex());
                }
            }
        });
    }

    public static void sendToServer(BlockPos pos, float valueBase, int unitIndex) {
        PacketDistributor.sendToServer(new ValueUnitUpdatePayload(pos, valueBase, unitIndex));
    }
}
