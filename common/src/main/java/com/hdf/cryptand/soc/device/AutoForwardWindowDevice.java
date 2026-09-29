package com.hdf.cryptand.soc.device;

import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== 自动转发窗口（FSMC / 8080 并口的现实模拟，2026-09-18）=====
 *
 * <p>用户定案："MCU 的组件就是 IO 口数量…然后类似 FSMC，芯片编写程序时只需要写入一段内存就能
 * 自动转发到对应的接口输出"。</p>
 *
 * <p>现实里 MCU 驱动外设有两招：</p>
 * <ol>
 *   <li><b>GPIO 手动时序</b>：软件按协议 toggle 引脚（软件 I2C 就是这么做）。慢，但任何协议都能软模拟。</li>
 *   <li><b>FSMC/FMC 或 8080 并口窗口</b>：把外设当成"一段内存"，写内存时<b>硬件自动产生总线时序</b>——
 *       固件代码就是 {@code *(volatile uint16_t *)LCD_DATA = px;}，它根本不碰引脚。</li>
 * </ol>
 *
 * <p>本类是第二种的模拟：一段 MMIO 窗口，<b>每次 store = 一次总线传输</b>，交给
 * {@link Sink}（平台侧，例如"转发给 OC 的屏幕"）。</p>
 *
 * <h3>8080 的 RS 线用地址低位表达</h3>
 * <p>真实 8080 并口用 RS 线区分命令/数据（很多屏把 A0 接到 RS）。这里同构：
 * <b>{@code offset} 的 bit0 = 0 ⇒ 命令，= 1 ⇒ 数据</b>。于是 "写 LCD_CMD / LCD_DATA"
 * 就是写同一个窗口的两个相邻地址，固件侧不需要任何额外寄存器。</p>
 *
 * <h3>🔴 带宽纪律（为什么不能"想写多快就多快"）</h3>
 * <p>窗口写如果无限快，就变成"比 PCIe 还快的魔法"，档位与接口差异全部消失（而我们整套模型
 * 的意义正是"链路决定能力"）。所以这里按接口吞吐记账：每字节消耗 {@code 1e9 / bytesPerSecond} 纳秒
 * 信用，信用耗尽时抛 {@link MemoryAccessException}（等价真实总线的等待/回压），
 * 由平台的 {@link #tick(long)} 按**真实经过时间**恢复信用（含一个突发上限，避免长时间空闲后
 * 突然可以一次写几兆）。</p>
 *
 * <p>当前只做**输出窗口**（屏、打印口这类只写设备）。要挂 NOR/PSRAM 这类可读设备时，
 * 给 {@link Sink} 补一个 {@code read()} 再把 {@link #load} 接上即可 —— 那时再说，不预先造。</p>
 */
public final class AutoForwardWindowDevice implements MemoryMappedDevice {

    /** 传输接收方：一次总线写最终落到这里（命令 / 数据由 RS 位区分） */
    public interface Sink {

        /** 收到一条**命令**（8080 的 RS=0），例如 LCD 的 0x2C */
        void command(int value);

        /** 收到一个**数据字**（8080 的 RS=1），例如一个像素 */
        void data(int value);
    }

    /** 默认突发额度：1ms —— 上电即可写一小段，长时间空闲也不会攒出无限信用 */
    public static final long DEFAULT_BURST_NANOS = 1_000_000L;

    /** 窗口大小（字节）：命令/数据按 bit0 交替，8 字节够放 4 组（其余位留给将来的背光/复位等） */
    private static final int WINDOW_BYTES = 8;

    private final String name;
    /** 总线宽度：1 = 8080 八位并口，2 = FSMC 十六位 */
    private final int widthBytes;
    private final long bytesPerSecond;
    private final long maxCreditNanos;
    /** 每字节消耗的纳秒（用 double 保留小数，charge 时向上取整） */
    private final double nanosPerByte;

    private Sink sink;
    /** 绑定的共享总线（null = 自己算速率、写即转发；见 {@link #withBus}） */
    private com.hdf.cryptand.soc.peripheral.SharedBus bus;
    private String busCmdId;
    private String busDataId;
    private long droppedByBus;
    private long busyByBus;
    private long creditNanos = DEFAULT_BURST_NANOS;
    private long commands;
    private long dataWords;
    private long busyRejects;
    private int lastValue;

    public AutoForwardWindowDevice(String name, int widthBytes, long bytesPerSecond) {
        this(name, widthBytes, bytesPerSecond, DEFAULT_BURST_NANOS);
    }

    public AutoForwardWindowDevice(String name, int widthBytes, long bytesPerSecond, long burstNanos) {
        if (widthBytes != 1 && widthBytes != 2) {
            throw new IllegalArgumentException("widthBytes must be 1 (8080) or 2 (FSMC), got " + widthBytes);
        }
        if (bytesPerSecond <= 0) {
            throw new IllegalArgumentException("bytesPerSecond must be > 0");
        }
        this.name = name == null ? "AUTO-FWD" : name;
        this.widthBytes = widthBytes;
        this.bytesPerSecond = bytesPerSecond;
        this.maxCreditNanos = Math.max(1L, burstNanos);
        this.nanosPerByte = 1_000_000_000.0 / (double) bytesPerSecond;
    }

    /** 装配时接上接收方（必须接；没接就写 = 固件写了没人收，要明确报错而不是静默丢弃） */
    public void sink(Sink sink) {
        this.sink = sink;
    }

    /**
     * 把本设备接到一条**共享总线**上：带宽的竞争与协议流控都交给总线统一裁决。
     *
     * <p>绑定之后语义变了（这就是"模块层统一管带宽"的意义）：{@code store()} 只把字节
     * <b>入队</b>，真正到达外设要等 {@code bus.tick(dt)} —— 而 tick 由模块层/板级
     * <b>统一调一次</b>，绝不要每个设备各 tick 自己那条总线（会重复结算额度）。</p>
     *
     * <p>命令与数据分成两个队列：8080 的 RS 语义（命令/数据）不能在包里丢掉。</p>
     */
    public AutoForwardWindowDevice withBus(com.hdf.cryptand.soc.peripheral.SharedBus bus, String deviceId) {
        this.bus = bus;
        this.busCmdId = deviceId + "-cmd";
        this.busDataId = deviceId + "-data";
        bus.register(busCmdId, bytesPerSecond);
        bus.register(busDataId, bytesPerSecond);
        bus.sink(busCmdId, (bytes, data) -> {
            if (data != null && data.length > 0) {
                commands++;
                final Sink s = sink;
                if (s != null) {
                    s.command(data[0] & 0xFF);
                }
            }
        });
        bus.sink(busDataId, (bytes, data) -> {
            if (data != null && data.length > 0) {
                dataWords++;
                final Sink s = sink;
                if (s != null) {
                    s.data(data[0] & 0xFF);
                }
            }
        });
        return this;
    }

    /** 因为总线队列满被丢掉的写次数（绑总线后才有意义） */
    public long droppedByBus() {
        return droppedByBus;
    }

    /** 因为总线背压（BUSY）被挡下的写次数（绑总线后才有意义） */
    public long busyByBus() {
        return busyByBus;
    }

    public String name() {
        return name;
    }

    public int widthBytes() {
        return widthBytes;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    /** 收到的命令条数（诊断/无人化断言） */
    public long commands() {
        return commands;
    }

    /** 收到的数据字数 */
    public long dataWords() {
        return dataWords;
    }

    /** 因为带宽耗尽而被拒绝的写次数（>0 说明"写内存"确实被总线限住了） */
    public long busyRejects() {
        return busyRejects;
    }

    public int lastValue() {
        return lastValue;
    }

    public long creditNanos() {
        return creditNanos;
    }

    @Override
    public int getLength() {
        return WINDOW_BYTES;
    }

    @Override
    public int getSupportedSizes() {
        return widthBytes == 1
                ? (1 << Sizes.SIZE_8_LOG2)
                : ((1 << Sizes.SIZE_8_LOG2) | (1 << Sizes.SIZE_16_LOG2));
    }

    @Override
    public long load(int offset, int size) {
        throw new MemoryAccessException(offset, name + ": 只写窗口（输出设备）—— 要支持读请给 Sink 加 read()");
    }

    @Override
    public void store(int offset, long value, int size) {
        if (offset < 0 || offset >= WINDOW_BYTES) {
            throw new MemoryAccessException(offset, name + ": offset out of range (0.." + (WINDOW_BYTES - 1) + ")");
        }
        if (size != Sizes.SIZE_8 && size != Sizes.SIZE_16) {
            throw new MemoryAccessException(offset, name + ": 只支持 8/16 位访问");
        }
        final Sink target = sink;
        if (target == null) {
            throw new IllegalStateException(name + ": 没有接收方（Sink）—— 装配时必须接上，"
                    + "否则固件往窗口写的结果没人收（不允许静默丢弃）");
        }
        final int masked = (int) (value & (size == Sizes.SIZE_8 ? 0xFFL : 0xFFFFL));
        lastValue = masked;
        // 8080 的 RS 线 = 地址 bit0（真实：A0 接 RS）
        final boolean isData = (offset & 1) != 0;
        if (bus != null) {
            // 绑了共享总线：这次写只是**入队**，到达外设由 bus.tick 按速率/流控结算
            final com.hdf.cryptand.soc.peripheral.SharedBus.Offer offer =
                    bus.offer(isData ? busDataId : busCmdId, new byte[] { (byte) masked }, 1);
            if (offer == com.hdf.cryptand.soc.peripheral.SharedBus.Offer.DROPPED) {
                droppedByBus++;
            } else if (offer == com.hdf.cryptand.soc.peripheral.SharedBus.Offer.BUSY) {
                busyByBus++;
            }
            return;
        }
        charge(offset, size);      // 未绑总线：保持"自己算速率、写即转发"的原行为
        if (isData) {
            dataWords++;
            target.data(masked);
        } else {
            commands++;
            target.command(masked);
        }
    }

    /** 平台在 tick 边界按**真实经过时间**恢复带宽信用（含突发上限） */
    public void tick(long elapsedNanos) {
        if (elapsedNanos > 0) {
            creditNanos = Math.min(maxCreditNanos, creditNanos + elapsedNanos);
        }
    }

    /** 扣一次带宽信用；不够就按"总线忙"拒绝（可观测、不静默丢） */
    private void charge(int offset, int bytes) {
        final long need = Math.max(1L, (long) Math.ceil(bytes * nanosPerByte));
        if (creditNanos < need) {
            busyRejects++;
            throw new MemoryAccessException(offset, name + ": 总线忙（带宽信用用尽：每字节 "
                    + (long) Math.ceil(nanosPerByte) + "ns，当前剩余 " + creditNanos + "ns）");
        }
        creditNanos -= need;
    }
}
