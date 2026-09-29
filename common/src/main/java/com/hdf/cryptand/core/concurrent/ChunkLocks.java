package com.hdf.cryptand.core.concurrent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 区块锁表：<b>一个区块一把锁</b>，多区块任务同时持有多把。
 *
 * <p>为什么按区块而不是条带（striped）：
 * <ul>
 *   <li>条带会有<b>假冲突</b>（不同区块哈希到同一把锁），按区块则零假冲突；</li>
 *   <li>优先级<b>内生于锁语义</b>：单区块任务只需一把锁，成功率≈1；多区块任务需要 N 把同时空闲。
 *       于是「单区块任务先跑、快进快出，多区块任务的窗口随后自然变大」自发发生，
 *       <b>不需要</b>分桶、升序放行扫描器、老化计数这些额外调度机制。</li>
 * </ul>
 *
 * <p>加锁语义是<b>全得或全不得</b>：{@link #tryLockAll} 任一失败立刻释放已拿到的全部。
 * 这是唯一让「多键 + 并发」不死锁的写法 —— 部分持有必然导致循环等待。
 *
 * <p>锁不可重入，因此<span>申明集合必须先去重</span>（同一个 key 出现两次会对同一单元
 * CAS 两次：第二次必然失败，被误判为冲突）。
 */
public final class ChunkLocks {

    /** 0 = 空闲；否则为 {@code owner + 1}。 */
    private static final int FREE = 0;

    private final ConcurrentHashMap<Long, AtomicInteger> table = new ConcurrentHashMap<>();

    /** 解析 key → 占用单元（不存在则创建）。可并发调用；仅提交时走一次。 */
    public AtomicInteger cell(final long key) {
        return table.computeIfAbsent(key, k -> new AtomicInteger(FREE));
    }

    /** 已登记的区块数，探针用。 */
    public int trackedKeys() {
        return table.size();
    }

    /**
     * 尝试占用全部单元（调用方保证 cells 按 key 升序且已去重）。
     *
     * @param owner 线程编号 + 1（大于 0）；由应用者分配，避免 {@code Thread.getId()} 的复用歧义
     * @return true = 全部拿到；false = 一把没拿（已拿到的已全部释放）
     */
    public static boolean tryLockAll(final AtomicInteger[] cells, final int count, final int owner) {
        int got = 0;
        for (; got < count; got++) {
            if (!cells[got].compareAndSet(FREE, owner)) {
                releaseAll(cells, got);
                return false;
            }
        }
        return true;
    }

    /** 释放前 count 个单元（必须与成功的 tryLockAll 严格配对）。 */
    public static void releaseAll(final AtomicInteger[] cells, final int count) {
        for (int i = 0; i < count; i++) {
            cells[i].set(FREE);
        }
    }
}
