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
 */
public enum TaskMode {

    /** 普通模式：走虚拟线程执行（默认）。 */
    NORMAL,

    /** 独占模式：跳过虚拟线程，直接 Worker 常驻线程优先执行（不建议，易卡死）。 */
    EXCLUSIVE
}
