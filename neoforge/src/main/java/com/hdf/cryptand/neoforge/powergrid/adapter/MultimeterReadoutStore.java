/**
 * ===== 万用表读数存储（packet 驱动） =====
 *
 * 客户端每 10 tick 发送 MultimeterRequestPayload（C2S）→ 服务端用【网络级】相量
 * 计算（PhasorEngine.voltageAcross / voltageBetween / wireCurrent，全部走
 * buildContextFromNetwork，跨变压器合并，零导线 BFS）→ 回发
 * MultimeterResponsePayload（S2C）→ 客户端【仅】在收到响应包后更新本地结果表。
 *
 * 不再依赖 ServerTickEvent 每 tick 遍历共享表：完全由请求-响应包驱动，
 * 天然支持多人服务器（每人独立请求/响应）。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MultimeterReadoutStore {

    /** 测量目标种类 */
    public enum Kind {
        /** 同一方块两端子电压（变压器/绕组/其他方块） */
        SAME_BLOCK_VOLTAGE,
        /** 跨方块两点电压（任意两点电位差） */
        BETWEEN_BLOCKS_VOLTAGE,
        /** 导线电流（钳流） */
        WIRE_CURRENT,
        /** 单点电流（2026-08-15 用户要求：电流模式单点接线 → 测流经该点的电流，KCL） */
        NODE_CURRENT,
        /** 双点等效电阻（手持电阻表，2026-08-19：服务端测试电流法，单位 Ω） */
        RESISTANCE_BETWEEN
    }

    /** 测量目标（客户端构建，经请求包传给服务端） */
    public static final class Target {
        public final Kind kind;
        public final BlockPos posA;
        public final int tA;
        public final BlockPos posB;
        public final int tB;
        public final int eid;        // WIRE_CURRENT：导线实体 id
        public final double freq;    // Hz（>1 = AC）

        public Target(Kind kind, BlockPos posA, int tA, BlockPos posB, int tB, int eid, double freq) {
            this.kind = kind;
            this.posA = posA;
            this.tA = tA;
            this.posB = posB;
            this.tB = tB;
            this.eid = eid;
            this.freq = freq;
        }

        public static Target sameBlockVoltage(BlockPos pos, int t1, int t2, double freq) {
            return new Target(Kind.SAME_BLOCK_VOLTAGE, pos, t1, null, t2, -1, freq);
        }

        public static Target betweenBlocksVoltage(BlockPos a, int ta, BlockPos b, int tb, double freq) {
            return new Target(Kind.BETWEEN_BLOCKS_VOLTAGE, a, ta, b, tb, -1, freq);
        }

        public static Target wireCurrent(int eid, double freq) {
            return new Target(Kind.WIRE_CURRENT, null, -1, null, -1, eid, freq);
        }

        /** 单点电流（电流模式单点接线：流经 pos#term 的电流） */
        public static Target nodeCurrent(BlockPos pos, int term, double freq) {
            return new Target(Kind.NODE_CURRENT, pos, term, null, -1, -1, freq);
        }

        /** 双点等效电阻（电阻表：A 端子 → B 端子，服务端测试电流法） */
        public static Target resistanceBetween(BlockPos a, int ta, BlockPos b, int tb) {
            return new Target(Kind.RESISTANCE_BETWEEN, a, ta, b, tb, -1, 0);
        }

        /** 从请求包字段反序列化（posA/posB 已解包为 BlockPos；null = 无） */
        public static Target fromWire(int kindOrd, BlockPos posA, int tA, BlockPos posB, int tB,
                                      int eid, double freq) {
            Kind k = kindOrd >= 0 && kindOrd < Kind.values().length ? Kind.values()[kindOrd] : null;
            if (k == null) return null;
            switch (k) {
                case SAME_BLOCK_VOLTAGE:
                    return posA == null ? null : sameBlockVoltage(posA, tA, tB, freq);
                case BETWEEN_BLOCKS_VOLTAGE:
                    return (posA == null || posB == null) ? null
                            : betweenBlocksVoltage(posA, tA, posB, tB, freq);
                case WIRE_CURRENT:
                    return eid < 0 ? null : wireCurrent(eid, freq);
                case NODE_CURRENT:
                    return (posA == null || tA < 0) ? null : nodeCurrent(posA, tA, freq);
                case RESISTANCE_BETWEEN:
                    return (posA == null || posB == null) ? null
                            : resistanceBetween(posA, tA, posB, tB);
            }
            return null;
        }
    }

    /** 读数结果（客户端在收到响应包后写入，客户端读） */
    public static final class Readout {
        public final double value;   // RMS（V 或 A）
        public final double freq;    // 服务端实际求解频率（Hz；>1 = AC）
        public final long timeMs;
        Readout(double value, double freq, long timeMs) { this.value = value; this.freq = freq; this.timeMs = timeMs; }
    }

    /** 客户端结果表：key → Readout（收到服务端响应包才写入） */
    private static final Map<String, Readout> RESULTS = new ConcurrentHashMap<>();

    /** 失败记录：key → 失败时间（服务端明确返回 invalid 时记录，用于显示"读取不到"） */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    private MultimeterReadoutStore() {}

    /** 客户端读取；无新鲜结果返回 null */
    public static Readout read(String key) {
        if (key == null) return null;
        Readout r = RESULTS.get(key);
        if (r == null) return null;
        if (System.currentTimeMillis() - r.timeMs > 3000) return null; // 过期（比请求间隔宽松）
        return r;
    }

    /**
     * 客户端：收到服务端响应包后更新读数（仅此路径写结果）。
     * valid=false（服务端明确失败）→ 移除读数并记录失败（显示"读取不到"）。
     * freq = 服务端实际求解频率（网络级可靠，供客户端 AC/DC 判定）。
     */
    public static void updateFromResponse(String key, double rms, double freq, boolean valid) {
        if (key == null) return;
        if (!valid || Double.isNaN(rms)) {
            RESULTS.remove(key);
            FAILED.put(key, System.currentTimeMillis());
            return;
        }
        FAILED.remove(key);
        RESULTS.put(key, new Readout(rms, freq, System.currentTimeMillis()));
    }

    /** 是否最近收到服务端"读取失败"（invalid 响应）；超过 3 秒视为过期不再失败 */
    public static boolean isFailed(String key) {
        if (key == null) return false;
        Long t = FAILED.get(key);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 3000) {
            FAILED.remove(key);
            return false;
        }
        return true;
    }
}
