/**
 * ===== 外设客户端异步核心（2026-09-13 用户定稿）=====
 *
 * 用户原话："客户端的异步核心按照固定频率处理和发送，比如客户端40次就是一秒四十次执行，
 * 服务端20次就是每秒20次接收，然后客户端只需要每次在此异步线程中串行遍历计算即可。
 * 接收处理->计算->发送类似这样的" + "可以使用我们的多线程分配器处理"
 *
 * <p>结构（<b>不自建线程</b>，走项目统一的多线程分配器 {@code ThreadDispatchers}）：
 * <pre>
 *   ThreadDispatchers.schedule(EXCLUSIVE, period = 1/peripheralClientCoreHz)   // 默认 40Hz
 *     └─ pump() 在【常驻 Worker 线程上串行执行】：
 *          ① 接收处理：服务端下发的 sable 数据已由 payload 写入各 BE 的 volatile 字段（这里直接读）
 *          ② 计算　　：串行遍历所有已注册外设方块 → 采样设备 → 舵角/档位 → 力反馈通道
 *          ③ 发送　　：把上行队列投递到主线程发送（网络包只能在主线程发）
 * </pre>
 *
 * <p>为什么用 {@code EXCLUSIVE}：用户要的是"在<b>此异步线程</b>中<b>串行</b>遍历"——
 * EXCLUSIVE 保证同一常驻线程串行执行（NORMAL 走虚拟线程池，周期之间可能换线程）。
 * 本任务极短（遍历几十个方块，微秒级），不会阻塞该 Worker（分配器注释里的"长任务"警告不适用）。
 *
 * <p>与设备池（{@code PeripheralHelmInput}，自带 SDL 线程）分工：核心只做计算与编排，
 * 两者通过无锁快照交互；上行频率由 {@link PeripheralSessionClient} 在核心周期内按各自节奏排队。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PeripheralCore {

    /** 已注册的外设方块（key = BlockPos.asLong；主线程注册/注销，核心线程遍历） */
    private static final Map<Long, PeripheralTickable> TICKABLES = new ConcurrentHashMap<>();

    /**
     * 统一出站队列（用户定稿："每次UI更新数据到服务器也是走这个异步核心，方便管理"）。
     * <p>任何线程都可以把"需要在主线程执行的发送动作"postSend 进来，
     * 由核心周期统一投递到主线程 —— 会话上行、UI 的连接/断开/保存、指令回包全走这一条通道，
     * 出站因此只有一处可管理、可诊断。
     */
    private static final java.util.Queue<Runnable> OUTBOX = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * 内部消息队列（入站）：任意线程可投递一条"给核心处理的消息"，由核心在自己的周期里
     * 于【接收处理】阶段串行消化。这是外设各线程之间唯一的协调手段 —— 无锁、无共享状态标志。
     */
    private static final java.util.Queue<Runnable> INBOX = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final AtomicBoolean scheduled = new AtomicBoolean(false);
    private static volatile com.hdf.cryptand.circuitsimulation.compute.ScheduledHandle handle;

    /** 上一次核心周期真正执行完的时刻（ms）。看门狗据此判断"周期是否静默停摆"。 */
    private static volatile long lastPumpAt = System.currentTimeMillis();

    /**
     * 判定"周期已停摆"的阈值（ms）。正常 40Hz ⇒ 25ms 一次；
     * 给足 20 倍余量，避免 GC/加载卡顿被误判。
     */
    private static final long STALL_TIMEOUT_MS = 500L;

    private PeripheralCore() {
    }

    /** 注册一个外设方块（客户端；方块加载时调用，重复注册幂等）。首次注册即启动核心周期。 */
    public static void register(long key, PeripheralTickable tickable) {
        if (tickable == null) {
            return;
        }
        TICKABLES.put(key, tickable);
        ensureScheduled();
    }

    /** 注销（方块被移除 / 区块卸载）。 */
    public static void unregister(long key) {
        TICKABLES.remove(key);
    }

    public static int registeredCount() {
        return TICKABLES.size();
    }

    public static boolean isScheduled() {
        return scheduled.get();
    }

    /**
     * 把"主线程发送动作"投递进核心出站队列（任意线程可调）。
     * <p>核心尚未启动时（例如还没放任何外设方块就打开了界面）直接投递主线程执行，保证不丢包。
     */
    public static void postSend(Runnable action) {
        if (action == null) {
            return;
        }
        if (!scheduled.get()) {
            net.minecraft.client.Minecraft.getInstance().execute(action);
            return;
        }
        OUTBOX.add(action);
    }

    /**
     * 向核心投递一条内部消息（任意线程；无锁）。
     * <p>用途示例：设备池扫描完成后通知核心"设备表已更新"、界面请求立即刷新等 ——
     * 全部以消息表达，核心不需要去轮询任何状态标志。
     */
    public static void postMessage(Runnable message) {
        if (message != null) {
            INBOX.add(message);
        }
    }

    /** 入站消息队列长度（诊断用） */
    public static int inboxSize() {
        return INBOX.size();
    }

    /**
     * 消息处理：设备表已更新 → 清掉"方块已不存在"的会话计时记录。
     * <p>由设备池在扫描完成后经 {@link #postMessage} 投递（无锁消息，核心在自己的周期里执行）。
     */
    public static void pruneStaleSessions() {
        PeripheralSessionClient.pruneExcept(TICKABLES.keySet());
    }

    /** 接收处理阶段：串行消化入站消息（在核心线程执行）。 */
    private static void drainInbox() {
        Runnable message;
        int guard = 0;
        while ((message = INBOX.poll()) != null && guard++ < 256) {
            try {
                message.run();
            } catch (Throwable ignored) {
                // 单条消息出错不影响后续
            }
        }
    }

    /** 出站队列长度（诊断用） */
    public static int outboxSize() {
        return OUTBOX.size();
    }

    /**
     * 发送阶段：把出站队列投递到主线程执行（网络包只能在主线程发）。
     * <p>⚠ 关键优化（2026-09-14 用户："消息机制进行两个线程交互" / "扫描时执行消息机制交互"）：
     * <b>一个核心周期只投递一个 Runnable</b>，由它批量 drain 整个队列 ——
     * 旧实现是"每个动作投递一次"，40Hz × 每方块一条会把主线程任务队列塞满，
     * 扫描期间池一抖动投递更密，跨线程交互开销被成倍放大。
     */
    private static void flushOutbox() {
        if (OUTBOX.isEmpty()) {
            return;
        }
        net.minecraft.client.Minecraft.getInstance().execute(PeripheralCore::drainOutbox);
    }

    /** 在主线程里批量执行出站动作（一次投递，批量发送）。 */
    private static void drainOutbox() {
        Runnable action;
        int guard = 0;
        while ((action = OUTBOX.poll()) != null && guard++ < 1024) {
            try {
                action.run();
            } catch (Throwable ignored) {
                // 单条发送失败不影响其它
            }
        }
    }

    /** 核心周期（微秒级，绝对时间累加不漂移；频率来自配置，改配置需重启）。 */
    private static void ensureScheduled() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        int hz = Math.max(1, ConfigAero.peripheralClientCoreHz());
        long periodUs = Math.max(1_000L, 1_000_000L / hz);
        try {
            handle = ThreadDispatchers.schedule(PeripheralCore::pump, TaskMode.EXCLUSIVE,
                    periodUs, periodUs);
        } catch (Throwable t) {
            scheduled.set(false);   // 分配器不可用 → 允许下次注册时重试
        }
    }

    /** 一个核心周期：接收处理 → 计算（串行遍历）→ 发送。 */
    private static void pump() {
        lastPumpAt = System.currentTimeMillis();
        try {
            // ① 接收处理：先消化投递进来的内部消息（设备表变更、启停请求等）。
            //    ⚠ 刻意【不用锁、不查别人的状态标志】（用户："不使用锁，使用消息机制更合适"）：
            //    线程间只通过无锁队列（消息）与不可变快照（Sample）交互。
            drainInbox();
            long now = System.currentTimeMillis();
            // ② 计算：串行遍历（用户："每次在此异步线程中串行遍历计算即可"）
            for (PeripheralTickable tickable : TICKABLES.values()) {
                try {
                    tickable.coreTick(now);
                } catch (Throwable ignored) {
                    // 单个方块出错不拖垮核心
                }
            }
            // ③ 发送：把出站队列统一投递到主线程（网络包只能在主线程发）
            flushOutbox();
        } catch (Throwable ignored) {
            // 核心尽力而为，绝不冒泡到游戏
        }
    }

    /**
     * 看门狗（主线程每 tick 调用）：<b>核心周期一旦静默停摆就重新排程</b>。
     *
     * <p>为什么需要：核心周期由 {@code ThreadDispatchers} 的定时线程驱动，句柄可能在
     * 分配器重建 / 定时线程异常后失效，而 {@code scheduled} 标志仍是 true ——
     * 于是 {@link #ensureScheduled()} 会因 CAS 失败而直接返回，<b>外设从此永久不再计算</b>。
     * 这类失效没有异常、没有日志，表现就是"退出重进世界后外设输出失效"。
     * 本方法用"最近一次周期时刻"这一唯一事实做判定，失效即重排，确定性自愈。
     */
    public static void watchdog() {
        if (TICKABLES.isEmpty()) {
            // 没有任何外设方块：核心不需要运行，看门狗也不主动拉起（避免空转的周期任务）
            return;
        }
        if (!scheduled.get()) {
            ensureScheduled();   // 有方块却没在跑（上次排程失败）→ 补上
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPumpAt <= STALL_TIMEOUT_MS) {
            return;
        }
        // 周期停摆：丢弃失效句柄并重新排程（stop 会把 scheduled 复位，使 ensureScheduled 能生效）
        stop();
        lastPumpAt = now;   // 先记账，避免同一轮重复重排
        ensureScheduled();
    }

    /** 核心周期是否在正常运行（诊断） */
    public static boolean isAlive() {
        return scheduled.get() && System.currentTimeMillis() - lastPumpAt <= STALL_TIMEOUT_MS;
    }

    /**
     * 世界卸载（退出世界/断线）：清空注册表与会话计时，避免把上一个世界的方块实体
     * 带进新世界 —— 残留的 BE 引用会继续被核心遍历，读的是已废弃的 Level。
     */
    public static void onLevelUnload() {
        TICKABLES.clear();
        INBOX.clear();
        OUTBOX.clear();
        PeripheralSessionClient.clear();
        // 世界已卸载 → 周期没有存在意义：停掉（下次有方块注册时 ensureScheduled 自动重启）
        stop();
    }

    /** 停止核心周期（客户端卸载/退出时可选调用）。 */
    public static void stop() {
        com.hdf.cryptand.circuitsimulation.compute.ScheduledHandle current = handle;
        if (current != null) {
            try {
                current.cancel();
            } catch (Throwable ignored) {
            }
        }
        handle = null;
        scheduled.set(false);
    }
}
