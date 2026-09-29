package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.ui.SocClientState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * ===== SoC 芯片状态包（S2C，2026-09-15）=====
 *
 * @param pos          芯片位置
 * @param state        运行态（RUNNING / HALTED / WAITING / SANDBOX_FAULTED）
 * @param ticks        已执行 tick 数
 * @param cycles       累计指令数
 * @param faultCause   故障码（0 = 无）
 * @param faultDetail  故障说明
 * @param regs         寄存器区快照
 * @param firmwareSize 固件大小（字节）
 */
public record SocStatusPayload(BlockPos pos, String state, long ticks, long cycles,
                               int faultCause, String faultDetail, int[] regs, int firmwareSize)
        implements CustomPacketPayload {

    public static final Type<SocStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "soc_status"));

    // 字段数 8 > composite 上限 6 ⇒ 手写编解码
    public static final StreamCodec<ByteBuf, SocStatusPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                CompileRequestPayload.writeString(buf, payload.state());
                buf.writeLong(payload.ticks());
                buf.writeLong(payload.cycles());
                buf.writeInt(payload.faultCause());
                CompileRequestPayload.writeString(buf, payload.faultDetail());
                final int[] regs = payload.regs() == null ? new int[0] : payload.regs();
                buf.writeInt(regs.length);
                for (int v : regs) {
                    buf.writeInt(v);
                }
                buf.writeInt(payload.firmwareSize());
            },
            buf -> {
                final BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                final String state = CompileRequestPayload.readString(buf);
                final long ticks = buf.readLong();
                final long cycles = buf.readLong();
                final int faultCause = buf.readInt();
                final String faultDetail = CompileRequestPayload.readString(buf);
                final int n = buf.readInt();
                final int[] regs = new int[Math.max(0, Math.min(n, 256))];
                for (int i = 0; i < regs.length; i++) {
                    regs[i] = buf.readInt();
                }
                final int firmwareSize = buf.readInt();
                return new SocStatusPayload(pos, state, ticks, cycles, faultCause, faultDetail, regs, firmwareSize);
            });

    @Override
    public Type<SocStatusPayload> type() {
        return TYPE;
    }

    /** 客户端：更新缓存并通知面板监听者 */
    public void handle(IPayloadContext context) {
        SocClientState.update(this);
    }
}
