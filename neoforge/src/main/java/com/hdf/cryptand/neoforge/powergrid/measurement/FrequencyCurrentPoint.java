/**
 * ===== 频率当前点（示波器式游标） =====
 *
 * 模拟示波器水平扫描的光标位置：位置从 0 扫到 maxValue（分辨率决定，
 * 如 8bit → 0~255），每周期（AC 计算线程）推进一个步长：
 *
 *   step = (maxValue+1) × waveformHz / computeHz
 *
 * 即一个完整波形周期 = 一次完整扫过整个量程。50Hz@20Hz 采样会因欠采样
 * 在两点间跳动（这正是示波器混叠的真实表现）。
 *
 * 初始位置 = maxValue × 0.2（8bit 时 = 51 = 25.5×2），由构造时设定。
 * 位置由 AC 线程推进（volatile，线程安全）；服务端 tick/UI 只读。
 */

package com.hdf.cryptand.neoforge.powergrid.measurement;

public class FrequencyCurrentPoint {

    private final int resolutionBits;
    private final int maxValue;
    /** 当前游标位置 [0, maxValue]（仅 AC 线程写入，UI 只读） */
    private volatile double position;

    public FrequencyCurrentPoint(int resolutionBits) {
        this.resolutionBits = Math.max(1, Math.min(16, resolutionBits));
        this.maxValue = (1 << this.resolutionBits) - 1;
        // 初始位置 = 最大值的 1/5（8bit → 255×0.2 = 51 = 25.5×2）
        this.position = this.maxValue * 0.2;
    }

    /**
     * 每个计算周期推进游标。
     *
     * @param waveformHz 波形频率（Hz）
     * @param computeHz  计算线程频率（Hz）
     */
    public void advance(double waveformHz, double computeHz) {
        if (waveformHz <= 0 || computeHz <= 0) return;
        double step = (maxValue + 1.0) * waveformHz / computeHz;
        double next = position + step;
        position = next % (maxValue + 1.0);
        if (position < 0) position += maxValue + 1.0;
    }

    /** 当前游标位置（取整） */
    public int getPosition() {
        return (int) position;
    }

    /** 位置最大值（= 2^bits - 1） */
    public int getMaxValue() {
        return maxValue;
    }

    /** 分辨率（bit） */
    public int getResolutionBits() {
        return resolutionBits;
    }

    /** 当前位置对应的波形相位（弧度） */
    public double getPhaseRad() {
        return 2 * Math.PI * position / (maxValue + 1.0);
    }
}
