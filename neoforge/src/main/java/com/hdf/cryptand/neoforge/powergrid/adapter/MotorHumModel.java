/**
 * ===== 电机电磁嗡鸣声音模型（客户端 OpenAL 合成） =====
 *
 * 继承 {@link SoundModel}（统一声音模型基类）：
 *   - 波形：电磁嗡鸣（绕组/铁芯振动），50Hz 基波 + 少量谐波
 *   - 音调 ∝ 电频率（AL_PITCH = f/50）、音量 ∝ 电流/转差
 *     （服务器仅发送频率/电流/转速数据 → 客户端合成）
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import java.util.function.BooleanSupplier;

public class MotorHumModel extends SoundModel {

    public MotorHumModel(BooleanSupplier alive) {
        super(alive);
    }

    @Override
    protected String waveKey() {
        return "motor_hum";
    }

    @Override
    protected void fillWave(short[] samples) {
        // 基波 50Hz + 2/3 次谐波（电磁嗡鸣音色，比变压器干净）
        double[] amps = {1.0, 0.4, 0.2};
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) samples.length;
            double v = 0;
            for (int h = 0; h < amps.length; h++) {
                v += amps[h] * Math.sin(2 * Math.PI * (h + 1) * 50.0 * t);
            }
            double norm = Math.max(-1.0, Math.min(1.0, v / 1.6));
            samples[i] = (short) (norm * 0.4 * 32767);
        }
    }
}
