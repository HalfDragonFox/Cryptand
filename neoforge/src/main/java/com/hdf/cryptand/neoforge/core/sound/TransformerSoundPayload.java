/**
 * ===== 变压器声音参数包（S2C） =====
 *
 * 服务端每 10 tick 将一台变压器的【真实】运行参数发给附近玩家（声音合成放客户端）：
 *   - pos  变压器方块位置
 *   - freq 求解频率（Hz）        —— 音调（波形频率 freq/50）
 *   - iP   初级电流（A）          —— 音量（∝ 初级电流）
 *   - iS   次级电流瞬时值（A）    —— 停止判定（次级断开立即静音）
 *
 * 客户端据此用服务端真实参数驱动 SynthHumSound 合成声音
 * （替代直接读服务端静态表 TransformerHeatStore，多人服务器下也可靠）。
 */

package com.hdf.cryptand.neoforge.core.sound;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TransformerSoundPayload(BlockPos pos, double freq, double iP, double iS)
        implements CustomPacketPayload {

    public static final Type<TransformerSoundPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "transformer_sound"));

    public static final StreamCodec<ByteBuf, TransformerSoundPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                BlockPos.STREAM_CODEC.encode(buf, p.pos());
                buf.writeDouble(p.freq());
                buf.writeDouble(p.iP());
                buf.writeDouble(p.iS());
            },
            buf -> new TransformerSoundPayload(
                    BlockPos.STREAM_CODEC.decode(buf),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<TransformerSoundPayload> type() {
        return TYPE;
    }

    /** 客户端：写入本地声音状态表（服务端真实参数驱动合成声音）。
     *  playToClient 注册已保证只在客户端收到；不再检查 context.flow()——
     *  NeoForge 21.1 的 flow() 可能为 null → 直接跳过写入 → 客户端永远无状态
     *  → 变压器无声（实测 [TfSoundSrv] 正常发送但客户端无 [TfSoundCli] 日志）。 */
    public static void handle(TransformerSoundPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            TransformerSoundState.set(payload.pos(), payload.freq(), payload.iP(), payload.iS());
            try {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[TfSoundCli] RCV pos={} freq={} iP={} iS={}",
                        payload.pos(), String.format("%.1f", payload.freq()),
                        String.format("%.2f", payload.iP()), String.format("%.3f", payload.iS()));
            } catch (Throwable ignored) {
            }
        });
    }
}
