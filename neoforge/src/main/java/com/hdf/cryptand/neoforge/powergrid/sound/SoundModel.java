package com.hdf.cryptand.neoforge.powergrid.sound;

import net.minecraft.client.Minecraft;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 声音模型基类（2026-08-12 用户要求）。
 * <p>
 * 变压器 / 电机 / 发电机等设备【继承本类】：客户端用 LWJGL → OpenAL 直接
 * 合成指定波形输出（16-bit PCM，44100Hz，mono，循环播放），服务器【仅发送
 * 相关数据】（SoundPayload → SoundState 缓存），客户端据此合成声音
 * （音量 ∝ 电流、音调 ∝ 频率/转速）。
 * <p>
 * 封装通用 OpenAL 管理：
 *   - 合成 PCM buffer（子类 {@link #fillWave} 生成波形，按 {@link #waveKey}
 *     缓存共享——同设备共用同一 buffer，改 AL_PITCH 调音调）
 *   - 3D 线性距离衰减（近处响、远处弱，48 格外基本无声）
 *   - {@link #update}/{@link #setPosition}/{@link #stop}/{@link #close}
 *   - 静态 {@link #tickAll()}：客户端每 tick 自检（来源失效关闭 / 游戏暂停静音）
 * <p>
 * 纯客户端（引用 Minecraft/SoundManager，JVM 懒加载保证服务端不触发）。
 * OpenAL context 由 MC SoundEngine 初始化；本类只新建独立 source/buffer，
 * 不与 MC channel 冲突。
 */
public abstract class SoundModel {

    /** 全部活跃实例（客户端每 tick 自检：失效关闭 / 暂停静音） */
    private static final Set<SoundModel> ACTIVE = ConcurrentHashMap.newKeySet();

    /** 波形缓存：waveKey → 合成 PCM buffer id（同设备共享，改 PITCH 调音调） */
    private static final Map<String, Integer> PCM_BUFFERS = new ConcurrentHashMap<>();

    /** 来源是否仍"活着"（方块存在）；false → 自动关闭（爆炸/卸载不再响） */
    protected final BooleanSupplier alive;

    /** OpenAL 不可用（渲染线程无 LWJGL capabilities / 初始化失败）→ true */
    public volatile boolean failed;

    private int source = -1;
    private boolean playing;

    protected SoundModel(BooleanSupplier alive) {
        this.alive = alive != null ? alive : () -> true;
        try {
            if (!AL.getCapabilities().OpenAL10) {
                failed = true;
                return;
            }
        } catch (Throwable t) {
            failed = true;
            return;
        }
        int buf = pcmBuffer();
        if (buf <= 0) {
            failed = true;
            return;
        }
        try {
            source = AL10.alGenSources();
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);
            AL10.alSourcei(source, AL10.AL_BUFFER, buf);
            // 3D 线性距离衰减（近处响、远处弱，48 格外基本无声）
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0f);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 48.0f);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
        } catch (Throwable t) {
            failed = true;
            try {
                AL10.alDeleteSources(source);
            } catch (Throwable ignored) {
            }
            source = -1;
            return;
        }
        ACTIVE.add(this);
    }

    // ===== 子类：波形定义 =====

    /** 波形缓存 key（同 key 设备共享同一合成 PCM buffer） */
    protected abstract String waveKey();

    /** 子类合成 1 秒 44100Hz 16-bit mono 波形（归一化 -1.0 ~ 1.0） */
    protected abstract void fillWave(short[] samples);

    /** 获取/创建合成 PCM buffer（按 waveKey 缓存；首次由本实例合成） */
    private int pcmBuffer() {
        String key = waveKey();
        Integer b = PCM_BUFFERS.get(key);
        if (b != null) return b;
        synchronized (PCM_BUFFERS) {
            b = PCM_BUFFERS.get(key);
            if (b != null) return b;
            try {
                int rate = 44100;
                short[] samples = new short[rate];
                fillWave(samples);
                ByteBuffer bb = ByteBuffer.allocateDirect(samples.length * 2)
                        .order(ByteOrder.nativeOrder());
                for (short s : samples) bb.putShort(s);
                bb.flip();
                int buf = AL10.alGenBuffers();
                AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, bb, rate);
                PCM_BUFFERS.put(key, buf);
                return buf;
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    // ===== 播放控制 =====

    /**
     * 输入电频率 → 音调（统一规则，2026-08-12 用户要求：声音频率与输入电的
     * 频率有关）。50Hz → pitch 1.0（基准），线性跟随电频率，钳制 0.5~4.0。
     * 变压器/电机/发电机统一用此方法，保证合成声音音调严格跟随输入电频率。
     */
    public static float pitchFromFrequency(double freqHz) {
        return (float) net.minecraft.util.Mth.clamp(freqHz / 50.0, 0.5, 4.0);
    }

    /**
     * 功率 → 音量（统一规则，2026-08-12 用户要求：声音大小与功率有关）。
     * 响度 ∝ √P（能量 → 声压），400W → ~1.0。电机/发电机用。
     */
    public static float volumeFromPower(double powerW) {
        return (float) net.minecraft.util.Mth.clamp(
                Math.sqrt(Math.max(0.0, Math.abs(powerW))) / 20.0, 0.0, 1.0);
    }

    /**
     * 整体电流 → 音量（统一规则，2026-08-12 用户要求：声音大小与整体电流有关）。
     * 响度 ∝ √I，20A → ~1.0。变压器用（初级+次级总电流）。
     */
    public static float volumeFromCurrent(double totalCurrentA) {
        return (float) net.minecraft.util.Mth.clamp(
                Math.sqrt(Math.max(0.0, Math.abs(totalCurrentA))) / 4.5, 0.0, 1.0);
    }

    /** 每 tick：更新音量与音调（音量∝电流、音调∝频率），并开始播放 */
    public void update(float volume, float pitch) {
        if (failed || source < 0) return;
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(volume, 1f)));
        AL10.alSourcef(source, AL10.AL_PITCH, Math.max(0.25f, Math.min(pitch, 4f)));
        if (!playing) {
            AL10.alSourcePlay(source);
            playing = true;
        }
    }

    /** 设置 3D 位置（方块中心） */
    public void setPosition(double x, double y, double z) {
        if (failed || source < 0) return;
        AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
    }

    /** 暂停/停止播放（不释放资源） */
    public void stop() {
        if (playing && source >= 0) {
            AL10.alSourceStop(source);
            playing = false;
        }
    }

    /** 释放 OpenAL source（并从活跃集合移除） */
    public void close() {
        try {
            stop();
            if (source >= 0) {
                AL10.alDeleteSources(source);
                source = -1;
            }
        } catch (Throwable ignored) {
        }
        ACTIVE.remove(this);
    }

    /** 是否正在播放 */
    public boolean isPlaying() {
        return playing;
    }

    // ===== 静态管理 =====

    /**
     * 客户端每 tick 调用（ClientSoundTicker）：
     *   - 来源失效（方块爆炸/卸载/世界关闭）→ 关闭并释放 OpenAL source
     *   - 游戏暂停（ESC 菜单）→ 停止播放（恢复后 update 自动续播）
     */
    public static void tickAll() {
        if (ACTIVE.isEmpty()) return;
        boolean paused;
        try {
            paused = Minecraft.getInstance().isPaused();
        } catch (Throwable t) {
            paused = false;
        }
        Iterator<SoundModel> it = ACTIVE.iterator();
        while (it.hasNext()) {
            SoundModel s = it.next();
            try {
                if (!s.alive.getAsBoolean()) {
                    s.close();
                    it.remove();
                } else if (paused && s.playing) {
                    s.stop();
                }
            } catch (Throwable ignored) {
                s.close();
                it.remove();
            }
        }
    }

    /** 活跃实例数（诊断） */
    public static int activeCount() {
        return ACTIVE.size();
    }
}
