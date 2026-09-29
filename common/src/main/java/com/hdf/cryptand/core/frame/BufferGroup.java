package com.hdf.cryptand.core.frame;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 定长批缓冲组（分帧工作集）。
 *
 * <p>用途：把「一批数据」限制在固定容量内，让调用方（典型是 MC 主线程）每次只处理
 * 有上限的一批，处理完再继续下一批，从而保证单帧工作量有界、不卡顿。
 *
 * <p>契约（只有一条路径，不做兜底）：
 * <ul>
 *   <li>{@link #add(Object)}：放入一条；若本次放入后【已满】返回 {@code true}；</li>
 *   <li>已满后再次 {@code add} → 抛 {@link IllegalStateException}
 *       （调用方必须先 {@link #flush(Consumer)} 或 {@link #clear()}）；</li>
 *   <li>{@link #clear()}：清空并释放槽位引用，可复用；</li>
 *   <li>本类<b>非线程安全</b>：设计上只由持有它的那一侧单线程使用。</li>
 * </ul>
 *
 * <p>标准调用循环：
 * <pre>
 *   while (cursor.hasRemaining()) {
 *       if (group.add(cursor.next())) {   // 满了
 *           sink.accept(group);           // 处理这一帧的份额
 *           group.clear();                // 要再缓冲必须再调一次 add
 *           break;                        // 本帧到此为止
 *       }
 *   }
 * </pre>
 *
 * <p>为什么不用 ArrayList：定长数组零扩容、零装箱（配 LongBufferGroup/IntBufferGroup）、
 * 内存可预测（capacity 个引用），契合本项目的「调度零分配」要求。
 *
 * @param <T> 元素类型
 */
public final class BufferGroup<T> {

    /** 默认容量 = 一个 chunk section 的格数（16*16*16）。 */
    public static final int DEFAULT_CAPACITY = 4096;

    private final Object[] slots;
    private int size;

    public BufferGroup() {
        this(DEFAULT_CAPACITY);
    }

    public BufferGroup(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.slots = new Object[capacity];
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
     * 放入一条。
     *
     * @return 本次放入后【已满】返回 {@code true}；否则 {@code false}
     * @throws IllegalStateException 已满时调用
     */
    public boolean add(final T value) {
        Objects.requireNonNull(value, "value");
        if (size == slots.length) {
            throw new IllegalStateException(
                    "BufferGroup full (capacity=" + slots.length + "); flush()/clear() before adding");
        }
        slots[size++] = value;
        return size == slots.length;
    }

    /** 只读访问（0 &lt;= index &lt; {@link #size()}）。 */
    @SuppressWarnings("unchecked")
    public T get(final int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index=" + index + " size=" + size);
        }
        return (T) slots[index];
    }

    /**
     * 逐条交给 sink，然后清空。
     *
     * @return 本次处理的条数
     */
    public int flush(final Consumer<? super T> sink) {
        Objects.requireNonNull(sink, "sink");
        final int n = size;
        for (int i = 0; i < n; i++) {
            @SuppressWarnings("unchecked") final T v = (T) slots[i];
            sink.accept(v);
        }
        clear();
        return n;
    }

    /** 把当前内容按顺序转移进 out，然后清空。 */
    public <C extends Collection<? super T>> C drainTo(final C out) {
        Objects.requireNonNull(out, "out");
        for (int i = 0; i < size; i++) {
            @SuppressWarnings("unchecked") final T v = (T) slots[i];
            out.add(v);
        }
        clear();
        return out;
    }

    /** 清空并释放槽位引用（防内存泄漏），实例可继续复用。 */
    public void clear() {
        for (int i = 0; i < size; i++) {
            slots[i] = null;
        }
        size = 0;
    }

    @Override
    public String toString() {
        return "BufferGroup{size=" + size + "/" + slots.length + "}";
    }
}
