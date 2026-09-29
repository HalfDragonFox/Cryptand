package com.hdf.cryptand.circuitsimulation.solver;

/**
 * 统一仿真时钟（2026-08-12 用户要求：仿真绑定实际时钟，伪时域）。
 * <p>
 * 伪时域语义（用户）：线程/网络更新仍每秒 20 次（每 tick 一次），但【仿真
 * 时间绑定实际时钟】——每次求解推进的 dt = 真实流逝时间：
 *   - 第一次调用：建立基准（simTime = 0，dt = 0）
 *   - 第二次调用：真实时间已过去 100ms → dt = 0.1s → simTime = 0.1s
 *   - 卡顿/慢：真实过了多久就推进多久 → 状态元件（电容/电感/温度/能量）
 *     按真实时间演进，与现实同步（不随 tick 率漂移）
 * <p>
 * 2026-08-20 主线程驱动 + 节流（用户要求）：
 *   - 【主线程驱动】时间只由主线程推进（{@link #tick()} 每 tick 一次），
 *     异步/后台线程只读（{@link #time()}/{@link #lastDt()}）——主线程卡死
 *     → 时间不再推进 → 异步线程收不到最新推进值 → 无法推进（安全机制，
 *     避免后台线程脱离主线程自行演化）。
 *   - 【节流】动力学（温度等慢变量）不必每 tick 推进：{@code tickThrottle}
 *     设置推进次数（如 10 = 每 10 tick 推进一次），期间 dt 累积，
 *     到节拍点一次性推进（{@link #consumeTick()} 返回累积 dt）。
 *   - 矩阵求解的稳态网络可跳过求解，但温度等依赖时间的动力学模型
 *     仍按节流推进（电压稳态 ≠ 温度稳态）。
 * <p>
 * 状态元件（电容/电感/半导体/温度/能量）在求解后用 {@link #time()} 推进状态，
 * 下轮求解用更新后的状态 → 系统渐进演进（瞬态：开机/负载切换/电容充放电）。
 * 线性无记忆元件（电阻/源/导线）不依赖时间（相量稳态）。
 * <p>
 * 用法（主线程每 tick）：
 *   <pre>
 *   double dt = simClock.tick();          // 主线程：推进真实时间（节流内部处理）
 *   // 求解循环（主线程或异步线程）：
 *   net.dt = simClock.lastDt(); net.time = simClock.time(); // 只读，不推进
 *   solver.solve(net);                    // 求解当前状态下的稳态
 *   </pre>
 */
public final class SimClock implements TimeBase {

    /** 上次推进时间戳（ns）；0 = 未建立基准。
     *  ⚠ 2026-08-30 审计（thread 低危）：volatile——主线程 tick() 写、
     *  后台求解线程 time()/lastDt() 只读，无 volatile 则 JMM 数据竞争
     *  （后台可能读到陈旧仿真时间/步长）。 */
    private volatile long lastNanos;
    /** 累计仿真时间（s），绑定实际时钟（仅主线程推进） */
    private volatile double time;
    /** 步长钳制（真实时间，防暂停/长卡顿后跳变爆炸） */
    private static final double MIN_DT = 0.01, MAX_DT = 1.0;

    /** 节流：每 N 次 tick 才实际推进一次（2026-08-20 用户要求）。
     *  默认 1 = 每 tick 推进（行为与旧一致）；设 10 = 每 10 tick（0.5s）推进一次。 */
    private int tickThrottle = 1;
    /** 当前节流窗口内累计的 tick 数 */
    private int throttleCount;
    /** 节流窗口内累积的真实时间（s），到节拍点一次性并入 simTime。
     *  ⚠ 2026-08-30 审计：volatile（与 time/lastNanos 同理，跨线程可见）。 */
    private volatile double pendingDt;

    /**
     * 推进预算（2026-08-20 用户要求：推进次数与异步线程每秒计算次数有关）。
     * <p>
     * 消费者模型（令牌桶，2026-08-20 用户建议"参考消费者模型防主线程卡死
     * 异步线程跑飞"）：
     *   - 【生产者 = 主线程】每 tick 调 {@link #tick(int)} 补充预算——budget =
     *     ceil(异步线程每秒计算次数 / 主线程每秒 tick 数)（如异步 100Hz / 20 tick =
     *     每 tick 5 次）；
     *   - 【消费者 = 异步求解线程】每次矩阵求解调 {@link #consumeStep()} 消耗 1：
     *     预算 > 0 → true（推进）；预算 = 0 → false（本 tick 已用完推进次数）。
     *   - 【有界】补充带上限 {@link #budgetCap}（= budgetPerTick×4）——即使主线程
     *     卡死前消费慢累积了预算，最多也只够再跑 cap 次，跑完即停（不无限跑飞）；
     *   - 【原子】remainingBudget 用 {@link java.util.concurrent.atomic.AtomicInteger}，
     *     CAS 消耗，多异步线程并发安全；
     *   - 【心跳看门狗】主线程每 tick 更新 {@link #lastTickNanos}，消费者
     *     consumeStep 先查心跳：超过 {@link #HEARTBEAT_TIMEOUT_NS} 未 tick →
     *     判定主线程卡死 → 立即拒绝推进（即使预算未耗尽）——严格防跑飞。
     */
    private final java.util.concurrent.atomic.AtomicInteger remainingBudget =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 每 tick 推进预算（主线程设置）：异步线程每秒计算次数 / 主线程每秒 tick 数 */
    private volatile int budgetPerTick = 1;
    /** 预算上限（cap = budgetPerTick×4，防无限累积） */
    private volatile int budgetCap = 1;
    /** 主线程最近一次 tick 的纳秒时间戳（心跳；异步线程据此判定主线程是否卡死） */
    private volatile long lastTickNanos;
    /** 心跳超时（ns）：主线程超过此时间未 tick → 判定卡死 → 停止推进 */
    private static final long HEARTBEAT_TIMEOUT_NS = 500_000_000L; // 0.5s

    /**
     * 【主线程驱动】每 tick 调用一次：推进真实时间 + 补充推进预算。
     * 预算 = 每 tick 推进次数（由调用方按异步频率/主线程 tick 率计算传入；
     * 见 {@link #setBudgetPerTick}）。返回本次应推进的 dt。
     */
    public double tick(int budgetPerTick) {
        this.budgetPerTick = Math.max(0, budgetPerTick);
        this.budgetCap = Math.max(this.budgetPerTick * 4, 1); // 上限 = 4 tick 预算
        // 补充预算（带 cap，CAS 循环：主线程唯一生产者，但保持原子语义）
        while (true) {
            int cur = remainingBudget.get();
            int next = Math.min(cur + this.budgetPerTick, budgetCap);
            if (remainingBudget.compareAndSet(cur, next)) break;
        }
        lastTickNanos = System.nanoTime(); // 心跳更新（主线程活着）
        return tick();
    }

    /** 【主线程驱动】每 tick 调用一次（无预算补充，仅推进时间）。 */
    public double tick() {
        long now = System.nanoTime();
        double dt;
        if (lastNanos == 0) {
            dt = 0; // 第一次：建立基准，仿真时间 = 0
        } else {
            double elapsed = (now - lastNanos) / 1e9;
            dt = Math.min(MAX_DT, Math.max(MIN_DT, elapsed));
        }
        lastNanos = now;
        pendingDt += dt;
        throttleCount++;
        if (throttleCount >= tickThrottle) {
            time += pendingDt;      // 节拍点：一次性并入
            double out = pendingDt;
            pendingDt = 0;
            throttleCount = 0;
            lastEmittedDt = out;
            return out;
        }
        lastEmittedDt = 0; // 节流中：本次不推进
        return 0;
    }

    /** 每 tick 推进预算（主线程设置）：异步线程每秒计算次数 / 主线程每秒 tick 数。 */
    public void setBudgetPerTick(int n) {
        this.budgetPerTick = Math.max(0, n);
        this.budgetCap = Math.max(this.budgetPerTick * 4, 1);
    }

    public int budgetPerTick() { return budgetPerTick; }

    /** 当前剩余预算（异步线程可读）。 */
    public int remainingBudget() { return remainingBudget.get(); }

    /**
     * 消耗 1 次推进预算（求解循环每次调用；异步线程安全，CAS 原子）。
     * <p>
     * 消费者模型入口：先查【心跳看门狗】（主线程最近 tick 距今超过
     * {@link #HEARTBEAT_TIMEOUT_NS} → 主线程疑似卡死 → 拒绝推进，即使预算未
     * 耗尽——严格防跑飞），再 CAS 消耗预算。
     *
     * @return true = 预算充足且主线程心跳正常（可推进本次求解）；
     *         false = 预算用完 / 主线程心跳超时（本 tick 停止）
     */
    @Override
    public boolean consumeStep() {
        // 心跳看门狗：主线程卡死 → 异步线程立即停止（预算未耗尽也不推进）
        if (System.nanoTime() - lastTickNanos > HEARTBEAT_TIMEOUT_NS) return false;
        while (true) {
            int cur = remainingBudget.get();
            if (cur <= 0) return false;
            if (remainingBudget.compareAndSet(cur, cur - 1)) return true;
        }
    }

    /** 推进步长（单步）：预算够且心跳正常 → 用最近 dt；否则 0。 */
    public double stepDt() {
        if (System.nanoTime() - lastTickNanos > HEARTBEAT_TIMEOUT_NS) return 0;
        return remainingBudget.get() > 0 ? Math.max(lastEmittedDt, 0.05) : 0;
    }

    /** 上次 tick 实际推进的 dt（s）；节流期间为 0（只读，异步线程用） */
    private volatile double lastEmittedDt;

    /** 上次 tick 实际推进的 dt（s，只读） */
    public double lastDt() { return lastEmittedDt; }

    /** 主线程心跳是否存活（最近 tick 距今 ≤ HEARTBEAT_TIMEOUT_NS）。
     *  ⚠ 2026-08-30 求解轮时间基准改【真实流逝】用：主线程卡死 → 心跳超时 →
     *  求解轮不推进状态（保留"时间只由主线程驱动"的安全语义）。
     *  ⚠ 2026-08-30 每实例兼容：lastTickNanos==0（独立实例 / 从未 tick）→ 无
     *  主线程心跳约束 → 视为存活（纯真实流逝推进）——仿真核心每实例用独立
     *  SimClock（EDA/多客户端）时不依赖主线程 tick；MC 主线程 tick 后非 0 →
     *  卡死超时 → false（安全门控生效）。 */
    public boolean isHeartbeatAlive() {
        return lastTickNanos == 0
                || System.nanoTime() - lastTickNanos <= HEARTBEAT_TIMEOUT_NS;
    }

    /** TimeBase 接口实现（2026-09 补全契约）：真实时间模式 = 主线程心跳存活 */
    @Override
    public boolean isAlive() {
        return isHeartbeatAlive();
    }

    /** ⚠ 2026-08-30 真实流逝基准（TimeBase 接口实现）：返回距上次调用本方法的
     * 真实时间差（s；首轮默认 0.05；clamp [0.01, 1.0]）。主线程心跳超时 →
     * 0（不推进状态，保留"时间只由主线程驱动"安全语义）。
     * 状态推进（solveAll → advancePseudoTime）统一经本方法获取 dt——与求解
     * 轮次解耦：轮多 dt 小、轮少 dt 大 → 状态演化绑定真实时间（1 倍速），
     * 不因求解频率（上限 100Hz，实际轮次由计算速度决定）而超速/减速。 */
    private volatile long lastAdvanceNanos;

    @Override
    public double advance() {
        if (!isHeartbeatAlive()) return 0; // 主线程卡死 → 不推进
        long now = System.nanoTime();
        long last = lastAdvanceNanos;
        if (last == 0) {
            lastAdvanceNanos = now;
            return 0.05; // 首轮默认（未建立基准）
        }
        double dt = Math.min(1.0, Math.max(0.01, (now - last) / 1e9));
        lastAdvanceNanos = now;
        return dt;
    }

    /** 当前累计仿真时间（s，绑定实际时钟，仅主线程推进；异步线程只读） */
    public double time() { return time; }

    /** 设置节流：每 N 次 tick 推进一次动力学（1 = 每 tick，默认） */
    public void setTickThrottle(int n) {
        this.tickThrottle = Math.max(1, n);
        this.throttleCount = 0;
        this.pendingDt = 0;
    }

    public int tickThrottle() { return tickThrottle; }

    /** 重置（世界重载/新网络） */
    public void reset() {
        lastNanos = 0;
        time = 0;
        pendingDt = 0;
        throttleCount = 0;
        lastEmittedDt = 0;
        remainingBudget.set(0);
        budgetPerTick = 1;
        budgetCap = 1;
        lastTickNanos = 0;
        lastAdvanceNanos = 0; // ⚠ 2026-08-30：清真实流逝基准
    }

    @Override
    public String toString() {
        return "SimClock{t=" + String.format("%.1f", time) + "s throttle="
                + tickThrottle + " budget=" + budgetPerTick + "/tick left="
                + remainingBudget + "}";
    }
}
