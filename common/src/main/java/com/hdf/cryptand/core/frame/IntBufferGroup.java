package com.hdf.cryptand.core.frame;

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * {@code int} 特化版定长批缓冲组（去装箱）。
 *
 * <p>语义与 {@link BufferGroup} 完全一致：{@link #add(int)} 放入后已满返回 {@code true}，
 * 已满再 add 抛 {@link IllegalStateException}。
 *
 * <p>典型用途：region 内 cell 的线性索引（0..4095）、水位值、局部偏移。
 */
public final class IntBufferGroup {

    public static final int DEFAULT_CAPACITY = BufferGroup.DEFAULT_CAPACITY;

    private final int[] slots;
    private int size;

    public IntBufferGroup() {
        this(DEFAULT_CAPACITY);
    }

    public IntBufferGroup(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.slots = new int[capacity];
    }

    public int capacity() {
        return slots.length;
    }

    public int size() {
        return size;
    }

    public int remaining() {
        return slots.length - size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isFull() {
        return size == slots.length;
    }

    /**
     * 放入一个 int。
     *
     * @return 本次放入后【已满】返回 {@code true}
     * @throws IllegalStateException 已满时调用
     */
    public boolean add(final int value) {
        if (size == slots.length) {
            throw new IllegalStateException(
                    "IntBufferGroup full (capacity=" + slots.length + "); flush()/clear() before adding");
        }
        slots[size++] = value;
        return size == slots.length;
    }

    /** 只读访问（0 &lt;= index &lt; {@link #size()}）。 */
    public int get(final int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index=" + index + " size=" + size);
        }
        return slots[index];
    }

    /**
     * 逐条交给 sink，然后清空。
     *
     * @return 本次处理的条数
     */
    public int flush(final IntConsumer sink) {
        Objects.requireNonNull(sink, "sink");
        final int n = size;
        for (int i = 0; i < n; i++) {
            sink.accept(slots[i]);
        }
        clear();
        return n;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            slots[i] = 0;
        }
        size = 0;
    }

    @Override
    public String toString() {
        return "IntBufferGroup{size=" + size + "/" + slots.length + "}";
    }
}
