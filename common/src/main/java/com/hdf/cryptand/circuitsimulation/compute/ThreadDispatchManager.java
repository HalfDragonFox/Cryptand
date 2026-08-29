package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.composite.ThreadDispatchElement;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程分发管理器（2026-08-12 用户要求；2026-08-22 触发模式增强）。
 * <p>
 * 维护【支持额外线程分发】的复合元件列表（{@link ThreadDispatchElement}，如
 * 变压器等多回路设备）。向线程分配器 {@link ThreadDispatcher} 批量提交列表中
 * 所有元件的内容（每个元件单独打包为一个分发计算任务 → Worker 线程计算温度等）。
 * <p>
 * 【触发模式（2026-08-22 用户要求：能走触发模式就走触发，提高线程使用率）】：
 * 外部数据源变化时（电压/参数更新）调 {@link #notifyDataChanged} —— 经全局
 * {@link TriggerDispatcher} 触发一次【聚合】批量提交（可丢失合并：多次变化聚合
 * 成一次处理，只处理最后一次载荷，空闲零占用、不主动轮询）。周期兜底仍可用
 * {@link #submitAll}。
 * <p>
 * 线程安全：{@link CopyOnWriteArrayList}，构建线程（主线程）注册/网络重建清理，
 * 求解/触发线程批量提交——并发安全。
 */
public final class ThreadDispatchManager {

    /** 分发电元件列表（网络收集时注册；重建/重置时清理） */
    private final List<ThreadDispatchElement> elements = new CopyOnWriteArrayList<>();

    private final AtomicLong submitted = new AtomicLong();   // 真实提交次数（非空表）+ 计数
    private final AtomicLong handled = new AtomicLong();     // 触发 handler 被调度处理次数（诊断：验证触发链）
    private volatile TriggerVector trig;
    private final Object trigLock = new Object();

    /** 注册分发电元件（去重） */
    public void register(ThreadDispatchElement e) {
        if (e != null && !elements.contains(e)) elements.add(e);
    }

    /** 注销分发电元件 */
    public void unregister(ThreadDispatchElement e) {
        if (e != null) elements.remove(e);
    }

    /** 清空（网络重建/重置时调用） */
    public void clear() { elements.clear(); }

    /** 当前列表大小 */
    public int size() { return elements.size(); }

    /** 列表快照 */
    public List<ThreadDispatchElement> list() { return elements; }

    /** 电压提供者：为每个分发电元件提供其所属网络求解结果的节点相量数组 */
    public interface VoltageProvider {
        /** 该元件的节点相量数组（求解结果）；null = 跳过该元件 */
        Complex[] voltagesFor(ThreadDispatchElement e);
    }

    // ==================== 触发模式（2026-08-22） ====================

    /** 触发批量提交的载荷（每次数据变化的提交上下文） */
    private static final class FlushCtx {
        final VoltageProvider vp;
        final double omega;
        final long nowNanos;

        FlushCtx(VoltageProvider vp, double omega, long nowNanos) {
            this.vp = vp;
            this.omega = omega;
            this.nowNanos = nowNanos;
        }
    }

    /**
     * 数据变化触发批量提交（任何线程调用，通常求解/数据源变化后）。
     * 经全局触发调度器：只触发一次聚合处理（多次变化合并，只提交最后一次载荷）——
     * 无变化时不主动轮询，提高线程使用率。周期兜底仍用 {@link #submitAll}。
     */
    public void notifyDataChanged(VoltageProvider vp, double omega, long nowNanos) {
        if (vp == null) return; // 空列表也允许触发（handler 内 submitAll 空表 no-op）
        ThreadDispatchers.triggers().trigger(aggVector(), new FlushCtx(vp, omega, nowNanos));
    }

    /** 惰性注册聚合触发向量（载荷版：收到聚合载荷 → 批量提交） */
    private TriggerVector aggVector() {
        TriggerVector v = trig;
        if (v == null) {
            synchronized (trigLock) {
                v = trig;
                if (v == null) {
                    v = ThreadDispatchers.triggers().vector("ThreadDispatchManager",
                            (Object payload) -> {
                                handled.incrementAndGet(); // 触发被调度处理（诊断）
                                if (payload instanceof FlushCtx ctx) {
                                    submitAll(ThreadDispatchers.get(), ctx.vp,
                                            ctx.omega, ctx.nowNanos);
                                }
                            }, 0);
                    trig = v;
                }
            }
        }
        return v;
    }

    /** 触发模式已登记的聚合向量（可能 null 未用）——诊断 */
    public TriggerVector triggerVector() { return trig; }

    /** 触发 handler 被调度处理次数（诊断：验证"数据变化 → 触发链"是否生效） */
    public long triggerHandled() { return handled.get(); }

    /** 触发模式累计提交次数（诊断：验证"有变化才提交"） */
    public long triggerSubmissions() { return submitted.get(); }

    // ==================== 周期批量提交（兜底；原逻辑） ====================

    /**
     * 向线程分配器批量提交列表中所有元件的计算（每个元件单独分发任务）。
     * 触发模式下由 {@link #notifyDataChanged} 驱动（变化才提交）；调用方也可
     * 按需周期调用本方法兜底（如初始化/强制刷新）。
     */
    public void submitAll(ThreadDispatcher dispatcher, VoltageProvider vp,
                          double omega, long nowNanos) {
        if (dispatcher == null || vp == null || elements.isEmpty()) return;
        submitted.incrementAndGet();
        for (ThreadDispatchElement e : elements) {
            try {
                Complex[] vs = vp.voltagesFor(e);
                if (vs != null) e.dispatch(dispatcher, vs, omega, nowNanos);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 向线程分配器批量提交（所有元件共用同一电压数组，如单网络场景）。便捷重载。 */
    public void submitAll(ThreadDispatcher dispatcher, Complex[] nodeVoltages,
                          double omega, long nowNanos) {
        if (dispatcher == null || elements.isEmpty()) return;
        submitted.incrementAndGet();
        for (ThreadDispatchElement e : elements) {
            try {
                e.dispatch(dispatcher, nodeVoltages, omega, nowNanos);
            } catch (Throwable ignored) {
            }
        }
    }
}
