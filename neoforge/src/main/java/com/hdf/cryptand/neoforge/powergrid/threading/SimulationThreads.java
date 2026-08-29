/**
 * ===== AC / DC 分离计算线程 =====
 *
 * 交流与直流计算分开在两个独立线程上（旧 acCompute/dcComputeFrequencyHz
 * 独立配置已废除，统一按求解频率节拍推进）：
 *   - AC 线程（Cryptand-AC-Compute）：推进所有已注册交流源的【频率当前点】
 *     （示波器游标）。
 *   - DC 线程（Cryptand-DC-Compute）：执行所有已注册直流计算任务（电容/
 *     电感的伴生模型计算，结果写入 volatile 待应用字段）。
 *
 * 线程安全约定：
 *   - 后台线程只做【纯计算】（数值写 volatile 字段）；
 *   - 对 PowerGrid 导线/节点/网络的写入仍只在服务端 tick 线程进行
 *     （electricalTick 读取 volatile 待应用字段后落盘），避免与求解器竞争。
 *
 * 懒启动：第一个提供者注册时自动 start()；daemon 线程随 JVM 退出。
 * 任务集合用弱引用（WeakHashMap）：BE 被移除/不再被世界引用后自动清理，
 * 无需依赖 BlockEntity.setRemoved（SmartBlockEntity 中为 final 不可覆盖）。
 */
package com.hdf.cryptand.neoforge.powergrid.threading;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.adapter.FrequencyCurrentPoint;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class SimulationThreads implements AutoCloseable {

    private static final SimulationThreads INSTANCE = new SimulationThreads();

    public static SimulationThreads get() {
        return INSTANCE;
    }

    /** AC 源提供者：AC 线程每周期推进其频率当前点 */
    public interface AcSource {
        double getWaveformFrequencyHz();
        FrequencyCurrentPoint getCurrentPoint();
    }

    /** DC 计算任务：DC 线程每周期执行 */
    public interface DcTask {
        void runDcCompute();
    }

    /** 弱引用集合：BE 被移除后自动清理，避免内存泄漏（WeakHashMap 非线程安全，全部操作加锁） */
    private final Set<AcSource> acSources = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<DcTask> dcTasks = Collections.newSetFromMap(new WeakHashMap<>());
    private final Object registryLock = new Object();
    private final Object lock = new Object();
    private volatile boolean running;
    private Thread acThread;
    private Thread dcThread;

    public void registerAcSource(AcSource source) {
        start();
        synchronized (registryLock) {
            acSources.add(source);
        }
    }

    public void unregisterAcSource(AcSource source) {
        synchronized (registryLock) {
            acSources.remove(source);
        }
    }

    public void registerDcTask(DcTask task) {
        start();
        synchronized (registryLock) {
            dcTasks.add(task);
        }
    }

    public void unregisterDcTask(DcTask task) {
        synchronized (registryLock) {
            dcTasks.remove(task);
        }
    }

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            acThread = new Thread(this::acLoop, "Cryptand-AC-Compute");
            acThread.setDaemon(true);
            acThread.start();
            // ⚠ DC 线程退役（2026-08-14 完全禁用时域）：电容/电感直流伴生模型
            // （时域 Backward Euler）不再使用，统一相量 + 固定节拍伪时域。
            // dcLoop/DcTask/registerDcTask 定义保留（兼容注册，但不启动线程，
            // 电容/电感行为由相量核心 PhasorNetworkBuilder 建模接管）。
            dcThread = null;
            CryptandNeoForge.WAF_LOGGER.info("SimulationThreads started (AC only, DC retired)");
        }
    }

    // ========== AC 线程：推进频率当前点 ==========

    private void acLoop() {
        while (running) {
            // 统一频率（2026-08-12：移除单独 AC 线程配置，用统一拓扑频率）
            double computeHz = Math.max(1, ConfigLoad.CRYPTAND_TOPOLOGY_FREQUENCY_HZ.get());
            long periodNs = (long) (1e9 / computeHz);
            long startNs = System.nanoTime();
            // 快照后迭代，避免与注册并发修改冲突
            AcSource[] snapshot;
            synchronized (registryLock) {
                snapshot = acSources.toArray(new AcSource[0]);
            }
            for (AcSource source : snapshot) {
                try {
                    FrequencyCurrentPoint point = source.getCurrentPoint();
                    if (point != null) point.advance(source.getWaveformFrequencyHz(), computeHz);
                } catch (Throwable ignored) {
                }
            }
            sleepRemaining(periodNs, startNs);
        }
    }

    // ========== DC 线程：执行直流计算任务 ==========

    private void dcLoop() {
        while (running) {
            // 统一频率（2026-08-12：移除单独 DC 线程配置，用统一拓扑频率）
            double computeHz = Math.max(1, ConfigLoad.CRYPTAND_TOPOLOGY_FREQUENCY_HZ.get());
            long periodNs = (long) (1e9 / computeHz);
            long startNs = System.nanoTime();
            DcTask[] snapshot;
            synchronized (registryLock) {
                snapshot = dcTasks.toArray(new DcTask[0]);
            }
            for (DcTask task : snapshot) {
                try {
                    task.runDcCompute();
                } catch (Throwable ignored) {
                }
            }
            sleepRemaining(periodNs, startNs);
        }
    }

    private void sleepRemaining(long periodNs, long startNs) {
        long elapsedNs = System.nanoTime() - startNs;
        long sleepNs = periodNs - elapsedNs;
        if (sleepNs > 0) {
            try {
                Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (acThread != null) acThread.interrupt();
        if (dcThread != null) dcThread.interrupt();
        CryptandNeoForge.WAF_LOGGER.info("SimulationThreads stopped");
    }
}
