package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.compile.ServerCompileService;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * ===== SoC 编译请求 · 上行包（C2S，2026-09-15）=====
 *
 * <p>用户定稿：客户端编译优先，<b>本地无工具链且服务端开启编译功能时</b>请求服务端代编译
 * （服务端 `soc.toml#enableServerCompile`，<b>默认关闭</b>）。</p>
 *
 * <p>服务端侧：限流（并发核心数 / 队列上限 / 源码大小）→ 后台编译 → {@link CompileResultPayload} 回执。</p>
 *
 * @param requestId      请求号（客户端自增；用于匹配回执与超时）
 * @param programName    程序名（工作目录/诊断）
 * @param source         主源码（main.c）
 * @param extraFilesJson 附加文件（JSON：文件名 → 内容；如 cryptand.ld、util.h）
 * @param march          目标 ISA（默认 rv32im）
 * @param mabi           目标 ABI（默认 ilp32）
 * @param linkerScript   链接脚本名（须在 extraFilesJson 中；可空）
 * @param timeoutMs      客户端期望的超时（服务端会取配置与其较小值）
 */
public record CompileRequestPayload(long requestId, String programName, String source,
                                    String extraFilesJson, String march, String mabi,
                                    String linkerScript, int timeoutMs) implements CustomPacketPayload {

    public static final Type<CompileRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "soc_compile_request"));

    // ⚠ 字段数 8 > StreamCodec.composite 上限（6），故手写编解码；
    //   字符串用 length-prefix UTF-8（不受 STRING_UTF8 默认 32KB 限制，源码可达 256KB）。
    public static final StreamCodec<ByteBuf, CompileRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeLong(payload.requestId());
                writeString(buf, payload.programName());
                writeString(buf, payload.source());
                writeString(buf, payload.extraFilesJson());
                writeString(buf, payload.march());
                writeString(buf, payload.mabi());
                writeString(buf, payload.linkerScript());
                buf.writeInt(payload.timeoutMs());
            },
            buf -> new CompileRequestPayload(
                    buf.readLong(),
                    readString(buf),
                    readString(buf),
                    readString(buf),
                    readString(buf),
                    readString(buf),
                    readString(buf),
                    buf.readInt()));

    /** 写 length-prefix UTF-8 字符串（供本包两个 payload 共用） */
    static void writeString(ByteBuf buf, String value) {
        final byte[] bytes = (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    /** 读 length-prefix UTF-8 字符串 */
    static String readString(ByteBuf buf) {
        final int length = buf.readInt();
        final byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public Type<CompileRequestPayload> type() {
        return TYPE;
    }

    /** 客户端：请求服务端代编译 */
    public static void sendToServer(CompileRequestPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 服务端：入队编译（限流 + 超时；未开启则直接回执说明） */
    public void handle(IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ServerCompileService.handle(player, this);
        }
    }
}
