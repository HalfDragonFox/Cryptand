/**
 * ===== 外设模拟传动器绑定更新包（C2S，2026-09-14）=====
 *
 * 界面点【保存并应用】后发送：方块坐标 + 完整绑定（设备 / 轴 / 输入范围 / 曲线 / 强制类型）。
 * ⚠ 曲线是变长点集且 composite 只支持固定 6 组参数 ⇒ 手写 StreamCodec。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

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

public record PeripheralTransmissionBindPayload(BlockPos pos, PeripheralTransmissionBinding binding)
        implements CustomPacketPayload {

    public static final Type<PeripheralTransmissionBindPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_transmission_bind"));

    public static final StreamCodec<ByteBuf, PeripheralTransmissionBindPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PeripheralTransmissionBindPayload decode(ByteBuf buf) {
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
                    return new PeripheralTransmissionBindPayload(pos, new PeripheralTransmissionBinding(
                            device, axis, invert, inMin, inMax, new EditableCurve(pts), forceKind));
                }

                @Override
                public void encode(ByteBuf buf, PeripheralTransmissionBindPayload payload) {
                    PeripheralTransmissionBinding b = payload.binding();
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
                }
            };

    @Override
    public Type<PeripheralTransmissionBindPayload> type() {
        return TYPE;
    }

    public static void sendToServer(BlockPos pos, PeripheralTransmissionBinding binding) {
        PacketDistributor.sendToServer(new PeripheralTransmissionBindPayload(pos, binding));
    }

    public void handle(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level().getBlockEntity(pos) instanceof PeripheralTransmissionBlockEntity be)) {
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
