/**
 * ===== 温度表响应包（S2C） =====
 *
 * 服务端收到 ThermometerRequestPayload 后直接查温度存储（不做求解），
 * 将摄氏温度回发给请求玩家。客户端【仅】在收到本包后更新本地读数。
 */

package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.measurement.ThermometerReadoutStore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ThermometerResponsePayload(String key, double tempC, String label, boolean valid)
        implements CustomPacketPayload {

    public static final Type<ThermometerResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "thermometer_response"));

    public static final StreamCodec<ByteBuf, ThermometerResponsePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ThermometerResponsePayload::key,
            ByteBufCodecs.DOUBLE, ThermometerResponsePayload::tempC,
            ByteBufCodecs.STRING_UTF8, ThermometerResponsePayload::label,
            ByteBufCodecs.BOOL, ThermometerResponsePayload::valid,
            ThermometerResponsePayload::new);

    @Override
    public Type<ThermometerResponsePayload> type() {
        return TYPE;
    }

    /** 客户端处理：收到服务端响应后才更新读数 */
    public static void handle(ThermometerResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow() != null && context.flow().isClientbound()) {
                ThermometerReadoutStore.updateFromResponse(
                        payload.key(), payload.tempC(), payload.label(), payload.valid());
            }
        });
    }
}
