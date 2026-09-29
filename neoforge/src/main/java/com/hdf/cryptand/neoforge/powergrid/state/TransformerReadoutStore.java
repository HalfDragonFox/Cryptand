/**
 * ===== 变压器服务端相量读数存储 =====
 *
 * 服务端 tick 每 10 tick 计算变压器的初/次级【相量】电压（RMS）存入此处。
 * 单机（集成服务器）下客户端/服务端同 JVM → goggle 显示与高级万用表可直接
 * 读取（跨线程安全 ConcurrentHashMap），获得精确匝数比电压（无时域失真）。
 * 多人服务器：客户端读不到服务端写入 → 调用方回退客户端 BFS 相量。
 */

package com.hdf.cryptand.neoforge.powergrid.state;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TransformerReadoutStore {

    /** 变压器初/次级相量电压（RMS，V） */
    public static final class Readout {
        public final double v1;
        public final double v2;
        public final long timeMs;
        public Readout(double v1, double v2, long timeMs) {
            this.v1 = v1;
            this.v2 = v2;
            this.timeMs = timeMs;
        }
    }

    private static final Map<BlockPos, Readout> MAP = new ConcurrentHashMap<>();

    /** 服务端写入（RMS） */
    public static void put(BlockPos pos, double v1, double v2) {
        MAP.put(pos, new Readout(v1, v2, System.currentTimeMillis()));
    }

    /** 读取；无读数返回 null */
    public static Readout get(BlockPos pos) {
        return MAP.get(pos);
    }

    private TransformerReadoutStore() {}
}
