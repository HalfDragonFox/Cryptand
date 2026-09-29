package com.hdf.cryptand.neoforge.powergrid.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.function.BooleanSupplier;

/**
 * 电磁嗡鸣循环声音实例（TRANSFORMER_HUM）。
 *
 * 封装 AbstractSoundInstance 的 protected 字段访问：
 * 调用方每帧调用 {@link #cryptand$set} 更新位置 / 音量 / 音调，
 * 即可让声音随真实物理状态（电频率 / 转差）动态变化。
 *
 * 关键：本实例在自身 {@link #tick()} 里通过 alive 回调自检。
 * 即使调用方（RotorBehaviour.tickAudio）因方块移除 / 区块卸载而不再执行，
 * 声音实例也会自行停止，杜绝"断电了还在嗡嗡响"。
 *
 * 注意：本类引用 net.minecraft.client 类，只应在客户端代码中使用
 * （服务端不会加载引用它的类，JVM 懒加载保证安全）。
 */
public class CryptandHumSound extends AbstractTickableSoundInstance {

    /** 行为是否仍然"活着"（方块存在、仍是控制器、世界未卸载）；false → 自停 */
    private final BooleanSupplier alive;

    public CryptandHumSound(SoundEvent event, SoundSource source, RandomSource random,
                            BooleanSupplier alive) {
        super(event, source, random);
        this.alive = alive;
        this.looping = true;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.relative = false;
    }

    /** 更新位置（方块中心）与音量/音调 */
    public void cryptand$set(double x, double y, double z, float volume, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.volume = volume;
        this.pitch = pitch;
    }

    /** 停止播放（封装 protected stop） */
    public void cryptand$stop() {
        this.stop();
    }

    @Override
    public void tick() {
        // 自检：行为失效（方块移除 / 不再控制器 / 世界卸载）→ 停止
        if (!alive.getAsBoolean()) {
            this.stop();
        }
    }
}
