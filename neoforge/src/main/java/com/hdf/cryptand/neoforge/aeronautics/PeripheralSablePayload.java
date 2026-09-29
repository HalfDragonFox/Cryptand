/**
 * ===== 外设会话 · 下行包（S2C，2026-09-13）=====
 *
 * 服务端（被动端）在收到上行包后回一份**该方块所在物理结构的运动数据**：
 * 客户端做力反馈时需要"权威"的船体速度/加速度 —— 本地推算在多人下会与真实结构不同步，
 * 所以由服务端采集并下发（{@code PeripheralHelmMotion} 是零 Sable 编译期依赖的双端工具，
 * 服务端可以直接用）。
 *
 * <p>客户端收到后写入 BE，力反馈优先使用这份数据；尚未收到时退回本地采样（单人游戏下两者一致）。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PeripheralSablePayload(BlockPos pos,
                                     float speedMps,
                                     float accelMps2,
                                     float accelX,
                                     float accelY,
                                     float accelZ,
                                     float velocityX,
                                     float velocityY,
                                     float velocityZ,
                                     int tick) implements CustomPacketPayload {

    public static final Type<PeripheralSablePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_sable"));

    /**
     * ⚠ 手写 codec：{@code StreamCodec.composite} 最多只支持 6 组参数（Function6），
     * 本包有 10 个字段，用 composite 会直接编译失败（实测"找不到合适的方法"）。
     */
    public static final StreamCodec<ByteBuf, PeripheralSablePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PeripheralSablePayload decode(ByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    float speedMps = ByteBufCodecs.FLOAT.decode(buf);
                    float accelMps2 = ByteBufCodecs.FLOAT.decode(buf);
                    float ax = ByteBufCodecs.FLOAT.decode(buf);
                    float ay = ByteBufCodecs.FLOAT.decode(buf);
                    float az = ByteBufCodecs.FLOAT.decode(buf);
                    float vx = ByteBufCodecs.FLOAT.decode(buf);
                    float vy = ByteBufCodecs.FLOAT.decode(buf);
                    float vz = ByteBufCodecs.FLOAT.decode(buf);
                    int tick = ByteBufCodecs.VAR_INT.decode(buf);
                    return new PeripheralSablePayload(pos, speedMps, accelMps2,
                            ax, ay, az, vx, vy, vz, tick);
                }

                @Override
                public void encode(ByteBuf buf, PeripheralSablePayload payload) {
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    ByteBufCodecs.FLOAT.encode(buf, payload.speedMps());
                    ByteBufCodecs.FLOAT.encode(buf, payload.accelMps2());
                    ByteBufCodecs.FLOAT.encode(buf, payload.accelX());
                    ByteBufCodecs.FLOAT.encode(buf, payload.accelY());
                    ByteBufCodecs.FLOAT.encode(buf, payload.accelZ());
                    ByteBufCodecs.FLOAT.encode(buf, payload.velocityX());
                    ByteBufCodecs.FLOAT.encode(buf, payload.velocityY());
                    ByteBufCodecs.FLOAT.encode(buf, payload.velocityZ());
                    ByteBufCodecs.VAR_INT.encode(buf, payload.tick());
                }
            };

    @Override
    public Type<PeripheralSablePayload> type() {
        return TYPE;
    }

    /** 客户端：写入目标 BE（不存在的方块忽略）。 */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            if (mc.level.getBlockEntity(pos)
                    instanceof com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmBlockEntity helm) {
                helm.applySableData(speedMps, accelMps2, accelX, accelY, accelZ,
                        velocityX, velocityY, velocityZ, tick);
            } else if (mc.level.getBlockEntity(pos)
                    instanceof com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBlockEntity lever) {
                lever.applySableData(speedMps, accelMps2, tick);
            }
        });
    }
}
