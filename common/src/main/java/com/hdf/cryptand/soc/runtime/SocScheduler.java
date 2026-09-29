package com.hdf.cryptand.soc.runtime;

import com.hdf.cryptand.circuitsimulation.compute.PinnedWorker;
import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.soc.api.SocFault;
import com.hdf.cryptand.soc.sandbox.SocSandbox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * ===== SoC 调度器（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>把多个 {@link SocSandbox} 绑到项目统一线程分配器
 * （{@code com.hdf.cryptand.circuitsimulation.compute}）上：
 * <b>轻负载共享线程池（顺序时间片）、重负载自动升级为独占常驻线程</b>，
 * 负载回落后再降级并归还名额。</p>
 *
 * <h3>与核心铁律一致</h3>
 * <ul>
 *   <li><b>主线程零计算</b>：{@link #tickAll()} 只做"非阻塞派发"，绝不等待芯片；</li>
 *   <li><b>背压</b>：上一 tick 未跑完的芯片本 tick 跳过（计入 {@code skippedTicks}），
 *       不排队堆积；</li>
 *   <li><b>状态单线程独占</b>：同一芯片同一时刻只有一个任务在跑
 *       （{@link AtomicBoolean} 门闩），故迁移只发生在<b>闲置时</b>（tick 边界安全）。</li>
 * </ul>
 *
 * <h3>负载判据与滞回</h3>
 * <p>以"实测每 tick 耗时"为判据：连续 {@code promoteAfterTicks} 次超过
 * {@code promoteThresholdNanos} → 升级独占；连续 {@code demoteAfterTicks} 次低于
 * {@code demoteThresholdNanos} → 降级共享；每次迁移后进入 {@code cooldownTicks}
 * 冷却，避免抖动。</p>
 */
public final class SocScheduler implements AutoCloseable {

    /** 调度限额与阈值（由配置注入） */
    public static final class Limits {
        /** 同时调度的芯片数上限 */
        public int maxSandboxes = 8;
        /** 升级阈值：单 tick 耗时超过它（ns） */
        public long promoteThresholdNanos = 200_000L;
        /** 降级阈值：单 tick 耗时低于它（ns） */
        public long demoteThresholdNanos = 50_000L;
        /** 连续超阈值多少次 → 升级 */
        public int promoteAfterTicks = 8;
        /** 连续低阈值多少次 → 降级 */
        public int demoteAfterTicks = 120;
        /** 迁移冷却（tick） */
        public int cooldownTicks = 40;

        public Limits maxSandboxes(int v) {
            this.maxSandboxes = Math.max(1, v);
            return this;
        }

        public Limits promoteThresholdNanos(long v) {
            this.promoteThresholdNanos = Math.max(1, v);
            return this;
        }

        public Limits demoteThresholdNanos(long v) {
            this.demoteThresholdNanos = Math.max(1, v);
            return this;
        }

        public Limits promoteAfterTicks(int v) {
            this.promoteAfterTicks = Math.max(1, v);
            return this;
        }

        public Limits demoteAfterTicks(int v) {
            this.demoteAfterTicks = Math.max(1, v);
            return this;
        }

        public Limits cooldownTicks(int v) {
            this.cooldownTicks = Math.max(0, v);
            return this;
        }
    }

    /** 单芯片调度状态（UI/诊断） */
    public record Status(String id, boolean dedicated, long ticks, long skippedTicks,
                         long lastTickNanos, long maxTickNanos,
                         String sandboxState, String fault) {
    }

    private static final class Entry {
        final String id;
        final SocSandbox sandbox;
        final AtomicBoolean running = new AtomicBoolean();
        volatile PinnedWorker dedicated;
        long ticks;
        long skippedTicks;
        long lastTickNanosNanos;
        int aboveStreak;
        int belowStreak;
        int cooldown;

        Entry(String id, SocSandbox sandbox) {
            this.id = id;
            this.sandbox = sandbox;
        }
    }

    private final ThreadDispatcher dispatcher;
    private final Limits limits;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public SocScheduler() {
        this(ThreadDispatchers.get(), new Limits());
    }

    public SocScheduler(ThreadDispatcher dispatcher, Limits limits) {
        this.dispatcher = dispatcher == null ? ThreadDispatchers.get() : dispatcher;
        this.limits = limits == null ? new Limits() : limits;
    }

    // ==================== 注册 / 注销 ====================

    /**
     * 注册并开始调度一个芯片。
     *
     * @return false = 超出 {@link Limits#maxSandboxes} 配额（调用方应拒绝放置）
     */
    public synchronized boolean register(String id, SocSandbox sandbox) {
        if (id == null || sandbox == null || entries.containsKey(id)) {
            return false;
        }
        if (entries.size() >= limits.maxSandboxes) {
            return false;
        }
        entries.put(id, new Entry(id, sandbox));
        return true;
    }

    /** 注销（释放独占线程；芯片状态由调用方决定是否留存档） */
    public synchronized boolean unregister(String id) {
        final Entry e = entries.remove(id);
        if (e == null) {
            return false;
        }
        releaseDedicated(e);
        return true;
    }

    public synchronized boolean contains(String id) {
        return entries.containsKey(id);
    }

    public synchronized int size() {
        return entries.size();
    }

    // ==================== 驱动 ====================

    /**
     * 主线程每 tick 调用：<b>非阻塞派发</b>所有芯片的预算。
     *
     * <p>不做任何等待/计算；上一 tick 未完成的芯片本 tick 跳过（背压）。</p>
     */
    public void tickAll() {
        final List<Entry> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(entries.values());
        }
        for (Entry e : snapshot) {
            // 迁移只发生在闲置时（tick 边界安全）；忙碌则本 tick 不迁移
            if (!e.running.get()) {
                evaluateMigration(e);
            }
            if (!e.running.compareAndSet(false, true)) {
                e.skippedTicks++;   // 背压：上一 tick 尚未跑完
                continue;
            }
            final Runnable task = () -> runOne(e);
            final PinnedWorker dedicated = e.dedicated;
            if (dedicated != null) {
                try {
                    dedicated.post(task);
                } catch (Throwable t) {
                    e.running.set(false);
                    e.skippedTicks++;
                }
            } else {
                dispatcher.submitGeneric(task, TaskMode.EXCLUSIVE);
            }
        }
    }

    private void runOne(Entry e) {
        try {
            e.sandbox.tick();
            e.lastTickNanosNanos = e.sandbox.lastTickNanos();
            e.ticks++;
            observeLoad(e);
        } catch (Throwable ignored) {
            // 沙箱自身已把异常收敛为故障态；这里兜底保证门闩释放
        } finally {
            e.running.set(false);
        }
    }

    // ==================== 负载判据 / 迁移 ====================

    private void observeLoad(Entry e) {
        if (e.lastTickNanosNanos > limits.promoteThresholdNanos) {
            e.aboveStreak++;
            e.belowStreak = 0;
        } else if (e.lastTickNanosNanos < limits.demoteThresholdNanos) {
            e.belowStreak++;
            e.aboveStreak = 0;
        } else {
            e.aboveStreak = 0;
            e.belowStreak = 0;
        }
    }

    private void evaluateMigration(Entry e) {
        if (e.cooldown > 0) {
            e.cooldown--;
            return;
        }
        if (e.dedicated == null && e.aboveStreak >= limits.promoteAfterTicks) {
            try {
                e.dedicated = ThreadDispatchers.pinPermanent("soc-" + e.id, false);
                e.aboveStreak = 0;
                e.cooldown = limits.cooldownTicks;
            } catch (Throwable ignored) {
                e.aboveStreak = 0;   // 申请失败：下轮再试
            }
        } else if (e.dedicated != null && e.belowStreak >= limits.demoteAfterTicks) {
            releaseDedicated(e);
            e.belowStreak = 0;
            e.cooldown = limits.cooldownTicks;
        }
    }

    private void releaseDedicated(Entry e) {
        final PinnedWorker worker = e.dedicated;
        if (worker == null) {
            return;
        }
        e.dedicated = null;
        try {
            dispatcher.releasePinnedSlot(worker);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 查询 / 关停 ====================

    /** 全部芯片状态快照（UI/诊断） */
    public List<Status> statuses() {
        final List<Status> out = new ArrayList<>();
        for (Entry e : snapshot()) {
            final SocFault fault = e.sandbox.getFault();
            out.add(new Status(e.id, e.dedicated != null, e.ticks, e.skippedTicks,
                    e.lastTickNanosNanos, e.sandbox.maxTickNanos(),
                    e.sandbox.state().name(),
                    fault.isFaulted() ? SocFault.causeName(fault.getCause()) : ""));
        }
        return out;
    }

    private synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    /** 当前独占线程数（诊断/配额） */
    public int dedicatedCount() {
        int n = 0;
        for (Entry e : snapshot()) {
            if (e.dedicated != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 等待所有芯片空闲。
     *
     * <p><b>仅供自测与关停使用</b>——生产路径（主线程 tick）绝不调用，
     * 以维持"主线程零等待"。</p>
     */
    public boolean awaitIdle(long timeoutMs) {
        final long deadline = System.nanoTime() + Math.max(1, timeoutMs) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            boolean busy = false;
            for (Entry e : snapshot()) {
                if (e.running.get()) {
                    busy = true;
                    break;
                }
            }
            if (!busy) {
                return true;
            }
            LockSupport.parkNanos(50_000L);
        }
        return false;
    }

    /** 诊断摘要（多行） */
    public String describe() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SocScheduler[chips=").append(size())
                .append(" dedicated=").append(dedicatedCount()).append("]");
        for (Status s : statuses()) {
            sb.append(String.format("%n  %s %s ticks=%d skipped=%d last=%.1fus max=%.1fus %s%s",
                    s.id(), s.dedicated() ? "[独占]" : "[共享]",
                    s.ticks(), s.skippedTicks(),
                    s.lastTickNanos() / 1000.0, s.maxTickNanos() / 1000.0,
                    s.sandboxState(),
                    s.fault().isEmpty() ? "" : " fault=" + s.fault()));
        }
        return sb.toString();
    }

    /** 关停：注销全部芯片并释放独占线程（虚拟线程池由分配器自身管理） */
    @Override
    public synchronized void close() {
        for (Entry e : entries.values()) {
            releaseDedicated(e);
        }
        entries.clear();
    }
}
