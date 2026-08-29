/**
 * ===== 电机声音参数客户端缓存 =====
 *
 * 客户端保存"换向器 pos → 服务端同步的真实参数"。
 * 条目带时间戳，超过 3 秒未收到新包（电机被拆 / 区块卸载）即视为失效，
 * 由 RotorSoundMixin 每帧调用 {@link #tick()} 清理。
 */

package com.hdf.cryptand.neoforge.core.sound;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MotorSoundState {

    /** 超过该时长未收到新包 → 条目失效（电机已拆/卸载/断网） */
    private static final long TIMEOUT_MS = 3000;

    public record Entry(double armFreq, double excFreq, float current, float power,
                        double voltage, double rotorFreq, boolean acExcited,
                        boolean mismatched, long lastUpdate) {

        public Entry(double armFreq, double excFreq, float current, float power,
                     double voltage, double rotorFreq, boolean acExcited, boolean mismatched) {
            this(armFreq, excFreq, current, power, voltage, rotorFreq, acExcited,
                    mismatched, System.currentTimeMillis());
        }

        public boolean expired() {
            return System.currentTimeMillis() - lastUpdate > TIMEOUT_MS;
        }
    }

    private static final Map<BlockPos, Entry> PARAMS = new ConcurrentHashMap<>();

    private MotorSoundState() {
    }

    public static void set(BlockPos pos, Entry e) {
        if (pos == null || e == null) return;
        PARAMS.put(pos.immutable(), e);
    }

    /** 取参数；已过期则移除并返回 null */
    public static Entry get(BlockPos pos) {
        Entry e = PARAMS.get(pos);
        if (e == null) return null;
        if (e.expired()) {
            PARAMS.remove(pos);
            return null;
        }
        return e;
    }

    /** 每帧清理所有过期条目（电机被拆 / 区块卸载后包停止到达） */
    public static void tick() {
        long now = System.currentTimeMillis();
        PARAMS.entrySet().removeIf(en -> now - en.getValue().lastUpdate > TIMEOUT_MS);
    }
}
