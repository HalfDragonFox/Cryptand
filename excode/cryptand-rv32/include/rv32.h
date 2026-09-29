/*
 * ============================================================================
 * Cryptand RV32 native kernel — public interface (rv32.h, 2026-09-17)
 *
 * 用户 2026-09-17 决定：**内核模拟直接用 C++**（不再走"先优化 Java"的中间路线）。
 * 本工程放 excode/（所有非 Java 代码的统一目录），编译成 DLL 由 JNI 调用。
 *
 * 设计要点（与 Java 版 Rv32Core 语义对齐，但为性能重写）：
 *   1. **内存自持**：ROM/RAM 由 native 侧 malloc，Java 只在上电时灌镜像、诊断时读写，
 *      解释循环里没有任何 JNI 往返。
 *   2. **MMIO 用"停下 + 事务"模型**：访问到非 ROM/RAM 地址时记录一条待处理事务并
 *      **立即返回**（不猜设备语义）。Java 侧在设备上兑现后调用 completeMmio() 继续。
 *      设备访问频率低（UART/定时器每 tick 几次），所以这个往返代价可以忽略，
 *      换来的是"设备仍然是 Java 对象"（soc 子包的 RegBank/Timer/UART 全部复用）。
 *   3. **指令集**：RV32IM + Zicsr + M 模式陷阱（第一版）。S/U + Sv32 留给 Linux 里程碑。
 *   4. 计数器：mcycle/minstret 真实递增（FreeRTOS 与我们的自测都依赖）。
 * ============================================================================
 */
#ifndef CRYPTAND_RV32_H
#define CRYPTAND_RV32_H

#include <cstdint>
#include <vector>

namespace cryptand {

/* ---------- 特权级（与 Rv32.java 一致） ---------- */
enum Privilege : uint8_t {
    PRIV_U = 0,
    PRIV_S = 1,
    PRIV_M = 3,
};

/* ---------- 特权档位（MCU 默认 M_ONLY；SOC/CPU 后续开 S/U） ---------- */
enum PrivilegeMode : uint8_t {
    M_ONLY = 0,
    M_S = 1,
    M_S_U = 2,
};

/* ---------- 陷阱原因（与 SocFault 对齐） ---------- */
enum Cause : uint32_t {
    CAUSE_INSTRUCTION_ADDR_MISALIGNED = 0,
    CAUSE_INSTRUCTION_ACCESS_FAULT = 1,
    CAUSE_ILLEGAL_INSTRUCTION = 2,
    CAUSE_BREAKPOINT = 3,
    CAUSE_LOAD_ADDR_MISALIGNED = 4,
    CAUSE_LOAD_ACCESS_FAULT = 5,
    CAUSE_STORE_ADDR_MISALIGNED = 6,
    CAUSE_STORE_ACCESS_FAULT = 7,
    CAUSE_ECALL_FROM_U = 8,
    CAUSE_ECALL_FROM_S = 9,
    CAUSE_ECALL_FROM_M = 11,
    INTERRUPT_BIT = 0x80000000u,
};

/* ---------- CSR ---------- */
enum Csr : uint32_t {
    CSR_MSTATUS = 0x300, CSR_MISA = 0x301, CSR_MEDELEG = 0x302, CSR_MIDELEG = 0x303,
    CSR_MIE = 0x304, CSR_MTVEC = 0x305, CSR_MCOUNTEREN = 0x306,
    CSR_MSCRATCH = 0x340, CSR_MEPC = 0x341, CSR_MCAUSE = 0x342, CSR_MTVAL = 0x343,
    CSR_MIP = 0x344, CSR_MCYCLE = 0xB00, CSR_MINSTRET = 0xB02,
    CSR_MVENDORID = 0xF11, CSR_MARCHID = 0xF12, CSR_MIMPID = 0xF13, CSR_MHARTID = 0xF14,
};

/* ---------- 配置 ---------- */
struct Config {
    uint32_t resetVector = 0x80000000u;
    uint32_t ramBase = 0x20000000u;
    uint32_t ramSize = 128u * 1024u;
    uint32_t romBase = 0x00000000u;
    uint32_t romSize = 512u * 1024u;

    /* CLINT（机器定时器）：**虚拟机自建**的一块寄存器窗口，布局照真 CLINT ——
     * +0x00 mtime_lo, +0x04 mtime_hi（只读）, +0x08 mtimecmp_lo, +0x0C mtimecmp_hi（读写）。
     * guest 读写它就是普通内存访问：不产生 MMIO 事务、不停 CPU、不经过宿主
     * （2026-09-28 用户定案：定时器和 UART 一样是虚拟机构建的模块，周期管理全由虚拟机完成）。
     * clintSize == 0 表示不建这台定时器（纯内核自测/老配置）。 */
    uint32_t clintBase = 0x10001000u;
    uint32_t clintSize = 16u;
    uint32_t mtimeDiv = 1u;             /* 每多少个执行周期 mtime 进 1 */

    PrivilegeMode privilegeMode = M_ONLY;
    bool enableA = false;
    bool haltWithoutHandler = true;
};

/* ---------- MMIO 事务（Java 侧 drain）---------- */
struct MmioTxn {
    uint32_t addr = 0;
    uint32_t value = 0;      /* 写：要写入的值；读：complete 时回填 */
    uint32_t size = 4;
    uint8_t  isWrite = 0;
    uint8_t  pending = 0;
};

/* ---------- 机器 ---------- */
class Machine {
public:
    explicit Machine(const Config& cfg);

    void reset();

    /** 上电灌镜像（写进 ROM 区） */
    void loadImage(uint32_t addr, const uint8_t* data, uint32_t len);

    /**
     * 执行至多 cycles 条指令。
     * @return 实际执行条数（< cycles 表示停机 / 故障 / 等待 MMIO 兑现）
     */
    int step(int cycles);

    /* ---------- 诊断 / JNI 访问 ---------- */
    uint32_t pc() const { return pc_; }
    uint32_t reg(int i) const { return (i > 0 && i < 32) ? regs_[i] : 0u; }

    /* ---------- 调试写入（沙箱断点/单步用；只在沙箱线程调用） ---------- */
    void setReg(int i, uint32_t v) { if (i > 0 && i < 32) regs_[i] = v; }
    void setPc(uint32_t v) { pc_ = v; }
    void clearFault() { faulted_ = false; }
    void clearHalted() { halted_ = false; }
    uint32_t csr(uint32_t addr) const;
    uint64_t instructionsRetired() const { return instret_; }
    bool faulted() const { return faulted_; }
    bool halted() const { return halted_; }
    uint32_t faultCause() const { return faultCause_; }
    uint32_t faultTval() const { return faultTval_; }
    uint32_t faultEpc() const { return faultEpc_; }

    /* ---------- MMIO 往返 ---------- */
    bool hasMmio() const { return mmio_.pending != 0; }
    const MmioTxn& mmio() const { return mmio_; }
    /** 设备读兑现后把值交回（写事务不需要值） */
    void completeMmio(uint32_t value);

    /**
     * 宿主推进"设备待处理中断位图"（每次 step 前调一次）。
     * RISC-V 标准位：3=MSIP、7=MTIP（机器定时器，FreeRTOS tick 用这个）、11=MEIP。
     */
    void setPendingInterrupts(uint32_t bits);

    /* ---------- CLINT（虚拟机自建定时器；宿主只读诊断） ---------- */
    uint64_t mtime() const { return mtime_; }
    uint64_t mtimecmp() const { return mtimecmp_; }
    bool timerEnabled() const { return timerEnabled_; }
    bool timerPending() const { return mtipAsserted_; }
    uint64_t timerIrqCount() const { return timerIrqCount_; }   /* MTIP 断言次数 = tick 数 */

    /* ---------- 内存（诊断/自测） ---------- */
    void readMemory(uint32_t addr, uint8_t* out, uint32_t len) const;
    void writeMemory(uint32_t addr, const uint8_t* in, uint32_t len);

    /**
     * 主机内存直通（零拷贝）：返回物理地址所在缓冲区的指针。
     *
     * 供 JNI 侧包装成 DirectByteBuffer（绕开 GetByteArrayElements + memcpy）。
     * 只有整段落在 ROM 或 RAM 内才返回指针，其余（含设备区、越界、len == 0）返回 nullptr。
     * ⚠ 返回的是**共享**内存：沙箱仍在写它，调用方必须立即消费，且不得跨 destroy 持有。
     */
    uint8_t* memoryPointer(uint32_t addr, uint32_t len);

private:
    Config cfg_;
    uint32_t regs_[32];
    uint32_t pc_;
    uint32_t mtvec_, mscratch_, mepc_, mcause_, mtval_, mstatus_, mie_, mip_;
    uint32_t medeleg_, mideleg_, misa_;
    uint64_t mcycle_, instret_;

    std::vector<uint8_t> rom_;
    std::vector<uint8_t> ram_;

    bool faulted_;
    bool halted_;
    bool waitingForInterrupt_;
    uint32_t faultCause_, faultTval_, faultEpc_;
    uint32_t pendingIrq_;              /* 宿主注入的**外部事件**中断位图（定时器不在这里） */

    /* ---------- CLINT（VM 自建定时器：时间与周期由虚拟机自己管） ---------- */
    uint64_t mtime_ = 0;               /* 虚拟机内部时间，随执行周期推进 */
    uint64_t mtimecmp_ = 0;
    uint32_t mtimeRemainder_ = 0;      /* mtimeDiv > 1 时的余数累加器 */
    int64_t  mtipCountdown_ = 0;       /* 距 mtimecmp 还有多少个执行周期（<=0 = 已到） */
    bool     timerEnabled_ = false;    /* 写过 mtimecmp 才算启用（真 CLINT 没有使能位） */
    bool     mtipAsserted_ = false;    /* 电平语义：mtime >= mtimecmp */
    uint64_t timerIrqCount_ = 0;       /* MTIP 由 0 变 1 的次数（= tick 数，诊断用） */

    MmioTxn mmio_;

    /* ---------- 内存访问 ---------- */
    bool inRom(uint32_t addr, uint32_t size) const;
    bool inRam(uint32_t addr, uint32_t size) const;
    uint32_t loadMem(uint32_t addr, uint32_t size);      /* 可能触发 MMIO 停摆 */
    void     storeMem(uint32_t addr, uint32_t size, uint32_t value);

    /* ---------- CLINT（虚拟机自建定时器） ---------- */
    bool     inClint(uint32_t addr, uint32_t size) const;
    uint32_t clintLoad(uint32_t addr, uint32_t size);
    void     clintStore(uint32_t addr, uint32_t size, uint32_t value);
    void     refreshTimerDeadline();    /* 重排下一次 MTIP（写 mtimecmp / mtime 前进后） */
    uint32_t effectiveIrq() const;      /* 宿主外部事件位 | 虚拟机自产 MTIP */
    int      runChunk(int cycles);      /* 跑到定时器边界为止的一个子批 */

    /* ---------- 取指与执行 ---------- */
    bool fetch(uint32_t addr, uint32_t& out);
    void execute(uint32_t instr);
    void trap(uint32_t cause, uint32_t tval);
    void enterTrapStateM();
    uint32_t doMret();

    /* ---------- CSR 读写（含特权/只读检查） ---------- */
    uint32_t readCsrChecked(uint32_t addr, bool& ok);
    void writeCsrChecked(uint32_t addr, uint32_t value, bool& ok);
    uint32_t readCsr(uint32_t addr) const;
    void writeCsr(uint32_t addr, uint32_t value);

    void raiseIllegal(uint32_t instr);
};

} /* namespace cryptand */

#endif /* CRYPTAND_RV32_H */
