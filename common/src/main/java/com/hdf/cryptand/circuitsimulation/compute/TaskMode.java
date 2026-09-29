package com.hdf.cryptand.circuitsimulation.compute;

/**
 * 任务执行模式（2026-08-16 用户要求：虚拟线程支持）。
 * <p>
 * 每个 Worker（常驻平台线程）可管理一批虚拟线程（默认每线程最大 1000，可配置）：
 *   - {@link #NORMAL}（默认）：任务走虚拟线程执行——Worker 收到任务后若虚拟线程
 *     数量未满则创建虚拟线程执行，否则继续排队等待空闲虚拟线程。
 *   - {@link #EXCLUSIVE}：独占模式，跳过虚拟线程，直接在 Worker 常驻线程上执行。
 *     ⚠ 一般不要使用：独占任务在 Worker 上串行执行，长任务会阻塞该 Worker 的
 *     虚拟线程调度，很容易卡死。
 * <p>
 * 优先级（2026-08-30 用户：任务可按优先级处理；负载动态平衡，无需担心其他任务）：
 *   - 队列按 {@link #priority} 从高到低排序（同优先级 FIFO）。
 *   - 物理（Sable）STEP 任务用高优先级（{@link #PRI_PHYSICS_HIGH}），
 *     确保物理 tick 不被普通计算任务阻塞（阻塞物理 = 主线程等待 = 卡）。
 *   - 后续线程分配器可按优先级/负载自动分配（用户愿景）。
 */
public enum TaskMode {

    /** 普通模式：走虚拟线程执行（默认）。 */
    NORMAL(0),

    /** 独占模式：跳过虚拟线程，直接 Worker 常驻线程优先执行（不建议，易卡死）。 */
    EXCLUSIVE(10),

    /** 物理（Sable）STEP 独占优先级（2026-08-30：最高优先，防物理 tick 被阻塞）。 */
    PHYSICS_HIGH(20);

    /** 优先级（越大越优先；0 = 默认） */
    public final int priority;

    TaskMode(int priority) {
        this.priority = priority;
    }
}
