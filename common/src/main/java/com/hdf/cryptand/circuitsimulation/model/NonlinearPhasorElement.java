package com.hdf.cryptand.circuitsimulation.model;

import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 非线性相量元件接口（2026-08-15 用户需求：分段线性化 / 谐波平衡 / 动态相量
 * 让非线性也能直接进行相量计算，替代伪时域）。
 *
 * 三种相量算法共用本接口的四个能力：
 * <pre>
 *   分段线性化   : equivalentAdmittance + equivalentCurrent（工作点切线外推，
 *                 牛顿迭代收敛到一致解）
 *   谐波平衡法   : timeDomainCurrent（时域采样 → DFT 得到各谐波电流分量）
 *   动态相量法   : equivalentAdmittance + envelopeDerivativeCoeff（相量包络
 *                 的导数项，包络随时间慢变）
 * </pre>
 *
 * 实现约定：两端电压相量 v = Va - Vb（元件两端电压），电流正方向 a → b。
 * 求解器每轮求解后用电压解更新工作点 → 重求等效参数 → 再解，直到收敛。
 */
public interface NonlinearPhasorElement {

    /**
     * 工作点电压相量 v 下的等效小信号导纳 Y = dI/dV（复数值）。
     * 分段线性化：迭代中用 Y 装配线性矩阵（替代非线性元件）。
     * 动态相量：作为当前包络下的线性化导纳。
     */
    Complex equivalentAdmittance(Complex v, double omega);

    /**
     * 工作点电压相量 v 下的线性化补偿电流源 I0 = I(V0) - Y·V0（切线外推）。
     * 迭代装配时 I ≈ I0 + Y·V 在收敛点等于真实非线性电流。
     */
    Complex equivalentCurrent(Complex v, double omega);

    /**
     * 给定元件两端电压的时域采样序列（v(t)，一个基波周期均分，基波 omega），
     * 返回元件电流的时域采样（与输入等长对齐）。
     * 谐波平衡法：对电流采样做 DFT 得到各谐波电流相量。
     */
    double[] timeDomainCurrent(double[] vSamples, double omega);

    /**
     * 动态相量：包络导数系数 c —— 元件动态相量电流
     * i(t) = Y·V(t) + c·(dV/dt)（c 对电压包络求导的系数）。
     * 默认无记忆非线性（c = 0）；电感/含惯性非线性覆写。
     */
    default Complex envelopeDerivativeCoeff(Complex v, double omega) {
        return Complex.ZERO;
    }
}
