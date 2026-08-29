/**
 * ===== 元件库条目列表响应包（S2C） =====
 *
 * 服务端返回库条目名列表；客户端 Screen 接收后渲染选择按钮。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public record LibraryListResponsePayload(List<String> names) implements CustomPacketPayload {

    public static final Type<LibraryListResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "library_list_response"));

    public static final StreamCodec<ByteBuf, LibraryListResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    LibraryListResponsePayload::names,
                    LibraryListResponsePayload::new);

    @Override
    public Type<LibraryListResponsePayload> type() {
        return TYPE;
    }

    public static void handle(LibraryListResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isClientbound()) {
                // LDLib2 界面：刷新元件库按钮列表
                com.hdf.cryptand.neoforge.core.ui.ProgrammableUi.receiveLibraryList(payload.names());
            }
        });
    }
}
