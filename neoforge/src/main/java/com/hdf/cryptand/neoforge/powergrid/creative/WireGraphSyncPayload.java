/**
 * ===== 自管导线图同步包（S2C，2026-08-13） =====
 *
 * 服务端把【转换后的自管 WireGraph】广播给客户端（边列表 + 端点 + 颜色），
 * 客户端渲染层（WireRenderManager）从客户端图缓存读取生成 Flywheel 效果。
 *
 * 为什么需要：服务端 removeConvertedWires 删除原版导线实体后，MC 实体
 * 同步会把客户端实体一并删除——客户端渲染（原先遍历实体）失去数据源。
 * 同步自管图 → 渲染完全由【转换后的自管内容】驱动（与"全部依靠转换功能
 * 转换"架构一致），实体删除后视觉保留。
 *
 * 端点编码：B 点 = 方块 pos + 端子索引；J 点（接线端子）= 方块 pos，term=-1。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.core.client.ClientWireGraphStore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record WireGraphSyncPayload(List<WireEdgeData> edges) implements CustomPacketPayload {

    /** 一条图边：块坐标（端点为块位置）+ 精确位置（渲染用，原版端子位置）
     *   + 渲染器引用 id + 染色覆盖（2026-08-14 引用化：客户端按 rendererId
     *   查 SaggingWireRegistry 取 texture/color/sag——不再每边传渲染参数，
     *   包体积变小；colorOverride 0=用渲染器默认色）。
     *   term=-1 = 接线端子 J 点（位置=块中心）。 */
    public record WireEdgeData(
            int ax, int ay, int az, int aTerm,
            int bx, int by, int bz, int bTerm,
            float axF, float ayF, float azF,
            float bxF, float byF, float bzF,
            String rendererId, int colorOverride) {
    }

    public static final Type<WireGraphSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "wire_graph_sync"));

    private static final StreamCodec<ByteBuf, WireEdgeData> EDGE_CODEC = new StreamCodec<>() {
        @Override
        public WireEdgeData decode(ByteBuf buf) {
            return new WireEdgeData(
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    ByteBufCodecs.STRING_UTF8.decode(buf), buf.readInt());
        }

        @Override
        public void encode(ByteBuf buf, WireEdgeData d) {
            buf.writeInt(d.ax());
            buf.writeInt(d.ay());
            buf.writeInt(d.az());
            buf.writeInt(d.aTerm());
            buf.writeInt(d.bx());
            buf.writeInt(d.by());
            buf.writeInt(d.bz());
            buf.writeInt(d.bTerm());
            buf.writeFloat(d.axF());
            buf.writeFloat(d.ayF());
            buf.writeFloat(d.azF());
            buf.writeFloat(d.bxF());
            buf.writeFloat(d.byF());
            buf.writeFloat(d.bzF());
            ByteBufCodecs.STRING_UTF8.encode(buf,
                    d.rendererId() == null ? "" : d.rendererId());
            buf.writeInt(d.colorOverride());
        }
    };

    public static final StreamCodec<ByteBuf, WireGraphSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.collection(ArrayList::new, EDGE_CODEC),
                    WireGraphSyncPayload::edges,
                    WireGraphSyncPayload::new);

    @Override
    public Type<WireGraphSyncPayload> type() {
        return TYPE;
    }

    /** 客户端处理：更新本地自管图缓存（渲染数据源） */
    public static void handle(WireGraphSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isClientbound()) {
                try {
                    int n = payload.edges() == null ? 0 : payload.edges().size();
                    // 诊断（节流）：收到图同步包 → 打印边数，定位渲染断点
                    if (++syncDiag % 40 == 0 || n > 0) {
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[WireSync] client received edges={} diagTick={}", n, syncDiag);
                    }
                    ClientWireGraphStore.update(payload.edges());
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static int syncDiag;
}
