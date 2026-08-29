/**
 * ===== 导线放置阻挡包（S2C，2026-08-14） =====
 *
 * 服务端放置导线时检测到路径被方块阻挡（CryptandWirePlacement.blockedBlocks）
 * → 把阻挡方块列表发给【该玩家】，客户端用红色方框选中显示（像 Create
 * 冲突/蓝图选区那样提示"这里被挡了"）。
 *
 * 只发给放置者本人（PacketDistributor.sendToPlayer），3 秒后客户端自动消失。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.core.client.ClientBlockedStore;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record WireBlockedPayload(List<BlockPos> blocks) implements CustomPacketPayload {

    public static final Type<WireBlockedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "wire_blocked"));

    public static final StreamCodec<ByteBuf, WireBlockedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.collection(ArrayList::new, BlockPos.STREAM_CODEC),
                    WireBlockedPayload::blocks,
                    WireBlockedPayload::new);

    @Override
    public Type<WireBlockedPayload> type() {
        return TYPE;
    }

    /** 客户端处理：记录阻挡方块（渲染层读取，3s 过期） */
    public static void handle(WireBlockedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isClientbound()) {
                ClientBlockedStore.show(payload.blocks());
            }
        });
    }
}
