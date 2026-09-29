package com.hdf.cryptand.core.frame;

import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * {@code long} 特化版定长批缓冲组（去装箱）。
 *
 * <p>语义与 {@link BufferGroup} 完全一致：{@link #add(long)} 放入后已满返回 {@code true}，
 * 已满再 add 抛 {@link IllegalStateException}。
 *
 * <p>典型用途：跨 region 的方块坐标（{@code BlockPos.asLong()} 打包）、水位变更点。
 */
public final class LongBufferGroup {

    public static final int DEFAULT_CAPACITY = BufferGroup.DEFAULT_CAPACITY;

    private final long[] slots;
    private int size;
    /** 是否有元素被 add 过（long 的默认值 0 无法区分「未写入」与「写入 0」）。 */
    private final boolean[] filled;

    public LongBufferGroup() {
        this(DEFAULT_CAPACITY);
    }

    public LongBufferGroup(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.slots = new long[capacity];
        this.filled = new boolean[capacity];
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
     * 放入一个 long。
     *
     * @return 本次放入后【已满】返回 {@code true}
     * @throws IllegalStateException 已满时调用
     */
    public boolean add(final long value) {
        if (size == slots.length) {
            throw new IllegalStateException(
                    "LongBufferGroup full (capacity=" + slots.length + "); flush()/clear() before adding");
        }
        slots[size] = value;
        filled[size] = true;
        size++;
        return size == slots.length;
    }

    /** 只读访问（0 &lt;= index &lt; {@link #size()}）。 */
    public long get(final int index) {
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
    public int flush(final LongConsumer sink) {
        Objects.requireNonNull(sink, "sink");
        final int n = size;
        for (int i = 0; i < n; i++) {
            sink.accept(slots[i]);
        }
        clear();
        return n;
    }

    /** 清空（槽位引用为原始类型，无需置空；置 filled 标记表明未写入）。 */
    public void clear() {
        for (int i = 0; i < size; i++) {
            slots[i] = 0L;
            filled[i] = false;
        }
        size = 0;
    }

    @Override
    public String toString() {
        return "LongBufferGroup{size=" + size + "/" + slots.length + "}";
    }
}
