/**
 * ===== 示波器波形数据存储（客户端，packet 驱动） =====
 *
 * 客户端每 10 tick 发送 OscilloscopeRequestPayload（C2S）→ 服务端求解后
 * 回发 OscilloscopeResponsePayload（S2C）→ 本类更新本地波形数据（每频率
 * 分量）。HUD 小屏幕与放大 Screen 都从这里读数据渲染。
 * 仅收到响应包才更新（避免显示过期值）。
 */

package com.hdf.cryptand.neoforge.core.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OscilloscopeStore {

    /** 单频率分量（峰值幅值 + 相位） */
    public static final class Tone {
        public final double freq;   // Hz
        public final double amp;    // 峰值 V
        public final double phase;  // 度
        public Tone(double freq, double amp, double phase) {
            this.freq = freq;
            this.amp = amp;
            this.phase = phase;
        }

        /** 有效值 RMS = A/√2（AC） */
        public double rms() {
            return amp / Math.sqrt(2.0);
        }

        /** 瞬时值贡献 */
        public double valueAt(double t) {
            return amp * Math.sin(2 * Math.PI * freq * t + Math.toRadians(phase));
        }
    }

    private static volatile String key = "";
    private static volatile List<Tone> tones = Collections.emptyList();
    private static volatile long lastUpdateTick;
    private static volatile long lastUpdateMs;
    /** 最近一次有效响应的主导频率（Hz，服务端权威；客户端复用避免每 tick BFS） */
    private static volatile double lastFreq;

    // ===== 按时间记录：环形采样缓冲（2026-08-13 用户要求） =====
    // 客户端每 tick（20Hz）用稳态相量合成瞬时值 v(t)=ΣA·sin(2πft+φ) 采样，
    // 存入环形缓冲（容量 60s）。显示时取最近【检测时间】窗口内的采样点，
    // 随时间滚动（真实示波器效果）。
    private static final int BUFFER_SIZE = 20 * 60; // 60 秒 @20Hz
    private static final double[] BUFFER = new double[BUFFER_SIZE];
    private static int bufferHead = 0;     // 下一个写入位置
    private static int bufferCount = 0;    // 已采样点数
    private static long lastSampleTick = -1;
    /** 检测时间（秒）：显示/记录的采样窗口，0.1~5.0 */
    private static double detectSeconds = 1.0;
    /** 暂停采样（2026-08-18 界面按钮：冻结波形便于分析） */
    private static volatile boolean paused;

    // 窗口采样缓存（波形每 tick 才变化，同一帧内 HUD/图表复用同一数组，避免每帧分配）
    private static long cachedWinTick = Long.MIN_VALUE;
    private static double cachedWinDetect = -1;
    private static double[][] cachedWin;

    private OscilloscopeStore() {}

    /** 客户端主 tick 调用：有数据且手持示波器时，每 tick 采样一次瞬时值 */
    public static void tick() {
        try {
            if (paused) return; // 暂停：冻结波形（不写入采样缓冲）
            if (tones.isEmpty()) return;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            if (!com.hdf.cryptand.neoforge.core.client.OscilloscopeHud.isHoldingOscilloscope()) return;
            long gt = mc.level.getGameTime();
            if (gt == lastSampleTick) return;
            lastSampleTick = gt;
            double t = gt / 20.0; // 仿真时间（秒）
            double v = valueAt(t);
            BUFFER[bufferHead] = v;
            bufferHead = (bufferHead + 1) % BUFFER_SIZE;
            if (bufferCount < BUFFER_SIZE) bufferCount++;
        } catch (Throwable ignored) {
        }
    }

    /** 检测时间（秒）：设置后影响显示窗口。支持 us/ms/s 级（1e-6 ~ 10s） */
    public static double detectSeconds() { return detectSeconds; }

    public static void setDetectSeconds(double s) {
        detectSeconds = Math.max(1e-6, Math.min(10.0, s));
    }

    /** 暂停采样（冻结波形） */
    public static boolean paused() { return paused; }

    public static void setPaused(boolean p) { paused = p; }

    /** 最近【检测时间】窗口内的采样序列（时间 t + 电压 v，按时间升序）。
     *  按 tick 缓存：波形数据每 tick 才变化，HUD 与图表同帧复用同一数组。 */
    public static double[][] windowSamples() {
        if (lastSampleTick == cachedWinTick && detectSeconds == cachedWinDetect && cachedWin != null) {
            return cachedWin;
        }
        double[][] win = computeWindowSamples();
        cachedWinTick = lastSampleTick;
        cachedWinDetect = detectSeconds;
        cachedWin = win;
        return win;
    }

    private static double[][] computeWindowSamples() {
        int n = Math.max(2, (int) Math.round(detectSeconds * 20.0));
        n = Math.min(n, bufferCount);
        if (n < 2) return new double[][]{new double[0], new double[0]};
        double[] ts = new double[n];
        double[] vs = new double[n];
        for (int i = 0; i < n; i++) {
            int idx = (bufferHead - n + i + BUFFER_SIZE) % BUFFER_SIZE;
            vs[i] = BUFFER[idx];
        }
        double t0 = lastSampleTick >= 0 ? lastSampleTick / 20.0 - (n - 1) / 20.0 : 0;
        for (int i = 0; i < n; i++) ts[i] = t0 + i / 20.0;
        return new double[][]{ts, vs};
    }

    /** 更新数据（仅响应包到达时调用） */
    public static void put(String key, boolean valid, float[] freqs, float[] amps, float[] phases) {
        if (key != null) OscilloscopeStore.key = key;
        if (!valid || freqs == null || amps == null || phases == null) {
            if (tones.isEmpty()) return; // 已有数据保持（网络短暂无解）
            return;
        }
        int n = Math.min(freqs.length, Math.min(amps.length, phases.length));
        List<Tone> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (freqs[i] <= 0) continue;
            list.add(new Tone(freqs[i], Math.max(amps[i], 0), phases[i]));
        }
        list.sort((a, b) -> Double.compare(a.freq, b.freq));
        tones = Collections.unmodifiableList(list);
        double f = 0;
        for (Tone t : list) { if (t.freq > 0) { f = t.freq; break; } }
        lastFreq = f;
        lastUpdateTick = net.minecraft.client.Minecraft.getInstance().level == null
                ? 0 : net.minecraft.client.Minecraft.getInstance().level.getGameTime();
        lastUpdateMs = System.currentTimeMillis();
    }

    public static String key() { return key; }

    public static List<Tone> tones() { return tones; }

    public static boolean hasData() { return !tones.isEmpty(); }

    /** 主导频率（最低非零频率；无数据 → 0） */
    public static double dominantFrequency() {
        for (Tone t : tones) if (t.freq > 0) return t.freq;
        return 0;
    }

    /** 合成瞬时值 v(t) = Σ A·sin(2πft + φ) */
    public static double valueAt(double t) {
        double v = 0;
        for (Tone t2 : tones) v += t2.valueAt(t);
        return v;
    }

    /** 合成 RMS = √(Σ A²/2) */
    public static double compositeRms() {
        double s = 0;
        for (Tone t : tones) s += t.amp * t.amp / 2.0;
        return Math.sqrt(s);
    }

    public static long lastUpdateTick() { return lastUpdateTick; }
    public static long lastUpdateMs() { return lastUpdateMs; }

    /** 最近一次采样写入 tick（图表据此按 tick 重绘，20Hz 而非每帧） */
    public static long lastSampleTick() { return lastSampleTick; }

    /** 最近一次有效响应的主导频率（Hz）；无数据 → 0 */
    public static double lastFreq() { return lastFreq; }

    /** 预设每频率颜色（按频率排序分配，不同波形不同颜色） */
    public static int colorFor(int index) {
        final int[] COLORS = {
                0xFFFF4040, // 红
                0xFF40FF40, // 绿
                0xFF40C0FF, // 蓝
                0xFFFFFF40, // 黄
                0xFFFF40FF, // 品红
                0xFF40FFFF, // 青
                0xFFFFA040, // 橙
                0xFFA040FF  // 紫
        };
        return COLORS[Math.floorMod(index, COLORS.length)];
    }
}
