/**
 * ===== 导线剪线包（C2S，2026-08-17） =====
 *
 * 自管模式下导线实体已删除 → 原版"剪线钳右键导线实体"不可用。客户端
 * WireLookPicker 射线检测玩家看向的导线（WireLookStore）→ 手持剪线钳
 * 右键时发送本包（两端点身份）→ 服务端按边拆除 + 返回导线物品 + 同步。
 *
 * 数据：导线两端点（方块 pos + 端子索引），与 WireGraph 边端点一致。
 * 服务端校验：自管模式 + 手持剪线钳 + 边存在 → CryptandWirePlacement.
 * removeWireByEndpoints。
 */

package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.network.wire.CryptandWirePlacement;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record WireCutPayload(int ax, int ay, int az, int aTerm,
                             int bx, int by, int bz, int bTerm)
        implements CustomPacketPayload {

    public static final Type<WireCutPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "wire_cut"));

    public static final StreamCodec<ByteBuf, WireCutPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WireCutPayload decode(ByteBuf buf) {
                    return new WireCutPayload(
                            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
                }

                @Override
                public void encode(ByteBuf buf, WireCutPayload p) {
                    buf.writeInt(p.ax());
                    buf.writeInt(p.ay());
                    buf.writeInt(p.az());
                    buf.writeInt(p.aTerm());
                    buf.writeInt(p.bx());
                    buf.writeInt(p.by());
                    buf.writeInt(p.bz());
                    buf.writeInt(p.bTerm());
                }
            };

    @Override
    public Type<WireCutPayload> type() {
        return TYPE;
    }

    /** 服务端处理：自管剪线（按两端点身份移除单条边 + 返回物品 + 同步） */
    public static void handle(WireCutPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.flow().isServerbound()
                        && context.player() instanceof ServerPlayer sp) {
                    Level level = sp.level();
                    if (level == null || level.isClientSide) return;
                    if (!CryptandWirePlacement.selfManaged(level)) return;
                    // 手持剪线钳校验（主手或副手）
                    if (!isWireCutter(sp.getMainHandItem())
                            && !isWireCutter(sp.getOffhandItem())) return;
                    CryptandWirePlacement.removeWireByEndpoints(level, sp,
                            payload.ax(), payload.ay(), payload.az(), payload.aTerm(),
                            payload.bx(), payload.by(), payload.bz(), payload.bTerm());
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 剪线钳判定（powergrid:wire_cutter） */
    private static boolean isWireCutter(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && id.getNamespace().equals("powergrid")
                    && id.getPath().equals("wire_cutter");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
