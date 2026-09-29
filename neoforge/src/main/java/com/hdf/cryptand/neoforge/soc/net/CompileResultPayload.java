package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.compile.RemoteServerCompiler;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * ===== SoC 编译回执 · 下行包（S2C，2026-09-15）=====
 *
 * <p>服务端代编译的结果（含失败原因与工具链信息）。客户端按 {@code requestId} 匹配等待中的请求；
 * 超时未收到由客户端自行判定（服务端未装/未开启时表现为超时）。</p>
 *
 * @param requestId   请求号（与上行一致）
 * @param ok          是否成功
 * @param binary      产物（纯二进制固件；失败为空数组）
 * @param error       失败原因（含"服务端未开启编译功能"等）
 * @param diagnostics 诊断文本（file:line:col: error: … 逐行）
 * @param toolchain   服务端实际使用的工具链（路径 + 版本）
 * @param elapsedMs   服务端耗时
 */
public record CompileResultPayload(long requestId, boolean ok, byte[] binary, String error,
                                   String diagnostics, String toolchain, long elapsedMs)
        implements CustomPacketPayload {

    public static final Type<CompileResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "soc_compile_result"));

    // ⚠ 手写编解码（字段数超 composite 上限；二进制产物与诊断文本均可较大）
    public static final StreamCodec<ByteBuf, CompileResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeLong(payload.requestId());
                buf.writeBoolean(payload.ok());
                final byte[] binary = payload.binary() == null ? new byte[0] : payload.binary();
                buf.writeInt(binary.length);
                buf.writeBytes(binary);
                CompileRequestPayload.writeString(buf, payload.error());
                CompileRequestPayload.writeString(buf, payload.diagnostics());
                CompileRequestPayload.writeString(buf, payload.toolchain());
                buf.writeLong(payload.elapsedMs());
            },
            buf -> {
                final long requestId = buf.readLong();
                final boolean ok = buf.readBoolean();
                final int length = buf.readInt();
                final byte[] binary = new byte[length];
                buf.readBytes(binary);
                return new CompileResultPayload(requestId, ok, binary,
                        CompileRequestPayload.readString(buf),
                        CompileRequestPayload.readString(buf),
                        CompileRequestPayload.readString(buf),
                        buf.readLong());
            });

    @Override
    public Type<CompileResultPayload> type() {
        return TYPE;
    }

    /** 客户端：交接给等待中的请求（在客户端主线程调用） */
    public void handle(IPayloadContext context) {
        RemoteServerCompiler.complete(this);
    }
}
