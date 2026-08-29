/**
 * ===== 元件库条目列表请求包（C2S） =====
 *
 * 客户端打开可编程元件界面时请求服务端库条目列表。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.circuitsimulation.lib.SpiceSubcircuit;
import com.hdf.cryptand.neoforge.core.library.ComponentLibrary;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record LibraryListRequestPayload() implements CustomPacketPayload {

    public static final Type<LibraryListRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "library_list_request"));

    public static final StreamCodec<ByteBuf, LibraryListRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new LibraryListRequestPayload());

    @Override
    public Type<LibraryListRequestPayload> type() {
        return TYPE;
    }

    public static void handle(LibraryListRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer serverPlayer) {
                List<String> names = new ArrayList<>();
                for (SpiceSubcircuit sub : ComponentLibrary.get().all()) {
                    names.add(sub.name);
                }
                PacketDistributor.sendToPlayer(serverPlayer,
                        new LibraryListResponsePayload(names));
            }
        });
    }

    public static void sendToServer() {
        PacketDistributor.sendToServer(new LibraryListRequestPayload());
    }
}
