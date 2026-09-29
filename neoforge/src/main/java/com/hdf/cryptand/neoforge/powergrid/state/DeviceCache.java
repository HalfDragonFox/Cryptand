/**
 * ===== 设备缓存（2026-08-15 用户设计：组装器缓存化分支的原子缓存，多槽） =====
 *
 * 用户："组装器分支用三种（BE→组装器 / 组装器→BE / 双向），并且缓冲数据支持
 * 多个以便多个数据同步。"
 *
 * 无锁设计：每个槽一个 {@link AtomicReference} 原子替换不可变 {@link Data} 快照。
 * 多槽约定（组装器按需用）：
 *   - 槽 0（{@link #SLOT_IN}）：输入——BE 模型 → 组装器（主线程写，后台组装器读）
 *   - 槽 1（{@link #SLOT_OUT}）：输出——组装器 → BE 模型（后台组装器写，主线程应用）
 *   - 槽 2+：扩展（多组数据同步）
 * 三种分支：
 *   - SourceCacheAssembler（BE→组装器）：只用输入槽
 *   - SinkCacheAssembler（组装器→BE）：只用输出槽
 *   - BidiCacheAssembler（双向）：两槽都用
 */
package com.hdf.cryptand.neoforge.powergrid.state;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class DeviceCache {

    /** 输入槽（BE→组装器）：主线程写，后台组装器读 */
    public static final int SLOT_IN = 0;
    /** 输出槽（组装器→BE）：后台组装器写，主线程读应用 */
    public static final int SLOT_OUT = 1;
    /** 默认槽数（输入+输出） */
    public static final int DEFAULT_SLOTS = 2;

    /** 缓存数据快照（不可变；写方 set 新对象，读方 get 原子读） */
    public static final class Data {
        /** 绕组/主线电阻（Ω） */
        public final double resistance;
        /** 绕组电感（H） */
        public final double inductance;
        /** 幅值/trim/滑片值（V/A/Ω 按设备语义） */
        public final double amplitude;
        /** 相位（度，AC 源用） */
        public final double phaseDeg;
        /** 频率（Hz，AC 源用） */
        public final double frequencyHz;
        /** 开关/接地闭合等布尔状态 */
        public final boolean enabled;
        /** 更新版本（写方每次 set +1；读方据此判断变化） */
        public final long version;
        /** ⚠ 2026-08-30 代理组装器传递：真实设备 BE 类名（DeviceConnector 代理
         *  facing 设备用——refreshCache 存类名 → assembleFromCache 查类名 → 真实
         *  组装器建设备模型到接线柱端子）。null = 非代理/无设备。 */
        public final String className;

        public static final Data EMPTY = new Data(0, 0, 0, 0, 0, false, 0, null);

        public Data(double resistance, double inductance, double amplitude,
                    double phaseDeg, double frequencyHz, boolean enabled, long version) {
            this(resistance, inductance, amplitude, phaseDeg, frequencyHz, enabled, version, null);
        }

        public Data(double resistance, double inductance, double amplitude,
                    double phaseDeg, double frequencyHz, boolean enabled, long version,
                    String className) {
            this.resistance = resistance;
            this.inductance = inductance;
            this.amplitude = amplitude;
            this.phaseDeg = phaseDeg;
            this.frequencyHz = frequencyHz;
            this.enabled = enabled;
            this.version = version;
            this.className = className;
        }
    }

    private final AtomicReferenceArray<AtomicReference<Data>> slots;

    /** 设备电流缓存（A，有向：流入端子0为正；2026-08-22 用户需求：组装器缓存
     *  电流——后台求解 round 写入，主线程 tick 检测供电）。无锁 volatile。 */
    private volatile double deviceCurrent;

    /** 写入设备电流（后台求解 round；无锁） */
    public void setDeviceCurrent(double amperes) {
        this.deviceCurrent = amperes;
    }

    /** 读取设备电流（主线程 tick；无锁；未写入默认 0） */
    public double deviceCurrent() {
        return deviceCurrent;
    }

    /** 默认 2 槽（输入 + 输出） */
    public DeviceCache() {
        this(DEFAULT_SLOTS);
    }

    /** 多槽（多个数据同步：输入/输出/扩展） */
    public DeviceCache(int slotCount) {
        int n = Math.max(1, slotCount);
        slots = new AtomicReferenceArray<>(n);
        for (int i = 0; i < n; i++) {
            slots.set(i, new AtomicReference<>(Data.EMPTY));
        }
    }

    /** 读指定槽（无锁快速） */
    public Data get(int slot) {
        AtomicReference<Data> r = slot < 0 || slot >= slots.length() ? null : slots.get(slot);
        return r == null ? Data.EMPTY : r.get();
    }

    /** 写指定槽（无锁快速，原子替换） */
    public void set(int slot, Data d) {
        AtomicReference<Data> r = slot < 0 || slot >= slots.length() ? null : slots.get(slot);
        if (r != null && d != null) r.set(d);
    }

    /** 输入槽（BE→组装器）读 */
    public Data in() {
        return get(SLOT_IN);
    }

    /** 输入槽写 */
    public void setIn(Data d) {
        set(SLOT_IN, d);
    }

    /** 输出槽（组装器→BE）读 */
    public Data out() {
        return get(SLOT_OUT);
    }

    /** 输出槽写 */
    public void setOut(Data d) {
        set(SLOT_OUT, d);
    }

    /** 指定槽版本 */
    public long version(int slot) {
        return get(slot).version;
    }

    /** 槽数 */
    public int slotCount() {
        return slots.length();
    }
}

