/**
 * ===== 变压器声音参数客户端缓存 =====
 *
 * 客户端保存"变压器 pos → 服务端同步的真实运行参数"（freq/iP/iS），
 * 由 TransformerSoundPayload（S2C）写入，SynthHumSound 合成声音读取。
 * 条目带时间戳，超过 3 秒未收到新包（变压器被拆/区块卸载/未更新）即失效。
 */

package com.hdf.cryptand.neoforge.core.sound;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TransformerSoundState {

    /** 超过该时长未收到新包 → 条目失效（变压器已拆/卸载/断网） */
    private static final long TIMEOUT_MS = 3000;

    public record Entry(double freq, double iP, double iS, long lastUpdate) {
        public Entry(double freq, double iP, double iS) {
            this(freq, iP, iS, System.currentTimeMillis());
        }

        public boolean expired() {
            return System.currentTimeMillis() - lastUpdate > TIMEOUT_MS;
        }
    }

    private static final Map<BlockPos, Entry> STATES = new ConcurrentHashMap<>();

    private TransformerSoundState() {
    }

    public static void set(BlockPos pos, double freq, double iP, double iS) {
        if (pos == null) return;
        STATES.put(pos.immutable(), new Entry(freq, iP, iS));
    }

    /** 取状态；已过期则移除并返回 null */
    public static Entry get(BlockPos pos) {
        if (pos == null) return null;
        Entry e = STATES.get(pos);
        if (e == null) return null;
        if (e.expired()) {
            STATES.remove(pos);
            return null;
        }
        return e;
    }

    /** 频率（Hz）；无状态返回 0 */
    public static double getFreq(BlockPos pos) {
        Entry e = get(pos);
        return e == null ? 0 : e.freq();
    }

    /** 初级电流（A）；无状态返回 0 */
    public static double getCurrent(BlockPos pos) {
        Entry e = get(pos);
        return e == null ? 0 : e.iP();
    }

    /** 次级电流瞬时值（A）；无状态返回 0（声音停止判定用） */
    public static double getSecCurrentRaw(BlockPos pos) {
        Entry e = get(pos);
        return e == null ? 0 : e.iS();
    }

    /** 每帧清理所有过期条目（变压器被拆 / 区块卸载后包停止到达） */
    public static void tick() {
        long now = System.currentTimeMillis();
        STATES.entrySet().removeIf(en -> now - en.getValue().lastUpdate() > TIMEOUT_MS);
    }
}
