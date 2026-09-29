/**
 * ===== 电机声音参数包（S2C） =====
 *
 * 服务端每 5 tick 将一台换向器/发电机的【真实】运行参数发给附近玩家：
 *   - armFreq   电枢网络频率（Hz）    —— 音调（波形频率）
 *   - excFreq   励磁频率（Hz）
 *   - current   电枢电流              —— 音量（电磁声 ∝ 电流）
 *   - power     功率
 *   - voltage   电压
 *   - rotorFreq 转子转速频率（Hz）    —— 转差计算
 *   - acExcited 交流励磁
 *   - mismatched 励磁/电枢不匹配
 *
 * 客户端据此用【服务端真实参数】发声（替代客户端 BFS 近似）。
 */

package com.hdf.cryptand.neoforge.core.sound;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MotorSoundPayload(BlockPos pos, double armFreq, double excFreq, float current,
                                float power, double voltage, double rotorFreq,
                                boolean acExcited, boolean mismatched)
        implements CustomPacketPayload {

    public static final Type<MotorSoundPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "motor_sound"));

    public static final StreamCodec<ByteBuf, MotorSoundPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeDouble(p.armFreq());
                buf.writeDouble(p.excFreq());
                buf.writeFloat(p.current());
                buf.writeFloat(p.power());
                buf.writeDouble(p.voltage());
                buf.writeDouble(p.rotorFreq());
                buf.writeBoolean(p.acExcited());
                buf.writeBoolean(p.mismatched());
            },
            buf -> new MotorSoundPayload(
                    BlockPos.STREAM_CODEC.decode(buf),
                    buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
                    buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readBoolean()));

    @Override
    public Type<MotorSoundPayload> type() {
        return TYPE;
    }

    /** 客户端：写入本地声音状态表 */
    public static void handle(MotorSoundPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isClientbound()) {
                MotorSoundState.set(payload.pos(), new MotorSoundState.Entry(
                        payload.armFreq(), payload.excFreq(), payload.current(), payload.power(),
                        payload.voltage(), payload.rotorFreq(), payload.acExcited(),
                        payload.mismatched()));
            }
        });
    }
}
