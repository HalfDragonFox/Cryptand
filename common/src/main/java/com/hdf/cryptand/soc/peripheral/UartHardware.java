package com.hdf.cryptand.soc.peripheral;

import com.hdf.cryptand.soc.board.OcBoardLayout;
import com.hdf.cryptand.soc.board.UartRegs;

import java.util.ArrayDeque;

/**
 * ===== UART 这块"硬件"（虚拟机侧实现，纯 Java 零 MC，2026-09-27 用户定案）=====
 *
 * <h3>层级（虚拟机 = 硬件层；芯片 = 只执行 + 碰寄存器）</h3>
 * <pre>
 *   世界侧（控制台/日志/玩家/无人化工具）        ← {@link #setByteSink} / {@link #offerRx}
 *        ↕  每 tick 整块供料（芯片**看不见**宿主）
 *   本类 = 虚拟机为芯片建立的一块 UART 硬件：
 *        寄存器文件（{@link UartRegs} 的布局）
 *        + 硬件缓存：默认 1 字节 DR；**装载**通用 FIFO 模块（{@link PeripheralFifo}）才 &gt;1 字节
 *        + 收发装配（波特率节流 / 移位寄存器 / 状态位 TXE TC RXNE OVR）
 *        ↕  只有普通内存读写（零 MMIO 事务）
 *   芯片（RV 内核 + 固件）：只执行指令、写 DR、读 DR、轮询状态位
 * </pre>
 *
 * <p>⚠ <b>固件侧没有任何"叫宿主 pump 一下"的入口</b>：它看到的只有
 * {@link UartRegs} 那段内存里的寄存器与状态位（这正是用户 2026-09-27 定案的
 * "虚拟机就是类似 FPGA 的硬件层面的接口，虚拟机只对芯片建立模拟组件…供芯片使用"）。</p>
 *
 * <h3>硬件缓存模型（照真实芯片）</h3>
 * <ul>
 *   <li><b>默认 = 1 字节保持寄存器（DR）</b>：芯片设计值，不多给。写 DR ⇒ 字节进本器件（VM 侧硬件缓存），
 *       CPU 不等、只轮询状态位；没及时取走就置 <b>OVR</b>（{@link #ovrTx()} / {@link #ovrRx()}），<b>不静默丢</b>。</li>
 *   <li><b>&gt;1 字节 = 装载通用 FIFO 模块</b>（4/8/16/32/64/128/256；档位与槽位计价见
 *       {@link PeripheralFifo#slotsFor(int)}），并且要**软件使能**（CR1.FIFOEN）+ 阈值位 —— 照真实芯片。
 *       不使能 ⇒ 生效容量退回 1 字节。</li>
 *   <li><b>数据窗口</b>（{@link UartRegs#OFF_TX_BUF} / {@link UartRegs#OFF_RX_BUF}）是硬件缓存的**映射视图**：
 *       芯片只能碰普通内存（零 MMIO 纪律），所以 DR/FIFO 数据口必须映射成内存槽位。
 *       容量仍是芯片设计值（= 模块深度），软件改不了。</li>
 * </ul>
 *
 * <h3>每 tick 一次整块同步（{@link #sync}）</h3>
 * <p>织物侧每 tick 做四件事：① 读固件独占的寄存器（CR1 / 写 DR 计数 / 读 DR 计数 / 溢出确认）；
 * ② 把窗口里的发送字节搬进硬件缓存；③ 按波特率推进线路（吐给世界侧出口）；
 * ④ 把世界侧到达的字节搬进硬件缓存 → 窗口，发布 SR 与计数器。全程只有普通内存读写。</p>
 */
public final class UartHardware {

    /** 发送字节出口（世界侧：控制台/日志/面板） */
    public interface ByteSink {
        void onTx(int value);
    }

    /** 一次同步的账（诊断/闸门断言用） */
    public record SyncResult(int txAccepted, int rxGiven, int txDropped, int rxDropped,
                             boolean anomaly, boolean windowRead) {
        /** 窗口读不回来（装配错/沙箱没起来） */
        public static final SyncResult NONE = new SyncResult(0, 0, 0, 0, false, false);
    }

    /** 世界侧注入队列上限（字节）：满了计数丢弃，绝不静默 */
    public static final int INBOX_MAX = 4096;

    /** 每字节消耗的周期数（波特率语义；本芯片的板级时基见 OcBoardLayout.UART_CYCLES_PER_BYTE） */
    private static final int CYCLES_PER_BYTE = Math.max(1, OcBoardLayout.UART_CYCLES_PER_BYTE);

    private final String name;
    /** 装载的通用 FIFO 模块（null = 芯片没装 ⇒ 永远 1 字节 DR） */
    private final PeripheralFifo txFifo;
    private final PeripheralFifo rxFifo;
    private final boolean moduleLoaded;

    /** 软件使能位（CR1.FIFOEN）：只有装载了模块才有意义 */
    private boolean fifoEnabled;
    private int rxThresholdCode;
    private int txThresholdCode;
    private int lastCr1;

    /** 1 字节 DR（不使能 FIFO 时它就是全部硬件缓存；-1 = 空） */
    private int drTx = -1;
    private int drRx = -1;
    /** 移位寄存器（正在往线路上发的那一个字节；-1 = 空） */
    private int shift = -1;
    private long txCredit;
    private long rxCredit;

    /** 世界侧出口（器件 → 世界） */
    private volatile ByteSink sink;
    /** 世界侧注入（世界 → 器件）：织物侧队列，芯片看不到 */
    private final ArrayDeque<Integer> inbox = new ArrayDeque<>();
    private long inboxDropped;

    /** 织物侧水位（与 guest 的单调计数对齐） */
    private int txPushAck;
    private int txTakenFromWindow;
    private int rxGivenToWindow;
    private int rxPopAck;
    private int ovrAckSeen;

    private long ovrTx;
    private long ovrRx;
    private long txTotal;
    private long rxTotal;
    private long syncs;
    private long anomalies;
    private long missingWindow;
    private long lastCycles;
    private int lastSr;

    public UartHardware(String name, int fifoDepth) {
        this.name = name == null ? "UART" : name;
        this.moduleLoaded = fifoDepth > 1;
        this.txFifo = moduleLoaded ? new PeripheralFifo(fifoDepth) : null;
        this.rxFifo = moduleLoaded ? new PeripheralFifo(fifoDepth) : null;
    }

    /** 本芯片装载了 FIFO 模块吗（设计值） */
    public boolean moduleLoaded() {
        return moduleLoaded;
    }

    /** 模块深度（0 = 没装） */
    public int moduleDepth() {
        return moduleLoaded ? txFifo.depth() : 0;
    }

    /** 装载该模块要占的组件槽位（计价口径见 {@link PeripheralFifo#slotsFor(int)}） */
    public int moduleSlots() {
        return moduleLoaded ? PeripheralFifo.slotsFor(moduleDepth()) : 0;
    }

    /** 当前生效的硬件缓存容量（字节）：装载且软件使能才算模块深度，否则 1（经典 DR） */
    public int capacity() {
        return UartRegs.effectiveSlots(moduleDepth(), fifoEnabled);
    }

    public String name() {
        return name;
    }

    // ==================== 世界侧接口（芯片看不见这一侧） ====================

    /** 装发送出口（世界侧：控制台/日志；null = 丢弃） */
    public void setByteSink(ByteSink sink) {
        this.sink = sink;
    }

    /** 世界侧投递一个接收字节（进织物侧队列；队列满则计数丢弃，调用方能看到） */
    public boolean offerRx(int value) {
        if (inbox.size() >= INBOX_MAX) {
            inboxDropped++;
            return false;
        }
        inbox.add(value & 0xFF);
        return true;
    }

    /** 世界侧批量投递（返回实际接受的字节数） */
    public int offerRx(byte[] data, int offset, int length) {
        int accepted = 0;
        for (int i = 0; i < length; i++) {
            if (!offerRx(data[offset + i] & 0xFF)) {
                break;
            }
            accepted++;
        }
        return accepted;
    }

    // ==================== 织物侧：初始镜像 + 每 tick 同步 ====================

    /** 开机一次：把寄存器窗口的初始镜像写进 guest RAM（必须在芯片放行之前） */
    public byte[] initialImage() {
        return UartRegs.initialImage(moduleLoaded);
    }

    /**
     * 一次同步（织物 tick）：读寄存器 → 搬字节 → 推线路 → 发布状态位。
     *
     * @param ram       guest 内存访问面（native 内核的 CpuCore 或 Java 内核的 SocBoard）
     * @param cyclesNow 沙箱已执行周期数（设备时间的唯一来源）
     */
    public SyncResult sync(UartRegs.GuestRam ram, long cyclesNow) {
        syncs++;
        final long delta = Math.max(0L, cyclesNow - lastCycles);
        lastCycles = cyclesNow;

        // ① 读固件独占的 16 字节（cr1 / tx_push / rx_pop / ovr_ack）
        final byte[] g = ram.read(OcBoardLayout.UART_CACHE_BASE + UartRegs.GUEST_OFFSET,
                UartRegs.GUEST_BYTES);
        if (g == null || g.length < UartRegs.GUEST_BYTES) {
            missingWindow++;
            return SyncResult.NONE;
        }
        final int cr1 = UartRegs.rd32(g, 0);
        final int txPush = UartRegs.rd32(g, 4);
        final int rxPop = UartRegs.rd32(g, 8);
        final int ovrAck = UartRegs.rd32(g, 12);
        // 溢出确认**本轮就生效**（软件写了 ovr_ack ⇒ 这一 tick 发布的 SR.OVR 就该清掉）
        ovrAckSeen = ovrAck;
        applyCr1(cr1, txPush, rxPop);

        // ② 发送：数据窗口 → 硬件缓存（DR / FIFO）。超出生效容量的部分记 OVR 丢掉（绝不静默）
        int txAccepted = 0;
        int txDropped = 0;
        int pending = txPush - txPushAck;
        if (pending < 0 || pending > UartRegs.TX_BYTES) {
            anomalies++;                        // 固件写回的计数不自洽（布局版本不一致？）
            pending = 0;
            txPushAck = txPush;
        }
        // 硬件缓存还能接多少：**这就是 TXE 的判据**（1 字节 DR 时只有 1 个位置）
        final int hwRoom = Math.max(0, capacity() - hwTxLevel());
        final int take = Math.min(pending, hwRoom);
        if (pending > take) {
            txDropped = pending - take;         // 软件没看 TXE 就连写 ⇒ 后到的丢，计入 OVR（不静默）
        }
        if (take > 0) {
            final byte[] seg = readWindow(ram, UartRegs.OFF_TX_BUF, txPushAck, take, UartRegs.TX_BYTES);
            for (final byte value : seg) {
                if (hwPushTx(value & 0xFF)) {
                    txAccepted++;
                } else {
                    txDropped++;
                }
            }
        }
        txPushAck += pending;                   // 超出的那些也一并算消费（否则会被重复发）
        txTakenFromWindow += take;              // 只有真正进了硬件缓存的才算"取走"
        ovrTx += txDropped;

        // ③ 线路推进（波特率）：硬件缓存 → 世界侧出口
        stepLine(delta);

        // ④ 接收：世界侧字节按波特率"到达"硬件缓存；无处存放 ⇒ OVR（照真实芯片：新字节丢、计数）
        int rxDropped = arriveRx(delta);
        ovrRx += rxDropped;

        // ⑤ 接收：硬件缓存 → 数据窗口（软件读窗口 = 读 DR）
        final int room = UartRegs.RX_BYTES - (rxGivenToWindow - rxPop);
        if (room < 0) {
            anomalies++;
            rxGivenToWindow = rxPop;
        }
        int rxGiven = 0;
        if (room >= 0) {
            final byte[] out = new byte[Math.min(room, hwRxLevel())];
            for (int i = 0; i < out.length; i++) {
                final int b = hwPopRx();
                if (b < 0) {
                    break;
                }
                out[i] = (byte) b;
                rxGiven++;
            }
            if (rxGiven > 0) {
                writeWindow(ram, UartRegs.OFF_RX_BUF, rxGivenToWindow, out, rxGiven, UartRegs.RX_BYTES);
                rxGivenToWindow += rxGiven;
            }
        }
        rxPopAck = rxPop;

        // ⑥ 发布状态位 + 计数器（先数据、后状态字 → 芯片不会读到"有数据但还没写进窗口"）
        final int sr = computeSr(txPush);
        lastSr = sr;
        publish(ram, sr, txTakenFromWindow, rxGivenToWindow);
        return new SyncResult(txAccepted, rxGiven, txDropped, rxDropped, false, true);
    }

    // ==================== 内部：控制字 / 线路 / 硬件缓存 ====================

    private void applyCr1(int cr1, int txPush, int rxPop) {
        final boolean wantFifo = (cr1 & UartRegs.CR1_FIFOEN) != 0;
        fifoEnabled = moduleLoaded && wantFifo;
        rxThresholdCode = (cr1 & UartRegs.CR1_RXFTH_MASK) >>> UartRegs.CR1_RXFTH_SHIFT;
        txThresholdCode = (cr1 & UartRegs.CR1_TXFTH_MASK) >>> UartRegs.CR1_TXFTH_SHIFT;
        if (moduleLoaded) {
            txFifo.setRxThreshold(rxThresholdCode);      // 阈值位（真实芯片每方向各一组；这里各存一份）
            txFifo.setTxThreshold(txThresholdCode);
            rxFifo.setRxThreshold(rxThresholdCode);
            rxFifo.setTxThreshold(txThresholdCode);
            if (fifoEnabled != txFifo.enabled()) {
                txFifo.setEnabled(fifoEnabled);
                rxFifo.setEnabled(fifoEnabled);
            }
        }
        // 清缓冲位按**上升沿**生效（电平复位后应归零，由软件负责）
        final boolean rxFlush = (cr1 & UartRegs.CR1_RXFLUSH) != 0 && (lastCr1 & UartRegs.CR1_RXFLUSH) == 0;
        final boolean txFlush = (cr1 & UartRegs.CR1_TXFLUSH) != 0 && (lastCr1 & UartRegs.CR1_TXFLUSH) == 0;
        if (rxFlush) {
            // 清接收缓冲：硬件缓存清空**并且**把窗口里还没被读走的字节判成丢弃
            // （软件没读它们就清 FIFO —— 真实芯片就是这个语义）。两个单调计数必须对齐：
            // 固件的 rx_pop 不会因清缓冲而前进 ⇒ 窗口水位回到它那里。
            drRx = -1;
            if (rxFifo != null) {
                rxFifo.flush();
            }
            rxGivenToWindow = rxPop;
        }
        if (txFlush) {
            // 清发送缓冲：硬件缓存清空 + 窗口里还没被搬走的字节一并作废（否则会被重发一遍）
            drTx = -1;
            shift = -1;
            if (txFifo != null) {
                txFifo.flush();
            }
            txPushAck = txPush;
        }
        lastCr1 = cr1;
    }

    /** 把硬件缓存里的一个字节交给线路（进硬件缓存；满了返回 false） */
    private boolean hwPushTx(int value) {
        if (moduleLoaded && fifoEnabled) {
            return txFifo.push(value);
        }
        if (drTx >= 0) {
            return false;                       // 1 字节 DR 已被占：写 DR 会覆盖（软件该先看 TXE）
        }
        drTx = value;
        return true;
    }

    /** 线路推进：按真实波特率把缓存里的字节吐给世界侧出口 */
    private void stepLine(long cycles) {
        if (cycles <= 0) {
            return;
        }
        txCredit += cycles;
        while (txCredit >= CYCLES_PER_BYTE) {
            if (shift < 0) {
                shift = hwPopTx();
                if (shift < 0) {
                    txCredit = Math.min(txCredit, CYCLES_PER_BYTE);   // 空闲时不留额度（照真实线路）
                    return;
                }
            }
            txCredit -= CYCLES_PER_BYTE;
            final int b = shift;
            shift = -1;
            txTotal++;
            final ByteSink s = sink;
            if (s != null) {
                try {
                    s.onTx(b);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 从硬件缓存里取一个待发字节（-1 = 空） */
    private int hwPopTx() {
        if (moduleLoaded && fifoEnabled) {
            final int b = txFifo.pop();
            if (b >= 0) {
                return b;
            }
        }
        final int b = drTx;
        drTx = -1;
        return b;
    }

    /**
     * 世界侧字节"到达"器件：按波特率节流（真实线路上字节是一个一个到的），
     * 到达时硬件缓存放不下 ⇒ 记 OVR 并丢掉**这一个**（照真实芯片：新字节丢、老字节保留）。
     *
     * @return 本次因溢出丢掉的字节数
     */
    private int arriveRx(long cycles) {
        if (cycles > 0) {
            rxCredit += cycles;
        }
        int dropped = 0;
        while (rxCredit >= CYCLES_PER_BYTE && !inbox.isEmpty()) {
            if (hwRxFull()) {
                // 缓存满 ⇒ 新到的字节丢掉并计数（不静默）；额度照扣（线路不会等我们）
                rxCredit -= CYCLES_PER_BYTE;
                inbox.poll();
                rxTotal++;
                dropped++;
                continue;
            }
            rxCredit -= CYCLES_PER_BYTE;
            final int b = inbox.poll();
            hwPushRx(b);
            rxTotal++;
        }
        if (inbox.isEmpty()) {
            rxCredit = Math.min(rxCredit, CYCLES_PER_BYTE);
        }
        return dropped;
    }

    private boolean hwRxFull() {
        return hwRxLevel() >= capacity();
    }

    private int hwRxLevel() {
        if (moduleLoaded && fifoEnabled) {
            return rxFifo.level() + (drRx >= 0 ? 1 : 0);
        }
        return drRx >= 0 ? 1 : 0;
    }

    private void hwPushRx(int value) {
        if (moduleLoaded && fifoEnabled) {
            rxFifo.push(value);
            return;
        }
        drRx = value;
    }

    private int hwPopRx() {
        if (moduleLoaded && fifoEnabled) {
            final int b = rxFifo.pop();
            if (b >= 0) {
                return b;
            }
        }
        final int b = drRx;
        drRx = -1;
        return b;
    }

    /** 计算状态位（照真实芯片的语义命名） */
    private int computeSr(int txPush) {
        int sr = 0;
        // ⚠ TXE 的判据是**硬件缓存还有没有位置**（不是窗口还剩几格）：窗口只是映射视图，
        //   每 tick 都被织物搬空，用它判"能不能写"会让软件在 FIFO 满时继续灌 ⇒ 只能靠 OVR 兜。
        if (hwTxLevel() < capacity()) {
            sr |= UartRegs.SR_TXE;              // 有空位可写
        }
        final int pending = (txPush - txPushAck) + hwTxLevel();
        if (pending == 0) {
            sr |= UartRegs.SR_TXFE;
        }
        if (shift < 0 && pending == 0) {
            sr |= UartRegs.SR_TC;               // 发送完成
        }
        final int rxLevel = rxGivenToWindow - rxPopAck;
        if (rxLevel > 0) {
            sr |= UartRegs.SR_RXNE;
        }
        if (rxLevel >= capacity()) {
            sr |= UartRegs.SR_RXFF;
        }
        if (moduleLoaded && rxLevel >= rxFifo.rxThresholdLevel()) {
            sr |= UartRegs.SR_RXFT;
        }
        if (ovrTx + ovrRx > ovrAckSeen) {
            sr |= UartRegs.SR_OVR;              // 粘滞：软件确认之前一直为 1
        }
        if (fifoEnabled) {
            sr |= UartRegs.SR_FIFOEN;
        }
        if (moduleLoaded) {
            sr |= UartRegs.SR_FIFOMOD;
        }
        return sr;
    }

    private int hwTxLevel() {
        if (moduleLoaded && fifoEnabled) {
            return txFifo.level() + (drTx >= 0 ? 1 : 0);
        }
        return drTx >= 0 ? 1 : 0;
    }

    private void publish(UartRegs.GuestRam ram, int sr, int txTaken, int rxGiven) {
        final byte[] a = new byte[UartRegs.HOST_A_BYTES];
        UartRegs.wr32(a, UartRegs.OFF_MAGIC - UartRegs.HOST_A_OFFSET, UartRegs.MAGIC);
        UartRegs.wr32(a, UartRegs.OFF_SR - UartRegs.HOST_A_OFFSET, sr);
        // ⚠ 发布的是**已消费**的总数（含因溢出丢弃的）：固件用 "自己的写计数 − 这个值" 做
        //   "窗口还没被搬走的字节数"这个硬不变式（防的是一个 tick 内连写超过窗口容量）。
        //   真正的流控判据是 SR.TXE（硬件缓存还有没有位置），不是这个差值。
        UartRegs.wr32(a, UartRegs.OFF_TX_TAKEN - UartRegs.HOST_A_OFFSET, txPushAck);
        UartRegs.wr32(a, UartRegs.OFF_RX_GIVEN - UartRegs.HOST_A_OFFSET, rxGiven);
        UartRegs.wr32(a, UartRegs.OFF_OVR - UartRegs.HOST_A_OFFSET, (int) (ovrTx + ovrRx));
        UartRegs.wr32(a, UartRegs.OFF_TX_SLOTS - UartRegs.HOST_A_OFFSET, capacity());
        UartRegs.wr32(a, UartRegs.OFF_RX_LEVEL - UartRegs.HOST_A_OFFSET, rxGivenToWindow - rxPopAck);
        UartRegs.wr32(a, UartRegs.OFF_TX_TOTAL - UartRegs.HOST_A_OFFSET, (int) txTotal);
        UartRegs.wr32(a, UartRegs.OFF_RX_TOTAL - UartRegs.HOST_A_OFFSET, (int) rxTotal);
        ram.write(OcBoardLayout.UART_CACHE_BASE + UartRegs.HOST_A_OFFSET, a);
        ram.write(OcBoardLayout.UART_CACHE_BASE + UartRegs.HOST_B_OFFSET, new byte[UartRegs.HOST_B_BYTES]);
    }

    /** 读窗口里从 {@code from}（单调计数）起的 {@code count} 字节（跨环尾分两段） */
    private byte[] readWindow(UartRegs.GuestRam ram, int base, int from, int count, int ringBytes) {
        final byte[] out = new byte[count];
        int idx = from & (ringBytes - 1);
        int done = 0;
        while (done < count) {
            final int run = Math.min(count - done, ringBytes - idx);
            final byte[] seg = ram.read(OcBoardLayout.UART_CACHE_BASE + base + idx, run);
            if (seg == null || seg.length == 0) {
                return java.util.Arrays.copyOf(out, done);
            }
            System.arraycopy(seg, 0, out, done, Math.min(run, seg.length));
            done += Math.min(run, seg.length);
            idx = 0;
        }
        return out;
    }

    /** 写窗口（跨环尾分两段） */
    private void writeWindow(UartRegs.GuestRam ram, int base, int to, byte[] data, int count, int ringBytes) {
        int idx = to & (ringBytes - 1);
        int done = 0;
        while (done < count) {
            final int run = Math.min(count - done, ringBytes - idx);
            ram.write(OcBoardLayout.UART_CACHE_BASE + base + idx,
                    java.util.Arrays.copyOfRange(data, done, done + run));
            done += run;
            idx = 0;
        }
    }

    // ==================== 诊断（面板/无人化/心跳日志） ====================

    public long ovrTx() {
        return ovrTx;
    }

    public long ovrRx() {
        return ovrRx;
    }

    public long ovrTotal() {
        return ovrTx + ovrRx;
    }

    public long txTotal() {
        return txTotal;
    }

    public long rxTotal() {
        return rxTotal;
    }

    public long inboxDropped() {
        return inboxDropped;
    }

    public int inboxPending() {
        return inbox.size();
    }

    /**
     * 接收窗口占用（字节）。
     *
     * <p>⚠ 这是**织物侧的近似**：用的是上一次同步读回的固件读计数。固件在两次同步之间可能又读走了
     * 若干字节 ⇒ 这里可能比真实占用小（诊断用，取 0 下限）。织物的**空闲量核算**用的是同步开头
     * 现读的那个值（那一个才是准的），所以不会越写。</p>
     */
    public int rxWindowLevel() {
        return Math.max(0, rxGivenToWindow - rxPopAck);
    }

    /** 诊断：固件写进窗口、但还没被真正送进硬件缓存的字节数（正常恒为 0） */
    public int txWindowPending() {
        return txPushAck - txTakenFromWindow;
    }

    /** 诊断：真正进了硬件缓存的发送字节数（单调） */
    public int txAccepted() {
        return txTakenFromWindow;
    }

    public long syncs() {
        return syncs;
    }

    public long anomalies() {
        return anomalies;
    }

    public long missingWindow() {
        return missingWindow;
    }

    public int lastSr() {
        return lastSr;
    }

    public boolean fifoEnabled() {
        return fifoEnabled;
    }

    @Override
    public String toString() {
        return name + "[" + (moduleLoaded ? ("FIFO" + moduleDepth()) : "1B DR")
                + (fifoEnabled ? " 使能" : " 旁路") + " 发=" + txTotal + " 收=" + rxTotal
                + " 溢出=" + ovrTotal() + "]";
    }
}
