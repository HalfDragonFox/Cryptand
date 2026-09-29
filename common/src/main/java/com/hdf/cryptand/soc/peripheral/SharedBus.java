package com.hdf.cryptand.soc.peripheral;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== 共享总线（带宽竞争 + 真实等效流控，2026-09-18）=====
 *
 * <p>用户定案："如果一个设备速率满的话其他设备速率就会下降，模拟真实的"、
 * "尽可能把协议效果按照真实的等效逻辑来做"。</p>
 *
 * <h3>三件事，各有出处</h3>
 * <ol>
 *   <li><b>按字节信用</b>（不是按包数）：每 tick credit += rate × dt，传输按字节扣
 *       —— 包大小不一时按包数限速会失真。</li>
 *   <li><b>per-device 队列 + 加权轮转</b>：单 FIFO 是先到先服务，一个设备狂塞会让别的设备
 *       <b>饿死</b>；按各自申报速率加权，才得到"一个吃满、其他按比例<b>降速</b>"。
 *       空份额会被**回收再分配**（有积压的设备能吃掉别人没用的额度，不浪费带宽）。</li>
 *   <li><b>队列满时的行为按协议真实等效逻辑</b>（{@link FlowControl}）——
 *       有的总线丢，有的总线让生产端等：</li>
 * </ol>
 *
 * <table border="1">
 *   <tr><th>流控</th><th>现实对应</th><th>队列满时</th></tr>
 *   <tr><td>{@link FlowControl#NONE_DROP}</td><td>UART 无硬件流控 / SPI 无从机应答</td>
 *       <td><b>丢</b>（FIFO 溢出），计数可观测</td></tr>
 *   <tr><td>{@link FlowControl#CLOCK_STRETCH}</td><td>I2C 从机拉低 SCL</td><td>主机<b>等</b></td></tr>
 *   <tr><td>{@link FlowControl#BUSY_SIGNAL}</td><td>8080/FSMC 的 LCD BUSY 脚</td><td>主机轮询<b>等</b></td></tr>
 *   <tr><td>{@link FlowControl#CREDIT_BASED}</td><td>PCIe Flow Control Credits</td>
 *       <td>发送端<b>停发</b>（不丢）</td></tr>
 *   <tr><td>{@link FlowControl#NAK_RETRY}</td><td>USB 的 token/NAK 重试</td><td>设备回 NAK，主机重试</td></tr>
 * </table>
 *
 * <p>⚠ 包是**原子**的：一个包要么整包发出（信用够），要么留到下一 tick
 * ——真实总线不会把包切一半发出去（这也让"信用略小于包"时表现为等待，而不是部分发送）。</p>
 */
public final class SharedBus {

    /** 总线的流控模型（真实等效逻辑） */
    public enum FlowControl {
        /** 无流控：接收侧跟不上就丢（UART FIFO 溢出 / SPI 无从机应答） */
        NONE_DROP("无流控·溢出即丢"),
        /** 时钟拉伸：从机拉低 SCL 让主机等（I2C 的真实背压） */
        CLOCK_STRETCH("时钟拉伸·主机等待"),
        /** BUSY 信号：主机轮询等待（8080/FSMC 的 LCD BUSY 脚） */
        BUSY_SIGNAL("BUSY 信号·主机等待"),
        /** 信用流控：接收端信用不足则发送端停发（PCIe Flow Control Credits） */
        CREDIT_BASED("信用流控·发送端停发"),
        /** NAK + 重试：设备未就绪回 NAK，主机重试（USB token/NAK） */
        NAK_RETRY("NAK 重试");

        private final String label;

        FlowControl(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 队列满时是否让生产端等待（false = 直接丢） */
        public boolean backpressure() {
            return this != NONE_DROP;
        }
    }

    /** 一次入队的结果 */
    public enum Offer {
        /** 已入队（不代表立刻发出 —— 发不发由信用决定） */
        ACCEPTED,
        /** 队列满且该总线有背压能力 ⇒ 生产端必须等（RV 侧表现为写忙） */
        BUSY,
        /** 队列满且该总线无流控 ⇒ 丢（已计数，可观测） */
        DROPPED
    }

    /**
     * 一个已排队的包。
     *
     * <p>{@code data} 可以为 null —— 只关心带宽/竞争时不必带内容；要真正把数据交给外设
     * （外设层 → 模块接口层 → RV 内核 这条链路）时才带上。</p>
     */
    public static final class Packet {
        final int bytes;
        final byte[] data;

        Packet(int bytes, byte[] data) {
            this.bytes = bytes;
            this.data = data;
        }

        public int bytes() {
            return bytes;
        }

        public byte[] data() {
            return data;
        }
    }

    /** 包送出时的接收方（模块接口层用它把数据交给外设层） */
    public interface PacketSink {
        void onPacket(int bytes, byte[] data);
    }

    /** 一个设备的发送队列（按包记，保持包的原子性） */
    private static final class DeviceQueue {
        final String id;
        long declaredBytesPerSecond;
        final ArrayDeque<Packet> packets = new ArrayDeque<>();
        /**
         * 该设备**没花完的额度零头**（字节）。
         *
         * <p>为什么额度要结转到设备账号上，而不是留在总线里：总线每 tick 的传输能力是**新鲜**的
         * （rate × dt），分配出去的份额即使这一拍没花完（例如包是 100 字节而份额只有 750 里的 50），
         * 也是"这个设备的传输时间"，不能被别的设备抢走 —— 否则慢设备会被反复截断，
         * 长期比例就偏了（实测过：3:1 会漂成 4:1）。</p>
         */
        double quotaCarry;
        int queuedBytes;
        long acceptedBytes;
        long sentBytes;
        long droppedBytes;
        long busyCount;

        DeviceQueue(String id, long declaredBytesPerSecond) {
            this.id = id;
            this.declaredBytesPerSecond = Math.max(1L, declaredBytesPerSecond);
        }
    }

    private final String name;
    private final long bytesPerSecond;
    private final FlowControl flowControl;
    private final int queueCapacityBytes;
    // 注：**没有信用池上限** —— 总线是连续可用的，真实经过多久就补多少额度。
    //（"突发上限"是以太网/包交换里的概念；UART/SPI/I2C/PCIe 链路只有速率，没有突发上限。）
    private final Map<String, DeviceQueue> queues = new LinkedHashMap<>();
    private final Map<String, PacketSink> sinks = new LinkedHashMap<>();

    private long sentBytes;
    private long droppedBytes;
    private long busyRejects;
    private long ticks;

    public SharedBus(String name, long bytesPerSecond, FlowControl flowControl, int queueCapacityBytes) {
        if (bytesPerSecond <= 0) {
            throw new IllegalArgumentException("bytesPerSecond must be > 0");
        }
        if (queueCapacityBytes <= 0) {
            throw new IllegalArgumentException("queueCapacityBytes must be > 0");
        }
        this.name = name == null ? "BUS" : name;
        this.bytesPerSecond = bytesPerSecond;
        this.flowControl = flowControl == null ? FlowControl.NONE_DROP : flowControl;
        this.queueCapacityBytes = queueCapacityBytes;
    }

    public String name() {
        return name;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    public FlowControl flowControl() {
        return flowControl;
    }

    public int queueCapacityBytes() {
        return queueCapacityBytes;
    }

    /**
     * 注册一个设备并申报它的速率（用于加权分配）。
     *
     * @param declaredBytesPerSecond 设备期望的速率（只影响竞争时的**份额**，不影响总线总速率）
     */
    public SharedBus register(String deviceId, long declaredBytesPerSecond) {
        queues.computeIfAbsent(deviceId, id -> new DeviceQueue(id, declaredBytesPerSecond));
        return this;
    }

    /**
     * 把一个包放进该设备的队列。
     *
     * @param bytes 包大小（一个包不可分割）
     * @return 见 {@link Offer}；DROPPED/BUSY 都会计数，绝不静默
     */
    public Offer offer(String deviceId, int bytes) {
        return offer(deviceId, bytes, null);
    }

    /** 把一段数据作为包放进队列（送出时会回调 {@link PacketSink}） */
    public Offer offer(String deviceId, byte[] data, int len) {
        if (data == null || len <= 0 || len > data.length) {
            throw new IllegalArgumentException("bad packet: len=" + len);
        }
        return offer(deviceId, len, java.util.Arrays.copyOf(data, len));
    }

    /** 绑定某设备的包接收方（模块接口层装配时调用） */
    public SharedBus sink(String deviceId, PacketSink sink) {
        if (sink == null) {
            sinks.remove(deviceId);
        } else {
            sinks.put(deviceId, sink);
        }
        return this;
    }

    private Offer offer(String deviceId, int bytes, byte[] data) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("packet size must be > 0");
        }
        final DeviceQueue q = queues.get(deviceId);
        if (q == null) {
            throw new IllegalStateException(name + ": 设备 " + deviceId
                    + " 没在总线上注册（先 register，否则写给谁都不知道）");
        }
        if (q.queuedBytes + bytes > queueCapacityBytes) {
            if (flowControl.backpressure()) {
                q.busyCount++;
                busyRejects++;
                return Offer.BUSY;
            }
            q.droppedBytes += bytes;
            droppedBytes += bytes;
            return Offer.DROPPED;
        }
        q.packets.addLast(new Packet(bytes, data));
        q.queuedBytes += bytes;
        q.acceptedBytes += bytes;
        return Offer.ACCEPTED;
    }

    /**
     * 按真实经过时间结算信用，并加权轮转地把包发出去。
     *
     * @return 本 tick 实际发出的字节数
     */
    public long tick(long elapsedNanos) {
        ticks++;
        if (elapsedNanos <= 0) {
            return 0;
        }
        // 本 tick 的**新鲜传输能力**（总线每 tick 重置，不是累加池）：真实经过多久就给多少
        final double budget = (double) bytesPerSecond * (double) elapsedNanos / 1_000_000_000.0;

        long weightSum = 0;
        for (final DeviceQueue q : queues.values()) {
            if (q.queuedBytes > 0) {
                weightSum += q.declaredBytesPerSecond;      // 只有**有需求**的设备参与竞争
            }
        }
        if (weightSum == 0) {
            return 0;                                      // 没人要发：额度自然作废（不攒到下 tick）
        }

        long sentThisTick = 0;
        for (final DeviceQueue q : queues.values()) {
            if (q.queuedBytes == 0) {
                continue;                                  // 无需求 ⇒ 不拿份额（空份额自动让给有需求的）
            }
            q.quotaCarry += budget * ((double) q.declaredBytesPerSecond / (double) weightSum);
            final PacketSink sink = sinks.get(q.id);
            while (!q.packets.isEmpty()) {
                final Packet head = q.packets.peekFirst();
                if (head.bytes > q.quotaCarry) {
                    break;      // 包原子：额度不够一整包就留到下一 tick（零头结转到本设备账号）
                }
                q.packets.pollFirst();
                q.queuedBytes -= head.bytes;
                q.sentBytes += head.bytes;
                q.quotaCarry -= head.bytes;
                sentThisTick += head.bytes;
                if (sink != null) {
                    // 模块接口层在这里把数据交给外设层（外设层 → 模块接口层 → RV 内核 的中间一跳）
                    sink.onPacket(head.bytes, head.data);
                }
            }
        }
        sentBytes += sentThisTick;
        return sentThisTick;
    }

    // ==================== 诊断 ====================

    public long sentBytes() {
        return sentBytes;
    }

    public long droppedBytes() {
        return droppedBytes;
    }

    public long busyRejects() {
        return busyRejects;
    }

    public long ticks() {
        return ticks;
    }

    /** 某设备当前没花完的额度零头（字节；只在诊断里有意义） */
    public double quotaCarry(String deviceId) {
        final DeviceQueue q = queues.get(deviceId);
        return q == null ? 0.0 : q.quotaCarry;
    }

    /** 某设备当前积压字节数 */
    public int backlog(String deviceId) {
        final DeviceQueue q = queues.get(deviceId);
        return q == null ? 0 : q.queuedBytes;
    }

    /** 某设备已被发出的字节数 */
    public long deviceSentBytes(String deviceId) {
        final DeviceQueue q = queues.get(deviceId);
        return q == null ? 0 : q.sentBytes;
    }

    /** 某设备被丢的字节数（无流控总线） */
    public long deviceDroppedBytes(String deviceId) {
        final DeviceQueue q = queues.get(deviceId);
        return q == null ? 0 : q.droppedBytes;
    }

    /** 某设备遭遇的背压次数（有流控总线） */
    public long deviceBusyCount(String deviceId) {
        final DeviceQueue q = queues.get(deviceId);
        return q == null ? 0 : q.busyCount;
    }

    /** 一行摘要（日志/UI/无人化断言） */
    public String summary() {
        final StringBuilder sb = new StringBuilder();
        sb.append(name).append(' ').append(bytesPerSecond / 1000).append("KB/s ")
                .append(flowControl.label())
                .append(" sent=").append(sentBytes)
                .append(" dropped=").append(droppedBytes)
                .append(" busy=").append(busyRejects);
        for (final DeviceQueue q : queues.values()) {
            sb.append(" | ").append(q.id).append(":q=").append(q.queuedBytes)
                    .append(",sent=").append(q.sentBytes);
        }
        return sb.toString();
    }
}
