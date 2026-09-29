package com.hdf.cryptand.algorithm.curve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ===== 可编辑曲线核心（2026-09-14）=====
 *
 * 用户需求："可以把添加节点的曲线做成一个抽象的核心放 common 当作单独子包，然后供其他子包使用。"
 *
 * <p>纯 Java、<b>零 Minecraft 依赖</b>：NBT/网络等平台细节由调用方在各自子包里适配，
 * 本包只负责"点集 ⇄ 曲线"这件事，任何子包都能直接用（也能整体删除）。
 *
 * <h2>公式：单调三次 Hermite 插值（PCHIP / Fritsch–Carlson）</h2>
 * 控制点 (x_0,y_0)…(x_n,y_n)，h_k = x_{k+1}-x_k，Δ_k = (y_{k+1}-y_k)/h_k：
 * <pre>
 *   ① 区间斜率   Δ_k = (y_{k+1} - y_k) / h_k
 *   ② 内部节点    Δ_{k-1}·Δ_k ≤ 0 ⇒ m_k = 0（极值点压平，杜绝过冲）
 *                否则 m_k = (w₁+w₂)/(w₁/Δ_{k-1} + w₂/Δ_k)，w₁=2h_k+h_{k-1}，w₂=h_k+2h_{k-1}
 *   ③ 端点       m_0 = ((2h_0+h_1)Δ_0 − h_0Δ_1)/(h_0+h_1)，再按单调性钳制（符号反 ⇒ 0；|m|>3|Δ| ⇒ 3Δ）
 *   ④ 区间求值   t=(x−x_k)/h_k，y = h₀₀y_k + h₁₀h_k m_k + h₀₁y_{k+1} + h₁₁h_k m_{k+1}
 *                h₀₀=2t³−3t²+1  h₁₀=t³−2t²+t  h₀₁=−2t³+3t²  h₁₁=t³−t²
 * </pre>
 * 选它的理由：<b>天然单调、绝不过冲</b> —— 摇杆类曲线最怕中间点之间"甩出去"造成反直觉回拉；
 * 且公式闭式、可复现，不含任何迭代或启发式。
 *
 * <p>曲线永远覆盖整个 [0,1]：端点缺失时自动补齐（首点左补 (0,y₀)、末点右补 (1,y_n)），
 * 所以外部怎么加点都能求值。默认曲线 = 两点线性 (0,0)→(1,1)（直通）。
 *
 * <p><b>实例不可变</b>：编辑操作（加点/删点/移点）都返回新实例，方便做无锁快照与撤销。
 */
public final class EditableCurve {

    /** 默认曲线：两点线性 (0,0) → (1,1)。 */
    public static final EditableCurve DEFAULT = new EditableCurve(new CurvePoint[0]);

    private final CurvePoint[] points;
    /** 预计算的节点导数 m_k（②③步），与 {@link #points} 等长 */
    private final double[] slope;

    /** 从点集构造（会排序 / 去重 / 补齐端点） */
    public EditableCurve(List<CurvePoint> raw) {
        this(raw == null ? new CurvePoint[0] : raw.toArray(new CurvePoint[0]));
    }

    public EditableCurve(CurvePoint[] raw) {
        this.points = normalize(raw);
        this.slope = computeSlopes(this.points);
    }

    /**
     * 从紧凑原始数组构造：{@code [x0,y0,x1,y1,…]}（便于 NBT/网络里用 double[] 存）。
     * 长度为奇数时忽略最后一个。
     */
    public static EditableCurve fromRaw(double[] raw) {
        if (raw == null || raw.length < 2) {
            return DEFAULT;
        }
        int n = raw.length / 2;
        CurvePoint[] pts = new CurvePoint[n];
        for (int i = 0; i < n; i++) {
            pts[i] = new CurvePoint(raw[i * 2], raw[i * 2 + 1]);
        }
        return new EditableCurve(pts);
    }

    /** 导出为紧凑原始数组 {@code [x0,y0,x1,y1,…]} */
    public double[] toRaw() {
        double[] out = new double[points.length * 2];
        for (int i = 0; i < points.length; i++) {
            out[i * 2] = points[i].x();
            out[i * 2 + 1] = points[i].y();
        }
        return out;
    }

    /** 归一化后的控制点（已排序、去重、补齐端点） */
    public List<CurvePoint> points() {
        return List.of(points);
    }

    public int size() {
        return points.length;
    }

    /** 是否为默认两点线性曲线（界面据此判断【重置】按钮的状态） */
    public boolean isDefault() {
        return points.length == 2
                && points[0].x() == 0.0 && points[0].y() == 0.0
                && points[1].x() == 1.0 && points[1].y() == 1.0;
    }

    // ==================== 求值 ====================

    /** 曲线求值：{@code t} ∈ [0,1] → {@code y} ∈ [0,1]。端点外取端点值，不外推。 */
    public double apply(double t) {
        int n = points.length;
        if (n == 0) {
            return CurvePoint.clamp01(t);
        }
        if (n == 1 || t <= points[0].x()) {
            return points[0].y();
        }
        if (t >= points[n - 1].x()) {
            return points[n - 1].y();
        }
        int k = segmentOf(t);
        CurvePoint p0 = points[k];
        CurvePoint p1 = points[k + 1];
        double h = p1.x() - p0.x();
        if (h <= 0.0) {
            return p0.y();
        }
        double s = (t - p0.x()) / h;
        double s2 = s * s;
        double s3 = s2 * s;
        double h00 = 2.0 * s3 - 3.0 * s2 + 1.0;
        double h10 = s3 - 2.0 * s2 + s;
        double h01 = -2.0 * s3 + 3.0 * s2;
        double h11 = s3 - s2;
        double y = h00 * p0.y() + h10 * h * slope[k] + h01 * p1.y() + h11 * h * slope[k + 1];
        return CurvePoint.clamp01(y);
    }

    private int segmentOf(double t) {
        int lo = 0;
        int hi = points.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (points[mid].x() <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ==================== 编辑（返回新实例，保持不可变） ====================

    /** 添加一个控制点（已存在同 x 的点则替换） */
    public EditableCurve withPoint(CurvePoint point) {
        List<CurvePoint> next = new ArrayList<>(List.of(points));
        next.add(point);
        return new EditableCurve(next);
    }

    /**
     * 删除下标 {@code index} 的控制点（越界则原样返回）。
     * <p>⚠ <b>首尾端点不可删除</b>：曲线必须永远覆盖整个 [0,1]（用户 2026-09-14：
     * "最开始和最后点必须要在两端"）。删掉它们只会让 normalize 拿邻近点的 y 重新补一个，
     * 结果就是"删了又冒出来"的困惑；直接拒绝更明确。
     */
    public EditableCurve withoutPoint(int index) {
        if (index < 0 || index >= points.length) {
            return this;
        }
        if (index == 0 || index == points.length - 1) {
            return this;   // 端点不可删
        }
        List<CurvePoint> next = new ArrayList<>(List.of(points));
        next.remove(index);
        return new EditableCurve(next);
    }

    /**
     * 把下标 {@code index} 的点移到 {@code to}。
     * <p>⚠ 端点（x=0 与 x=1 的那两个）只允许改 y、不允许改 x ——
     * 否则曲线会失去对 [0,1] 的覆盖，求值区间出现空洞。
     */
    public EditableCurve withPointMoved(int index, CurvePoint to) {
        if (index < 0 || index >= points.length) {
            return this;
        }
        CurvePoint old = points[index];
        boolean leftEnd = old.x() <= 1.0E-4;
        boolean rightEnd = old.x() >= 1.0 - 1.0E-4;
        CurvePoint moved = (leftEnd || rightEnd)
                ? new CurvePoint(old.x(), to.y())
                : to;
        List<CurvePoint> next = new ArrayList<>(List.of(points));
        next.set(index, moved);
        return new EditableCurve(next);
    }

    /** 重置为默认两点线性曲线 */
    public EditableCurve reset() {
        return DEFAULT;
    }

    // ==================== 归一化 / 导数 ====================

    private static CurvePoint[] normalize(CurvePoint[] raw) {
        List<CurvePoint> out = new ArrayList<>();
        if (raw != null) {
            for (CurvePoint p : raw) {
                if (p != null) {
                    out.add(p);
                }
            }
        }
        out.sort(Comparator.comparingDouble(CurvePoint::x));
        // 同 x 只保留最后一个（拖动重叠时不产生零宽区间 ⇒ 不除零）
        List<CurvePoint> dedup = new ArrayList<>(out.size());
        for (CurvePoint p : out) {
            if (!dedup.isEmpty()
                    && Math.abs(dedup.get(dedup.size() - 1).x() - p.x()) < 1.0E-6) {
                dedup.set(dedup.size() - 1, p);
            } else {
                dedup.add(p);
            }
        }
        if (dedup.isEmpty()) {
            dedup.add(new CurvePoint(0.0, 0.0));
            dedup.add(new CurvePoint(1.0, 1.0));
        } else if (dedup.size() == 1) {
            CurvePoint only = dedup.get(0);
            dedup.clear();
            dedup.add(new CurvePoint(0.0, only.y()));
            dedup.add(new CurvePoint(1.0, only.y()));
        } else {
            // 端点补齐：曲线必须覆盖整个 [0,1]
            if (dedup.get(0).x() > 1.0E-6) {
                dedup.add(0, new CurvePoint(0.0, dedup.get(0).y()));
            }
            int last = dedup.size() - 1;
            if (dedup.get(last).x() < 1.0 - 1.0E-6) {
                dedup.add(new CurvePoint(1.0, dedup.get(last).y()));
            }
        }
        return dedup.toArray(new CurvePoint[0]);
    }

    private static double[] computeSlopes(CurvePoint[] p) {
        int n = p.length;
        double[] m = new double[n];
        if (n < 2) {
            return m;
        }
        double[] h = new double[n - 1];
        double[] d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            h[i] = p[i + 1].x() - p[i].x();
            d[i] = h[i] <= 0.0 ? 0.0 : (p[i + 1].y() - p[i].y()) / h[i];
        }
        if (n == 2) {
            m[0] = d[0];
            m[1] = d[0];
            return m;
        }
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] * d[i] <= 0.0) {
                m[i] = 0.0;
            } else {
                double w1 = 2.0 * h[i] + h[i - 1];
                double w2 = h[i] + 2.0 * h[i - 1];
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
            }
        }
        m[0] = endpoint(h[0], h[1], d[0], d[1]);
        m[n - 1] = endpoint(h[n - 2], h[n - 3], d[n - 2], d[n - 3]);
        return m;
    }

    /** 端点导数 + Fritsch–Carlson 单调性钳制 */
    private static double endpoint(double h0, double h1, double d0, double d1) {
        double m = ((2.0 * h0 + h1) * d0 - h0 * d1) / (h0 + h1);
        if (m * d0 <= 0.0) {
            return 0.0;
        }
        if (d0 * d1 < 0.0 && Math.abs(m) > 3.0 * Math.abs(d0)) {
            return 3.0 * d0;
        }
        return m;
    }
}
