/**
 * ===== 力显示响应包（S2C，2026-09-14） =====
 *
 * <p>服务端对 {@link SableForceRequestPayload} 的被动回复。
 * 两种情况：
 * <ul>
 *   <li>{@code supported=false}：服务端未开启力显示接口（或未装 Cryptand）→
 *       客户端立即进入"不支持"状态：停止请求 + 一次性提示，不再产生任何流量</li>
 *   <li>{@code supported=true}：携带本 tick 采集到的力样本（按结构分组）</li>
 * </ul>
 *
 * <p>编码（按结构分组，控制包大小）：
 * <pre>
 *   supported:boolean, detailed:boolean, structureCount:varint
 *   [ structureKey:int, sampleCount:varint,
 *     [ id:utf, kind:byte, cx,cy,cz,fx,fy,fz : 6×double ] × sampleCount ] × structureCount
 * </pre>
 */
package com.hdf.cryptand.neoforge.sable.force.net;

import com.hdf.cryptand.Cryptand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record SableForceResponsePayload(boolean supported, boolean detailed,
                                        List<StructureForces> structures)
        implements CustomPacketPayload {

    /** 一个结构的力样本集合（key 仅用于客户端分组/替换）。 */
    public record StructureForces(int structureKey, List<Sample> samples) {
    }

    /** 一条力样本（世界坐标）。 */
    public record Sample(String forceId, byte kind,
                         double cx, double cy, double cz,
                         double fx, double fy, double fz) {
    }

    public static final Type<SableForceResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "sable_force_response"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端未开启接口时的标准回复（零数据）。 */
    public static SableForceResponsePayload unsupported() {
        return new SableForceResponsePayload(false, false, List.of());
    }

    public static final StreamCodec<FriendlyByteBuf, SableForceResponsePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SableForceResponsePayload decode(final FriendlyByteBuf buf) {
                    final boolean supported = buf.readBoolean();
                    final boolean detailed = buf.readBoolean();
                    final int structures = buf.readVarInt();
                    final List<StructureForces> list = new ArrayList<>(Math.min(structures, 64));
                    for (int i = 0; i < structures; i++) {
                        final int key = buf.readInt();
                        final int count = buf.readVarInt();
                        final List<Sample> samples = new ArrayList<>(Math.min(count, 256));
                        for (int j = 0; j < count; j++) {
                            samples.add(new Sample(
                                    buf.readUtf(128), buf.readByte(),
                                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                    buf.readDouble(), buf.readDouble(), buf.readDouble()));
                        }
                        list.add(new StructureForces(key, samples));
                    }
                    return new SableForceResponsePayload(supported, detailed, list);
                }

                @Override
                public void encode(final FriendlyByteBuf buf, final SableForceResponsePayload p) {
                    buf.writeBoolean(p.supported());
                    buf.writeBoolean(p.detailed());
                    buf.writeVarInt(p.structures().size());
                    for (final StructureForces sf : p.structures()) {
                        buf.writeInt(sf.structureKey());
                        buf.writeVarInt(sf.samples().size());
                        for (final Sample s : sf.samples()) {
                            buf.writeUtf(s.forceId(), 128);
                            buf.writeByte(s.kind());
                            buf.writeDouble(s.cx());
                            buf.writeDouble(s.cy());
                            buf.writeDouble(s.cz());
                            buf.writeDouble(s.fx());
                            buf.writeDouble(s.fy());
                            buf.writeDouble(s.fz());
                        }
                    }
                }
            };

    /** 客户端处理入口：写入力显示缓存（不渲染，渲染由 ForceDisplayRenderer 负责）。 */
    public static void handle(final SableForceResponsePayload payload,
                              final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() ->
                com.hdf.cryptand.neoforge.sable.force.client.ForceDisplayStore.INSTANCE
                        .onResponse(payload));
    }
}
