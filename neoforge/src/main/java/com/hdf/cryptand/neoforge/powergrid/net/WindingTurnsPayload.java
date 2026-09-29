/**
 * ===== 匝数配置包（C2S，2026-08-15 完全自管）=====
 *
 * CryptandWindingScreen 设好匝数后发送：{变压器方块, 起点端子, 匝数}。
 * 服务端存到 CryptandWirePlacement 会话（PENDING + PENDING_TURNS），
 * 第二次点击完成缠绕时读取。完全不走原版 TransformerWindingC2SPacket/
 * CONNECTION_DATA/WireConnection。
 */
package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.network.wire.CryptandWirePlacement;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record WindingTurnsPayload(int x, int y, int z, int terminal, int turns)
        implements CustomPacketPayload {

    public static final Type<WindingTurnsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "winding_turns"));

    public static final StreamCodec<ByteBuf, WindingTurnsPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WindingTurnsPayload decode(ByteBuf buf) {
                    return new WindingTurnsPayload(
                            buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readInt(), buf.readInt());
                }

                @Override
                public void encode(ByteBuf buf, WindingTurnsPayload p) {
                    buf.writeInt(p.x());
                    buf.writeInt(p.y());
                    buf.writeInt(p.z());
                    buf.writeInt(p.terminal());
                    buf.writeInt(p.turns());
                }
            };

    @Override
    public Type<WindingTurnsPayload> type() {
        return TYPE;
    }

    /** 客户端发送（CryptandWindingScreen.saveAndClose 调用） */
    public static void send(BlockPos pos, int terminal, int turns) {
        PacketDistributor.sendToServer(
                new WindingTurnsPayload(pos.getX(), pos.getY(), pos.getZ(), terminal, turns));
    }

    /** 服务端处理：匝数存会话（客户端已开屏，服务端 PENDING 可能已记录起点） */
    public static void handle(WindingTurnsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.flow().isServerbound()
                        && context.player() instanceof ServerPlayer sp) {
                    CryptandWirePlacement.setPendingTurns(sp,
                            new BlockPos(payload.x(), payload.y(), payload.z()),
                            payload.terminal(), payload.turns());
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
