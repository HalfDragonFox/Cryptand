package com.hdf.cryptand.algorithm.curve;

/**
 * ===== 曲线映射（输入范围 + 曲线）=====
 *
 * 把"轴原始值"一路映射到"归一化输出"，这就是用户要的那条链：
 * <pre>
 *   raw  →  t = (raw - inMin) / (inMax - inMin)   （可反向；钳制到 [0,1]）
 *        →  y = curve.apply(t)                     （0..1 = 0%..100%）
 * </pre>
 *
 * 其中 {@code inMin}/{@code inMax} 就是界面上那两个"输入最大最小值"文本框。
 * 纯 Java，零 MC 依赖。
 *
 * @param inMin  输入下限（文本框）
 * @param inMax  输入上限（文本框）
 * @param invert 轴反向（部分踏板/摇杆"未推"读数是 +1 或 -1，与直觉相反）
 * @param curve  曲线
 */
public record CurveMap(double inMin, double inMax, boolean invert, EditableCurve curve) {

    /** 默认：输入 0..1 不反向 + 直通曲线（两点线性） */
    public static final CurveMap DEFAULT =
            new CurveMap(0.0, 1.0, false, EditableCurve.DEFAULT);

    public CurveMap {
        if (curve == null) {
            curve = EditableCurve.DEFAULT;
        }
        if (!Double.isFinite(inMin)) {
            inMin = 0.0;
        }
        if (!Double.isFinite(inMax)) {
            inMax = 1.0;
        }
    }

    /** 原始值 → 归一化输出 0..1（= 0%..100%） */
    public double apply(double raw) {
        return curve.apply(normalize(raw));
    }

    /** 原始值 → 归一化输入 t（钳制到 [0,1]；范围退化时恒为 0） */
    public double normalize(double raw) {
        double v = invert ? -raw : raw;
        double span = inMax - inMin;
        if (Math.abs(span) < 1.0E-9) {
            return 0.0;
        }
        return CurvePoint.clamp01((v - inMin) / span);
    }

    /** 输出百分比（0..100，界面显示用） */
    public double percent(double raw) {
        return apply(raw) * 100.0;
    }

    public CurveMap withRange(double min, double max) {
        return new CurveMap(min, max, invert, curve);
    }

    public CurveMap withInvert(boolean value) {
        return new CurveMap(inMin, inMax, value, curve);
    }

    public CurveMap withCurve(EditableCurve value) {
        return new CurveMap(inMin, inMax, invert, value);
    }
}
