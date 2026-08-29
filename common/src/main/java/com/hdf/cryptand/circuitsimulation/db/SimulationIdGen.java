package com.hdf.cryptand.circuitsimulation.db;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仿真电路 64 位 ID 生成器（2026-08-15 用户要求：每个组装器需要一个 64 位 id）。
 * <p>
 * 设计：{@code id = (salt << 32) | counter}
 * <ul>
 *   <li><b>高位 31 位随机盐</b>（{@link #randomSalt()}）：每世界/每数据库持久化
 *      （存网表 meta 表），跨世界/跨数据库互不冲突；高位恒 0 → id 恒非负。</li>
 *   <li><b>低位 32 位计数器</b>：进程内 AtomicLong 单调递增；持久化后跨重启
 *      续用（不重复）。每盐最多 2^32 个 id，足够。</li>
 * </ul>
 * 用于：网络 id、组装器 id、导线 id 等所有需要 64 位稳定标识的实体。
 * 线程安全：next() 原子递增，任意线程可调（主线程/后台求解/存档线程）。
 */
public final class SimulationIdGen {

    /** 随机盐（31 位，恒非负 → id 恒非负） */
    private final long salt;
    /** 低位计数器 */
    private final AtomicLong counter;

    /**
     * @param salt    持久化盐（见 {@link #randomSalt()}；超 31 位自动掩码）
     * @param counter 持久化计数器（已用数量；从该值继续）
     */
    public SimulationIdGen(long salt, long counter) {
        this.salt = salt & 0x7FFFFFFFL;
        this.counter = new AtomicLong(Math.max(0, counter));
    }

    /** 生成随机盐（31 位；建议持久化到数据库 meta，跨会话稳定） */
    public static long randomSalt() {
        return ThreadLocalRandom.current().nextLong() & 0x7FFFFFFFL;
    }

    /** 下一个 64 位 id（恒非负） */
    public long next() {
        return (salt << 32) | (counter.getAndIncrement() & 0xFFFFFFFFL);
    }

    /** 当前盐（持久化用） */
    public long salt() {
        return salt;
    }

    /** 当前计数器（已分配数量；持久化用） */
    public long counter() {
        return counter.get();
    }
}
