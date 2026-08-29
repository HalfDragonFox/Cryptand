/**
 * ===== 加热器：完全接管（消灭原版热模型推进，2026-08-18） =====
 *
 * PowerGrid 原版 HeaterBlockEntity 发热链：
 *   ElectricBlockEntity.tick() → electricalTick() → applyPower(wire)
 *     →（基类）power 字段累加 + thermal.applyWirePower(power)
 *     → ThermalBehaviour.temp += (power/20)/thermalMass（原版 I²R 功率注入温度）
 *
 * 原版热模型是"时域功率 → 温度积分"的近似，且与 Cryptand 自管引擎的相量
 * 功率/温度双轨并行——原版注入会使温度读数/烧毁判定混乱（双热源）。
 *
 * 本 mixin 对 electricalTick() 做 HEAD cancel（像 TransformerBlockEntityMixin
 * 对变压器那样）：原版 applyPower 完全禁用 → 原版热模型【无功率注入】，
 * 温度不再被原版推进。加热器温度完全由 Cryptand 引擎驱动：
 *   HeaterAssembler → MotorModel（R-L 绕组）+ DeviceThermalStore.
 *   thermalForHighTemp(pos)，advanceState 按相量 I²R 推进。
 *
 * 保留：super.tick()（SmartBlockEntity 行为系统）与 updateState（COLD/
 * SMOKING/BLASTING 方块状态显示）。原版温度恒 0 后状态停在 COLD；
 * 如需按 Cryptand 温度驱动状态显示，后续单独接入（当前只消灭发热推进）。
 *
 * 2026-08-20 声音：加热器工作（网络有交流频率）时播放小蜂鸣
 * （SoundScapes HUM，音量 0.25 小）；DC 无频率不响。原版 tickAudio 音量
 * ∝ power（被禁 → 恒 0）→ 本 tick 注入按 Cryptand 频率驱动。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.utility.sound.SoundScapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.patryk3211.powergrid.electricity.heater.HeaterBlockEntity", remap = false)
public abstract class HeaterBlockEntityMixin {

    /**
     * electricalTick() → HEAD 取消：原版 applyPower(wire)（功率注入原版
     * ThermalBehaviour 温度）完全禁用——消灭原版热模型推进，杜绝双热源。
     */
    @Inject(method = "electricalTick", at = @At("HEAD"), cancellable = true)
    private void cryptand$noVanillaElectricalTick(CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * tick() TAIL（客户端）：加热器工作（网络有交流频率）→ 播放小蜂鸣。
     * 频率检测优先 Cryptand 端子频率（TerminalRegistry，求解回填），兜底
     * 客户端 BFS（getBlockNetworkFrequencyHz）。音量 0.25（小的声音，用户
     * 要求），音调随频率（50Hz → pitch 1.0）。玩家太远/无频率 → 不响
     * （SoundScapes 每 tick play 驱动，无 play 自动衰减停止）。
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$heaterHum(CallbackInfo ci) {
        try {
            BlockEntity be = (BlockEntity) (Object) this;
            net.minecraft.world.level.Level level = be.getLevel();
            if (level == null || !level.isClientSide) return;
            // 玩家太远就停（听不到，省开销）
            net.minecraft.client.player.LocalPlayer player =
                    net.minecraft.client.Minecraft.getInstance().player;
            if (player == null || player.distanceToSqr(be.getBlockPos().getCenter())
                    > 48.0 * 48.0) {
                return;
            }
            // 频率检测（有交流频率 → 蜂鸣；DC 无频率不响）
            double freq = 0;
            try {
                com.hdf.cryptand.circuitsimulation.model.TerminalElement t0 =
                        com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry
                                .get(be.getBlockPos(), 0);
                if (t0 != null && t0.valid() && t0.frequency() > 0) {
                    freq = t0.frequency();
                }
            } catch (Throwable ignored) {
            }
            if (freq <= 0) {
                freq = com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug
                        .getBlockNetworkFrequencyHz(level, be.getBlockPos());
            }
            if (freq <= 0) return;
            // 小的蜂鸣：音量 0.25，音调随频率（50Hz → pitch 1.0）
            float pitch = (float) Math.max(0.5, freq / 50.0);
            SoundScapes.play(SoundScapes.AmbienceGroup.HUM, be.getBlockPos(),
                    0.25f, pitch);
        } catch (Throwable ignored) {
        }
    }
}
