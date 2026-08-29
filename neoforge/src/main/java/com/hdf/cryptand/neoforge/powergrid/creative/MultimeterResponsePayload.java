/**
 * ===== 万用表测量响应包（S2C） =====
 *
 * 服务端收到 MultimeterRequestPayload 后完成网络级相量计算，
 * 将 RMS 结果回发给请求玩家。客户端【仅】在收到本包后更新本地读数。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterReadoutStore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MultimeterResponsePayload(String key, double rms, boolean valid, double freq)
        implements CustomPacketPayload {

    public static final Type<MultimeterResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "multimeter_response"));

    public static final StreamCodec<ByteBuf, MultimeterResponsePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MultimeterResponsePayload::key,
            ByteBufCodecs.DOUBLE, MultimeterResponsePayload::rms,
            ByteBufCodecs.BOOL, MultimeterResponsePayload::valid,
            ByteBufCodecs.DOUBLE, MultimeterResponsePayload::freq,
            MultimeterResponsePayload::new);

    @Override
    public Type<MultimeterResponsePayload> type() {
        return TYPE;
    }

    /** 客户端处理：收到服务端响应后才更新读数（过期检查由 read 处理） */
    public static void handle(MultimeterResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isClientbound()) {
                try {
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[MeterRecvC] key={} valid={} rms={} freq={}",
                            payload.key(), payload.valid(), payload.rms(), payload.freq());
                } catch (Throwable ignored) {
                }
                MultimeterReadoutStore.updateFromResponse(payload.key(), payload.rms(),
                        payload.freq(), payload.valid());
            }
        });
    }
}
