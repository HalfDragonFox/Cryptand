package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;

import java.util.ArrayList;
import java.util.List;

/**
 * 同网络合并求解器（2026-08-12 用户要求：同网络合并 + 稀疏 LU + 增量求解）。
 * <p>
 * 把多个【同模式同频率】的独立网络合并成一块【分块对角稀疏矩阵】一次求解：
 *   - 每子网 stamp 到对角块（节点偏移），块之间无耦合 → 分块对角矩阵
 *   - 一次 SuperLU 稀疏 LU（O(n·nnz)），替代 N 次小矩阵求解
 *     （省调度开销 + 对象分配 + 大矩阵一次符号分解）
 *   - 结果按各子网节点偏移拆分返回，与输入同序
 * <p>
 * 仅适用于【交流相量】合并（ComplexMnaBuilder 稀疏存储 + SuperLU native）。
 * DC/时域是稠密矩阵，合并成一块对角 n² 反而更糟 → 保持逐个求解。
 * <p>
 * 增量求解：参数变化时子网级 paramVersion 重解（不重建网络，结构复用）——
 * 合并求解后该子网块数值更新，其余块不变；未来可叠加 SuperLU 符号分解缓存
 * （zgstrf/zgstrs 分离：结构不变只做数值重分解）。
 * <p>
 * 相位与波形参数（2026-08-12 用户提醒，均已核实正确传递）：
 *   - 相位：各子网内源的 phaseDeg 是【绝对相位，相对该子网自己的地】。块对角
 *     合并中每个子网独立固定自身 groundNode → 各子网相位基准保持、互不干扰
 *     （物理独立电路各有相位基准；物理相连者已在 buildContextFromNetwork 合并
 *     为同一 Network，不会进入合并路径）。AcVoltageSource 用
 *     fromPolar(amp, phaseDeg)，WaveformSource 同。
 *   - 波形：WaveformSource 在相量域用傅里叶【基波近似】fundamentalFactor()
 *     （SINE=1、SQUARE=4/π、TRIANGLE=8/π²、SAWTOOTH=2/π、DC=0），相位用
 *     phaseDeg、方波占空比 duty、直流偏置 offset——各源 stamp 时用自身参数，
 *     合并不改变。
 *   - 序列化（NetworkSnapshot）：waveform 类型 + params（含 phase/duty/offset）
 *     完整传递，远端重建相位/波形一致。
 */
public final class MergedNetworkSolver {

    /** 防奇异 GMin（与 ComplexMnaSolver 一致） */
    private static final double GMIN = 1e-7;

    private MergedNetworkSolver() {}

    /**
     * 合并求解多个交流网络（必须同频率：同一 omega）。
     *
     * @param nets  同频率交流网络列表（非空）
     * @param omega 角频率（rad/s）＝ 2π·f
     * @return 与输入同序的每子网 {@link SolveResult}（幅值数组 + 相量数组）；
     *         合并失败（native 回退也失败）时逐子网独立求解兜底
     */
    public static List<SolveResult> solveMergedComplex(List<Network> nets, double omega) {
        if (nets == null || nets.isEmpty()) return List.of();
        // 2026-08-20 求解器原生开路支持：stamp 前标记各子网开路电流源（合并装配
        // 不走 Solvers.create，须在此标记）
        for (Network net : nets) Solvers.markOpenCurrentSources(net);
        if (nets.size() == 1) {
            return List.of(Solvers.create(SolveMode.COMPLEX_AC, nets.get(0)).solve(nets.get(0)));
        }
        // 1) 每子网节点偏移（全局索引 = 子网内索引 + offset）
        int[] offset = new int[nets.size()];
        int total = 0;
        for (int i = 0; i < nets.size(); i++) {
            offset[i] = total;
            total += nets.get(i).nodeCount();
        }
        if (total == 0) return List.of();

        // 2) 一块分块对角装配：每子网 stamp 到自己的对角块
        ComplexMnaBuilder m = new ComplexMnaBuilder(total);
        for (int i = 0; i < nets.size(); i++) {
            Network net = nets.get(i);
            int n = net.nodeCount();
            int g = net.groundNode;
            m.offset = offset[i];
            for (Element e : net.elements()) e.stampComplex(m, omega);
            // 接地（子网内节点 g → 全局 offset+g）
            if (g >= 0 && g < n) {
                m.clearRowCol(g);
                m.addY(g, g, new Complex(1, 0));
                // ⚠ 2026-08-24 终极根因修复：b 必须用【全局】索引（offset[i]+g）！
                // 此前直接 m.b[g]（全局第 g 行）——非首个子网时清的是【别的网络】
                // 的右端项 → 参考节点右端未清零 → 求解结果违反参考节点
                //（ground=0 但 res[0]=4.24V ≠ 0）→ 同一网表 hash 一致（指纹防线
                // 放行）但解 KCL 矛盾 → 假 1100A → 导线误烧（断点实锤）。
                m.b[offset[i] + g] = Complex.ZERO;
            }
            // GMin 兜底（与单网络求解一致）
            for (int j = 0; j < n; j++) {
                if (j == g) continue;
                m.addY(j, j, new Complex(GMIN, 0));
            }
        }
        m.offset = 0; // 复位（后续 addY 用全局坐标）

        // 3) 一次求解：稀疏 SuperLU 优先，失败回退稠密；两者都失败 → 逐子网兜底
        Complex[] v = solveOnce(m);
        if (v == null) {
            List<SolveResult> fallback = new ArrayList<>();
            for (Network net : nets) {
                try {
                    fallback.add(Solvers.create(SolveMode.COMPLEX_AC, net).solve(net));
                } catch (Throwable ignored) {
                    fallback.add(null);
                }
            }
            return fallback;
        }

        // 4) 按偏移拆分
        List<SolveResult> out = new ArrayList<>(nets.size());
        for (int i = 0; i < nets.size(); i++) {
            int off = offset[i];
            int n = nets.get(i).nodeCount();
            Complex[] sub = new Complex[n];
            System.arraycopy(v, off, sub, 0, n);
            double[] vd = new double[n];
            for (int j = 0; j < n; j++) vd[j] = sub[j].abs();
            SolveResult subRes = new SolveResult(vd, sub, true, 1, 0, SolveMode.COMPLEX_AC);
            // 端子测试点回填（2026-08-13 用户架构：合并求解拆分后自动写端子电压）
            TerminalRecorder.record(nets.get(i), subRes);
            // 2026-08-21 伪时域充电：合并求解后提交电容/电感状态（与
            // ComplexMnaSolver 一致——DC/低频 Backward Euler 充电需逐节拍
            // 更新 vPrev/iPrev；AC 电容用 Y=jωC 不受影响）
            try {
                Network net = nets.get(i);
                for (Element e : net.elements()) {
                    int a = e.nodeA(), b = e.nodeB();
                    if (a >= 0 && a < n && b >= 0 && b < n) {
                        e.commit(sub[a].re, sub[b].re, Math.max(net.dt, 1e-3));
                    }
                }
            } catch (Throwable ignored) {
            }
            out.add(subRes);
        }
        return out;
    }

    /** 一次求解：float SuperLU（SCZ）优先 → double SuperLU → 稠密 LU 回退。 */
    private static Complex[] solveOnce(ComplexMnaBuilder m) {
        int n = m.size;
        int thr = NativeSparse.solverThreshold();
        boolean useSparse = NativeSparse.isLoaded() && (thr == 0 || n >= thr);
        if (useSparse) {
            // 配置 floatEnabled + native 含单精度 → float SuperLU（SCZ）
            if (Solvers.floatEnabled && NativeSparse.isSingleLoaded()) {
                Complex[] vf = ComplexMnaSolver.solveSparseFloat(m);
                if (vf != null) return vf;
            }
            Complex[] v = ComplexMnaSolver.solveSparse(m);
            if (v != null) return v;
        }
        return DenseComplexLU.solve(m.toDense(), m.b);
    }
}
