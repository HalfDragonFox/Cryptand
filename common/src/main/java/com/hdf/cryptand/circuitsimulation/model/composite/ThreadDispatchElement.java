package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

import java.util.concurrent.CompletableFuture;

/**
 * 支持额外线程分发的复合元件基类（2026-08-12 用户要求）。
 * <p>
 * 【可选】中间基类：只给【需要单独线程计算】的复合元件使用（如变压器等多回路
 * 设备的温度推进），普通设备模型（电机/仪表等）【不】继承——保持最小侵入。
 * <p>
 * 某些复合元件的部分计算——如温度推进——不必紧跟主线程求解同步执行，可以
 * 【单独打包提交】给 {@link ThreadDispatcher}（线程分配器）在 Worker 线程上
 * 计算：不阻塞/占用主线程，且统一走计算引擎入口（未来可对接 C++/GPU/集群
 * {@code ComputeEngine} 而调度层不变）。
 * <p>
 * 继承 {@link CompositeModel}（设备复合模型基类：stamp 委托 + 统一生命周期），
 * 额外提供线程分发能力：
 *   - {@link #dispatch(ThreadDispatcher, Complex[], double, long)} —— 单独提交
 *     计算任务（默认在 Worker 上执行 {@link #computeOnWorker}：损耗 → 温度推进）
 *   - 可注册到 {@link com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchManager}，
 *     由管理器批量提交列表中所有元件
 * <p>
 * 多回路设备（变压器）覆写 {@link #computeOnWorker} 处理多个回路（原边/副边）
 * 的损耗与温度；单回路设备直接用默认两端口实现。
 */
public abstract class ThreadDispatchElement extends CompositeModel {

    /** 是否启用线程分发（默认 true；需要主线程直算时置 false → dispatch 走直算兜底） */
    protected volatile boolean dispatchEnabled = true;

    protected ThreadDispatchElement(Element[] simple) {
        super(simple);
    }

    protected ThreadDispatchElement(Element[] simple, ThermalModel thermal) {
        super(simple, thermal);
    }

    /** 是否启用线程分发 */
    public boolean dispatchEnabled() { return dispatchEnabled; }

    /** 启用/关闭线程分发（关闭时 dispatch 在主线程直算兜底，不丢失计算） */
    public void setDispatchEnabled(boolean on) { this.dispatchEnabled = on; }

    /**
     * 单独提交计算任务给线程分配器（温度等额外计算通过此接口）。
     * <p>
     * nodeVoltages = 求解结果的节点相量数组（只读快照，Worker 上计算用）；
     * 无分发器 / 未启用 → 主线程直算兜底（结果不丢失）。
     * 返回 future：调用方可按需等待完成（默认异步不等待）。
     */
    public CompletableFuture<Void> dispatch(ThreadDispatcher dispatcher,
                                            Complex[] nodeVoltages, double omega, long nowNanos) {
        final Complex[] vs = nodeVoltages;
        final double om = omega;
        final long now = nowNanos;
        if (dispatcher == null || !dispatchEnabled) {
            try {
                computeOnWorker(vs, om, now);
            } catch (Throwable ignored) {
            }
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.submitGeneric(() -> computeOnWorker(vs, om, now));
    }

    /**
     * 在 Worker 线程上执行的额外计算（默认：两端口损耗功率 → 温度推进）。
     * <p>
     * 多回路设备（变压器原边/副边、三相设备等）覆写本方法，从 nodeVoltages
     * 取各回路端口相量电压 → 分别算损耗 → 分别推进各回路温度模型。温度通过
     * 此接口计算（用户要求）。默认实现仅处理单回路（nodeA/nodeB 端口）。
     */
    protected void computeOnWorker(Complex[] nodeVoltages, double omega, long nowNanos) {
        int a = nodeA(), b = nodeB();
        if (thermal() != null && a >= 0 && a < nodeVoltages.length
                && b >= 0 && b < nodeVoltages.length) {
            thermal().advance(lossPower(nodeVoltages[a], nodeVoltages[b], omega), nowNanos);
        }
    }
}
