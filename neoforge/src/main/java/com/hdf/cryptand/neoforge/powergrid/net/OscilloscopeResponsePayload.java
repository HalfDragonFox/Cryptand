/**
 * ===== 示波器波形响应包（S2C） =====
 *
 * 服务端收到 OscilloscopeRequestPayload 后，对测量点所在网络求解（多频叠加
 * 时每频率独立求解），提取该端子的【每频率相量】（freq/amp/phase），回发
 * 本包。客户端仅收到本包才更新本地波形数据（OscilloscopeStore）→ 渲染。
 * 数据量小（分量通常 1-5 个），用等长 float 数组编码。
 */

package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.measurement.SelfManagedMultimeter;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OscilloscopeResponsePayload(String key, boolean valid,
                                          float[] freqs, float[] amps, float[] phases)
        implements CustomPacketPayload {

    public static final Type<OscilloscopeResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "oscilloscope_response"));

    public static final StreamCodec<ByteBuf, OscilloscopeResponsePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.key());
                buf.writeBoolean(p.valid());
                int n = p.freqs() == null ? 0 : p.freqs().length;
                buf.writeInt(n);
                for (int i = 0; i < n; i++) {
                    buf.writeFloat(p.freqs()[i]);
                    buf.writeFloat(p.amps()[i]);
                    buf.writeFloat(p.phases()[i]);
                }
            },
            buf -> {
                String key = ByteBufCodecs.STRING_UTF8.decode(buf);
                boolean valid = buf.readBoolean();
                int n = buf.readInt();
                if (n <= 0) return new OscilloscopeResponsePayload(key, valid, null, null, null);
                if (n > 64) n = 64; // 防御
                float[] freqs = new float[n];
                float[] amps = new float[n];
                float[] phases = new float[n];
                for (int i = 0; i < n; i++) {
                    freqs[i] = buf.readFloat();
                    amps[i] = buf.readFloat();
                    phases[i] = buf.readFloat();
                }
                return new OscilloscopeResponsePayload(key, valid, freqs, amps, phases);
            });

    @Override
    public Type<OscilloscopeResponsePayload> type() {
        return TYPE;
    }

    /** 客户端处理：统一走自定义测量类接受（更新本地波形数据，仅收到此包才更新） */
    public static void handle(OscilloscopeResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                SelfManagedMultimeter.acceptOscilloscope(
                        payload.key(), payload.valid(), payload.freqs(), payload.amps(), payload.phases());
            } catch (Throwable ignored) {
            }
        });
    }
}
