package com.hdf.cryptand.circuitsimulation.core.node;

import com.hdf.cryptand.circuitsimulation.compute.MatrixSolveNode;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.circuitsimulation.solver.Solver;
import com.hdf.cryptand.circuitsimulation.solver.Solvers;

import java.util.Arrays;
import java.util.Set;

/**
 * 电路 MNA 矩阵节点（2026-08-19 节点化求解：电路计算节点）。
 *
 * <p>一个网络一个 MnaNode：把自己的元件子矩阵 stamp 进块对角构建器
 * （{@link #stampBlock}，offset 已由 pipeline 设为全局偏移），可与其
 * 它网络 / 其它矩阵节点同频合成一次求解；或独立求解（{@link #directSolve}）。
 *
 * <p>形态选择（{@link #blockEligible()}）：
 * <ul>
 *   <li>单频 AC 相量 → 参与块对角合成（复用 MergedNetworkSolver 的
 *       offset/接地/GMin=1e-7 模式）</li>
 *   <li>DC（frequency≤0）→ RealMnaSolver 独立实数求解（DC 源在相量域
 *       不注入电压）</li>
 *   <li>多频波形组 / 相量非线性 → 独立求解（MultiTone / 非线性增强）</li>
 * </ul>
 */
public final class MnaNode implements MatrixSolveNode {

    public static final String ID = "mna";

    /** 防奇异 GMin（与 MergedNetworkSolver / ComplexMnaSolver 一致） */
    private static final double GMIN = 1e-7;

    private final Network net;

    /** 非线性相量求解方法（NONE = 普通相量；由上层配置写入） */
    public volatile PhasorNonlinearMethod nonlinearMethod = PhasorNonlinearMethod.NONE;

    /** applyBlock 注入的本块相量切片（非 null 表示已参与块对角合成） */
    private volatile Complex[] vBlock;

    public MnaNode(Network net) {
        this.net = net;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> dependsOn() {
        return Set.of();
    }

    @Override
    public int estimateCost() {
        return net.nodeCount();
    }

    @Override
    public boolean blockEligible() {
        if (net.frequency <= 0) return false;                     // DC → 实数，不合成
        if (net.waveforms() != null && net.waveforms().isMultiTone()) return false; // 多频
        if (nonlinearMethod != null && nonlinearMethod != PhasorNonlinearMethod.NONE
                && net.hasPhasorNonlinear()) return false;        // 非线性迭代
        return true;
    }

    @Override
    public int blockSize() {
        return net.nodeCount();
    }

    @Override
    public void stampBlock(ComplexMnaBuilder m, double omega) {
        // 接地：子网自身 groundNode → 全局 offset+g（MergedNetworkSolver 同款）
        int g = net.groundNode;
        if (g >= 0 && g < net.nodeCount()) {
            m.clearRowCol(g);
            m.addY(g, g, new Complex(1, 0));
            m.addB(g, Complex.ZERO);
        }
        // GMin 兜底（防奇异；与单网络求解一致）
        for (int j = 0; j < net.nodeCount(); j++) {
            m.addY(j, j, new Complex(GMIN, 0));
        }
        // 元件子矩阵
        for (var e : net.elements()) {
            try {
                e.stampComplex(m, omega);
            } catch (Throwable ignored) {
                // 单元件失败不炸整个网络（与旧求解路径一致）
            }
        }
    }

    @Override
    public void applyBlock(Complex[] global, int offset) {
        this.vBlock = Arrays.copyOfRange(global, offset, offset + blockSize());
    }

    @Override
    public SolveResult directSolve() {
        Complex[] block = this.vBlock;
        if (block != null) {
            // 块对角合成结果 → 本网络结果（幅值 + 完整相量）
            int n = net.nodeCount();
            double[] mag = new double[n];
            for (int i = 0; i < n; i++) {
                Complex v = i < block.length ? block[i] : Complex.ZERO;
                mag[i] = v.abs();
            }
            return new SolveResult(mag, block, true, 1, 0L, SolveMode.COMPLEX_AC);
        }
        // 独立求解路径（DC / 多频 / 非线性 / 单块 AC）
        return pickSolver().solve(net);
    }

    private Solver pickSolver() {
        if (net.frequency <= 0) {
            return new com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver();
        }
        if (nonlinearMethod != null && nonlinearMethod != PhasorNonlinearMethod.NONE
                && net.hasPhasorNonlinear()) {
            return Solvers.createNonlinear(net, nonlinearMethod);
        }
        return Solvers.create(net);
    }
}
