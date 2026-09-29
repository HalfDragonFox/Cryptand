/**
 * ===== 网络颜色同步包（S2C，2026-08-21 用户要求） =====
 *
 * 服务端把自管图每个【连通分量】（含孤立设备/悬空端子的单点分量）分配的
 * 稳定随机颜色广播给客户端 → ClientNetworkColorStore.setServiceColors →
 * NetworkColorRenderer 给网络内所有元件方块画彩色外框（直观显示网络合并/
 * 拆分是否正确：同网络同色、分裂网络异色、孤立设备也有独立颜色）。
 *
 * 与导线图同步（WireGraphSyncPayload）同发（sendEdges 时一并发送），保证
 * 客户端网络颜色与服务端拓扑一致。
 */

package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.client.ClientNetworkColorStore;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record NetworkColorPayload(List<ColorEntry> entries) implements CustomPacketPayload {

    /** 一个方块 + 所属网络的稳定颜色（ARGB） */
    public record ColorEntry(int x, int y, int z, int color) {
    }

    public static final Type<NetworkColorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "network_color"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<ByteBuf, NetworkColorPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NetworkColorPayload decode(ByteBuf buf) {
            int n = buf.readInt();
            List<ColorEntry> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new ColorEntry(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
            }
            return new NetworkColorPayload(out);
        }

        @Override
        public void encode(ByteBuf buf, NetworkColorPayload p) {
            buf.writeInt(p.entries().size());
            for (ColorEntry e : p.entries()) {
                buf.writeInt(e.x());
                buf.writeInt(e.y());
                buf.writeInt(e.z());
                buf.writeInt(e.color());
            }
        }
    };

    public static void handle(NetworkColorPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Map<BlockPos, Integer> m = new HashMap<>();
                for (ColorEntry e : payload.entries()) {
                    m.put(new BlockPos(e.x(), e.y(), e.z()), e.color());
                }
                ClientNetworkColorStore.setServiceColors(m);
            } catch (Throwable ignored) {
            }
        });
    }
}
