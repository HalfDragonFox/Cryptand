package com.hdf.cryptand.circuitsimulation.solver;

/**
 * 非线性相量计算方法（2026-08-15 用户需求：让非线性也能直接相量计算，
 * 替代伪时域）。
 */
public enum PhasorNonlinearMethod {
    /** 不启用：非线性网络走固定节拍伪时域（现状，能量状态推进） */
    NONE,
    /** 分段线性化：工作点线性化 + 牛顿迭代（基波） */
    PIECEWISE,
    /** 谐波平衡法：多谐波 + 时域采样 DFT 迭代（捕获谐波畸变） */
    HARMONIC_BALANCE,
    /** 动态相量法：相量包络时间推进（含 L/C 包络导数项） */
    DYNAMIC_PHASOR
}
