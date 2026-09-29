package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.api.CpuCore;
import com.hdf.cryptand.soc.api.InterruptSource;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Resettable;
import com.hdf.cryptand.soc.api.Steppable;
import com.hdf.cryptand.soc.device.RamDevice;
import com.hdf.cryptand.soc.device.RomDevice;
import com.hdf.cryptand.soc.memory.SimpleInterruptController;
import com.hdf.cryptand.soc.memory.SimpleMemoryMap;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== SoC 装配（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>把「CPU + 地址空间 + 设备 + 中断控制器」组装成一台可运行的 SoC。
 * <b>装配 = 定义能力表</b>：挂了哪些设备，芯片就能做什么（沙箱的能力语义）。</p>
 *
 * <p>典型布局（与真实 MCU 同构）：</p>
 * <pre>
 *   0x0000_0000  ROM  固件（复位向量处）
 *   0x1000_0000  RegBank / UART / Timer / PWM / ADC …（设备寄存器）
 *   0x2000_0000  RAM  运行时内存
 * </pre>
 *
 * <p><b>时间语义</b>：{@link #step(int)} 的 cycles 是 SoC 自己的周期单位；每步先推进
 * {@link Steppable} 外设（产生中断/更新输入），再让 CPU 执行同样数量的指令。
 * 与 MC tick 无关——固件内定时因此保持正确。</p>
 */
public final class SocBoard {

    /** 设备与 CPU 的交替粒度（指令数）——越小越"并行"，开销略增 */
    private static final int STEP_SLICE = 1_000;

    private final CpuCore cpu;
    private final SimpleMemoryMap memoryMap;
    private final SimpleInterruptController interruptController;
    private final List<MemoryMappedDevice> devices = new ArrayList<>();
    private final List<Steppable> steppables = new ArrayList<>();
    private final List<Resettable> resettables = new ArrayList<>();
    private final List<String> capabilityNames = new ArrayList<>();
    private int memoryBytes;

    private SocBoard(Builder builder) {
        this.cpu = builder.cpu;
        this.memoryMap = builder.memoryMap;
        this.interruptController = builder.interruptController;
        this.devices.addAll(builder.devices);
        this.steppables.addAll(builder.steppables);
        this.resettables.addAll(builder.resettables);
        this.capabilityNames.addAll(builder.capabilityNames);
        this.memoryBytes = builder.memoryBytes;
        cpu.setMemoryMap(memoryMap);
        cpu.setInterruptController(interruptController);
        cpu.reset();
    }

    // ==================== 运行 ====================

    /**
     * 推进 SoC。
     *
     * @param cycles 周期（= 指令预算）数
     * @return CPU 实际执行的指令数（&lt; cycles 表示停机/故障/等待中断）
     */
    public int step(int cycles) {
        if (cycles <= 0) {
            return 0;
        }
        // ⚠ 2026-09-15 修正：设备与 CPU **交替推进**（按切片），而非"tick 开头推一次设备"。
        //   否则固件里"忙等设备状态"（如 UART 的 LSR.THRE）会死锁——设备没有机会前进。
        //   切片大小是"设备与 CPU 的交替粒度"，不影响总预算。
        final int slice = cycles <= STEP_SLICE ? cycles : STEP_SLICE;
        int executed = 0;
        while (executed < cycles) {
            final int chunk = Math.min(slice, cycles - executed);
            for (Steppable device : steppables) {
                device.step(chunk);
            }
            final int done = cpu.step(chunk);
            executed += done;
            if (done < chunk) {
                break;   // 停机 / 故障 / 等待中断
            }
        }
        return executed;
    }

    /** 全板复位（CPU + 设备） */
    public void reset() {
        cpu.reset();
        for (Resettable device : resettables) {
            device.reset();
        }
        interruptController.reset();
    }

    // ==================== 固件 / 内存访问 ====================

    /*
     * ⚠ 这两个方法**只是转发**，真正干活的是内核（{@link CpuCore#readMemory} / {@link CpuCore#loadImage}）。
     * 原因：RAM/ROM 的持有者是内核 —— Java 内核挂在 MemoryMap 上，native 内核则是 C++ 侧的缓冲，
     * 本类用 {@code .ram()/.rom()} 挂上去的 RamDevice/RomDevice 在 native 内核下只是**布局声明**
     * （固件的访存根本不经过它们）。宿主若直接读那张表，拿到的是从来没被写过的影子设备：
     * 组件桥取 gpu.blit 的字符阵列时全 0，屏幕因此全黑。
     */

    /** 载入固件镜像到指定物理地址（装配后亦可调用 = "重新烧录"） */
    public void loadFirmware(long address, byte[] image) {
        cpu.loadImage(address, image);
    }

    /** 读 guest 物理内存（自测/快照/UI；组件桥取缓冲区也走这里） */
    public byte[] readMemory(long address, int length) {
        return cpu.readMemory(address, length);
    }

    /**
     * 写 guest 物理内存。
     *
     * <p>用途：宿主把**外部设备邮箱区**的结果写回 guest RAM（{@code OcAbi.MAILBOX_BASE} 那段）。
     * 必须转发给 {@code cpu} 而不是直接写 {@code memoryMap} —— native 内核**自持 RAM**，
     * Java 侧那块 {@code RamDevice} 只是影子（这正是当初"屏幕全黑"的根因）。</p>
     */
    public void writeMemory(long address, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        cpu.writeMemory(address, data);
    }

    /** 写一个 32 位小端整数到 guest 内存（邮箱字段用，省去每次拼 byte[]）。 */
    public void writeInt(long address, int value) {
        writeMemory(address, new byte[]{
                (byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF),
                (byte) ((value >>> 16) & 0xFF), (byte) ((value >>> 24) & 0xFF)});
    }

    /** 读 guest 内存里的一个 32 位小端整数（邮箱字段用）。 */
    public int readInt(long address) {
        final byte[] b = readMemory(address, 4);
        if (b.length < 4) {
            return 0;
        }
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    // ==================== 访问器 ====================

    public CpuCore cpu() {
        return cpu;
    }

    public SimpleMemoryMap memoryMap() {
        return memoryMap;
    }

    public SimpleInterruptController interruptController() {
        return interruptController;
    }

    /** 能力表（挂载的设备；顺序 = 装配顺序） */
    public List<MemoryMappedDevice> devices() {
        return new ArrayList<>(devices);
    }

    /** 能力名列表（诊断/UI 显示"这台芯片有什么"） */
    public List<String> capabilityNames() {
        return new ArrayList<>(capabilityNames);
    }

    /** 地址空间总字节数（沙箱配额核算用） */
    public int memoryBytes() {
        return memoryBytes;
    }

    /** 诊断：地址空间布局 */
    public String describeLayout() {
        return memoryMap.describe() + interruptController.describe();
    }

    // ==================== 装配器 ====================

    public static Builder builder(CpuCore cpu) {
        return new Builder(cpu);
    }

    /** SoC 装配器（能力表构建） */
    public static final class Builder {
        private final CpuCore cpu;
        private final SimpleMemoryMap memoryMap = new SimpleMemoryMap();
        private final SimpleInterruptController interruptController = new SimpleInterruptController();
        private final List<MemoryMappedDevice> devices = new ArrayList<>();
        private final List<Steppable> steppables = new ArrayList<>();
        private final List<Resettable> resettables = new ArrayList<>();
        private final List<String> capabilityNames = new ArrayList<>();
        private int memoryBytes;

        private Builder(CpuCore cpu) {
            if (cpu == null) {
                throw new IllegalArgumentException("cpu must not be null");
            }
            this.cpu = cpu;
        }

        /** 挂载 RAM */
        public Builder ram(long address, int size) {
            return map(address, new RamDevice(size), "RAM " + size + "B", false);
        }

        /** 挂载 ROM 并载入固件（ROM 区大小 = 固件长度） */
        public Builder rom(long address, byte[] firmware) {
            return rom(address, firmware, firmware.length);
        }

        /**
         * 挂载 ROM 并载入固件，**显式给出 ROM 区大小**。
         *
         * <p>为什么需要它：OC 机器的 ROM 是**分区**的（Boot 区 0x0 / 系统区 0x0001_0000，
         * 见 {@code OcBootLoader#LOAD_BASE}）。引导服务会把系统镜像"烧"进 ROM 的系统区，
         * 而 Java 内核下这条路径走 {@code RomDevice.writeImage}（整块 arraycopy，**不做
         * 范围检查**）—— 若 ROM 设备只按"EEPROM 镜像长度"申请（Boot 才 2.4KB），
         * 写 14KB 的 Cryptand OS / 406KB 的 UI OS 就会直接越界。
         * native 内核自持 ROM，不看这块影子设备，所以这个坑只在 Java 回落时爆 ——
         * 但装配必须只有一份，不能靠"主路径碰巧没事"。</p>
         */
        public Builder rom(long address, byte[] firmware, int sizeBytes) {
            final int size = Math.max(sizeBytes, Math.max(firmware.length, 4));
            final RomDevice rom = new RomDevice(size, "ROM");
            rom.writeImage(0, firmware, 0, firmware.length);
            return map(address, rom, "ROM " + size + "B", false);
        }

        /** 挂载普通设备（MMIO，无中断） */
        public Builder device(long address, MemoryMappedDevice device, String name) {
            return map(address, device, name, false);
        }

        /** 挂载中断源设备（MMIO 寄存器 + 中断线；如定时器/PWM/UART） */
        public Builder deviceWithIrq(long address, MemoryMappedDevice device, String name) {
            return map(address, device, name, true);
        }

        /**
         * 挂载中断源设备到**指定中断号**（2026-09-17 加，为 FreeRTOS 铺路）。
         *
         * <p>RISC-V 的中断号是标准固定的：{@code 3 = MSIP}、{@code 7 = MTIP（机器定时器）}、
         * {@code 11 = MEIP}。FreeRTOS 的 RISC-V port 只打开 {@code mie} 的 {@code 1<<7}，
         * 所以实时内核场景下定时器**必须**注册到 7 号（见 {@code SimpleInterruptController.registerSourceAt}）。</p>
         */
        public Builder deviceWithIrqAt(long address, MemoryMappedDevice device, String name, int irq) {
            map(address, device, name, false);
            if (device instanceof InterruptSource source) {
                interruptController.registerSourceAt(irq, source, name);
            }
            return this;
        }

        /**
         * 注册<b>纯中断源</b>（不占地址空间；如外部引脚中断、内部看门狗）。
         * <p>能力表同样登记（UI 可见"这台芯片有哪些中断线"）。</p>
         */
        public Builder interruptSource(InterruptSource source, String name) {
            if (source != null) {
                interruptController.registerSource(source, name);
                capabilityNames.add("IRQ " + (name == null ? "source" : name));
            }
            return this;
        }

        private Builder map(long address, MemoryMappedDevice device, String name, boolean withIrq) {
            if (!memoryMap.addDevice(address, device)) {
                throw new IllegalStateException("address 0x" + Long.toHexString(address)
                        + " overlaps existing mapping for " + name);
            }
            devices.add(device);
            capabilityNames.add(String.format("0x%08X %s", address, name));
            memoryBytes += device.getLength();
            if (device instanceof Steppable steppable) {
                steppables.add(steppable);
            }
            if (device instanceof Resettable resettable) {
                resettables.add(resettable);
            }
            if (withIrq && device instanceof InterruptSource source) {
                interruptController.registerSource(source, name);
            }
            return this;
        }

        public SocBoard build() {
            return new SocBoard(this);
        }
    }
}
