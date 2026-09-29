package com.hdf.cryptand.core.concurrent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次单次性访问申请（可池化槽）。
 *
 * <p>契约（只有一条路径，不做兜底）：
 * <ul>
 *   <li><b>可变且被复用</b>：由 {@link TxnPool} 出借，{@code submit} 之后归调度器所有，
 *       调用方不得再持有该引用。</li>
 *   <li>{@code chunkKeys} 必须<b>升序且无重复</b>（框架校验）。升序不是正确性要求
 *       （「全得或全不得」已经保证不死锁），而是让所有线程按同一顺序加锁，
 *       把「互相破坏对方半途加锁」的活锁概率压到最低。</li>
 *   <li>{@code cells} 由 {@link ChunkLocks} 在提交时解析并缓存，执行期不再碰锁表。</li>
 * </ul>
 */
public final class Txn {

    /** 单次事务最多申明的区块数；超出即拒绝（不截断、不降级）。 */
    public static final int MAX_KEYS = 16;

    private final long[] chunkKeys = new long[MAX_KEYS];
    private int keyCount;

    private TxnKind kind;
    private TxnExec exec;
    private int payload;

    /** 已解析的区块锁单元（按 key 升序、已去重）；只对需要加锁的事务填充。 */
    private final AtomicInteger[] cells = new AtomicInteger[MAX_KEYS];
    private int cellCount;

    /** true = 来自常驻池，执行完必须归还；false = 溢出分配，执行完等 GC。 */
    final boolean pooled;

    Txn(final boolean pooled) {
        this.pooled = pooled;
    }

    /** 填充（包内：只允许调度器调用）。 */
    void fill(final long[] keys, final int count, final TxnKind kind, final TxnExec exec, final int payload) {
        System.arraycopy(keys, 0, this.chunkKeys, 0, count);
        this.keyCount = count;
        this.kind = kind;
        this.exec = exec;
        this.payload = payload;
        this.cellCount = 0;
    }

    /** 归还池前清引用，避免池长期持有已结束事务的锁单元。 */
    void reset() {
        for (int i = 0; i < cellCount; i++) {
            cells[i] = null;
        }
        cellCount = 0;
        keyCount = 0;
        kind = null;
        exec = null;
        payload = 0;
    }

    // ---- 只读访问（应用者的 body 里用）----

    /** 申明的区块键（升序）；有效长度见 {@link #keyCount()}。 */
    public long[] chunkKeys() {
        return chunkKeys;
    }

    public int keyCount() {
        return keyCount;
    }

    public TxnKind kind() {
        return kind;
    }

    public TxnExec exec() {
        return exec;
    }

    /** 应用者自定义载荷（索引、句柄等，不参与框架逻辑）。 */
    public int payload() {
        return payload;
    }

    // ---- 包内：锁单元解析与执行 ----

    /**
     * 解析申明集合 → 锁单元（包内：提交时由调度器调用一次）。
     *
     * <p>顺便<b>去重</b>：锁不可重入，同一个 key 出现两次会对同一单元 CAS 两次，
     * 第二次必然失败并被误判成冲突。{@code chunkKeys} 已严格升序，故相邻比较即可。
     */
    void resolveCells(final ChunkLocks locks) {
        final long[] keys = chunkKeys;
        final AtomicInteger[] out = cells;
        int m = 0;
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < keyCount; i++) {
            final long k = keys[i];
            if (k == prev) {
                continue;
            }
            prev = k;
            out[m++] = locks.cell(k);
        }
        this.cellCount = m;
    }

    AtomicInteger[] cells() {
        return cells;
    }

    int cellCount() {
        return cellCount;
    }
}
