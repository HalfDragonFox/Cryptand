/**
 * ===== 导线视线选中状态（2026-08-17） =====
 *
 * WireLookPicker 每 tick 射线检测后把【玩家当前看向的导线】写入本 Store；
 * 渲染层（WireRenderManager）据此高亮，交互层（CryptandWireCutHandler）
 * 据此把剪线钳右键转成按边拆除。
 *
 * 线程：客户端主线程（tick + 事件处理）。volatile 足够。
 */

package com.hdf.cryptand.neoforge.powergrid.client.wire;

import net.minecraft.world.phys.Vec3;

public final class WireLookStore {

    /** 命中记录：导线端点【身份】（块坐标+端子索引，与 ClientWire 一致）+ 曲线参数 + 命中点 */
    public record WireHit(
            int ax, int ay, int az, int aTerm,
            int bx, int by, int bz, int bTerm,
            float t, Vec3 hitPoint) {

        /** 与客户端导线匹配（端点身份一致，顺序与同步包一致） */
        public boolean matches(ClientWireGraphStore.ClientWire w) {
            return w.ax() == ax && w.ay() == ay && w.az() == az && w.aTerm() == aTerm
                    && w.bx() == bx && w.by() == by && w.bz() == bz && w.bTerm() == bTerm;
        }
    }

    private static volatile WireHit current;
    /** 命中变化版本号：每次 set/clear +1（渲染层据此强制重扫换高亮色） */
    private static volatile int version;

    private WireLookStore() {
    }

    public static void set(WireHit hit) {
        if (hit == null ? current != null : !hit.equals(current)) {
            current = hit;
            version++;
        }
    }

    public static void clear() {
        set(null);
    }

    /** 当前看向的导线（null = 未看向任何导线） */
    public static WireHit current() {
        return current;
    }

    /** 命中变化版本（渲染层增量检测用） */
    public static int version() {
        return version;
    }
}
