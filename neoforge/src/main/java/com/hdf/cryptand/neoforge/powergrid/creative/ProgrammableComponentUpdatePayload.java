/**
 * ===== 可编程元件更新包（C2S） =====
 *
 * 客户端选择库条目 + 参数后发送：目标方块坐标 + 库条目名 + 参数(k,v 列表)。
 * 服务端更新对应 BE 并触发网络重建。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ProgrammableComponentUpdatePayload(BlockPos pos, String libraryName,
                                                 List<String> paramKeys, List<Double> paramValues)
        implements CustomPacketPayload {

    public static final Type<ProgrammableComponentUpdatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "programmable_component_update"));

    public static final StreamCodec<ByteBuf, ProgrammableComponentUpdatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ProgrammableComponentUpdatePayload::pos,
                    ByteBufCodecs.STRING_UTF8, ProgrammableComponentUpdatePayload::libraryName,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    ProgrammableComponentUpdatePayload::paramKeys,
                    ByteBufCodecs.DOUBLE.apply(ByteBufCodecs.list()),
                    ProgrammableComponentUpdatePayload::paramValues,
                    ProgrammableComponentUpdatePayload::new);

    @Override
    public Type<ProgrammableComponentUpdatePayload> type() {
        return TYPE;
    }

    public static void handle(ProgrammableComponentUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.level().getBlockEntity(payload.pos())
                        instanceof ProgrammableComponentBlockEntity be) {
                    // 选择模型 → 服务端把该条目的固定参数固化写入方块（不依赖库）
                    java.util.Map<String, Double> params = new java.util.HashMap<>();
                    int n = Math.min(payload.paramKeys().size(), payload.paramValues().size());
                    for (int i = 0; i < n; i++) {
                        params.put(payload.paramKeys().get(i), payload.paramValues().get(i));
                    }
                    be.setLibraryAndResolve(payload.libraryName(), params);
                }
            }
        });
    }

    public static void sendToServer(BlockPos pos, String libraryName, Map<String, Double> params) {
        List<String> keys = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        if (params != null) {
            for (Map.Entry<String, Double> e : params.entrySet()) {
                keys.add(e.getKey());
                values.add(e.getValue());
            }
        }
        PacketDistributor.sendToServer(
                new ProgrammableComponentUpdatePayload(pos, libraryName, keys, values));
    }
}
