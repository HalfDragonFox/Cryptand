package com.hdf.cryptand.algorithm.curve;

/**
 * 曲线上的一个控制点：{@code x} = 归一化输入 0..1，{@code y} = 归一化输出 0..1。
 * <p>纯 Java，零 Minecraft 依赖（本包整体可删除，只要去掉引用方）。
 */
public record CurvePoint(double x, double y) {

    public CurvePoint {
        x = clamp01(x);
        y = clamp01(y);
    }

    /** 钳制到 [0,1] */
    public static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }
}
