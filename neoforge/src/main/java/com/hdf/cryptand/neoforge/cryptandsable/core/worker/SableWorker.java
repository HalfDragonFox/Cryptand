package com.hdf.cryptand.neoforge.cryptandsable.core.worker;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.OfficialRapierEngine;
import com.hdf.cryptand.neoforge.cryptandsable.core.destruction.DestructionEvent;
import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.RigidBodyState;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * CryptandSable 物理核心的 worker 线程。
 *
 * <p>职责：
 * <ul>
 *   <li>持有主线程→核心的【入站命令队列】与核心→主线程的【出站结果/事件队列】</li>
 *   <li>接收心跳包（携带推进预算 advanceSteps/budgetNanos）驱动核心时钟（C3/C13）</li>
 *   <li>在核心线程内执行物理步进（高频小步进自收敛 C14），绝不触碰 Level/BE</li>
 * </ul>
 *
 * <p>约定：所有 native（Rapier3D）调用只发生在本 worker 线程（C，批量发送 C12）。
 */
public final class SableWorker {
    private static final String THREAD_NAME = "cryptand-sable-physics";

    private final Queue<Runnable> commands = new ArrayDeque<>();
    /** ★ 2026-09-05 【消息池（用户定案）】：核心→主线程出站消息按 runtimeId 单条保留
     *  最新值（强制替换）。每条消息一个 key（PoseSnapshot→"pose:"+runtimeId；
     *  DestructionEvent→"dest:"+bodyId；ChunkActivationQuery→"act" 整包）。 */
    private final java.util.Map<String, Object> outbound = new java.util.LinkedHashMap<>();

    private final SableSimulationContext sim = new SableSimulationContext();

    private volatile Thread thread;
    private volatile boolean running = false;

    /** 最近一次心跳携带的推进预算。 */
    private volatile SableMessages.Heartbeat lastHeartbeat = new SableMessages.Heartbeat(0L, 1, 0L);

    /** 每 tick 模拟回调（供 simulator 钩入高频步进）。 */
    private volatile Consumer<Integer> stepCallback;

    /** ★ 激活查询周期（每 N 心跳发起一次区块加载查询；用户：异步线程每隔几次触发）。 */
    private static final int ACTIVATION_QUERY_INTERVAL = 5;
    private int heartbeatSinceQuery = 0;

    /** ★ 2026-09-05 【定时任务句柄】物理循环注册到线程分配器定时任务的句柄（stop 取消）。 */
    private volatile com.hdf.cryptand.circuitsimulation.compute.ScheduledHandle timerHandle;

    public synchronized void start() {
        // ★ 2026-09-02 冻结修复：重进世界若旧线程已死但 running 残留 true →
        //   原 start 直接 return → 永不重建线程（物理冻结）。此处：
        //   ①旧线程仍活 → 已在运行，直接返回（防重进双线程）
        //   ②旧线程已死 → 清理引用并复位 running → 重建全新线程
        // ★ 2026-09-05 【定时任务 + 独占标识】物理循环【注册到线程分配器定时任务】
        //   （TaskMode.PHYSICS_HIGH=独占直算、最高优先级）：分配器 timer 线程按周期
        //   到点提交 tickOnce → 常驻 Worker 直算（不占满 Worker——每次一个任务，
        //   按负载分配）；取消由 stop()（running=false → 句柄 cancel）。
        final Thread t = this.thread;
        if (t != null && t.isAlive()) {
            return;
        }
        this.thread = null;
        this.running = false;
        this.running = true;
        try {
            final com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher dsp =
                    com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers.get();
            if (this.timerHandle != null) this.timerHandle.cancel();
            // 物理周期 = 50ms（20Hz 与 MC tick 对齐）；独占（PHYSICS_HIGH）
            this.timerHandle = dsp.schedule(this::tickOnce,
                    com.hdf.cryptand.circuitsimulation.compute.TaskMode.PHYSICS_HIGH,
                    0, 50_000L);
        } catch (final Throwable tr) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] worker schedule to dispatcher failed: {}", tr.toString());
            this.running = false;
            return;
        }
    }

    public synchronized void stop() {
        this.running = false;
        // ★ 2026-09-05 【定时任务取消】取消物理循环定时任务（不再触发 tickOnce）。
        final com.hdf.cryptand.circuitsimulation.compute.ScheduledHandle th = this.timerHandle;
        if (th != null) {
            th.cancel();
            this.timerHandle = null;
        }
        final Thread t = this.thread;
        if (t != null) {
            LockSupport.unpark(t);
            // ★ 2026-09-02 冻结修复：Join 等线程真正退出（上限 2s），
            //   确保重进 start() 不会与残留旧线程双线程步进（native 竞争挂死）。
            final long deadline = System.nanoTime() + 2_000_000_000L;
            while (t.isAlive() && System.nanoTime() < deadline) {
                try {
                    t.join(50L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (t.isAlive()) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] worker thread not exited within 2s (will join on restart)");
            }
            this.thread = null;
        }
    }

    /** 投递一条命令到工作线程（主线程调用，非阻塞）。 */
    public void post(Runnable command) {
        synchronized (commands) {
            commands.add(command);
        }
        wake();
    }

    /** 接收心跳（主线程调用）。 */
    public void onHeartbeat(SableMessages.Heartbeat hb) {
        lastHeartbeat = hb;
        wake();
    }

    /** 取一条出站消息（主线程轮询）。 */
    public Object pollOutbound() {
        synchronized (outbound) {
            if (outbound.isEmpty()) return null;
            final Object m = outbound.values().iterator().next();
            outbound.remove(outbound.keySet().iterator().next());
            return m;
        }
    }

    /** 批量取出站消息。 */
    public int drainOutbound(java.util.List<Object> into) {
        synchronized (outbound) {
            int n = 0;
            for (final Object o : outbound.values()) {
                into.add(o);
                n++;
            }
            outbound.clear();
            return n;
        }
    }

    /** 向主线程投递出站消息（★ 2026-09-05 消息池：按消息类型+runtimeId 强制替换，
     *  未消费的同 key 旧消息被覆盖——保证主线程每 tick 消费到的一定是最新值）。 */
    public void emit(Object message) {
        final String key = messageKey(message);
        synchronized (outbound) {
            outbound.put(key, message);
        }
    }

    /** 消息池 key：PoseSnapshot → "pose:"+runtimeId；DestructionEvent → "dest:"+bodyId；
     *  ChunkActivationQuery → "act"（整包替换）；其他 → 类型名+identityHashCode（不可合并）。 */
    private static String messageKey(final Object message) {
        if (message instanceof SableMessages.PoseSnapshot ps) {
            return "pose:" + ps.runtimeId();
        }
        if (message instanceof DestructionEvent de) {
            return "dest:" + de.bodyId();
        }
        if (message instanceof SableMessages.ChunkActivationQuery) {
            return "act";
        }
        return message.getClass().getName() + ":" + System.identityHashCode(message);
    }

    public void setStepCallback(Consumer<Integer> cb) {
        this.stepCallback = cb;
    }

    /** worker 线程引用（供 isWorkerThread 判定）。 */
    public Thread currentThread() {
        return thread;
    }

    /** 当前调用线程是否即 worker。 */
    public boolean isWorkerThread() {
        return Thread.currentThread() == thread;
    }

    public SableSimulationContext simulation() {
        return sim;
    }

    public SableMessages.Heartbeat heartbeat() {
        return lastHeartbeat;
    }

    private void wake() {
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }

    /** ★ 2026-09-01 已消费心跳的 serverTick（防同一心跳在 0.2ms 空转循环中被重复消费 */
    private long lastConsumedServerTick = Long.MIN_VALUE;

    /** ★ 2026-09-05 【定时任务单次执行】物理循环每次被定时任务触发执行一次
     *  （分配器常驻 Worker 直算 = PHYSICS_HIGH 独占）；异常兜底不抛出（防 Worker 静默死）。 */
    private void tickOnce() {
        try {
            if (!this.running) return;      // 已停止 → 忽略
            this.thread = Thread.currentThread();
            loopOnce();
        } catch (Throwable t) {
            // ★ 2026-09-02 冻结修复：worker 任务绝不可因未捕获异常静默死亡
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] worker tick error (continue)", t);
        }
    }

    /** 单次循环体（loop 每轮调用；异常由 loop 兜底，绝不退出线程）。 */
    private void loopOnce() {
        {
            Runnable cmd;
            synchronized (commands) {
                cmd = commands.poll();
            }
            if (cmd != null) {
                try {
                    cmd.run();
                } catch (Throwable t) {
                    CryptandNeoForge.WAF_LOGGER.warn("[CryptandSable] worker command failed", t);
                }
                return;
            }

            // 无命令 → 按心跳推进预算执行物理步进
            SableMessages.Heartbeat hb = lastHeartbeat;
            int steps = hb.advanceSteps();
            if (steps > 0) {
                // ★ 2026-09-01 "几秒几万格"修复：心跳必须【每 tick 只消费一次】。
                //   之前空转循环每 0.2ms 都执行 step（advanceSteps 未清零）→
                //   每秒约 5000 次 step → 重力自由落体 250 倍速 → 几秒落几万格。
                //   官方：每游戏 tick（1/20s）只 prePhysicsTicks + physicsTick×substeps。
                if (hb.serverTick() == lastConsumedServerTick) {
                    LockSupport.parkNanos(200_000L);
                    return;
                }
                lastConsumedServerTick = hb.serverTick();
                steps = Math.min(steps, 64); // 防单 tick 无限推进
                Consumer<Integer> cb = stepCallback;
                if (cb != null) {
                    // ★ 2026-09-01 修正"瞬间到底"：stepCallback 内部已按 steps 子步
                    //   批量推进（SableSimulator.stepBatch(ctx, steps)，dt=BASE_DT/steps），
                    //   这里【只调用一次】——之前循环 steps 次调用 → 16 子步/tick
                    //   （4 倍速模拟）→ 结构下落瞬间完成无过程。
                    cb.accept(0);
                }
                // ★ 2026-09-06 【空间模型下禁用旧激活查询】官方引擎 = 每空间独立场景 +
                //   空间任务自我驱动阶段4（getPoseBatch 批量拉位姿）；旧 emitActivationQuery
                //   遍历 sim.bodies() 逐体 getPose —— 在空间任务【重建/删除 body】窗口并发调
                //   native getPose → Rust rigid_bodies Index panic（崩溃根因）。空间模型
                //   激活/坐标更新由空间任务负责，此旧路径在官方引擎模式下完全跳过。
                //   （自研模拟器路径仍保留 —— 依赖该查询做激活判定。）
                if (CryptandSable.instance() == null
                        || !CryptandSable.instance()
                                .isOfficialEngine()) {
                    if (++heartbeatSinceQuery >= ACTIVATION_QUERY_INTERVAL) {
                        heartbeatSinceQuery = 0;
                        emitActivationQuery();
                    }
                }
            }

            // 空闲则睡一小段（模拟轮询，间隔可配）
            LockSupport.parkNanos(200_000L); // 0.2ms
        }
    }

    /** 构造并上行动已登记物理体的激活查询（worker 线程；只收集数据不触碰 Level）。
     *  ★ 2026-09-05 用户铁律：每次计算必须从 Rust 获取真实绝对坐标，Java 禁止坐标更改。
     *  → 激活查询坐标从 native getPose 拉取（不再用 Java 占位 b.position——那是物理化
     *  初始快照，从不更新 → 发旧坐标 → 查询错区）。 */
    private void emitActivationQuery() {
        try {
            final OfficialRapierEngine eng =
                    CryptandSable.instance() != null
                            && CryptandSable.instance().isOfficialEngine()
                            ? CryptandSable.instance().officialEngine()
                            : null;
            final int n = this.sim.bodies().size();
            if (n <= 0) return;
            final java.util.List<Integer> ids = new java.util.ArrayList<>(n);
            final java.util.List<Double> xs = new java.util.ArrayList<>(n);
            final java.util.List<Double> ys = new java.util.ArrayList<>(n);
            final java.util.List<Double> zs = new java.util.ArrayList<>(n);
            for (final java.util.Map.Entry<Integer, RigidBodyState> e : this.sim.bodies().entrySet()) {
                final RigidBodyState b = e.getValue();
                if (b == null || b.isRemoved()) continue;
                double px = b.position.x, py = b.position.y, pz = b.position.z;
                // ★ 2026-09-05 从 Rust native 拉真实绝对坐标（该体仍物理化时）；
                //   Java 侧【绝不】改坐标——只读 native 权威值。
                if (eng != null) {
                    try {
                        final double[] store = new double[7];
                        eng.getPose(b.runtimeId, store);
                        if (store[0] != 0 || store[1] != 0 || store[2] != 0) {
                            px = store[0]; py = store[1]; pz = store[2];
                        }
                    } catch (final Throwable ignored) {
                    }
                }
                ids.add(b.runtimeId);
                xs.add(px);
                ys.add(py);
                zs.add(pz);
            }
            if (ids.isEmpty()) return;
            final int[] idArr = new int[ids.size()];
            final double[] xArr = new double[ids.size()];
            final double[] yArr = new double[ids.size()];
            final double[] zArr = new double[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                idArr[i] = ids.get(i);
                xArr[i] = xs.get(i);
                yArr[i] = ys.get(i);
                zArr[i] = zs.get(i);
            }
            emit(new SableMessages.ChunkActivationQuery(idArr, xArr, yArr, zArr));
        } catch (final Throwable t) {
            // 查询构造失败不阻断主体
        }
    }
}