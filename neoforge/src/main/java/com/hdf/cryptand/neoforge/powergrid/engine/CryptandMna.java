/**
 * ===== Cryptand 求解器适配层 =====
 *
 * 时域求解彻底禁用（2026-08-10）：本类不再求解任何矩阵。
 * 节点电压完全由相量回写驱动（PhasorPipeline 每 tick 把相量 RMS 电压
 * 写回 PowerGrid 网络）。本类保留 IMNA 接口占位：
 *   - singleTick() → 空操作（converged=true），绝不产生时域电压/电流
 *   - stateVector() 等其余接口保持实现以维持 ElectricalNetwork 生命周期
 *
 * 通过 ElectricalNetworkMixin 在构造后替换 mna 字段启用。
 */

package com.hdf.cryptand.neoforge.powergrid.engine;

import org.patryk3211.powergrid.config.CSolver;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.solver.IMNA;
import org.patryk3211.powergrid.electricity.sim.solver.IMatrixAccess;

import java.util.List;

public class CryptandMna implements IMNA {

    private final ElectricalNetwork network;

    /** 雅可比矩阵（稠密，行=方程，列=变量） */
    private double[][] jacobian;
    /** 右端项（电流源/激励） */
    private double[] rhs;
    /** 状态向量（节点电压） */
    private double[] state;
    private boolean converged;
    private int size;

    public CryptandMna(ElectricalNetwork network) {
        this.network = network;
    }

    @Override
    public CSolver.SolverBackend type() {
        return CSolver.SolverBackend.JAVA;
    }

    @Override
    public void cleanup() {}

    @Override
    public void setPrecision(double absolute, double relative, double minimum, double alpha) {}

    @Override
    public void warmUp(int ticks) {}

    @Override
    public void jacobianAdd(int row, int col, double value) {
        if (value == 0) return;
        // 防御：跳过 prepare 后 jacobian 可能为 null/未分配（残余 singleTick 路径）
        if (jacobian == null || row < 0 || col < 0 || row >= jacobian.length || col >= jacobian.length) return;
        jacobian[row][col] += value;
    }

    @Override
    public void rhsAdd(int row, double value) {
        if (value == 0) return;
        // 防御：跳过 prepare 后 rhs 可能为 null/未分配（残余 singleTick 路径）
        if (rhs == null || row < 0 || row >= rhs.length) return;
        rhs[row] += value;
    }

    @Override
    public void allocate(int size) {
        this.size = size;
        this.jacobian = new double[size][size];
        this.rhs = new double[size];
        this.state = new double[size];
        // 用节点当前值作为初始状态（与 JavaMNA 一致），求解器从该值起步
        try {
            List<? extends INode> nodes = network.getNodes();
            for (int i = 0; i < size && i < nodes.size(); i++) {
                state[i] = network.getValue(nodes.get(i));
            }
        } catch (Throwable t) {
            // 初始状态可为零
        }
        this.converged = false;
    }

    @Override
    public void singleTick() {
        // ===== 时域求解彻底禁用（2026-08-10） =====
        // 不再求解任何矩阵：PowerGrid 时域（LRSeriesWire 电感历史积分、变压器
        // Tr2P2S 耦合、innerHooks 动态残差）已完全停止。若网络中仍有代码调用
        // singleTick（如残余路径），这里也只是空转，不产生任何时域电压/电流。
        // 节点电压完全由相量回写驱动（PhasorPipeline 每 tick 写回 RMS 电压）。
        // 保留 converged=true：PowerGrid 设备/导线 isConverged() 为真，
        // current() = potentialDifference()×conductance()、power() = V²/R
        // 全部从回写电压自然计算 → 原版设备行为由相量电压驱动，无发散。
        converged = true;
    }

    @Override
    public void hooksChanged() {}

    @Override
    public void zeroRHS() {
        if (rhs != null) java.util.Arrays.fill(rhs, 0.0);
    }

    @Override
    public void zeroState() {
        if (state != null) java.util.Arrays.fill(state, 0.0);
    }

    @Override
    public void jacobianPrepareForWrite() {
        if (jacobian != null) {
            for (double[] row : jacobian) java.util.Arrays.fill(row, 0.0);
        }
    }

    @Override
    public void finishJacobianWrite() {}

    @Override
    public void rowExchange(boolean enable) {
        // 简化：不做行交换（部分主元 LU 已含主元策略）
    }

    @Override
    public boolean rowExchange() {
        return false;
    }

    @Override
    public IMatrixAccess stateVector() {
        return new IMatrixAccess() {
            @Override public void set(int r, int c, double v) {
                // 相量回写（PhasorPipeline → network.setValue）写入 state。
                // state 可能从未 allocate（网络无源/跳过 prepare）→ 懒分配扩容。
                // 节点索引在 addNode 时已分配（0..n-1），与 prepare 无关。
                cryptand$ensureState(r);
                state[r] = v;
            }
            @Override public double get(int r, int c) {
                if (state == null || r < 0 || r >= state.length) return 0;
                return state[r];
            }
            @Override public int numRows() {
                // 返回足够大：ElectricalNetwork.setValue 用 `idx >= stateVector().numRows()`
                // 做越界检查（idx=节点索引）。Cryptand 禁用 prepare → allocate 不调用 →
                // size 字段恒 0 → numRows()=0 → `idx >= 0` 恒成立 → setValue 静默失败
                // → 节点电压不回写 → 悬空端 0V → 虚假大电流烧线（[WireBurn] 实锤）。
                // set() 内部 cryptand$ensureState 会按实际节点索引懒分配扩容，越界安全。
                return Integer.MAX_VALUE;
            }
            @Override public int numCols() { return 1; }
        };
    }

    /** 确保 state 数组长度 ≥ idx+1（相量回写懒分配） */
    private void cryptand$ensureState(int idx) {
        if (idx < 0) return;
        if (state == null || idx >= state.length) {
            int newSize = Math.max(idx + 1, state == null ? 0 : state.length);
            double[] ns = new double[newSize];
            if (state != null) System.arraycopy(state, 0, ns, 0, state.length);
            state = ns;
            if (size < newSize) size = newSize;
        }
    }

    @Override
    public boolean isConverged() {
        // 时域禁用：不求解矩阵，但网络视为"已收敛"（节点电压由相量回写提供）。
        // 否则跳过 prepare/singleTick 后 converged 保持 false，PowerGrid 内部
        // 检查 isConverged() 的地方（同步包/设备逻辑）会误判为求解失败。
        return true;
    }
}
