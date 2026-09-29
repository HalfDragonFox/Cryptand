package com.hdf.cryptand.neoforge.soc.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.soc.ui.SocClientState;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** ===== 组装台状态回报（S2C，2026-09-15）：槽位内容与预览行 ===== */
public record SocAssemblerStatusPayload(BlockPos pos, List<String> lines) implements CustomPacketPayload {

    public static final Type<SocAssemblerStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "soc_assembler_status"));

    public static final StreamCodec<ByteBuf, SocAssemblerStatusPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                buf.writeInt(payload.lines().size());
                for (String line : payload.lines()) {
                    CompileRequestPayload.writeString(buf, line);
                }
            },
            buf -> {
                final BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                final int n = Math.min(buf.readInt(), 64);
                final List<String> lines = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    lines.add(CompileRequestPayload.readString(buf));
                }
                return new SocAssemblerStatusPayload(pos, lines);
            });

    @Override
    public Type<SocAssemblerStatusPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        SocClientState.updateAssembler(pos, lines);
    }
}
