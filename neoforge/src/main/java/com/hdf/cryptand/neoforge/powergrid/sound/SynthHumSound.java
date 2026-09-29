/**
 * ===== 变压器电磁嗡鸣声音模型（客户端 OpenAL 合成） =====
 *
 * 继承 {@link SoundModel}（统一声音模型基类）：
 *   - 波形：真实变压器嗡鸣 = 磁致伸缩（2×电频率）+ 铁芯/绕组谐波，
 *     合成 50Hz 基波 + 2~5 次谐波的正弦叠加（16-bit PCM, 44100Hz, mono）
 *   - 播放时按电频率调 AL_PITCH、按电流调 AL_GAIN（服务器仅发送频率/电流数据）
 *
 * 兼容保留（TransformerBlockEntityMixin 使用本类，API 不变）。
 */
package com.hdf.cryptand.neoforge.powergrid.sound;

import java.util.function.BooleanSupplier;

public class SynthHumSound extends SoundModel {

    public SynthHumSound(BooleanSupplier alive) {
        super(alive);
    }

    @Override
    protected String waveKey() {
        return "transformer_hum";
    }

    @Override
    protected void fillWave(short[] samples) {
        // 基波 50Hz + 2~5 次谐波（磁致伸缩/铁芯谐波）
        double[] amps = {1.0, 0.55, 0.35, 0.22, 0.15};
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) samples.length;
            double v = 0;
            for (int h = 0; h < amps.length; h++) {
                v += amps[h] * Math.sin(2 * Math.PI * (h + 1) * 50.0 * t);
            }
            double norm = Math.max(-1.0, Math.min(1.0, v / 2.2));
            samples[i] = (short) (norm * 0.45 * 32767);
        }
    }
}
