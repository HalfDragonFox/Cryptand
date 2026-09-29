package com.hdf.cryptand.soc.board;

/**
 * ===== UART 寄存器窗口的**布局唯一来源**（guest RAM 里的设备寄存器，纯 Java 零 MC，2026-09-27）=====
 *
 * <h3>层级（用户定案：虚拟机 = 硬件层，芯片 = 只执行 + 碰寄存器）</h3>
 * <pre>
 *   世界侧（玩家 / 无人化工具 / 控制台 / 日志）
 *        │  每 tick 整块供料（宿主只碰"外部世界"这一侧，芯片看不见宿主）
 *        ▼
 *   虚拟机 = 硬件层（FPGA 式 fabric）
 *        └── {@link com.hdf.cryptand.soc.peripheral.UartHardware}：UART 这块"硬件"
 *              · 寄存器文件（本类的布局） + 硬件缓存（1 字节 DR；装载通用 FIFO 模块则更深）
 *              · 收发装配 / 波特率节流 / 状态位（TXE TC RXNE OVR）/ 溢出计数
 *        ▲  ▲ 只有**普通内存读写**（零 MMIO 事务）：固件读寄存器 → 像在碰真芯片
 *        │  │
 *   芯片（RV 内核 + 固件）：只执行指令 + 读写 DR / 轮询状态位
 * </pre>
 * <p>⚠ 固件侧**没有**任何"叫宿主 pump 一下"的入口：它只看到这段内存里的寄存器。
 * 旧口径的 16550 **MMIO 寄存器组**（{@code 0x1000_2000}，每字符跨线程事务、每次停 CPU）已整条删除。</p>
 *
 * <h3>寄存器布局（本文件是唯一来源；C 侧镜像 = hal.h 的 HAL_UART_*，由闸门逐项对拍）</h3>
 * <pre>
 *   +0x00 magic      u32  'CUAR'（织物写；固件读到它才知道窗口已就绪）
 *   +0x04 sr         u32  状态位 SR_*（织物写）
 *   +0x08 tx_taken   u32  单调：织物已从窗口取走的发送字节数
 *   +0x0C rx_given   u32  单调：织物已投进窗口的接收字节数
 *   +0x10 ovr        u32  单调：溢出（丢掉的）字节数 —— **绝不静默**
 *   +0x14 tx_slots   u32  当前生效的发送缓冲字节数（1 = 经典 DR；装载 FIFO 且使能 = 模块深度）
 *   +0x18 rx_level   u32  当前接收占用
 *   +0x1C tx_total   u32  单调：已交给线路（世界侧出口）的字节数
 *   +0x20 rx_total   u32  单调：世界侧已送进器件的字节数
 *   +0x24 reserved   u32
 *   +0x28 cr1        u32  控制位 CR1_*（**固件独占**）
 *   +0x2C tx_push    u32  单调：固件写 DR 的次数（**固件独占**；写事件靠它发布）
 *   +0x30 rx_pop     u32  单调：固件读 DR 的次数（**固件独占**；取走靠它发布）
 *   +0x34 ovr_ack    u32  单调：软件已确认的溢出次数（**固件独占**；等价"读 SR+DR 清 ORE"）
 *   +0x38 reserved   u32（织物）
 *   +0x3C reserved   u32（织物）
 *   +0x40 tx_buf[TX_BYTES]  发送数据窗口（固件写、织物读）
 *   +0x40+RX?  rx_buf[RX_BYTES] 接收数据窗口（织物写、固件读）
 * </pre>
 *
 * <h3>为什么数据也有窗口（而不是只给一个 1 字节 DR）</h3>
 * <p>芯片能看到的只有**普通内存**（零 MMIO 纪律），所以"数据寄存器/FIFO 数据口"必须映射成内存槽位。
 * 但**容量不由软件决定、也不写死在寄存路径里**：它等于**芯片装载的通用 FIFO 模块深度**
 * （{@link #TX_BYTES} = 本芯片设计值），默认不使能 FIFO 时**生效容量 = 1 字节**（经典 DR 语义：
 * 没及时取走就置 OVR，不静默丢）。软件使能（CR1.FIFOEN）后才按模块深度生效 —— 照真实芯片。</p>
 *
 * <h3>单写者纪律（每个字段一个写者 ⇒ 不需要锁）</h3>
 * <p>织物独占前 10 个字段 + reserved；固件独占 {@code cr1 / tx_push / rx_pop / ovr_ack}。
 * 织物发布时**只写自己那两段**（{@link #HOST_A_OFFSET} 与 {@link #HOST_B_OFFSET}），
 * 中间 16 字节归固件 —— 整块写会把它刚推进的计数倒回去（表现为"同一批日志被取两遍"）。</p>
 */
public final class UartRegs {

    /** 本芯片设计装载的通用 FIFO 模块深度（2 的幂；计价见 PeripheralFifo.slotsFor） */
    public static final int TX_BYTES = 256;
    public static final int RX_BYTES = 256;

    /** 头部字节数（寄存器区；数据窗口紧随其后） */
    public static final int HEADER_BYTES = 64;

    /** 整段窗口大小（寄存器区 + 两个数据窗口）= 装配 guest RAM 时要算进去的那一份 */
    public static final int TOTAL_BYTES = HEADER_BYTES + TX_BYTES + RX_BYTES;

    /** 头部魔数：小端写出去就是 'C''U''A''R' */
    public static final int MAGIC = 0x52415543;

    public static final int OFF_MAGIC = 0x00;
    /** 状态位 {@code SR_*}（织物写） */
    public static final int OFF_SR = 0x04;
    public static final int OFF_TX_TAKEN = 0x08;
    public static final int OFF_RX_GIVEN = 0x0C;
    public static final int OFF_OVR = 0x10;
    public static final int OFF_TX_SLOTS = 0x14;
    public static final int OFF_RX_LEVEL = 0x18;
    public static final int OFF_TX_TOTAL = 0x1C;
    public static final int OFF_RX_TOTAL = 0x20;
    /** 控制位 {@code CR1_*}（**固件独占**） */
    public static final int OFF_CR1 = 0x28;
    /** 单调写 DR 次数（**固件独占**） */
    public static final int OFF_TX_PUSH = 0x2C;
    /** 单调读 DR 次数（**固件独占**） */
    public static final int OFF_RX_POP = 0x30;
    /** 单调溢出确认次数（**固件独占**） */
    public static final int OFF_OVR_ACK = 0x34;

    /** 发送数据窗口（固件写、织物读） */
    public static final int OFF_TX_BUF = HEADER_BYTES;
    /** 接收数据窗口（织物写、固件读） */
    public static final int OFF_RX_BUF = HEADER_BYTES + TX_BYTES;

    // ==================== 状态位 SR_*（照真实芯片的命名与语义） ====================

    /** 发送数据寄存器空（**有空位可写**；固件写 DR 之前轮询它） */
    public static final int SR_TXE = 0x0001;
    /** 发送完成（硬件发送管线已空：FIFO/DR 与移位寄存器都没有字节） */
    public static final int SR_TC = 0x0002;
    /** 接收数据可读 */
    public static final int SR_RXNE = 0x0004;
    /** **溢出**（发送侧被覆盖 或 接收侧无处可放而丢弃）：软件确认（ovr_ack）之前一直为 1 */
    public static final int SR_OVR = 0x0008;
    /** 发送缓冲空（没有待发字节） */
    public static final int SR_TXFE = 0x0010;
    /** 接收缓冲满 */
    public static final int SR_RXFF = 0x0020;
    /** 接收占用达到软件设的阈值（阈值位语义） */
    public static final int SR_RXFT = 0x0040;
    /** 当前生效的 FIFO 使能（软件写 CR1.FIFOEN 且芯片装载了模块） */
    public static final int SR_FIFOEN = 0x0080;
    /** 芯片**装载了** FIFO 模块（设计值，软件改不了） */
    public static final int SR_FIFOMOD = 0x0100;

    // ==================== 控制位 CR1_*（固件独占写） ====================

    /** 软件使能 FIFO（不使能 ⇒ 生效容量退化为 1 字节 DR，照真实芯片） */
    public static final int CR1_FIFOEN = 0x0001;
    /** 接收阈值位（两位；0=1/4、1=1/2、2=3/4、3=满） */
    public static final int CR1_RXFTH_MASK = 0x0006;
    public static final int CR1_RXFTH_SHIFT = 1;
    /** 发送阈值位（两位，语义同真实芯片；本模型用轮询，主要用于状态/诊断） */
    public static final int CR1_TXFTH_MASK = 0x0018;
    public static final int CR1_TXFTH_SHIFT = 3;
    /** 清接收缓冲 */
    public static final int CR1_RXFLUSH = 0x0020;
    /** 清发送缓冲 */
    public static final int CR1_TXFLUSH = 0x0040;

    /**
     * 织物独占的第一段：magic + 全部状态/计数（到 reserved 为止）。
     * 它与第二段之间夹着 {@link #GUEST_OFFSET} 那 16 字节（cr1/tx_push/rx_pop/ovr_ack）——归固件。
     */
    public static final int HOST_A_OFFSET = OFF_MAGIC;
    public static final int HOST_A_BYTES = OFF_CR1 - OFF_MAGIC;                 // 40
    /** 固件独占区间（cr1 / tx_push / rx_pop / ovr_ack） */
    public static final int GUEST_OFFSET = OFF_CR1;
    public static final int GUEST_BYTES = 16;
    /** 织物独占的第二段（两个 reserved 字，凑齐 64 字节头） */
    public static final int HOST_B_OFFSET = GUEST_OFFSET + GUEST_BYTES;         // 0x38
    public static final int HOST_B_BYTES = HEADER_BYTES - HOST_B_OFFSET;        // 8

    /**
     * 织物的 guest 内存访问面（只要"读写 guest RAM"两件事）。
     *
     * <p>两个内核实现与离线装置都满足它：{@code CpuCore.readMemory/writeMemory}（native 沙箱自持 RAM）
     * 与 {@code SocBoard.readMemory/writeMemory}（Java 内核的映射表）⇒ 真机与离线闸门共用**同一份**器件实现。</p>
     */
    public interface GuestRam {
        byte[] read(long address, int length);

        void write(long address, byte[] data);
    }

    /** 从 Java 内核的板子取一个访问面（离线装置用；两方法接口不是函数式接口，方法引用套不上） */
    public static GuestRam of(SocBoard board) {
        return new GuestRam() {
            @Override
            public byte[] read(long address, int length) {
                return board.readMemory(address, length);
            }

            @Override
            public void write(long address, byte[] data) {
                board.writeMemory(address, data);
            }
        };
    }

    /** 从 CPU 内核取一个访问面（**真机路径**：native 沙箱自持 RAM，读写都经内核契约） */
    public static GuestRam of(com.hdf.cryptand.soc.api.CpuCore cpu) {
        return new GuestRam() {
            @Override
            public byte[] read(long address, int length) {
                return cpu.readMemory(address, length);
            }

            @Override
            public void write(long address, byte[] data) {
                cpu.writeMemory(address, data);
            }
        };
    }

    /**
     * 生效的缓冲容量：装载了 FIFO 模块**且**软件使能才算模块深度，否则就是经典 1 字节 DR。
     *
     * @param moduleDepth 装载的模块深度（0/1 = 没装载）
     */
    public static int effectiveSlots(int moduleDepth, boolean fifoEnabled) {
        if (moduleDepth <= 1 || !fifoEnabled) {
            return 1;
        }
        return Math.min(moduleDepth, TX_BYTES);     // 不会超过窗口容量（窗口 = 芯片设计值）
    }

    /** 初始镜像：magic + SR（空缓冲：TXE/TC/TXFE）+ 生效容量 1（软件还没使能 FIFO） */
    public static byte[] initialImage(boolean moduleLoaded) {
        final byte[] img = new byte[TOTAL_BYTES];
        wr32(img, OFF_MAGIC, MAGIC);
        int sr = SR_TXE | SR_TC | SR_TXFE;
        if (moduleLoaded) {
            sr |= SR_FIFOMOD;
        }
        wr32(img, OFF_SR, sr);
        wr32(img, OFF_TX_SLOTS, 1);
        return img;
    }

    /** 小端读（织物侧跨包使用：UartHardware 在 soc.peripheral） */
    public static int rd32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** 小端写（同上） */
    public static void wr32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private UartRegs() {
    }
}
