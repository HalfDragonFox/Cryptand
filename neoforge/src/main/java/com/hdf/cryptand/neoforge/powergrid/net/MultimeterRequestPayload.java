/**
 * ===== 万用表测量请求包（C2S） =====
 *
 * 客户端万用表测量目标变化/周期性刷新时发送：
 * 携带测量目标（key + kind + 端点/导线）。服务端收到后入队到
 * ServerMeasurementSystem，由其在服务端 tick 按【同一网络】分组合并求解
 * （一次 buildContext + solve，多方块反查），然后分别回发
 * MultimeterResponsePayload（S2C）。
 *
 * 客户端约定：每 REQUEST_INTERVAL_TICKS（10 tick）发送一次，且仅在收到
 * 服务端响应包后更新本地读数。
 */

package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterReadoutStore;
import com.hdf.cryptand.neoforge.powergrid.measurement.ServerMeasurementSystem;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MultimeterRequestPayload(String key, int kind, long posA, int tA,
                                       long posB, int tB, int eid, double freq)
        implements CustomPacketPayload {

    public static final Type<MultimeterRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "multimeter_request"));

    public static final StreamCodec<ByteBuf, MultimeterRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.key());
                buf.writeInt(p.kind());
                buf.writeLong(p.posA());
                buf.writeInt(p.tA());
                buf.writeLong(p.posB());
                buf.writeInt(p.tB());
                buf.writeInt(p.eid());
                buf.writeDouble(p.freq());
            },
            buf -> new MultimeterRequestPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readDouble()));

    /** 客户端请求节流间隔（tick） */
    public static final long REQUEST_INTERVAL_TICKS = 10L;

    @Override
    public Type<MultimeterRequestPayload> type() {
        return TYPE;
    }

    /** 服务端处理：把请求入队到 ServerMeasurementSystem（同网络合并求解） */
    public static void handle(MultimeterRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            boolean serverbound = context.flow() != null && context.flow().isServerbound();
            boolean isSP = context.player() instanceof ServerPlayer;
            try {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[MeterRecv] key={} kind={} flow={} serverbound={} isSP={} player={}",
                        payload.key(), payload.kind(), context.flow(), serverbound, isSP,
                        context.player() == null ? "null" : context.player().getName().getString());
            } catch (Throwable ignored) {
            }
            if (!serverbound || !isSP) {
                return;
            }
            ServerPlayer sp = (ServerPlayer) context.player();
            MultimeterReadoutStore.Target t = MultimeterReadoutStore.Target.fromWire(
                    payload.kind(),
                    decodePos(payload.posA()), payload.tA(),
                    decodePos(payload.posB()), payload.tB(),
                    payload.eid(), payload.freq());
            if (t == null) {
                PacketDistributor.sendToPlayer(sp,
                        new MultimeterResponsePayload(payload.key(), 0.0, false, payload.freq()));
                return;
            }
            // 入队待处理；ServerMeasurementSystem.tick 按网络分组合并求解后回发
            ServerMeasurementSystem.enqueue(sp, payload.key(), t);
        });
    }

    /** 客户端发送（仅客户端调用） */
    public static void sendToServer(String key, MultimeterReadoutStore.Target t) {
        PacketDistributor.sendToServer(new MultimeterRequestPayload(
                key,
                t.kind.ordinal(),
                t.posA == null ? Long.MIN_VALUE : t.posA.asLong(),
                t.tA,
                t.posB == null ? Long.MIN_VALUE : t.posB.asLong(),
                t.tB,
                t.eid,
                t.freq));
    }

    /** 编码辅助：long → BlockPos（Long.MIN_VALUE = null） */
    public static BlockPos decodePos(long packed) {
        return packed == Long.MIN_VALUE ? null : BlockPos.of(packed);
    }
}
