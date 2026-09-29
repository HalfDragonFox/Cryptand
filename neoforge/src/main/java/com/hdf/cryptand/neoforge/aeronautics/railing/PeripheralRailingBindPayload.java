/**
 * ===== 外设栏杆（按钮）绑定更新包（C2S，2026-09-13） =====
 *
 * 界面点【保存并应用】后发送：方块坐标 + 完整按键映射表（设备 + 条目列表）。
 * ⚠ 列表是变长的，必须手写 StreamCodec（composite 只支持固定 6 组参数）。
 * <p>条目 = 一个按键 + 一个信号（用户 2026-09-14："每条只能绑定一个按键"）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record PeripheralRailingBindPayload(BlockPos pos, PeripheralRailingBinding binding)
        implements CustomPacketPayload {

    public static final Type<PeripheralRailingBindPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_railing_bind"));

    public static final StreamCodec<ByteBuf, PeripheralRailingBindPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PeripheralRailingBindPayload decode(ByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    String device = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int count = ByteBufCodecs.VAR_INT.decode(buf);
                    List<PeripheralRailingBinding.Entry> entries = new ArrayList<>(Math.max(0, count));
                    for (int i = 0; i < count; i++) {
                        int signal = ByteBufCodecs.VAR_INT.decode(buf);
                        int button = ByteBufCodecs.VAR_INT.decode(buf);
                        entries.add(new PeripheralRailingBinding.Entry(signal, button));
                    }
                    int forceKind = ByteBufCodecs.VAR_INT.decode(buf);
                    return new PeripheralRailingBindPayload(pos,
                            new PeripheralRailingBinding(device, List.copyOf(entries), forceKind));
                }

                @Override
                public void encode(ByteBuf buf, PeripheralRailingBindPayload payload) {
                    PeripheralRailingBinding b = payload.binding();
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    ByteBufCodecs.STRING_UTF8.encode(buf, b.deviceId() == null ? "" : b.deviceId());
                    ByteBufCodecs.VAR_INT.encode(buf, b.entries().size());
                    for (PeripheralRailingBinding.Entry entry : b.entries()) {
                        ByteBufCodecs.VAR_INT.encode(buf, entry.signal());
                        ByteBufCodecs.VAR_INT.encode(buf, entry.button());
                    }
                    ByteBufCodecs.VAR_INT.encode(buf, b.forceKind());
                }
            };

    @Override
    public Type<PeripheralRailingBindPayload> type() {
        return TYPE;
    }

    public static void sendToServer(BlockPos pos, PeripheralRailingBinding binding) {
        PacketDistributor.sendToServer(new PeripheralRailingBindPayload(pos, binding));
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level().getBlockEntity(pos) instanceof PeripheralRailingBlockEntity railing)) {
            return;
        }
        if (player.distanceToSqr(pos.getCenter()) > 64.0 * 64.0) {
            return;
        }
        railing.setBinding(binding);
        if (railing.owner() == null) {
            railing.setOwner(player.getUUID());
        }
    }
}
