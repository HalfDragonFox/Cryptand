package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.TerminalElement;

/**
 * 端子测试点回填器（2026-08-13 用户架构：端子 = 电路测试点）。
 * <p>
 * 求解器 {@link Solver#solve} 完成后调用 {@link #record}，把该网络每个端子
 * （{@link TerminalElement}）对应节点的求解电压【自动回填到端子本身】——天然
 * 正确：悬空端因内部元件（绕组 R-L）MNA 等电位，求解值即等电位值；不做任何
 * "强制 0V / 强制等电位"的上层 hack。消费端（风扇/电机/仪表/写回）直接读
 * 端子电压。
 * <p>
 * 存储语义（与现有设备电流计算一致）：
 *   - 单频 AC 相量：voltage = |V|/√2（RMS），re/im = 完整相量
 *   - 多频叠加：voltage = 合成 RMS（voltages[]，驱动设备用），re/im = 主导频率相量
 *   - DC：voltage = 瞬时值，re = voltage, im = 0
 * <p>
 * 线程安全：volatile 字段，求解线程写、消费线程读；无锁。
 */
public final class TerminalRecorder {

    private TerminalRecorder() {
    }

    /**
     * 把求解结果回填到网络的所有端子测试点。网络/结果为空时无操作。
     * 由各 Solver 在 {@code solve()} 返回前调用（TransformerCoupler 子网由
     * 内部 ps/ss.solve 自动回填；MergedNetworkSolver 拆分后对每子网调用）。
     */
    public static void record(Network net, SolveResult res) {
        if (net == null || res == null || res.voltages == null) return;
        double[] v = res.voltages;
        Complex[] c = res.complex;
        int n = v.length;
        // ⚠ 2026-08-30 审计 #23：求解结果含 NaN/Infinity（native 求解器异常/
        // 奇异矩阵静默产 NaN，round 路径 solveAll 只查 converged 不查有限性）
        // → 端子读到 NaN 电压 → 消费端（风扇/电机/仪表）行为异常/永久不转。
        // 根因：非法结果【不写入测试点】——全量校验一次，非法 → 全部端子
        // invalidate（消费端 valid=false → 不动作）。
        boolean finite = true;
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(v[i])) { finite = false; break; }
            if (c != null && i < c.length && c[i] != null
                    && (!Double.isFinite(c[i].re) || !Double.isFinite(c[i].im))) {
                finite = false;
                break;
            }
        }
        if (!finite) {
            for (TerminalElement t : net.terminals()) t.invalidate();
            return;
        }
        boolean multiTone = res.toneVoltages != null && !res.toneVoltages.isEmpty();
        double freq = net.dominantFrequency();
        for (TerminalElement t : net.terminals()) {
            int id = t.engineNode;
            if (id < 0 || id >= n) {
                t.invalidate();
                continue;
            }
            if (multiTone) {
                // 合成 RMS（驱动设备）；主导频率相量（仪表相位）
                double re = 0, im = 0;
                if (c != null && id < c.length) { re = c[id].re; im = c[id].im; }
                t.record(v[id], re, im, freq);
            } else if (c != null && id < c.length) {
                Complex cv = c[id];
                t.record(cv.abs() / Math.sqrt(2.0), cv.re, cv.im, freq); // RMS + 相量
            } else {
                double val = v[id];
                t.record(val, val, 0, 0); // DC 瞬时（频率 0）
            }
        }
    }
}
