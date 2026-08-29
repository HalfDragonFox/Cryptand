package com.hdf.cryptand.circuitsimulation.netgraph;

import java.util.Objects;

/**
 * 导线（2026-08-13 用户架构：自管拓扑，IE+CEE 混合）。
 * <p>
 * 一条世界导线 = 两个端点 + 电学/物理参数。等价于 IE 的 Connection +
 * CEE 的 WireData 的混合体，但【只存静态参数，不存任何求解状态】——
 * 电压/电流/功率全由 Cryptand 引擎求解（本组件只负责拓扑与参数源）。
 * <p>
 * 参数（按需使用，适配层填入）：
 *   - resistance：段电阻（Ω，>0 参与分段建模；<=0 = 理想导线/仅拓扑）
 *   - temperatureKey：温度模型 key（段路径签名；跨重建持久）
 *   - sag/length：渲染下垂/长度（供 Cryptand-Flywheel，核心不计算）
 * <p>
 * 【纯算法组件】：不依赖 Minecraft/PowerGrid。不可变（替换 = 移除+新增）。
 */
public final class WireEdge {

    public final WirePoint a;
    public final WirePoint b;
    /** 段电阻（Ω；>0 = 参与引擎段电阻建模） */
    public final double resistance;
    /** 温度模型 key（段路径签名，可 null） */
    public final String temperatureKey;
    /** 物理长度（供渲染/电阻换算参考） */
    public final double length;
    /** 渲染器引用 id（SaggingWireRegistry 键，如 "copper"；2026-08-14 引用化：
     *  全局渲染器列表按 id 查 texture/color/sag——导线不再每边存渲染参数副本，
     *  减少内存；null=默认渲染） */
    public final String rendererId;
    /** 染色覆盖（ARGB；0=无染色，用渲染器默认色——副手染料染色每边独立，
     *  不属于渲染器静态参数，单独存覆盖值） */
    public final int colorOverride;
    /** 是否【自管放置】的边（2026-08-13 一步到位：玩家放置直接写图，不创建
     *  原版实体）。自管放置边不在 transmissionLines → convertWires 差量移除
     *  必须跳过（否则下 tick 被误删）。原版转换边 = false。 */
    public final boolean selfPlaced;
    /** 导线物品 ID（ResourceLocation 字符串；剪线返回物品用；可 null=未知）。 */
    public final String itemId;

    public WireEdge(WirePoint a, WirePoint b, double resistance,
                    String temperatureKey, double length, String rendererId,
                    int colorOverride, boolean selfPlaced, String itemId) {
        if (a == null || b == null) throw new IllegalArgumentException("WireEdge needs both endpoints");
        if (a.equals(b)) throw new IllegalArgumentException("WireEdge endpoints must differ: " + a);
        this.a = a;
        this.b = b;
        this.resistance = resistance;
        this.temperatureKey = temperatureKey;
        this.length = length;
        this.rendererId = rendererId;
        this.colorOverride = colorOverride;
        this.selfPlaced = selfPlaced;
        this.itemId = itemId;
    }

    public WireEdge(WirePoint a, WirePoint b, double resistance,
                    String temperatureKey, double length, String rendererId,
                    boolean selfPlaced, String itemId) {
        this(a, b, resistance, temperatureKey, length, rendererId, 0, selfPlaced, itemId);
    }

    public WireEdge(WirePoint a, WirePoint b, double resistance,
                    String temperatureKey, double length) {
        this(a, b, resistance, temperatureKey, length, null, 0, false, null);
    }

    public WireEdge(WirePoint a, WirePoint b, double resistance, String temperatureKey) {
        this(a, b, resistance, temperatureKey, 0.0, null, 0, false, null);
    }

    /** 另一端点 */
    public WirePoint other(WirePoint p) {
        if (p.equals(a)) return b;
        if (p.equals(b)) return a;
        throw new IllegalArgumentException(p + " not an endpoint of " + this);
    }

    /** 是否连接端点 p */
    public boolean connects(WirePoint p) { return a.equals(p) || b.equals(p); }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WireEdge e)) return false;
        // 无向边：a-b 与 b-a 相同
        return (a.equals(e.a) && b.equals(e.b)) || (a.equals(e.b) && b.equals(e.a));
    }

    @Override public int hashCode() {
        // 无向：排序后哈希
        return Objects.hash(a, b) + Objects.hash(b, a);
    }

    @Override public String toString() {
        return "WE(" + a + "<->" + b + " R=" + resistance + ")";
    }
}
