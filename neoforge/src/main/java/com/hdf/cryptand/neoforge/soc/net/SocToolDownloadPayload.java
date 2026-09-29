package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.download.ServerToolDownloadService;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * ===== 工具链下载请求（C2S，2026-09-15）=====
 *
 * <p>UI 选择"服务端"时发送：服务端做权限/开关校验后在独立线程下载，并回报状态。</p>
 */
public record SocToolDownloadPayload(int action, String toolId, String platform) implements CustomPacketPayload {

    public static final int ACTION_START = 0;
    public static final int ACTION_CANCEL = 1;
    public static final int ACTION_QUERY = 2;

    public static final Type<SocToolDownloadPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "soc_tool_download"));

    public static final StreamCodec<ByteBuf, SocToolDownloadPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SocToolDownloadPayload::action,
            ByteBufCodecs.STRING_UTF8, SocToolDownloadPayload::toolId,
            ByteBufCodecs.STRING_UTF8, SocToolDownloadPayload::platform,
            SocToolDownloadPayload::new);

    @Override
    public Type<SocToolDownloadPayload> type() {
        return TYPE;
    }

    public static void send(int action, String toolId, String platform) {
        PacketDistributor.sendToServer(new SocToolDownloadPayload(action, toolId, platform));
    }

    public void handle(IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ServerToolDownloadService.handle(player, this);
        }
    }
}
