/*
 * ============================================================================
 * Cryptand RV32 native kernel — 解释器核心（rv32_core.cpp，2026-09-17）
 *
 * 语义与 Java 版 com.hdf.cryptand.soc.riscv.Rv32Core 对齐（同一套自测固件要能跑出同样结果），
 * 但为性能重写：内存自持、无 JNI 往返、switch 派发。
 *
 * 第一版范围：RV32I + M + Zicsr + **M 模式**陷阱（MCU/SOC 档跑 FreeRTOS/LVGL/我们自己的 OS 够用）。
 * S/U + Sv32（Linux 里程碑）在本文件留了结构位置：privilege 判定与 CSR 分组已经是参数化的。
 * ============================================================================
 */

#include "rv32.h"

#include <cstring>

namespace cryptand {

/* ==================== 指令字段解码 ==================== */
enum Opcode : uint32_t {
    OP_LOAD = 0x03, OP_IMM = 0x13, OP_AUIPC = 0x17, OP_STORE = 0x23,
    OP_OP = 0x33, OP_LUI = 0x37, OP_BRANCH = 0x63, OP_JALR = 0x67,
    OP_JAL = 0x6F, OP_SYSTEM = 0x73,
};

static inline int32_t sext(uint32_t v, int bits)
{
    const uint32_t m = 1u << (bits - 1);
    return (int32_t)((v ^ m) - m);
}

Machine::Machine(const Config& cfg) : cfg_(cfg)
{
    rom_.assign(cfg_.romSize, 0);
    ram_.assign(cfg_.ramSize, 0);
    reset();
}

void Machine::reset()
{
    for (int i = 0; i < 32; i++) {
        regs_[i] = 0;
    }
    pc_ = cfg_.resetVector;
    mtvec_ = mscratch_ = mepc_ = mcause_ = mtval_ = 0;
    mstatus_ = 0;
    mie_ = mip_ = medeleg_ = mideleg_ = 0;
    misa_ = (1u << 30) | (1u << 8) | (1u << 12);   /* MXL=32, 'I', 'M' */
    mcycle_ = instret_ = 0;
    faulted_ = halted_ = false;
    waitingForInterrupt_ = false;
    faultCause_ = faultTval_ = faultEpc_ = 0;
    pendingIrq_ = 0;
    mtime_ = 0;
    mtimecmp_ = 0;
    mtimeRemainder_ = 0;
    mtipCountdown_ = 0;
    timerEnabled_ = false;
    mtipAsserted_ = false;
    timerIrqCount_ = 0;
    mmio_ = MmioTxn{};
}

void Machine::setPendingInterrupts(uint32_t bits)
{
    /* bit7 = MTIP 是**虚拟机自己**产生的（CLINT 电平），宿主注入不了的位在这里被屏蔽掉；
     * 宿主送进来的其余位只代表"外部事件"（键盘/串口收/组件回复等），与时间无关。 */
    pendingIrq_ = bits & ~(1u << 7);
}

void Machine::loadImage(uint32_t addr, const uint8_t* data, uint32_t len)
{
    for (uint32_t i = 0; i < len; i++) {
        const uint32_t a = addr + i;
        if (a >= cfg_.romBase && a < cfg_.romBase + cfg_.romSize) {
            rom_[a - cfg_.romBase] = data[i];
        } else if (a >= cfg_.ramBase && a < cfg_.ramBase + cfg_.ramSize) {
            ram_[a - cfg_.ramBase] = data[i];
        }
    }
}

/* ==================== 内存 ==================== */

bool Machine::inRom(uint32_t addr, uint32_t size) const
{
    return addr >= cfg_.romBase && (addr + size) <= (cfg_.romBase + cfg_.romSize);
}

bool Machine::inRam(uint32_t addr, uint32_t size) const
{
    return addr >= cfg_.ramBase && (addr + size) <= (cfg_.ramBase + cfg_.ramSize);
}

void Machine::readMemory(uint32_t addr, uint8_t* out, uint32_t len) const
{
    for (uint32_t i = 0; i < len; i++) {
        const uint32_t a = addr + i;
        if (a >= cfg_.romBase && a < cfg_.romBase + cfg_.romSize) {
            out[i] = rom_[a - cfg_.romBase];
        } else if (a >= cfg_.ramBase && a < cfg_.ramBase + cfg_.ramSize) {
            out[i] = ram_[a - cfg_.ramBase];
        } else {
            out[i] = 0;
        }
    }
}

void Machine::writeMemory(uint32_t addr, const uint8_t* in, uint32_t len)
{
    for (uint32_t i = 0; i < len; i++) {
        const uint32_t a = addr + i;
        if (a >= cfg_.ramBase && a < cfg_.ramBase + cfg_.ramSize) {
            ram_[a - cfg_.ramBase] = in[i];
        } else if (a >= cfg_.romBase && a < cfg_.romBase + cfg_.romSize) {
            rom_[a - cfg_.romBase] = in[i];
        }
    }
}

uint8_t* Machine::memoryPointer(uint32_t addr, uint32_t len)
{
    if (len == 0) {
        return nullptr;
    }
    /* 用 64 位做边界加法：32 位 addr + len 在末端地址附近会回绕，
     * 那正是"整段越界"最容易被误判成"段内"的地方。 */
    const uint64_t end = (uint64_t)addr + (uint64_t)len;
    if (addr >= cfg_.romBase && end <= (uint64_t)cfg_.romBase + (uint64_t)cfg_.romSize) {
        return rom_.data() + (addr - cfg_.romBase);
    }
    if (addr >= cfg_.ramBase && end <= (uint64_t)cfg_.ramBase + (uint64_t)cfg_.ramSize) {
        return ram_.data() + (addr - cfg_.ramBase);
    }
    return nullptr;
}

uint32_t Machine::loadMem(uint32_t addr, uint32_t size)
{
    if (inRam(addr, size)) {
        const uint8_t* p = &ram_[addr - cfg_.ramBase];
        switch (size) {
            case 1: return p[0];
            case 2: return (uint32_t)p[0] | ((uint32_t)p[1] << 8);
            default:
                return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                       ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        }
    }
    if (inRom(addr, size)) {
        const uint8_t* p = &rom_[addr - cfg_.romBase];
        switch (size) {
            case 1: return p[0];
            case 2: return (uint32_t)p[0] | ((uint32_t)p[1] << 8);
            default:
                return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                       ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        }
    }
    /* —— CLINT：虚拟机自建定时器，普通内存语义，零跨语言事务 —— */
    if (inClint(addr, size)) {
        return clintLoad(addr, size);
    }

    /* —— 设备区：交给宿主兑现（本 tick 到此为止）—— */
    mmio_.addr = addr;
    mmio_.size = size;
    mmio_.isWrite = 0;
    mmio_.pending = 1;
    return 0;
}

void Machine::storeMem(uint32_t addr, uint32_t size, uint32_t value)
{
    if (inRam(addr, size)) {
        uint8_t* p = &ram_[addr - cfg_.ramBase];
        for (uint32_t i = 0; i < size; i++) {
            p[i] = (uint8_t)(value >> (8 * i));
        }
        return;
    }
    if (inRom(addr, size)) {
        /* ROM 只读：忽略（真实硬件亦然） */
        return;
    }
    /* —— CLINT：写 mtimecmp 就是"启用定时器"（真 CLINT 没有独立使能位） —— */
    if (inClint(addr, size)) {
        clintStore(addr, size, value);
        return;
    }

    mmio_.addr = addr;
    mmio_.size = size;
    mmio_.value = value;
    mmio_.isWrite = 1;
    mmio_.pending = 1;
}

/* ==================== CLINT（虚拟机自建定时器） ==================== */

bool Machine::inClint(uint32_t addr, uint32_t size) const
{
    if (cfg_.clintSize == 0 || size > cfg_.clintSize) {
        return false;
    }
    const uint32_t off = addr - cfg_.clintBase;      /* 无符号：addr < base 时回绕成巨大值 */
    return off <= cfg_.clintSize - size;
}

uint32_t Machine::clintLoad(uint32_t addr, uint32_t size)
{
    const uint32_t off = addr - cfg_.clintBase;
    const uint32_t word = off & ~3u;                  /* 命中的 32 位半字 */
    const uint32_t within = off & 3u;
    uint32_t v;
    switch (word) {
        case 0x00: v = (uint32_t)mtime_;              break;
        case 0x04: v = (uint32_t)(mtime_ >> 32);      break;
        case 0x08: v = (uint32_t)mtimecmp_;           break;
        default:   v = (uint32_t)(mtimecmp_ >> 32);   break;
    }
    v >>= (within * 8u);
    if (size == 1) {
        v &= 0xFFu;
    } else if (size == 2) {
        v &= 0xFFFFu;
    }
    return v;
}

void Machine::clintStore(uint32_t addr, uint32_t size, uint32_t value)
{
    const uint32_t off = addr - cfg_.clintBase;
    const uint32_t word = off & ~3u;
    if (word == 0x00 || word == 0x04) {
        return;                                       /* mtime 只读（真 CLINT 亦然） */
    }
    const uint32_t shift = (off & 3u) * 8u;
    const uint32_t mask = (size >= 4) ? 0xFFFF'FFFFu : ((1u << (size * 8u)) - 1u);
    const uint64_t part = (uint64_t)((value & mask) << shift);
    if (word == 0x08) {
        mtimecmp_ = (mtimecmp_ & 0xFFFF'FFFF'0000'0000ull) | part;
    } else {
        mtimecmp_ = (mtimecmp_ & 0x0000'0000'FFFF'FFFFull) | (part << 32);
    }
    timerEnabled_ = true;                             /* 写 mtimecmp = 启用 */
    refreshTimerDeadline();
}

void Machine::refreshTimerDeadline()
{
    const uint32_t div = cfg_.mtimeDiv == 0 ? 1u : cfg_.mtimeDiv;
    if ((int64_t)mtime_ >= (int64_t)mtimecmp_) {
        if (!mtipAsserted_) {
            mtipAsserted_ = true;
            timerIrqCount_++;
        }
        mtipCountdown_ = 0;
        return;
    }
    mtipAsserted_ = false;                            /* 电平：还没到就撤销 */
    const int64_t remain = (int64_t)(mtimecmp_ - mtime_);
    mtipCountdown_ = remain * (int64_t)div - (int64_t)mtimeRemainder_;
    if (mtipCountdown_ < 1) {
        mtipCountdown_ = 1;
    }
}

uint32_t Machine::effectiveIrq() const
{
    return pendingIrq_ | (mtipAsserted_ ? (1u << 7) : 0u);
}

int Machine::runChunk(int cycles)
{
    int done = 0;
    /* 定时器的每指令推进（虚拟机自己的时间）：div == 1 是最常见配置，走无分支快路；
     * div > 1 才走余数累加（配置一次，之后常量，分支可预测）。 */
    const bool mtimeFast = (cfg_.mtimeDiv <= 1);
    while (done < cycles) {
        /* ---------- 中断交付（在指令边界，符合 RISC-V：mie & mip & mstatus.MIE）---------- */
        const uint32_t irqBits = effectiveIrq();      /* 宿主外部事件位 | 虚拟机自产 MTIP */
        if (irqBits != 0 && (mstatus_ & 0x8u) != 0u) {
            const uint32_t visible = (cfg_.privilegeMode == M_ONLY)
                                     ? irqBits
                                     : (irqBits & ~mideleg_);
            const uint32_t enabled = visible & mie_;
            if (enabled != 0u) {
                int irq = 0;
                while (((enabled >> irq) & 1u) == 0u) {
                    irq++;
                }
                trap(INTERRUPT_BIT | (uint32_t)irq, 0);
                waitingForInterrupt_ = false;         /* WFI 被中断唤醒 */
                if (halted_ || faulted_) {
                    break;
                }
                done++;                               /* 中断交付算一次"推进"，避免饿死 */
                continue;
            }
        }
        if (waitingForInterrupt_) {
            break;                                    /* WFI：交给外面推进设备/中断 */
        }
        uint32_t instr = 0;
        if (!fetch(pc_, instr)) {
            break;
        }
        execute(instr);
        mcycle_++;
        instret_++;
        done++;
        /* mtime 随执行周期推进（guest 读 CLINT 就是读它；不再按时钟分批，避免读到陈旧值） */
        if (mtimeFast) {
            mtime_++;
        } else {
            mtimeRemainder_++;
            if (mtimeRemainder_ >= cfg_.mtimeDiv) {
                mtimeRemainder_ = 0;
                mtime_++;
            }
        }
        /* 到 mtimecmp 就地拉高 MTIP（电平）；下一轮循环顶部的 effectiveIrq() 立刻交付中断 */
        if (timerEnabled_) {
            if (mtipCountdown_ > 0) {
                mtipCountdown_--;
            }
            if (mtipCountdown_ <= 0 && !mtipAsserted_) {
                mtipAsserted_ = true;
                timerIrqCount_++;
            }
        }
        if (mmio_.pending || halted_ || faulted_) {
            break;
        }
    }
    return done;
}

void Machine::completeMmio(uint32_t value)
{
    const MmioTxn txn = mmio_;
    mmio_ = MmioTxn{};

    /* ⚠ 读**和写**都必须推进 PC：被 MMIO 打断的那条访存指令到此才算执行完。
       只推进读的话，写设备的指令会被无限重放（实测：固件 x1 卡在 1、
       而指令计数照样涨 —— 因为每条重放的 sw 都算执行过）。 */
    if (txn.isWrite) {
        pc_ += 4;
        return;
    }
    const int rd = (int)(txn.value & 0x1Fu);
    uint32_t v = value;
    if (txn.size == 1) {
        v &= 0xFFu;
    } else if (txn.size == 2) {
        v &= 0xFFFFu;
    }
    if (rd != 0) {
        regs_[rd] = v;
    }
    pc_ += 4;
}

/* ==================== CSR ==================== */

uint32_t Machine::readCsr(uint32_t addr) const
{
    switch (addr) {
        case CSR_MSTATUS:    return mstatus_;
        case CSR_MISA:       return misa_;
        case CSR_MEDELEG:    return medeleg_;
        case CSR_MIDELEG:    return mideleg_;
        case CSR_MIE:        return mie_;
        case CSR_MTVEC:      return mtvec_;
        case CSR_MSCRATCH:   return mscratch_;
        case CSR_MEPC:       return mepc_;
        case CSR_MCAUSE:     return mcause_;
        case CSR_MTVAL:      return mtval_;
        case CSR_MIP:        return mip_;
        case CSR_MCYCLE:     return (uint32_t)mcycle_;
        case CSR_MINSTRET:   return (uint32_t)instret_;
        case CSR_MVENDORID:  return 0x43727970u;   /* 'Cryp' —— 我们自己的 vendor id（诊断用） */
        case CSR_MARCHID:    return 0x52563332u;   /* 'RV32' */
        case CSR_MIMPID:     return 1u;
        case CSR_MHARTID:    return 0u;
        default:             return 0u;
    }
}

void Machine::writeCsr(uint32_t addr, uint32_t value)
{
    switch (addr) {
        case CSR_MSTATUS:  mstatus_ = value; break;
        case CSR_MISA:     break;                      /* WARL：固定值 */
        case CSR_MEDELEG:  medeleg_ = value; break;
        case CSR_MIDELEG:  mideleg_ = value; break;
        case CSR_MIE:      mie_ = value; break;
        case CSR_MTVEC:    mtvec_ = value; break;
        case CSR_MSCRATCH: mscratch_ = value; break;
        case CSR_MEPC:     mepc_ = value & ~0x1u; break;
        case CSR_MCAUSE:   mcause_ = value; break;
        case CSR_MTVAL:    mtval_ = value; break;
        case CSR_MIP:      mip_ = value; break;
        case CSR_MCYCLE:   mcycle_ = value; break;
        case CSR_MINSTRET: instret_ = value; break;
        default: break;
    }
}

uint32_t Machine::csr(uint32_t addr) const
{
    return readCsr(addr);
}

uint32_t Machine::readCsrChecked(uint32_t addr, bool& ok)
{
    ok = true;
    if (addr == 0) {
        ok = false;
        return 0;
    }
    return readCsr(addr);
}

void Machine::writeCsrChecked(uint32_t addr, uint32_t value, bool& ok)
{
    ok = true;
    if (addr == 0) {
        ok = false;
        return;
    }
    writeCsr(addr, value);
}

/* ==================== 陷阱 ==================== */

void Machine::enterTrapStateM()
{
    /* mstatus: MPIE <- MIE, MIE <- 0, MPP <- M */
    const uint32_t mie = (mstatus_ >> 3) & 1u;
    mstatus_ &= ~((1u << 3) | (3u << 11));
    if (mie) {
        mstatus_ |= (1u << 7);
    }
    mstatus_ |= (3u << 11);
}

uint32_t Machine::doMret()
{
    const uint32_t mpp = (mstatus_ >> 11) & 3u;
    const uint32_t mpie = (mstatus_ >> 7) & 1u;
    mstatus_ &= ~((1u << 3) | (1u << 7) | (3u << 11));
    if (mpie) {
        mstatus_ |= (1u << 3);
    }
    mstatus_ |= (0u << 11);            /* 回到 U（M_ONLY 档下也只有 M 会用） */
    (void)mpp;
    return mepc_;
}

void Machine::trap(uint32_t cause, uint32_t tval)
{
    mcause_ = cause;
    mepc_ = pc_;
    mtval_ = tval;
    if (mtvec_ == 0) {
        if (cfg_.haltWithoutHandler) {
            halted_ = true;
            faulted_ = true;
            faultCause_ = cause;
            faultTval_ = tval;
            faultEpc_ = pc_;
        }
        return;
    }
    enterTrapStateM();
    pc_ = mtvec_ & ~0x3u;
}

void Machine::raiseIllegal(uint32_t instr)
{
    trap(CAUSE_ILLEGAL_INSTRUCTION, instr);
}

/* ==================== 取指 ==================== */

bool Machine::fetch(uint32_t addr, uint32_t& out)
{
    if (inRom(addr, 4)) {
        const uint8_t* p = &rom_[addr - cfg_.romBase];
        out = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        return true;
    }
    if (inRam(addr, 4)) {
        const uint8_t* p = &ram_[addr - cfg_.ramBase];
        out = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
        return true;
    }
    trap(CAUSE_INSTRUCTION_ACCESS_FAULT, addr);
    return false;
}

/* ==================== 执行 ==================== */

void Machine::execute(uint32_t instr)
{
    const uint32_t opcode = instr & 0x7Fu;
    const uint32_t rd  = (instr >> 7) & 0x1Fu;
    const uint32_t f3  = (instr >> 12) & 0x7u;
    const uint32_t rs1 = (instr >> 15) & 0x1Fu;
    const uint32_t rs2 = (instr >> 20) & 0x1Fu;
    const uint32_t f7  = (instr >> 25) & 0x7Fu;
    const uint32_t rv1 = regs_[rs1];
    const uint32_t rv2 = regs_[rs2];

    switch (opcode) {
        case OP_LUI:
            if (rd) regs_[rd] = instr & 0xFFFFF000u;
            pc_ += 4;
            return;

        case OP_AUIPC:
            if (rd) regs_[rd] = pc_ + (instr & 0xFFFFF000u);
            pc_ += 4;
            return;

        case OP_JAL: {
            const int32_t off = sext(((instr >> 31) << 20) | (((instr >> 12) & 0xFFu) << 12) |
                                     (((instr >> 20) & 1u) << 11) | (((instr >> 21) & 0x3FFu) << 1), 21);
            if (rd) regs_[rd] = pc_ + 4;
            pc_ = (uint32_t)(pc_ + off) & ~1u;
            return;
        }

        case OP_JALR: {
            const int32_t off = sext(instr >> 20, 12);
            const uint32_t target = (rv1 + (uint32_t)off) & ~1u;
            if (rd) regs_[rd] = pc_ + 4;
            pc_ = target;
            return;
        }

        case OP_BRANCH: {
            const int32_t off = sext(((instr >> 31) << 12) | (((instr >> 7) & 1u) << 11) |
                                     (((instr >> 25) & 0x3Fu) << 5) | (((instr >> 8) & 0xFu) << 1), 13);
            bool take = false;
            switch (f3) {
                case 0x0: take = (rv1 == rv2); break;                    /* beq */
                case 0x1: take = (rv1 != rv2); break;                    /* bne */
                case 0x4: take = ((int32_t)rv1 < (int32_t)rv2); break;   /* blt */
                case 0x5: take = ((int32_t)rv1 >= (int32_t)rv2); break;  /* bge */
                case 0x6: take = (rv1 < rv2); break;                     /* bltu */
                case 0x7: take = (rv1 >= rv2); break;                    /* bgeu */
                default: raiseIllegal(instr); return;
            }
            pc_ = take ? (uint32_t)(pc_ + off) : (pc_ + 4);
            return;
        }

        case OP_LOAD: {
            const int32_t off = sext(instr >> 20, 12);
            const uint32_t addr = rv1 + (uint32_t)off;
            uint32_t size;
            switch (f3) {
                case 0x0: case 0x4: size = 1; break;   /* lb / lbu */
                case 0x1: case 0x5: size = 2; break;   /* lh / lhu */
                case 0x2:           size = 4; break;   /* lw */
                default: raiseIllegal(instr); return;
            }
            const uint32_t raw = loadMem(addr, size);
            if (mmio_.pending) {
                mmio_.value = rd & 0x1Fu;              /* 把目标 rd 暂存给 completeMmio */
                return;                                 /* pc 不前进，等宿主兑现 */
            }
            uint32_t v = raw;
            if (f3 == 0x0) {
                v = (uint32_t)sext(raw, 8);
            } else if (f3 == 0x1) {
                v = (uint32_t)sext(raw, 16);
            }
            if (rd) regs_[rd] = v;
            pc_ += 4;
            return;
        }

        case OP_STORE: {
            const int32_t off = sext(((instr >> 25) << 5) | ((instr >> 7) & 0x1Fu), 12);
            const uint32_t addr = rv1 + (uint32_t)off;
            uint32_t size;
            switch (f3) {
                case 0x0: size = 1; break;
                case 0x1: size = 2; break;
                case 0x2: size = 4; break;
                default: raiseIllegal(instr); return;
            }
            storeMem(addr, size, rv2);
            if (mmio_.pending) {
                return;
            }
            pc_ += 4;
            return;
        }

        case OP_IMM: {
            const int32_t imm = sext(instr >> 20, 12);
            uint32_t v = 0;
            switch (f3) {
                case 0x0: v = rv1 + (uint32_t)imm; break;                       /* addi */
                case 0x2: v = ((int32_t)rv1 < (int32_t)imm) ? 1u : 0u; break;    /* slti */
                case 0x3: v = (rv1 < (uint32_t)imm) ? 1u : 0u; break;            /* sltiu */
                case 0x4: v = rv1 ^ (uint32_t)imm; break;                        /* xori */
                case 0x6: v = rv1 | (uint32_t)imm; break;                        /* ori */
                case 0x7: v = rv1 & (uint32_t)imm; break;                        /* andi */
                case 0x1: {                                                       /* slli */
                    const uint32_t sh = (instr >> 20) & 0x1Fu;
                    v = rv1 << sh;
                    break;
                }
                case 0x5: {
                    const uint32_t sh = (instr >> 20) & 0x1Fu;
                    v = (f7 & 0x20u) ? (uint32_t)((int32_t)rv1 >> sh) : (rv1 >> sh);
                    break;
                }
                default: raiseIllegal(instr); return;
            }
            if (rd) regs_[rd] = v;
            pc_ += 4;
            return;
        }

        case OP_OP: {
            uint32_t v = 0;
            if (f7 == 0x01u) {                       /* M 扩展 */
                switch (f3) {
                    case 0x0: v = rv1 * rv2; break;
                    case 0x1: v = (uint32_t)(((uint64_t)rv1 * (uint64_t)rv2) >> 32); break;
                    case 0x2: v = (uint32_t)(((int64_t)(int32_t)rv1 * (int64_t)(int32_t)rv2) >> 32); break;
                    case 0x3: v = (uint32_t)(((uint64_t)rv1 * (uint64_t)rv2) >> 32); break;
                    case 0x4:
                        if (rv2 == 0) v = 0xFFFFFFFFu;
                        else if (rv1 == 0x80000000u && rv2 == 0xFFFFFFFFu) v = 0x80000000u;
                        else v = (uint32_t)((int32_t)rv1 / (int32_t)rv2);
                        break;
                    case 0x5: v = (rv2 == 0) ? 0xFFFFFFFFu : (rv1 / rv2); break;
                    case 0x6:
                        if (rv2 == 0) v = rv1;
                        else if (rv1 == 0x80000000u && rv2 == 0xFFFFFFFFu) v = 0;
                        else v = (uint32_t)((int32_t)rv1 % (int32_t)rv2);
                        break;
                    case 0x7: v = (rv2 == 0) ? rv1 : (rv1 % rv2); break;
                    default: raiseIllegal(instr); return;
                }
            } else {
                switch (f3) {
                    case 0x0: v = (f7 & 0x20u) ? (rv1 - rv2) : (rv1 + rv2); break;
                    case 0x1: v = rv1 << (rv2 & 0x1Fu); break;
                    case 0x2: v = ((int32_t)rv1 < (int32_t)rv2) ? 1u : 0u; break;
                    case 0x3: v = (rv1 < rv2) ? 1u : 0u; break;
                    case 0x4: v = rv1 ^ rv2; break;
                    case 0x5: v = (f7 & 0x20u) ? (uint32_t)((int32_t)rv1 >> (rv2 & 0x1Fu))
                                               : (rv1 >> (rv2 & 0x1Fu)); break;
                    case 0x6: v = rv1 | rv2; break;
                    case 0x7: v = rv1 & rv2; break;
                    default: raiseIllegal(instr); return;
                }
            }
            if (rd) regs_[rd] = v;
            pc_ += 4;
            return;
        }

        case OP_SYSTEM: {
            if (f3 == 0) {
                const uint32_t f12 = (instr >> 20) & 0xFFFu;
                switch (f12) {
                    case 0x000:                       /* ecall */
                        trap(CAUSE_ECALL_FROM_M, 0);
                        return;
                    case 0x001:                       /* ebreak */
                        trap(CAUSE_BREAKPOINT, 0);
                        return;
                    case 0x105:                       /* wfi */
                        waitingForInterrupt_ = true;
                        pc_ += 4;
                        return;
                    case 0x302:                       /* mret */
                        pc_ = doMret();
                        return;
                    default:
                        raiseIllegal(instr);
                        return;
                }
            }
            /* CSR 指令（Zicsr） */
            const uint32_t csrAddr = instr >> 20;
            const bool isImm = (f3 & 0x4u) != 0;
            const uint32_t src = isImm ? rs1 : rv1;
            bool ok = true;
            const uint32_t old = readCsrChecked(csrAddr, ok);
            if (!ok) {
                raiseIllegal(instr);
                return;
            }
            uint32_t next = old;
            switch (f3 & 0x3u) {
                case 0x1: next = src; break;                       /* csrrw */
                case 0x2: if (src != 0) next = old | src; break;    /* csrrs */
                case 0x3: if (src != 0) next = old & ~src; break;   /* csrrc */
                default: break;
            }
            writeCsrChecked(csrAddr, next, ok);
            if (!ok) {
                raiseIllegal(instr);
                return;
            }
            if (rd) regs_[rd] = old;
            pc_ += 4;
            return;
        }

        default:
            raiseIllegal(instr);
            return;
    }
}

int Machine::step(int cycles)
{
    if (cycles <= 0 || halted_ || faulted_) {
        return 0;
    }
    if (mmio_.pending) {
        return 0;                        /* 等宿主兑现设备访问 */
    }
    /* CLINT 的推进与兑现都在 runChunk 的指令循环里（见那里的注释）：mtime 逐条指令前进，
     * 到 mtimecmp 就地拉高 MTIP，下一条指令的顶部就交付中断 —— 精确到指令边界，且不跨批拖后。 */
    return runChunk(cycles);
}


} /* namespace cryptand */
