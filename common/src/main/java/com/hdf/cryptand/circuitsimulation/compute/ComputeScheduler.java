package com.hdf.cryptand.circuitsimulation.compute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 计算调度器 —— 把任务自动分配到最合适的引擎。
 *
 * 选择策略：
 *  1. 过滤：引擎类型命中 task.engineMask，且引擎 quality >= task.minQuality
 *  2. 排序：质量等级最高（能满足且最“好”）→ 空载优先
 *  3. 执行：调用 engine.execute(task)
 *
 * 未来扩展：加权负载均衡、任务分片、多引擎并行、失败回退（高等级→低等级）。
 */
public final class ComputeScheduler {
    private final CopyOnWriteArrayList<ComputeEngine> engines = new CopyOnWriteArrayList<>();

    public void register(ComputeEngine e) {
        engines.add(e);
        // 按类型分组排序：CPU 在 GPU 前注册无妨，选择时按 quality 排序
    }

    public void unregister(ComputeEngine e) {
        engines.remove(e);
    }

    public List<ComputeEngine> engines() { return new ArrayList<>(engines); }

    /** 自动分配并执行。返回实际执行的引擎描述。 */
    public ComputeResult execute(ComputeTask task) {
        List<ComputeEngine> candidates = new ArrayList<>();
        for (ComputeEngine e : engines) {
            if (e.canExecute(task)) candidates.add(e);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No engine can execute task " + task.id + " (mask=" + task.engineMask
                    + ", minQuality=" + task.minQuality + ")");
        }
        // 质量从高到低选择（未来可改为负载感知）
        candidates.sort(Comparator.comparingInt((ComputeEngine e) -> e.quality().level).reversed());
        ComputeEngine best = candidates.get(0);
        return best.execute(task);
    }

    public void close() {
        for (ComputeEngine e : engines) {
            try { e.close(); } catch (Exception ignored) { }
        }
        engines.clear();
    }

    @Override
    public String toString() {
        return "ComputeScheduler{engines=" + engines.size() + "}";
    }
}
