package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.download.ServerDownloadClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * ===== 服务端下载状态回报（S2C，2026-09-15）=====
 *
 * <p>服务端下载的进度/结果行；客户端 UI 直接显示（"当前下载的是服务器还是客户端"一目了然）。</p>
 */
public record SocToolDownloadStatusPayload(String line) implements CustomPacketPayload {

    public static final Type<SocToolDownloadStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "soc_tool_download_status"));

    public static final StreamCodec<ByteBuf, SocToolDownloadStatusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SocToolDownloadStatusPayload::line,
            SocToolDownloadStatusPayload::new);

    @Override
    public Type<SocToolDownloadStatusPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        ServerDownloadClient.update(line);
    }
}
