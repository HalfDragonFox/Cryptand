package com.hdf.cryptand.neoforge.cryptandsable.server;

/**
 * 服务器心跳 + 推进预算控制器（SableServerHeartbeat）。
 *
 * <p>主线程每 tick 调用 {@link #tick(long, long)}，根据上一物理 tick 耗时动态计算
 * 推进预算（advanceSteps + budgetNanos），再发给核心（C13）。
 *
 * <p>策略（非常简单但稳）：
 * <ul>
 *   <li>目标模拟率 targetHz（默认 80Hz = 4 小步/20Hz）</li>
 *   <li>若物理 tick 耗时 > 预算 → 降步保帧（动态下调）</li>
 *   <li>若充分空闲 → 逐步恢复目标步数</li>
 *   <li>帧预算（主世界 tick 周期）→ 防物理吃满主线程</li>
 * </ul>
 *
 * <p>本类在主线程（Server）运行，只计算数值不碰核心状态（数据归属矩阵：预算 = 纯控制）。
 */
public final class SableServerHeartbeat {

    /** 目标：每个游戏 tick 推进的子步数（80Hz 模拟）。 */
    public static final int TARGET_STEPS = 4;
    /** 单子步预算（约 80Hz 的允许最大耗时）。 */
    private static final long STEP_BUDGET_NANOS = 4_000_000L; // 4ms

    private int currentSteps = TARGET_STEPS;
    private int lowFrames = 0;

    /** 最近一次心跳预算（供调试/输出）。 */
    private final long[] lastBudget = new long[2];

    public SableServerHeartbeat() {
        lastBudget[0] = currentSteps;
        lastBudget[1] = 0;
    }

    /**
     * 每游戏 tick 调用（主线程）。
     *
     * @param serverTick  服务端 tick 计数
     * @param lastPhysNanos 上一次物理核心本 tick 实际耗时（出站消息里带；0=未知）
     */
    public SableBudget tick(long serverTick, long lastPhysNanos) {
        // 自适应：物理耗时超预算 → 降步；空闲恢复（滞回）
        if (lastPhysNanos > STEP_BUDGET_NANOS * currentSteps) {
            currentSteps = Math.max(1, currentSteps - 1);
            lowFrames = 0;
        } else if (++lowFrames >= 20 && currentSteps < TARGET_STEPS) {
            currentSteps++;
            lowFrames = 0;
        }

        long budget = currentSteps * STEP_BUDGET_NANOS;
        lastBudget[0] = currentSteps;
        lastBudget[1] = budget;
        return new SableBudget(serverTick, currentSteps, budget);
    }

    public long[] lastBudget() {
        return lastBudget;
    }

    /** 心跳预算（纯数据，主线程→核心）。 */
    public record SableBudget(long serverTick, int steps, long budgetNanos) {
    }
}