package com.hdf.cryptand.dynamic.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * 核心声明的执行策略（**核心注册时提供**，框架据此调度该核心的运行方式）。
 *
 * @param parallel             可并行执行的阶段集合（默认 DISCOVER/PROBE/EXTRACT）
 * @param parallelInstantiate  是否允许并行 LOAD（默认 false —— 静态初始化可能触碰 MC 全局）
 * @param attachOnMain         ATTACH/DETACH 是否必须回主线程（默认 true）
 */
public record ExecutorPlan(Set<Stage> parallel, boolean parallelInstantiate, boolean attachOnMain) {

    public static final ExecutorPlan DEFAULT = new ExecutorPlan(
            EnumSet.of(Stage.DISCOVER, Stage.PROBE, Stage.EXTRACT), false, true);

    public ExecutorPlan {
        parallel = parallel == null ? Set.of() : Set.copyOf(parallel);
    }

    /** 该阶段是否并行（LOAD 需显式开启 parallelInstantiate）。 */
    public boolean parallelFor(Stage stage) {
        if (stage == Stage.LOAD) {
            return parallelInstantiate;
        }
        if (stage == Stage.ATTACH) {
            return !attachOnMain;
        }
        return parallel.contains(stage);
    }

    public static ExecutorPlan of(Stage... stages) {
        final EnumSet<Stage> s = EnumSet.noneOf(Stage.class);
        for (final Stage stage : stages) {
            s.add(stage);
        }
        return new ExecutorPlan(s, false, true);
    }
}
