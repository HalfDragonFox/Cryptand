package com.hdf.cryptand.core.concurrent;

/**
 * 事务槽常驻池（防重复分配）+ 溢出退化。
 *
 * <p>语义（用户 2026-09-28 定案）：
 * <ul>
 *   <li>构造时一次性分配 {@code capacity} 个槽并<b>永不释放</b> ⇒ 稳态零分配。</li>
 *   <li>超出 {@code capacity} 的申请照常 {@code new}（不进池），执行完失去引用 ⇒
 *       <b>由 GC 自动回收</b>；池本身不被撑大，峰值过后内存回落到 {@code capacity}。</li>
 *   <li>{@link #overflowCount()} 是当前在外的溢出对象数，用作探针 —— 稳态应当恒为 0。</li>
 * </ul>
 *
 * <p>{@code submit} 可能来自任意线程，故 acquire/release 用 {@code synchronized}
 * （临界区只有几次数组操作，代价可忽略）。
 */
public final class TxnPool {

    /** 常驻槽数默认值。 */
    public static final int DEFAULT_CAPACITY = 1024;

    private final Txn[] slots;
    private int freeTop;
    private int overflowCount;

    public TxnPool() {
        this(DEFAULT_CAPACITY);
    }

    public TxnPool(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.slots = new Txn[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new Txn(true);
        }
        this.freeTop = capacity;
    }

    public int capacity() {
        return slots.length;
    }

    /** 常驻池中仍在外的槽数（= capacity - 空闲数），探针用。 */
    public synchronized int pooledCount() {
        return slots.length - freeTop;
    }

    /** 当前在外的溢出对象数，探针用；稳态应为 0。 */
    public synchronized int overflowCount() {
        return overflowCount;
    }

    public synchronized Txn acquire() {
        if (freeTop > 0) {
            return slots[--freeTop];
        }
        overflowCount++;
        return new Txn(false);
    }

    public synchronized void release(final Txn txn) {
        if (!txn.pooled) {
            overflowCount--;
            return;     // 溢出对象：不回收，等 GC
        }
        txn.reset();
        slots[freeTop++] = txn;
    }
}
