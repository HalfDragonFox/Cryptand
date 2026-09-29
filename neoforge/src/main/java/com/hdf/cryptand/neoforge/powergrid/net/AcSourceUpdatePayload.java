/**
 * ===== 交流创造源配置更新包（C2S） =====
 *
 * 客户端菜单确认后发送：目标方块坐标 + 频率(Hz) + 幅值。
 * 服务端更新对应 BE。
 */

package com.hdf.cryptand.neoforge.powergrid.net;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.block.AcCreativeSourceBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AcSourceUpdatePayload(BlockPos pos, float frequencyHz, float amplitude, float phaseDegrees)
        implements CustomPacketPayload {

    public static final Type<AcSourceUpdatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "ac_source_update"));

    public static final StreamCodec<ByteBuf, AcSourceUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AcSourceUpdatePayload::pos,
            ByteBufCodecs.FLOAT, AcSourceUpdatePayload::frequencyHz,
            ByteBufCodecs.FLOAT, AcSourceUpdatePayload::amplitude,
            ByteBufCodecs.FLOAT, AcSourceUpdatePayload::phaseDegrees,
            AcSourceUpdatePayload::new);

    @Override
    public Type<AcSourceUpdatePayload> type() {
        return TYPE;
    }

    public static void handle(AcSourceUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow().isServerbound() && context.player() instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.level().getBlockEntity(payload.pos()) instanceof AcCreativeSourceBlockEntity be) {
                    be.setFrequencyHz(payload.frequencyHz());
                    be.setAmplitude(payload.amplitude());
                    be.setPhaseDegrees(payload.phaseDegrees());
                    // 2026-08-20 修复"设置值后 UI 不刷新，须重进游戏"：setter 只
                    // setUnsaved()（标记 NBT 保存），不触发【客户端同步】→ 客户端
                    // BE 恒旧值 → 重开 UI 读客户端 BE 显示旧值，须重进才从 NBT 读
                    // 新值。显式 sendBlockUpdated 让客户端重载区块/读取同步数据。
                    be.setChanged();
                    net.minecraft.world.level.Level lv = serverPlayer.level();
                    net.minecraft.world.level.block.state.BlockState st =
                            lv.getBlockState(payload.pos());
                    lv.sendBlockUpdated(payload.pos(), st, st, 3);
                    // ⚠ 2026-08-25 修复"改 AC 源参数电机数值不更新"：setter 只存
                    // 字段，引擎感知参数变化靠 DeviceParamCache.sync（每 tick 读 BE）
                    // → graphComponentFreq（频率）/ refreshParams（振幅）→ 重解。
                    // 但【频率变化】需网络级失效才能重建（AcVoltageSource.frequency
                    // 是 final，重建才生效）；振幅变化也需失效保证立即重解（不依赖
                    // DeviceParamCache 同步时机）。显式全局失效 → 下一 round 所有
                    // 网络重建/重解，电机数值立即更新。
                    try {
                        com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager.get().markTopologyChanged();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    public static void sendToServer(BlockPos pos, float frequencyHz, float amplitude, float phaseDegrees) {
        PacketDistributor.sendToServer(new AcSourceUpdatePayload(pos, frequencyHz, amplitude, phaseDegrees));
    }
}
