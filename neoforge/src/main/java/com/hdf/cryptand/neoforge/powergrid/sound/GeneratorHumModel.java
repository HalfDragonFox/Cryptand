/**
 * ===== 发电机声音模型（客户端 OpenAL 合成） =====
 *
 * 继承 {@link SoundModel}（统一声音模型基类）：
 *   - 波形：发电机低频电磁声（转子旋转 + 电枢绕组），50Hz 基波 + 谐波
 *   - 音调 ∝ 输出频率（转子转速）、音量 ∝ 电枢电流/负载
 *     （服务器仅发送频率/电流/转速数据 → 客户端合成）
 */
package com.hdf.cryptand.neoforge.powergrid.sound;

import java.util.function.BooleanSupplier;

public class GeneratorHumModel extends SoundModel {

    public GeneratorHumModel(BooleanSupplier alive) {
        super(alive);
    }

    @Override
    protected String waveKey() {
        return "generator_hum";
    }

    @Override
    protected void fillWave(short[] samples) {
        // 发电机音色：基波 + 较强 2 次谐波（电枢齿槽效应）+ 3 次（饱和）
        double[] amps = {1.0, 0.7, 0.3, 0.15};
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) samples.length;
            double v = 0;
            for (int h = 0; h < amps.length; h++) {
                v += amps[h] * Math.sin(2 * Math.PI * (h + 1) * 50.0 * t);
            }
            double norm = Math.max(-1.0, Math.min(1.0, v / 2.0));
            samples[i] = (short) (norm * 0.45 * 32767);
        }
    }
}
