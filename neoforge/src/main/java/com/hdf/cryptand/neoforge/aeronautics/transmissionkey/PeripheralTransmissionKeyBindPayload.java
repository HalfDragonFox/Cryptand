/**
 * ===== 外设模拟传动器绑定更新包（C2S，2026-09-14）=====
 *
 * 界面点【保存并应用】后发送：方块坐标 + 完整绑定（设备 / 轴 / 输入范围 / 曲线 / 强制类型）。
 * ⚠ 曲线是变长点集且 composite 只支持固定 6 组参数 ⇒ 手写 StreamCodec。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmissionkey;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.algorithm.curve.CurvePoint;
import com.hdf.cryptand.algorithm.curve.EditableCurve;
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

public record PeripheralTransmissionKeyBindPayload(BlockPos pos, PeripheralTransmissionKeyBinding binding)
        implements CustomPacketPayload {

    public static final Type<PeripheralTransmissionKeyBindPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_transmission_key_bind"));

    public static final StreamCodec<ByteBuf, PeripheralTransmissionKeyBindPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PeripheralTransmissionKeyBindPayload decode(ByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    String device = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int axis = ByteBufCodecs.VAR_INT.decode(buf);
                    boolean invert = ByteBufCodecs.BOOL.decode(buf);
                    double inMin = ByteBufCodecs.DOUBLE.decode(buf);
                    double inMax = ByteBufCodecs.DOUBLE.decode(buf);
                    int forceKind = ByteBufCodecs.VAR_INT.decode(buf);
                    int count = ByteBufCodecs.VAR_INT.decode(buf);
                    List<CurvePoint> pts = new ArrayList<>(Math.max(0, count));
                    for (int i = 0; i < count; i++) {
                        double x = ByteBufCodecs.DOUBLE.decode(buf);
                        double y = ByteBufCodecs.DOUBLE.decode(buf);
                        pts.add(new CurvePoint(x, y));
                    }
                    // 🆕 每点的按键（long 掩码）与时间（ms）
                    int keyCount = ByteBufCodecs.VAR_INT.decode(buf);
                    List<PeripheralTransmissionKeyBinding.PointKey> keys =
                            new ArrayList<>(Math.max(0, keyCount));
                    for (int i = 0; i < keyCount; i++) {
                        long mask = ByteBufCodecs.VAR_LONG.decode(buf);
                        int ms = ByteBufCodecs.VAR_INT.decode(buf);
                        keys.add(new PeripheralTransmissionKeyBinding.PointKey(mask, ms));
                    }
                    return new PeripheralTransmissionKeyBindPayload(pos, new PeripheralTransmissionKeyBinding(
                            device, axis, invert, inMin, inMax, new EditableCurve(pts), forceKind, keys));
                }

                @Override
                public void encode(ByteBuf buf, PeripheralTransmissionKeyBindPayload payload) {
                    PeripheralTransmissionKeyBinding b = payload.binding();
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    ByteBufCodecs.STRING_UTF8.encode(buf, b.deviceId() == null ? "" : b.deviceId());
                    ByteBufCodecs.VAR_INT.encode(buf, b.axisIndex());
                    ByteBufCodecs.BOOL.encode(buf, b.invertAxis());
                    ByteBufCodecs.DOUBLE.encode(buf, b.inMin());
                    ByteBufCodecs.DOUBLE.encode(buf, b.inMax());
                    ByteBufCodecs.VAR_INT.encode(buf, b.forceKind());
                    List<CurvePoint> pts = b.curve().points();
                    ByteBufCodecs.VAR_INT.encode(buf, pts.size());
                    for (CurvePoint p : pts) {
                        ByteBufCodecs.DOUBLE.encode(buf, p.x());
                        ByteBufCodecs.DOUBLE.encode(buf, p.y());
                    }
                    // 🆕 每点的按键（long 掩码）与时间（ms）
                    List<PeripheralTransmissionKeyBinding.PointKey> keys = b.pointKeys();
                    ByteBufCodecs.VAR_INT.encode(buf, keys.size());
                    for (PeripheralTransmissionKeyBinding.PointKey k : keys) {
                        ByteBufCodecs.VAR_LONG.encode(buf, k.keyMask());
                        ByteBufCodecs.VAR_INT.encode(buf, k.timeMs());
                    }
                }
            };

    @Override
    public Type<PeripheralTransmissionKeyBindPayload> type() {
        return TYPE;
    }

    public static void sendToServer(BlockPos pos, PeripheralTransmissionKeyBinding binding) {
        PacketDistributor.sendToServer(new PeripheralTransmissionKeyBindPayload(pos, binding));
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level().getBlockEntity(pos) instanceof PeripheralTransmissionKeyBlockEntity be)) {
            return;
        }
        if (player.distanceToSqr(pos.getCenter()) > 64.0 * 64.0) {
            return;
        }
        be.setBinding(binding);
        if (be.owner() == null) {
            be.setOwner(player.getUUID());
        }
    }
}
