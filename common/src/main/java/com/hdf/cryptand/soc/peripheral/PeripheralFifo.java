package com.hdf.cryptand.soc.peripheral;

/**
 * ===== 通用 FIFO 模块（虚拟机侧的"硬件"缓存，纯 Java 零 MC，2026-09-27 用户定案）=====
 *
 * <p>用户定案（{@code peripheral-hw-buffer-stm32-model-2026-09-27}）：</p>
 * <blockquote>"UART 缓冲通常虚拟机开辟一字节即可……芯片设计时缓存多大就多大；一般 UART 这种大于 1 字节
 * 都是使用硬件 FIFO，FIFO 也算是芯片模块的一种，可以虚拟机实现通用 FIFO 给实际芯片装载此功能，
 * 并且消耗对应组件资源"</blockquote>
 *
 * <h3>定位（层级必须写清：虚拟机 = 硬件层，芯片 = 只执行）</h3>
 * <p>本类是**虚拟机（硬件层 / FPGA 式 fabric）提供的通用 FIFO 实现**：它是一块**芯片内部硬件**，
 * 不是软件库。芯片/外设（UART0、SPI0…）**装载**它才能获得 &gt;1 字节的硬件缓冲；不装就是经典的
 * 1 字节保持寄存器（DR）。装载要**消耗组件槽位**（见 {@link #slotsFor(int)} 与
 * {@code SocModules} 的设备树计价）——这就是"缓存多大就多大、要更多就得占资源"。</p>
 *
 * <p>⚠ 芯片侧（固件）**看不到本类**：它只看到设备寄存器（数据寄存器 + 状态位 + 阈值位）。
 * FIFO 的使能（软件写使能位）、阈值、溢出（OVR）都照真实芯片语义暴露成寄存器位。</p>
 *
 * <h3>容量阶梯（照真实芯片：4/8/16 是 UART 常见档，往上是大缓冲设计值）</h3>
 * <pre>
 *   深度    4    8   16   32   64  128  256
 *   槽位    1    2    3    4    5    6    7   （每翻一倍多占一个组件槽位）
 * </pre>
 * <p>0 槽 = 不装载（= 1 字节 DR）。计价单位与 {@code PeripheralMap.Carrier} 的"组件槽位"同一个账。</p>
 */
public final class PeripheralFifo {

    /** 可装载的 FIFO 深度阶梯（字节；2 的幂 —— 下标用位与，热路径无除法） */
    public static final int[] LADDER = {4, 8, 16, 32, 64, 128, 256};

    /**
     * 装载一个深度为 {@code depth} 的 FIFO 模块要占几个组件槽位。
     *
     * <p>规则：4 字节 1 个槽，每翻一倍多占 1 个（8→2 / 16→3 / … / 256→7）。
     * 不装载（depth &lt;= 1）⇒ 0 槽 = 经典 1 字节 DR。</p>
     *
     * @throws IllegalArgumentException depth 不是 2 的幂、或超出阶梯
     */
    public static int slotsFor(int depth) {
        if (depth <= 1) {
            return 0;
        }
        int slots = 0;
        for (int d = 4; d <= 256; d <<= 1) {
            slots++;
            if (d == depth) {
                return slots;
            }
        }
        throw new IllegalArgumentException("FIFO 深度必须是 2 的幂且在 4..256（收到 " + depth + "）");
    }

    private final int depth;
    private final byte[] buf;
    private int head;
    private int tail;
    private boolean enabled;
    /** 接收阈值（0..3 → 1/4、1/2、3/4、满；照真实芯片的阈值位编码） */
    private int rxThreshold;
    /** 发送阈值（0..3） */
    private int txThreshold;
    private long pushed;
    private long popped;
    private long flushed;
    /** 满了还往里塞的次数（**绝不静默**：调用方与状态位都能看到） */
    private long overflow;

    public PeripheralFifo(int depth) {
        if (slotsFor(depth) == 0) {
            throw new IllegalArgumentException("深度必须 >= 4（1 字节保持寄存器不需要 FIFO 模块）");
        }
        this.depth = depth;
        this.buf = new byte[depth];
    }

    public int depth() {
        return depth;
    }

    /** 模块是否被软件使能（真实芯片：FIFOEN 位；不使能就只有 1 字节 DR 语义） */
    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            flush();
        }
    }

    public int rxThreshold() {
        return rxThreshold;
    }

    public int txThreshold() {
        return txThreshold;
    }

    /** 写阈值位（照真实芯片：两位一组，0=1/4、1=1/2、2=3/4、3=满） */
    public void setRxThreshold(int code) {
        this.rxThreshold = code & 0x3;
    }

    public void setTxThreshold(int code) {
        this.txThreshold = code & 0x3;
    }

    /** 阈值对应的"触发占用"（用于 RXFT 状态位） */
    public int rxThresholdLevel() {
        return switch (rxThreshold) {
            case 0 -> Math.max(1, depth / 4);
            case 1 -> Math.max(1, depth / 2);
            case 2 -> Math.max(1, (depth * 3) / 4);
            default -> depth;
        };
    }

    public int level() {
        return head - tail;
    }

    public boolean empty() {
        return head == tail;
    }

    public boolean full() {
        return level() >= depth;
    }

    /** 还有多少空位 */
    public int room() {
        return depth - level();
    }

    public long pushed() {
        return pushed;
    }

    public long popped() {
        return popped;
    }

    public long overflow() {
        return overflow;
    }

    public long flushed() {
        return flushed;
    }

    /**
     * 塞一个字节。
     *
     * @return false = 满了（**不静默**：{@link #overflow()} 计数 +1，调用方据此置溢出状态位/计数）
     */
    public boolean push(int value) {
        if (full()) {
            overflow++;
            return false;
        }
        buf[head & (depth - 1)] = (byte) value;
        head++;
        pushed++;
        return true;
    }

    /** 取一个字节（-1 = 空） */
    public int pop() {
        if (empty()) {
            return -1;
        }
        final int v = buf[tail & (depth - 1)] & 0xFF;
        tail++;
        popped++;
        return v;
    }

    /** 清空（真实芯片的 RXFLUSH/TXFLUSH） */
    public void flush() {
        head = tail = 0;
        flushed++;
    }

    @Override
    public String toString() {
        return "FIFO[depth=" + depth + (enabled ? " 使能" : " 旁路") + " 占用=" + level()
                + " 溢出=" + overflow + "]";
    }
}
