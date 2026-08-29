/**
 * ===== 温度表读数存储（packet 驱动） =====
 *
 * 客户端手持温度表绑定目标（元件方块或导线段）后，每 10 tick 发送
 * ThermometerRequestPayload（C2S）→ 服务端直接查温度存储（DeviceThermalStore /
 * ThermalBehaviour / WireThermalStore，不做求解）→ 回发 ThermometerResponsePayload
 * （S2C）→ 客户端【仅】在收到响应包后更新本地读数表。
 *
 * 单线连接：一次只绑一个目标（元件 或 导线段），新目标覆盖旧目标。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ThermometerReadoutStore {

    /** 温度读数（客户端收到 S2C 响应后写入，getText 读取） */
    public static final class Readout {
        public final double tempC;    // 摄氏度
        public final String label;    // 目标描述（元件/导线段）
        public final long timeMs;
        Readout(double tempC, String label, long timeMs) {
            this.tempC = tempC;
            this.label = label;
            this.timeMs = timeMs;
        }
    }

    /** key → 读数（收到服务端响应包才写入） */
    private static final Map<String, Readout> RESULTS = new ConcurrentHashMap<>();

    /** 失败记录：key → 时间（服务端明确 invalid → 显示"读取不到"） */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    private ThermometerReadoutStore() {}

    /** 客户端读取；无新鲜结果返回 null（3000ms 过期，比请求间隔宽松） */
    public static Readout read(String key) {
        if (key == null) return null;
        Readout r = RESULTS.get(key);
        if (r == null) return null;
        if (System.currentTimeMillis() - r.timeMs > 3000) return null;
        return r;
    }

    /** 是否明确失败（服务端返回 invalid） */
    public static boolean isFailed(String key) {
        if (key == null) return false;
        Long t = FAILED.get(key);
        if (t == null) return false;
        return System.currentTimeMillis() - t <= 3000;
    }

    /** 客户端：收到服务端响应包后更新（仅此路径写结果）。valid=false → 移除并记失败。 */
    public static void updateFromResponse(String key, double tempC, String label, boolean valid) {
        if (key == null) return;
        if (!valid || Double.isNaN(tempC)) {
            RESULTS.remove(key);
            FAILED.put(key, System.currentTimeMillis());
            return;
        }
        FAILED.remove(key);
        RESULTS.put(key, new Readout(tempC, label, System.currentTimeMillis()));
    }
}
