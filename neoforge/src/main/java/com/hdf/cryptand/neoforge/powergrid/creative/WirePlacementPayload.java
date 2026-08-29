/**
 * ===== 自管导线放置包（C2S，2026-08-13 一步到位） =====
 *
 * 客户端完成放置状态机后【手动发送】本包到服务端（不依赖原版右键包——
 * 客户端事件 interrupt 会阻止右键包发出，服务端收不到 → "无法放置"）。
 *
 * 数据：起点端子（方块 pos + 端子索引）+ 终点端子。导线类型/物品消耗由
 * 服务端从玩家当前手持物品读取。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.adapter.CryptandWirePlacement;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

public record WirePlacementPayload(int sx, int sy, int sz, int sTerm,
                                   int ex, int ey, int ez, int eTerm)
        implements CustomPacketPayload {

    public static final Type<WirePlacementPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "wire_placement"));

    public static final StreamCodec<ByteBuf, WirePlacementPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WirePlacementPayload decode(ByteBuf buf) {
                    return new WirePlacementPayload(
                            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
                }

                @Override
                public void encode(ByteBuf buf, WirePlacementPayload p) {
                    buf.writeInt(p.sx());
                    buf.writeInt(p.sy());
                    buf.writeInt(p.sz());
                    buf.writeInt(p.sTerm());
                    buf.writeInt(p.ex());
                    buf.writeInt(p.ey());
                    buf.writeInt(p.ez());
                    buf.writeInt(p.eTerm());
                }
            };

    @Override
    public Type<WirePlacementPayload> type() {
        return TYPE;
    }

    /** 服务端处理：自管放置（两端子 → 写自管图，不创建原版实体） */
    public static void handle(WirePlacementPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.flow().isServerbound()
                        && context.player() instanceof ServerPlayer sp) {
                    Level level = sp.level();
                    ItemStack stack = sp.getMainHandItem();
                    if (stack.isEmpty()) return;
                    // 清服务端放置会话（C2S 为主路径，防双路径残留）
                    CryptandWirePlacement.clearPending(sp);
                    CryptandWirePlacement.placeWire(level, stack, sp,
                            new BlockWireEndpoint(
                                    new BlockPos(payload.sx(), payload.sy(), payload.sz()),
                                    payload.sTerm()),
                            new BlockWireEndpoint(
                                    new BlockPos(payload.ex(), payload.ey(), payload.ez()),
                                    payload.eTerm()));
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
