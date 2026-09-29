/**
 * ===== 亚层物理化渲染同步包（S2C，2026-08-31） =====
 *
 * 服务端物理化（moveBlocksIntoSubLevel）时采集方块数据广播给客户端，
 * 客户端渲染模块据此绘制亚层（参考原版 sable 的 SingleSubLevel 渲染）。
 *
 * 数据含义：
 *  - uuid：亚层唯一 ID（客户端渲染缓存按它区分）
 *  - anchorX/Y/Z：亚层锚点（世界坐标，方块的质心近似位置，渲染平移基准）
 *  - boundMin/Max：局部包围盒（方块移动前世界坐标；客户端转局部偏移）
 *  - blocks：方块状态编码 [stateId, dx, dy, dz, ...]，dx/dy/dz = 相对 anchor 的偏移
 *  - removed：true = 拆卸（客户端删除对应渲染数据）
 *
 * 为何用 stateId（BlockState 网络 id）而非完整序列化：
 *  同步包应轻量；客户端有同版本 registry → stateId 可映射回 BlockState。
 *  方块实体（BE）不渲染（纯物理结构；零件/Create 零件由各 mod 自己的实体渲染器处理）
 *  但数据仍保留 stateId 供扩展。
 */
package com.hdf.cryptand.neoforge.cryptandsable.network;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableClientRenderModule;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SableSubLevelRenderPayload(
        UUID subLevelId,
        int runtimeId,                 // 核心物理体 id（渲染位姿跟随；0=未关联）
        int anchorX, int anchorY, int anchorZ,
        int boundMinX, int boundMinY, int boundMinZ,
        int boundMaxX, int boundMaxY, int boundMaxZ,
        List<Integer> blockData,     // [stateId, dx, dy, dz, ...] 每 4 个一组
        boolean removed
) implements CustomPacketPayload {

    public static final Type<SableSubLevelRenderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "sable_sublevel_render"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 每个方块：[stateId, dx, dy, dz]（相对 anchor，int）。 */
    private static final StreamCodec<ByteBuf, List<Integer>> BLOCK_DATA_CODEC = new StreamCodec<>() {
        @Override
        public List<Integer> decode(ByteBuf buf) {
            int count = buf.readInt();
            List<Integer> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                out.add(buf.readInt());
            }
            return out;
        }

        @Override
        public void encode(ByteBuf buf, List<Integer> data) {
            buf.writeInt(data.size());
            for (int v : data) {
                buf.writeInt(v);
            }
        }
    };

    private static final StreamCodec<ByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(ByteBuf buf) {
            return new UUID(buf.readLong(), buf.readLong());
        }

        @Override
        public void encode(ByteBuf buf, UUID uuid) {
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
        }
    };

    public static final StreamCodec<ByteBuf, SableSubLevelRenderPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SableSubLevelRenderPayload decode(ByteBuf buf) {
                    return new SableSubLevelRenderPayload(
                            UUID_CODEC.decode(buf),
                            buf.readInt(),
                            buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readInt(), buf.readInt(), buf.readInt(),
                            BLOCK_DATA_CODEC.decode(buf),
                            buf.readBoolean());
                }

                @Override
                public void encode(ByteBuf buf, SableSubLevelRenderPayload p) {
                    UUID_CODEC.encode(buf, p.subLevelId());
                    buf.writeInt(p.runtimeId());
                    buf.writeInt(p.anchorX());
                    buf.writeInt(p.anchorY());
                    buf.writeInt(p.anchorZ());
                    buf.writeInt(p.boundMinX());
                    buf.writeInt(p.boundMinY());
                    buf.writeInt(p.boundMinZ());
                    buf.writeInt(p.boundMaxX());
                    buf.writeInt(p.boundMaxY());
                    buf.writeInt(p.boundMaxZ());
                    BLOCK_DATA_CODEC.encode(buf, p.blockData());
                    buf.writeBoolean(p.removed());
                }
            };

    public static void handle(SableSubLevelRenderPayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            SableClientRenderModule.INSTANCE
                    .onSubLevelRenderData(payload);
        });
    }
}
