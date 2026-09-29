/**
 * ===== 变压器嗡鸣（MC 原版 SoundManager 播放，渲染线程安全可靠） =====
 *
 * 每 tick 读服务端同步状态（TransformerSoundState）更新音量（∝初级电流）
 * 与音调（∝频率）；条件不满足（悬空/直流/方块移除）→ stop() 自动清理。
 *
 * 替代：
 *   - 裸 LWJGL OpenAL（SynthHumSound）：渲染线程 OpenAL context 绑定不可靠 → 无声
 *   - SoundScapes.play(HUM)：单台设备时音量算法过低（clamp(soundCount/150,0.025)
 *     × 距离 × volume → 单台 ≈0.015）→ 近无声
 * 本实例走 MC SoundManager（AbstractTickableSoundInstance），音量直接可控。
 */
package com.hdf.cryptand.neoforge.core.sound;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.patryk3211.powergrid.collections.ModdedSoundEvents;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TransformerHumSoundInstance extends AbstractTickableSoundInstance {

    /** 方块位置 → 是否已在 SoundManager 播放（tickAudio 用它决定是否创建新实例） */
    public static final Map<BlockPos, Boolean> STARTED = new ConcurrentHashMap<>();

    private final BlockPos pos;

    public TransformerHumSoundInstance(BlockPos pos) {
        super(ModdedSoundEvents.TRANSFORMER_HUM.getMainEvent(), SoundSource.BLOCKS,
                SoundInstance.createUnseededRandom());
        this.pos = pos.immutable();
        var c = pos.getCenter();
        this.x = c.x;
        this.y = c.y;
        this.z = c.z;
        this.attenuation = Attenuation.LINEAR;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        // 诊断：确认 sound event 有效（无效事件 → SoundManager 静默失败 → 无声）
        try {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[TfHum] create pos={} event={}", pos,
                    ModdedSoundEvents.TRANSFORMER_HUM.getMainEvent());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        try {
            // 方块被移除/卸载 → 停止并清理标志
            var lv = Minecraft.getInstance().level;
            if (lv == null || lv.getBlockEntity(pos) == null) {
                STARTED.put(pos, false);
                stop();
                return;
            }
            double freq = TransformerSoundState.getFreq(pos);
            double iP = TransformerSoundState.getCurrent(pos);
            double iS = TransformerSoundState.getSecCurrentRaw(pos);
            // 只要有励磁电流（iP>0.02）就发声（音量∝电流，空载微弱、带载响亮）
            if (freq <= 0 || iP <= 0.02) {
                STARTED.put(pos, false);
                stop();
                return;
            }
            // 音量 ∝ 初级电流（原版 lastCurrent/20 语义）；音调 ∝ 频率（50Hz → 1.0）
            this.volume = (float) Math.min(1.0, iP / 20.0);
            this.pitch = (float) Mth.clamp(freq / 50.0, 0.5, 4.0);
            // 诊断（节流）：确认实例仍在播放且音量正常
            try {
                long gt = Minecraft.getInstance().level == null
                        ? -1 : Minecraft.getInstance().level.getGameTime();
                if ((gt & 0x7F) == 0) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[TfHum] tick pos={} vol={} pitch={} playing={}",
                            pos, String.format("%.2f", volume),
                            String.format("%.2f", pitch), isStopped());
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }
}
