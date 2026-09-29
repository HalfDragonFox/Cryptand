package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.device.ByteWindowDevice;
import com.hdf.cryptand.soc.peripheral.Peripheral;
import com.hdf.cryptand.soc.peripheral.PeripheralMap;
import com.hdf.cryptand.soc.peripheral.SharedBus;
import com.hdf.cryptand.soc.api.Sizes;

/**
 * ===== 通用外设模块（模块接口层，2026-09-18）=====
 *
 * <p>用户定案的三层结构："<b>外设层 → 模块接口层 → RV 内核</b>"。本类就是中间那一层：
 * 把一个 {@link Peripheral}（外设本体）包装成 RV 能看见的模块 —— 接口 + 速率 + 寄存器映射 +
 * 内存映射 + 流控，全都在这层一次做好，于是**新外设不必重复实现这些**。</p>
 *
 * <pre>
 *   RV 内核                写 TX 窗口（内存映射）= 发送；读 RX 窗口 = 接收；读 REG 窗口 = 状态
 *        ↓ 内存写 / 寄存器读
 *   模块接口层（本类）      把字节打成包交给 SharedBus（限速/竞争/流控都在那里）
 *        ↓ PacketSink 回调
 *   外设层                 Peripheral.accept(数据) / Peripheral.produce()
 * </pre>
 *
 * <h3>RV 看到的三段窗口</h3>
 * <ul>
 *   <li>{@code base + 0x000}（REG，32 位寄存器）：状态与计数；</li>
 *   <li>{@code base + 0x100}（TX，内存映射）：**写一个字节就发一个字节**（等价 UART 的 DR）；</li>
 *   <li>{@code base + 0x200}（RX，内存映射）：外设来的数据，读窗口即可取。</li>
 * </ul>
 *
 * <p>⚠ 寄存器/窗口的写入**不会**直接穿过总线：它们只入队，真正的发送由 {@link #tick} 按
 * 总线速率结算 —— 这就是"一次写内存 ≠ 立刻到达外设"的真实语义。</p>
 */
public final class PeripheralModule implements SocModuleImpl {

    /** 寄存器窗口偏移 */
    public static final int REG_OFFSET = 0x000;
    /** 发送窗口偏移（内存映射：RV 写 = 发送） */
    public static final int TX_OFFSET = 0x100;
    /** 接收窗口偏移（内存映射：RV 读 = 接收） */
    public static final int RX_OFFSET = 0x200;
    /** 整个模块占的窗口跨度 */
    public static final int WINDOW_BYTES = 0x1000;

    private static final int REG_STATUS = 0x00;
    private static final int REG_TX_SENT = 0x04;
    private static final int REG_TX_DROPPED = 0x08;
    private static final int REG_TX_BUSY = 0x0C;
    private static final int REG_RX_AVAIL = 0x10;

    private static final int STATUS_TX_BACKLOG = 1;
    private static final int STATUS_RX_READY = 1 << 1;
    private static final int STATUS_PERIPHERAL_BUSY = 1 << 2;

    private static final int TX_BYTES = 256;
    private static final int RX_BYTES = 256;

    private final SocModule spec;
    private final SharedBus bus;
    private final Peripheral peripheral;
    private final String deviceId;

    private final ByteWindowDevice reg;
    private final ByteWindowDevice tx;
    private final ByteWindowDevice rx;

    private final byte[] rxTemp = new byte[RX_BYTES];

    private long txDropped;
    private long txBusy;
    private long rxAvail;
    private long rxProduced;
    private long peripheralRejects;

    public PeripheralModule(String name, long baseAddress, SharedBus bus, Peripheral peripheral) {
        this.spec = new SocModule(name, carrierFor(bus), bus.bytesPerSecond(),
                baseAddress, WINDOW_BYTES, -1, peripheral.name());
        this.bus = bus;
        this.peripheral = peripheral;
        this.deviceId = name;
        bus.register(deviceId, bus.bytesPerSecond());
        bus.sink(deviceId, (bytes, data) -> {
            if (data != null && !peripheral.accept(data, bytes)) {
                // 外设当前不收：不许静默 —— 真实总线这时要么重试要么按流控丢
                peripheralRejects++;
            }
        });
        this.reg = new ByteWindowDevice(name + "-REG", 64, null);
        this.tx = new ByteWindowDevice(name + "-TX", TX_BYTES, new ByteWindowDevice.Hooks() {
            @Override
            public void onStore(int offset, long value, int size) {
                // RV 写一个字节 = 发一个字节（等价 UART 的 DR 写）
                final byte[] one = { (byte) (value & 0xFF) };
                final SharedBus.Offer offer = bus.offer(deviceId, one, 1);
                if (offer == SharedBus.Offer.DROPPED) {
                    txDropped++;
                } else if (offer == SharedBus.Offer.BUSY) {
                    txBusy++;
                }
            }
        });
        this.rx = new ByteWindowDevice(name + "-RX", RX_BYTES, null);
        refreshRegs();
    }

    /** 按总线的流控模型推断这个模块对外表现的载体（UART/I2C/PCIe…） */
    private static PeripheralMap.Carrier carrierFor(SharedBus bus) {
        return switch (bus.flowControl()) {
            case NONE_DROP -> PeripheralMap.Carrier.UART;
            case CLOCK_STRETCH -> PeripheralMap.Carrier.I2C;
            case BUSY_SIGNAL -> PeripheralMap.Carrier.PARALLEL8080;
            case CREDIT_BASED -> PeripheralMap.Carrier.PCIE;
            case NAK_RETRY -> PeripheralMap.Carrier.USB;
        };
    }

    @Override
    public SocModule spec() {
        return spec;
    }

    @Override
    public void attach(SocBoard.Builder board) {
        board.device(spec.baseAddress() + REG_OFFSET, reg, spec.name() + " REG");
        board.device(spec.baseAddress() + TX_OFFSET, tx, spec.name() + " TX");
        board.device(spec.baseAddress() + RX_OFFSET, rx, spec.name() + " RX");
    }

    @Override
    public void tick(long elapsedNanos) {
        // ① 总线按速率把队列里的包发出去（sink 回调把数据交给外设）
        bus.tick(elapsedNanos);
        // ② 外设产出的数据搬进 RX 窗口（RV 读窗口即取）
        final int n = peripheral.produce(rxTemp, rxTemp.length);
        if (n > 0) {
            rxAvail = n;
            rxProduced += n;
            for (int i = 0; i < n; i++) {
                rx.put(i, rxTemp[i]);
            }
        }
        refreshRegs();
    }

    /** 把状态与计数写进寄存器窗口（RV 读 REG 窗口看到的就是这些） */
    private void refreshRegs() {
        int status = 0;
        if (bus.backlog(deviceId) > 0) {
            status |= STATUS_TX_BACKLOG;
        }
        if (rxAvail > 0) {
            status |= STATUS_RX_READY;
        }
        if (peripheralRejects > 0) {
            status |= STATUS_PERIPHERAL_BUSY;
        }
        reg.store(REG_STATUS, status, Sizes.SIZE_32);
        reg.store(REG_TX_SENT, bus.deviceSentBytes(deviceId), Sizes.SIZE_32);
        reg.store(REG_TX_DROPPED, txDropped, Sizes.SIZE_32);
        reg.store(REG_TX_BUSY, txBusy, Sizes.SIZE_32);
        reg.store(REG_RX_AVAIL, rxAvail, Sizes.SIZE_32);
    }

    // ==================== 诊断（闸门/UI）====================

    public ByteWindowDevice regWindow() {
        return reg;
    }

    public ByteWindowDevice txWindow() {
        return tx;
    }

    public ByteWindowDevice rxWindow() {
        return rx;
    }

    public long txDropped() {
        return txDropped;
    }

    public long txBusy() {
        return txBusy;
    }

    public long rxProduced() {
        return rxProduced;
    }

    public long peripheralRejects() {
        return peripheralRejects;
    }

    public int backlog() {
        return bus.backlog(deviceId);
    }
}
