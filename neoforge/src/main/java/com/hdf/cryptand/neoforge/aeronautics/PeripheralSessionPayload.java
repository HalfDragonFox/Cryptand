/**
 * ===== 外设会话 · 上行包（C2S，2026-09-13）=====
 *
 * 用户定稿的架构：
 * <pre>
 *   客户端 = 主动端：全部外设计算在客户端异步完成，按固定频率（默认 20Hz，可配）
 *                    往服务端发"当前数据"或"空包"（空包 = 纯保活，维持会话窗口）。
 *   服务端 = 被动端：只负责开会话窗口 + 限流（每秒接收上限可配，超出直接丢弃）
 *                    + 回一份该方块所在结构的 sable 运动数据。
 * </pre>
 * 客户端发得比服务端接收上限快时，**多余的包会被服务端丢弃**（不做反压、不排队）——
 * 这正是"客户端主动、服务端被动"的语义。
 *
 * @param pos   目标方块（船舵 / 拉杆）
 * @param kind  {@link #KIND_HELM} 舵角 / {@link #KIND_LEVER} 档位 / {@link #KIND_PING} 空包保活
 * @param value 舵角（度）或档位（0..15）；空包时为 0
 * @param seq   客户端递增序号（诊断用：看丢了多少包）
 */

package com.hdf.cryptand.neoforge.aeronautics;

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

public record PeripheralSessionPayload(BlockPos pos, int kind, float value, int seq)
        implements CustomPacketPayload {

    /** 船舵：value = 目标舵角（度） */
    public static final int KIND_HELM = 0;
    /** 拉杆：value = 档位（0..15） */
    public static final int KIND_LEVER = 1;
    /** 空包：仅保活会话窗口（客户端主动端的"心跳"） */
    public static final int KIND_PING = -1;

    public static final Type<PeripheralSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_session"));

    public static final StreamCodec<ByteBuf, PeripheralSessionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PeripheralSessionPayload::pos,
                    ByteBufCodecs.VAR_INT, PeripheralSessionPayload::kind,
                    ByteBufCodecs.FLOAT, PeripheralSessionPayload::value,
                    ByteBufCodecs.VAR_INT, PeripheralSessionPayload::seq,
                    PeripheralSessionPayload::new);

    @Override
    public Type<PeripheralSessionPayload> type() {
        return TYPE;
    }

    /** 客户端：按会话频率上行（由 {@link PeripheralSessionClient} 调用）。 */
    public static void sendToServer(BlockPos pos, int kind, float value, int seq) {
        PacketDistributor.sendToServer(new PeripheralSessionPayload(pos, kind, value, seq));
    }

    /**
     * 服务端（被动端）：限流 → 采集 sable 数据 → 回包。
     * <p>超出该玩家每秒接收上限的包<b>直接丢弃</b>（不回复、不排队）。
     */
    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        PeripheralSessionServer.onUpstream(player, this);
    }
}
