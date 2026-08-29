/**
 * ===== 示波器波形请求包（C2S） =====
 *
 * 客户端示波器搭线到电网端点后，每 10 tick 发送本包（key + pos#term）。
 * 服务端对测量点所在网络求解（复用 PhasorEngine.solveBlocks，多频叠加时
 * 每频率独立求解），提取该端子每频率相量 → 回发 OscilloscopeResponsePayload。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public record OscilloscopeRequestPayload(String key, long posA, int tA, double freq)
        implements CustomPacketPayload {

    public static final Type<OscilloscopeRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "oscilloscope_request"));

    public static final StreamCodec<ByteBuf, OscilloscopeRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.key());
                buf.writeLong(p.posA());
                buf.writeInt(p.tA());
                buf.writeDouble(p.freq());
            },
            buf -> new OscilloscopeRequestPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readLong(),
                    buf.readInt(),
                    buf.readDouble()));

    @Override
    public Type<OscilloscopeRequestPayload> type() {
        return TYPE;
    }

    /** 服务端处理：求解测量点网络 → 提取每频率相量 → 回发 */
    public static void handle(OscilloscopeRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                boolean serverbound = context.flow() != null && context.flow().isServerbound();
                if (!serverbound || !(context.player() instanceof ServerPlayer sp)) return;
                if (!(sp.serverLevel() instanceof ServerLevel level)) return;
                BlockPos pos = BlockPos.of(payload.posA());
                long gameTime = level.getGameTime();
                // 求解测量点所在网络（万用表同款路径：buildContextFromBlocks）
                com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine.NetworkSolve solve =
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine.solveBlocks(
                                level, List.of(pos), List.of(), payload.freq(), gameTime);
                if (solve == null) {
                    PacketDistributor.sendToPlayer(sp,
                            new OscilloscopeResponsePayload(payload.key(), false, null, null, null));
                    return;
                }
                java.util.List<double[]> tones =
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorEngine.scopeToneFromSolve(
                                solve, pos, payload.tA());
                if (tones.isEmpty()) {
                    PacketDistributor.sendToPlayer(sp,
                            new OscilloscopeResponsePayload(payload.key(), false, null, null, null));
                    return;
                }
                int n = tones.size();
                float[] freqs = new float[n];
                float[] amps = new float[n];
                float[] phases = new float[n];
                for (int i = 0; i < n; i++) {
                    freqs[i] = (float) tones.get(i)[0];
                    amps[i] = (float) tones.get(i)[1];
                    phases[i] = (float) tones.get(i)[2];
                }
                PacketDistributor.sendToPlayer(sp,
                        new OscilloscopeResponsePayload(payload.key(), true, freqs, amps, phases));
            } catch (Throwable t) {
                try {
                    if (context.player() instanceof ServerPlayer sp) {
                        PacketDistributor.sendToPlayer(sp,
                                new OscilloscopeResponsePayload(payload.key(), false, null, null, null));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 客户端发送（仅客户端调用） */
    public static void sendToServer(String key, BlockPos pos, int t, double freq) {
        PacketDistributor.sendToServer(new OscilloscopeRequestPayload(
                key, pos.asLong(), t, freq));
    }
}
