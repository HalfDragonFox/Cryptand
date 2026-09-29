package com.hdf.cryptand.soc.riscv;

import com.hdf.cryptand.soc.api.CpuCore;
import com.hdf.cryptand.soc.api.InterruptController;
import com.hdf.cryptand.soc.api.MemoryAccessException;
import com.hdf.cryptand.soc.api.MemoryMap;
import com.hdf.cryptand.soc.api.MemoryMappedDevice;
import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.api.SocFault;
import com.hdf.cryptand.soc.device.RamDevice;

/**
 * ===== 自研 RV32IM 内核（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>MCU 语义的最小可用内核：<b>RV32I 基础 + M 扩展（乘除）+ 最小 M-mode 特权集</b>。
 * 刻意不含 F/D 浮点、C 压缩、A 原子、S/U 特权级与 MMU——应用处理器档位由
 * 可插拔的其它 {@link CpuCore} 实现承担（见 `soc-simulation-architecture.md`）。</p>
 *
 * <h3>沙箱三要素</h3>
 * <ol>
 *   <li><b>预算化执行</b>：{@link #step(int)} 至多执行 N 条指令即返回——协作式中断点，
 *       guest 无法逃避（代码只能经本内核执行）；</li>
 *   <li><b>异常边界</b>：访存失败/非法指令一律转为 RISC-V 陷阱（写 mepc/mcause/mtval
 *       并跳 mtvec）；<b>若未设置 mtvec 则停机</b>（"芯片死机"，游戏不受影响）；</li>
 *   <li><b>能力表</b>：能访问什么完全由挂载的 {@link MemoryMap} 决定——未映射即陷阱，
 *       不存在隐式通道。</li>
 * </ol>
 *
 * <h3>时间语义</h3>
 * <p>{@code step(budget)} 的"周期"就是指令数；设备由 {@code SocBoard} 在每次 step 前后
 * 按周期推进，因此固件内定时（mtime/定时器中断）与 MC tick 无关。</p>
 */
public final class Rv32Core implements CpuCore {

    /**
     * ===== 内核配置（档位裁剪，2026-09-16 参数化）=====
     *
     * <p>用户口径："**SOC 是和 MCU 带的内容不一样，比如 SOC 一般支持 MMU 这种**" ⇒
     * 同一颗内核按档位开特性，<b>默认仍是 MCU（M_ONLY + 无 MMU + 无 A）</b>，
     * 既有 MCU 玩法与自测完全不受影响：</p>
     *
     * <table border="1">
     *   <tr><th>档位</th><th>privilege</th><th>mmu</th><th>典型用途</th></tr>
     *   <tr><td>MCU-μ / MCU-标准</td><td>{@link Privilege#M_ONLY}</td><td>{@link Mmu#NONE}</td>
     *       <td>裸机 / FreeRTOS（无虚拟内存）</td></tr>
     *   <tr><td>MCU+ / 轻 OS</td><td>{@link Privilege#M_S}</td><td>{@link Mmu#NONE}</td>
     *       <td>S 模式固件、轻量 RTOS</td></tr>
     *   <tr><td>SoC-应用 / SoC-高端</td><td>{@link Privilege#M_S_U}</td><td>{@link Mmu#SV32}</td>
     *       <td>Linux / 需要用户态与分页的系统</td></tr>
     * </table>
     */
    public static final class Config {

        /** 特权档位（能力边界：决定是否有 S/U 模式与委派） */
        public enum Privilege {
            /** 仅 M 模式（MCU 语义，等价改造前的行为） */
            M_ONLY,
            /** M + S（无 U、无 MMU） */
            M_S,
            /** M + S + U（SoC 档；跑 Linux 的前提） */
            M_S_U
        }

        /** 地址翻译（只有 SoC 档需要） */
        public enum Mmu {
            /** 无 MMU（物理地址直通） */
            NONE,
            /** Sv32 两级页表（4KiB 页 / 4MiB 大页） */
            SV32
        }

        private boolean enableM = true;
        private boolean enableA = false;
        private Privilege privilege = Privilege.M_ONLY;
        private Mmu mmu = Mmu.NONE;
        private long resetVector = 0x8000_0000L;
        private boolean haltWithoutHandler = true;
        private boolean strictCsr = false;

        /** 是否实现 M 扩展（乘除）。关 = 纯 RV32I（更小的"入门 MCU"档） */
        public Config enableM(boolean v) {
            this.enableM = v;
            return this;
        }

        /** 是否实现 A 扩展（原子：lr/sc/amo）。Linux 需要；MCU 默认关 */
        public Config enableA(boolean v) {
            this.enableA = v;
            return this;
        }

        /** 特权档位（默认 {@link Privilege#M_ONLY} = 保持 MCU 行为） */
        public Config privilege(Privilege v) {
            this.privilege = v == null ? Privilege.M_ONLY : v;
            return this;
        }

        /** 地址翻译（默认 {@link Mmu#NONE}；SV32 需同时把 privilege 开到 M_S_U） */
        public Config mmu(Mmu v) {
            this.mmu = v == null ? Mmu.NONE : v;
            return this;
        }

        public Config resetVector(long v) {
            this.resetVector = v;
            return this;
        }

        /** 陷阱且 mtvec==0 时是否停机（默认 true = "芯片死机"给玩家看） */
        public Config haltWithoutHandler(boolean v) {
            this.haltWithoutHandler = v;
            return this;
        }

        /** 访问未实现 CSR 是否按非法指令陷阱（默认 false = 宽容：读 0 写忽略） */
        public Config strictCsr(boolean v) {
            this.strictCsr = v;
            return this;
        }

        /** 是否具备 S 模式（含 sret / S-mode CSR / 委派） */
        public boolean hasS() {
            return privilege != Privilege.M_ONLY;
        }

        /** 是否具备 U 模式 */
        public boolean hasU() {
            return privilege == Privilege.M_S_U;
        }

        /** 是否启用分页（需要 Sv32 且档位含 U/S） */
        public boolean paging() {
            return mmu == Mmu.SV32 && hasS();
        }

        /** 档位可读名（日志/UI） */
        public String levelName() {
            return (privilege == Privilege.M_ONLY ? "M" : privilege == Privilege.M_S ? "M+S" : "M+S+U")
                    + (mmu == Mmu.SV32 ? "+Sv32" : "")
                    + (enableA ? "+A" : "")
                    + (enableM ? "+M" : "");
        }
    }

    // ==================== 状态 ====================
    /** 通用寄存器 x1..x31（x0 恒 0；存符号扩展后的 32 位值） */
    private final long[] regs = new long[32];
    private long pc;

    private final Config config;
    private MemoryMap memoryMap;
    private InterruptController interruptController;

    private boolean halted;
    private SocFault fault = SocFault.NONE;
    private long instructionsRetired;
    private boolean waitingForInterrupt;
    private boolean interruptsGloballyEnabled = true;

    /** 挂起的陷阱（供宿主在 step 之间观察；halted 时保留最后一次） */
    private SocFault lastTrap = SocFault.NONE;

    // ==================== 特权级（2026-09-16：M/S/U） ====================
    /** 当前特权级：{@link Rv32#PRIV_U} / {@link Rv32#PRIV_S} / {@link Rv32#PRIV_M} */
    private int privilege = Rv32.PRIV_M;

    // ==================== CSR：M-mode ====================
    private long mstatus = 0;
    private long mie = 0;
    private long mip = 0;
    private long mtvec = 0;
    private long mscratch = 0;
    private long mepc = 0;
    private long mcause = 0;
    private long mtval = 0;
    private long mcycle = 0;
    /** 异常委派位图（SoC 档：把异常交给 S 模式处理） */
    private long medeleg = 0;
    /** 中断委派位图（SoC 档） */
    private long mideleg = 0;
    /** 计数器对 S/U 的可见性 */
    private long mcounteren = 0;

    // ==================== CSR：S-mode（SoC 档才实现） ====================
    // 说明：sstatus / sie / sip 都是 mstatus / mie / mip 的**视图**，不单独存；
    //      sstatus 的可见位由 Rv32.SSTATUS_MASK 界定，sie/sip 由 Rv32.SIE_MASK 界定。
    private long stvec = 0;
    private long sscratch = 0;
    private long sepc = 0;
    private long scause = 0;
    private long stval = 0;
    /** Sv32 的地址翻译控制（MODE=1 时启用；见 Rv32.SATP_*） */
    private long satp = 0;
    /** 计数器对 U 的可见性 */
    private long scounteren = 0;

    public Rv32Core() {
        this(new Config());
    }

    public Rv32Core(Config config) {
        this.config = config == null ? new Config() : config;
        this.pc = this.config.resetVector;
    }

    // ==================== CpuCore ====================

    @Override
    public String getName() {
        // 档位可读名：MCU 档就是 rv32im / rv32i；SoC 档带档位后缀（见 Config.levelName）
        final String base = config.enableM ? "rv32im" : "rv32i";
        return config.privilege == Config.Privilege.M_ONLY
                ? base
                : base + "-" + config.levelName();
    }

    @Override
    public void setMemoryMap(MemoryMap memoryMap) {
        this.memoryMap = memoryMap;
    }

    @Override
    public void setInterruptController(InterruptController controller) {
        this.interruptController = controller;
    }

    // ==================== 宿主侧内存访问（RAM/ROM 就在挂在 MemoryMap 上）====================

    @Override
    public byte[] readMemory(long address, int length) {
        ensureMemoryMap();
        final byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) memoryMap.load(address + i, Sizes.SIZE_8);
        }
        return out;
    }

    @Override
    public void writeMemory(long address, byte[] data) {
        ensureMemoryMap();
        for (int i = 0; i < data.length; i++) {
            memoryMap.store(address + i, data[i] & 0xFFL, Sizes.SIZE_8);
        }
    }

    /**
     * 烧录镜像：目标落在 RAM/ROM 设备上时整块写入（绕过 {@code RomDevice} 的只读约束，
     * 与真实"烧写"语义一致），其余情况逐字节 store。
     *
     * <p>这段逻辑原先在 {@code SocBoard.loadFirmware} 里，随"内存归内核"一起搬过来
     * —— 否则 native 内核下的烧录会落进那块影子设备。</p>
     */
    @Override
    public void loadImage(long address, byte[] image) {
        ensureMemoryMap();
        final MemoryMappedDevice device = memoryMap.getDeviceAt(address);
        if (device instanceof RamDevice ram) {
            final long[] range = memoryMap.getMemoryRange(ram);
            ram.writeImage((int) (address - range[0]), image, 0, image.length);
            return;
        }
        for (int i = 0; i < image.length; i++) {
            memoryMap.store(address + i, image[i] & 0xFFL, Sizes.SIZE_8);
        }
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(regs, 0L);
        pc = config.resetVector;
        halted = false;
        fault = SocFault.NONE;
        lastTrap = SocFault.NONE;
        waitingForInterrupt = false;
        instructionsRetired = 0;
        // 复位进 M 模式（RISC-V 语义）
        privilege = Rv32.PRIV_M;
        mstatus = 0;
        mie = 0;
        mip = 0;
        mtvec = 0;
        mscratch = 0;
        mepc = 0;
        mcause = 0;
        mtval = 0;
        mcycle = 0;
        medeleg = 0;
        mideleg = 0;
        mcounteren = 0;
        stvec = 0;
        sscratch = 0;
        sepc = 0;
        scause = 0;
        stval = 0;
        satp = 0;
        scounteren = 0;
    }

    @Override
    public int step(int instructionBudget) {
        if (halted || instructionBudget <= 0) {
            return 0;
        }
        int executed = 0;
        while (executed < instructionBudget) {
            if (halted) {
                break;
            }
            // 中断检查（每条指令前；WFI 等待期间也靠这里唤醒）
            if (checkInterrupt()) {
                waitingForInterrupt = false;
            }
            if (waitingForInterrupt) {
                break;   // 等待中断：不再消耗预算（真实 WFI 语义）
            }
            final long pcAtExec = pc;
            try {
                final int instruction = fetchInstruction(pcAtExec);
                executeInstruction(pcAtExec, instruction);
            } catch (AccessFaultException e) {
                trap(pcAtExec, e.access.getAddress(), e.cause, e.access.getMessage());
            } catch (MemoryAccessException e) {
                // 取指失败（fetch）→ 指令访问错误
                trap(pcAtExec, e.getAddress(), SocFault.CAUSE_INSTRUCTION_ACCESS_FAULT, "fetch: " + e.getMessage());
            } catch (IllegalInstructionException e) {
                trap(pcAtExec, e.instruction, SocFault.CAUSE_ILLEGAL_INSTRUCTION, "illegal instruction");
            }
            executed++;
            instructionsRetired++;
            mcycle++;
        }
        return executed;
    }

    @Override
    public SocFault getFault() {
        return halted ? lastTrap : SocFault.NONE;
    }

    @Override
    public boolean isFaulted() {
        return halted;
    }

    @Override
    public long getProgramCounter() {
        return pc;
    }

    @Override
    public long[] getRegisters() {
        return regs.clone();
    }

    @Override
    public long getInstructionsRetired() {
        return instructionsRetired;
    }

    @Override
    public long getResetVector() {
        return config.resetVector;
    }

    @Override
    public long getCsr(int csr) {
        return readCsr(csr);
    }

    @Override
    public void setInterruptsEnabled(boolean enabled) {
        this.interruptsGloballyEnabled = enabled;
    }

    /** 最近一次陷阱记录（含已由 handler 处理过的；UI/诊断用） */
    public SocFault getLastTrap() {
        return lastTrap;
    }

    /** 是否停在 WFI 等待中断 */
    public boolean isWaitingForInterrupt() {
        return waitingForInterrupt;
    }

    /** 内核配置（诊断） */
    public Config getConfig() {
        return config;
    }

    /** 停机标记（诊断/UI） */
    public boolean isHalted() {
        return halted;
    }

    // ==================== 取指 / 译码 / 执行 ====================

    private int fetchInstruction(long address) {
        ensureMemoryMap();
        final long raw = memoryMap.load(address, Sizes.SIZE_32);
        return (int) raw;
    }

    private void ensureMemoryMap() {
        if (memoryMap == null) {
            throw new MemoryAccessException(0, "no memory map attached");
        }
    }

    /** 访存访问错误（读/写区分 cause） */
    private static final class AccessFaultException extends RuntimeException {
        final int cause;
        final MemoryAccessException access;

        AccessFaultException(int cause, MemoryAccessException access) {
            super(access.getMessage());
            this.cause = cause;
            this.access = access;
        }
    }

    /**
     * 进入陷阱状态（**M 模式**）：MIE → MPIE、MPP ← 陷入前特权级，清 MIE。
     *
     * <p>MCU 档（M_ONLY）下 MPP 恒为 M，与改造前行为一致。</p>
     */
    private void enterTrapStateM() {
        final boolean mie = (mstatus & Rv32.MSTATUS_MIE) != 0;
        mstatus &= ~(Rv32.MSTATUS_MIE | Rv32.MSTATUS_MPIE | Rv32.MSTATUS_MPP);
        if (mie) {
            mstatus |= Rv32.MSTATUS_MPIE;
        }
        mstatus |= ((long) privilege) << 11;      // MPP ← 陷入前的特权级
    }

    /** 进入陷阱状态（**S 模式**）：SIE → SPIE、SPP ← (陷入前是否 S)，清 SIE */
    private void enterTrapStateS() {
        final boolean sie = (mstatus & Rv32.MSTATUS_SIE) != 0;
        mstatus &= ~(Rv32.MSTATUS_SIE | Rv32.MSTATUS_SPIE | Rv32.MSTATUS_SPP);
        if (sie) {
            mstatus |= Rv32.MSTATUS_SPIE;
        }
        if (privilege == Rv32.PRIV_S) {
            mstatus |= Rv32.MSTATUS_SPP;
        }
    }

    /**
     * 异常/中断是否应委派给 S 模式。
     *
     * <p>规则（RISC-V 特权规范）：含 S 档 + 当前特权级 ≤ S + 对应委派位置位
     * （异常看 {@code medeleg}、中断看 {@code mideleg}）；委派寄存器只覆盖低 32 个异常号。</p>
     */
    private boolean shouldDelegate(int cause, boolean isInterrupt) {
        if (!config.hasS() || privilege > Rv32.PRIV_S) {
            return false;
        }
        final int code = cause & 0x7FFF_FFFF;
        if (code >= 32) {
            return false;
        }
        final long deleg = isInterrupt ? mideleg : medeleg;
        return (deleg & (1L << code)) != 0L;
    }

    /** MRET：按 mstatus.MPP 降回特权级，并恢复 MIE/MPIE，跳回 mepc */
    private long doMret() {
        final int prev = (int) ((mstatus & Rv32.MSTATUS_MPP) >>> 11);
        final boolean mpie = (mstatus & Rv32.MSTATUS_MPIE) != 0;
        mstatus &= ~(Rv32.MSTATUS_MIE | Rv32.MSTATUS_MPIE | Rv32.MSTATUS_MPP);
        if (mpie) {
            mstatus |= Rv32.MSTATUS_MIE;
        }
        mstatus |= Rv32.MSTATUS_MPIE;
        if (config.hasS()) {
            privilege = switch (prev) {
                case Rv32.PRIV_U -> config.hasU() ? Rv32.PRIV_U : Rv32.PRIV_S;
                case Rv32.PRIV_S -> Rv32.PRIV_S;
                default -> Rv32.PRIV_M;
            };
        } else {
            privilege = Rv32.PRIV_M;   // MCU 档：MPP 无意义，恒回 M
        }
        return mepc & 0xFFFF_FFFFL;
    }

    /** SRET：按 mstatus.SPP 降回（S 或 U），恢复 SIE/SPIE，跳回 sepc */
    private long doSret() {
        final boolean spp = (mstatus & Rv32.MSTATUS_SPP) != 0;
        final boolean spie = (mstatus & Rv32.MSTATUS_SPIE) != 0;
        mstatus &= ~(Rv32.MSTATUS_SIE | Rv32.MSTATUS_SPIE | Rv32.MSTATUS_SPP);
        if (spie) {
            mstatus |= Rv32.MSTATUS_SIE;
        }
        mstatus |= Rv32.MSTATUS_SPIE;
        privilege = (spp && config.hasS()) ? Rv32.PRIV_S : (config.hasU() ? Rv32.PRIV_U : Rv32.PRIV_S);
        return sepc & 0xFFFF_FFFFL;
    }

    /** 非法指令（内部异常，转为陷阱而非抛出到宿主） */
    private static final class IllegalInstructionException extends RuntimeException {
        final int instruction;

        IllegalInstructionException(int instruction) {
            super("illegal instruction 0x" + Integer.toHexString(instruction));
            this.instruction = instruction;
        }
    }

    private void executeInstruction(long pcAtExec, int instruction) {
        final int opcode = instruction & 0x7F;
        final int rd = (instruction >>> 7) & 0x1F;
        final int funct3 = (instruction >>> 12) & 0x7;
        final int rs1 = (instruction >>> 15) & 0x1F;
        final int rs2 = (instruction >>> 20) & 0x1F;
        final int funct7 = (instruction >>> 25) & 0x7F;

        long nextPc = (pcAtExec + 4) & 0xFFFF_FFFFL;
        final long a = regs[rs1];
        final long b = regs[rs2];

        switch (opcode) {
            case Rv32.OP_LUI -> writeReg(rd, instruction & 0xFFFFF000);
            case Rv32.OP_AUIPC -> writeReg(rd, pcAtExec + (instruction & 0xFFFFF000));
            case Rv32.OP_JAL -> {
                writeReg(rd, nextPc);
                nextPc = (pcAtExec + decodeJImm(instruction)) & 0xFFFF_FFFFL;
            }
            case Rv32.OP_JALR -> {
                final long target = (a + decodeIImm(instruction)) & ~1L;
                writeReg(rd, nextPc);
                nextPc = target & 0xFFFF_FFFFL;
            }
            case Rv32.OP_BRANCH -> {
                if (branchTaken(funct3, a, b)) {
                    nextPc = (pcAtExec + decodeBImm(instruction)) & 0xFFFF_FFFFL;
                }
            }
            case Rv32.OP_LOAD -> {
                ensureMemoryMap();
                final long address = (a + decodeIImm(instruction)) & 0xFFFF_FFFFL;
                long value;
                try {
                    value = memoryMap.load(address, loadSize(funct3));
                } catch (MemoryAccessException e) {
                    throw new AccessFaultException(SocFault.CAUSE_LOAD_ACCESS_FAULT, e);
                }
                writeReg(rd, extendLoad(funct3, value));
            }
            case Rv32.OP_STORE -> {
                ensureMemoryMap();
                final long address = (a + decodeSImm(instruction)) & 0xFFFF_FFFFL;
                try {
                    memoryMap.store(address, b, storeSize(funct3));
                } catch (MemoryAccessException e) {
                    throw new AccessFaultException(SocFault.CAUSE_STORE_ACCESS_FAULT, e);
                }
            }
            case Rv32.OP_IMM -> nextPc = executeOpImm(rd, funct3, a, instruction, nextPc);
            case Rv32.OP_OP -> nextPc = executeOp(rd, funct3, funct7, a, b, nextPc, instruction);
            case Rv32.OP_MISC_MEM -> {
                // FENCE / FENCE.I：单核无缓存一致性需求 ⇒ no-op（真实 MCU 亦如此）
            }
            case Rv32.OP_SYSTEM -> nextPc = executeSystem(pcAtExec, rd, funct3, rs1, instruction, nextPc);
            default -> throw new IllegalInstructionException(instruction);
        }
        pc = nextPc & 0xFFFF_FFFFL;
    }

    private long executeOpImm(int rd, int funct3, long a, int instruction, long nextPc) {
        final int shamt = (instruction >>> 20) & 0x1F;
        final long imm = decodeIImm(instruction);
        switch (funct3) {
            case Rv32.F3_ADD -> writeReg(rd, a + imm);                       // ADDI
            case Rv32.F3_SLT -> writeReg(rd, (int) a < (int) imm ? 1 : 0);    // SLTI
            case Rv32.F3_SLTU -> writeReg(rd,
                    Long.compareUnsigned(a & 0xFFFF_FFFFL, imm & 0xFFFF_FFFFL) < 0 ? 1 : 0); // SLTIU
            case Rv32.F3_XOR -> writeReg(rd, a ^ imm);                        // XORI
            case Rv32.F3_OR -> writeReg(rd, a | imm);                         // ORI
            case Rv32.F3_AND -> writeReg(rd, a & imm);                        // ANDI
            case Rv32.F3_SLL -> writeReg(rd, a << shamt);                     // SLLI
            case Rv32.F3_SR -> {
                if (((instruction >>> 25) & 0x7F) == Rv32.F7_ALT) {
                    writeReg(rd, (int) a >> shamt);                           // SRAI（算术）
                } else {
                    writeReg(rd, (a & 0xFFFF_FFFFL) >>> shamt);               // SRLI（逻辑）
                }
            }
            default -> throw new IllegalInstructionException(instruction);
        }
        return nextPc;
    }

    private long executeOp(int rd, int funct3, int funct7, long a, long b,
                           long nextPc, int instruction) {
        if (funct7 == Rv32.F7_MULDIV) {
            if (!config.enableM) {
                throw new IllegalInstructionException(instruction);
            }
            writeReg(rd, executeMulDiv(funct3, a, b));
            return nextPc;
        }
        switch (funct3) {
            case Rv32.F3_ADD -> writeReg(rd, funct7 == Rv32.F7_ALT ? a - b : a + b);   // SUB / ADD
            case Rv32.F3_SLL -> writeReg(rd, a << (b & 0x1F));                        // SLL
            case Rv32.F3_SLT -> writeReg(rd, (int) a < (int) b ? 1 : 0);               // SLT
            case Rv32.F3_SLTU -> writeReg(rd,                                  // SLTU
                    Long.compareUnsigned(a & 0xFFFF_FFFFL, b & 0xFFFF_FFFFL) < 0 ? 1 : 0);
            case Rv32.F3_XOR -> writeReg(rd, a ^ b);                                   // XOR
            case Rv32.F3_SR -> writeReg(rd, funct7 == Rv32.F7_ALT
                    ? (int) a >> (b & 0x1F)                                            // SRA
                    : (a & 0xFFFF_FFFFL) >>> (b & 0x1F));                              // SRL
            case Rv32.F3_OR -> writeReg(rd, a | b);                                    // OR
            case Rv32.F3_AND -> writeReg(rd, a & b);                                   // AND
            default -> throw new IllegalInstructionException(instruction);
        }
        return nextPc;
    }

    /** M 扩展：乘除法（RISC-V 规范语义：除零不陷阱） */
    private long executeMulDiv(int funct3, long a, long b) {
        final long ua = a & 0xFFFF_FFFFL;
        final long ub = b & 0xFFFF_FFFFL;
        return switch (funct3) {
            case Rv32.F3_MUL -> (int) (a * b);
            case Rv32.F3_MULH -> (int) ((a * b) >> 32);
            case Rv32.F3_MULHSU -> (int) ((a * ub) >> 32);
            case Rv32.F3_MULHU -> (int) ((ua * ub) >>> 32);
            case Rv32.F3_DIV -> {
                if (b == 0) {
                    yield -1;
                }
                if ((int) a == Integer.MIN_VALUE && (int) b == -1) {
                    yield Integer.MIN_VALUE;
                }
                yield (int) a / (int) b;
            }
            case Rv32.F3_DIVU -> b == 0 ? -1 : (int) (ua / ub);
            case Rv32.F3_REM -> {
                if (b == 0) {
                    yield (int) a;
                }
                if ((int) a == Integer.MIN_VALUE && (int) b == -1) {
                    yield 0;
                }
                yield (int) a % (int) b;
            }
            case Rv32.F3_REMU -> b == 0 ? (int) a : (int) (ua % ub);
            default -> throw new IllegalInstructionException(0);
        };
    }

    private long executeSystem(long pcAtExec, int rd, int funct3, int rs1,
                               int instruction, long nextPc) {
        if (funct3 == Rv32.F3_PRIV) {
            final int funct12 = (instruction >>> 20) & 0xFFF;
            switch (funct12) {
                case Rv32.F12_ECALL -> {
                    // 按当前特权级区分 cause（U=8 / S=9 / M=11）—— MCU 档恒 M ⇒ 与改造前行为一致
                    final int cause = switch (privilege) {
                        case Rv32.PRIV_U -> Rv32.CAUSE_ECALL_FROM_U;
                        case Rv32.PRIV_S -> Rv32.CAUSE_ECALL_FROM_S;
                        default -> SocFault.CAUSE_ECALL_FROM_M;
                    };
                    trap(pcAtExec, 0, cause, "ecall from " + Rv32.privName(privilege) + " (exit)");
                    return pc;
                }
                case Rv32.F12_EBREAK -> {
                    trap(pcAtExec, 0, SocFault.CAUSE_BREAKPOINT, "ebreak");
                    return pc;
                }
                case Rv32.F12_WFI -> {
                    waitingForInterrupt = true;
                    return nextPc;
                }
                case Rv32.F12_MRET -> {
                    // MRET 是 M 档专属：低特权执行 ⇒ 非法指令
                    if (privilege != Rv32.PRIV_M) {
                        throw new IllegalInstructionException(instruction);
                    }
                    return doMret();
                }
                case Rv32.F12_SRET -> {
                    // SRET 需要含 S 的档位，且当前级 ≥ S（U 执行 ⇒ 非法指令）
                    if (!config.hasS() || privilege < Rv32.PRIV_S) {
                        throw new IllegalInstructionException(instruction);
                    }
                    return doSret();
                }
                case Rv32.F12_SFENCE_VMA -> {
                    // TLB 刷新：启用 Sv32 且当前级 ≥ S 才合法；本项目暂无硬件 TLB ⇒ no-op
                    if (!config.paging() || privilege < Rv32.PRIV_S) {
                        throw new IllegalInstructionException(instruction);
                    }
                    return nextPc;
                }
                default -> throw new IllegalInstructionException(instruction);
            }
        }
        final int csr = (instruction >>> 20) & 0xFFF;
        final boolean immediate = funct3 >= Rv32.F3_CSRRWI;
        final long source = immediate ? rs1 : regs[rs1];
        final long old = readCsrOrTrap(csr, pcAtExec, instruction);
        switch (funct3) {
            case Rv32.F3_CSRRW, Rv32.F3_CSRRWI -> writeCsrChecked(csr, source, pcAtExec, instruction);
            case Rv32.F3_CSRRS, Rv32.F3_CSRRSI -> {
                if (rs1 != 0) {
                    writeCsrChecked(csr, old | source, pcAtExec, instruction);
                }
            }
            case Rv32.F3_CSRRC, Rv32.F3_CSRRCI -> {
                if (rs1 != 0) {
                    writeCsrChecked(csr, old & ~source, pcAtExec, instruction);
                }
            }
            default -> throw new IllegalInstructionException(instruction);
        }
        // CSRRW/CSRRWI 且 rd==x0 时不读（避免副作用）；简化实现统一写回旧值
        if (rd != 0) {
            writeReg(rd, old);
        }
        return nextPc;
    }

    private long readCsrOrTrap(int csr, long pcAtExec, int instruction) {
        // ① 特权检查：低特权访问高特权 CSR ⇒ 非法指令（规范 csr[9:8] 编码最低可访问级）
        if (!csrAccessAllowed(csr)) {
            trap(pcAtExec, instruction, SocFault.CAUSE_ILLEGAL_INSTRUCTION,
                    "csr 0x" + Integer.toHexString(csr) + " not readable from " + Rv32.privName(privilege));
            throw new IllegalInstructionException(instruction);
        }
        // ② 实现检查（strictCsr 档才报错；MCU 默认宽容）
        if (config.strictCsr && !isImplementedCsr(csr)) {
            trap(pcAtExec, instruction, SocFault.CAUSE_ILLEGAL_INSTRUCTION,
                    "unimplemented csr 0x" + Integer.toHexString(csr));
            throw new IllegalInstructionException(instruction);
        }
        return readCsr(csr);
    }

    private void writeCsrChecked(int csr, long value, long pcAtExec, int instruction) {
        // ① 特权检查 + 只读检查（csr[11:10]==0b11 为只读）
        if (!csrAccessAllowed(csr) || csrIsReadOnly(csr)) {
            trap(pcAtExec, instruction, SocFault.CAUSE_ILLEGAL_INSTRUCTION,
                    "csr 0x" + Integer.toHexString(csr) + " not writable from " + Rv32.privName(privilege));
            throw new IllegalInstructionException(instruction);
        }
        // ② 实现检查
        if (config.strictCsr && !isImplementedCsr(csr)) {
            trap(pcAtExec, instruction, SocFault.CAUSE_ILLEGAL_INSTRUCTION,
                    "unimplemented csr 0x" + Integer.toHexString(csr));
            throw new IllegalInstructionException(instruction);
        }
        writeCsr(csr, value);
    }

    /** 当前特权级是否够访问该 CSR（规范：csr[9:8] = 最低可访问特权级） */
    private boolean csrAccessAllowed(int csr) {
        return privilege >= csrMinPrivilege(csr);
    }

    private static int csrMinPrivilege(int csr) {
        return (csr >>> 8) & 0x3;
    }

    /** 只读 CSR：csr[11:10] == 0b11 */
    private static boolean csrIsReadOnly(int csr) {
        return ((csr >>> 10) & 0x3) == 0x3;
    }

    /** CSR 是否实现（**按档位裁剪**：MCU 档没有 S-mode CSR 与计数器视图） */
    private boolean isImplementedCsr(int csr) {
        if (isMachineCsr(csr)) {
            return true;
        }
        if (!config.hasS()) {
            return false;
        }
        return isSupervisorCsr(csr) || isCounterCsr(csr);
    }

    private static boolean isMachineCsr(int csr) {
        return switch (csr) {
            case Rv32.CSR_MSTATUS, Rv32.CSR_MISA, Rv32.CSR_MIE, Rv32.CSR_MTVEC,
                 Rv32.CSR_MSCRATCH, Rv32.CSR_MEPC, Rv32.CSR_MCAUSE, Rv32.CSR_MTVAL,
                 Rv32.CSR_MIP, Rv32.CSR_MCYCLE, Rv32.CSR_MINSTRET,
                 Rv32.CSR_MVENDORID, Rv32.CSR_MARCHID, Rv32.CSR_MIMPID, Rv32.CSR_MHARTID,
                 Rv32.CSR_MEDELEG, Rv32.CSR_MIDELEG, Rv32.CSR_MCOUNTEREN -> true;
            default -> false;
        };
    }

    /** S-mode CSR（只在含 S 的档位存在；sstatus/sie/sip 是 M 侧寄存器的视图） */
    private static boolean isSupervisorCsr(int csr) {
        return switch (csr) {
            case Rv32.CSR_SSTATUS, Rv32.CSR_SIE, Rv32.CSR_STVEC, Rv32.CSR_SCOUNTEREN,
                 Rv32.CSR_SSCRATCH, Rv32.CSR_SEPC, Rv32.CSR_SCAUSE, Rv32.CSR_STVAL,
                 Rv32.CSR_SIP, Rv32.CSR_SATP -> true;
            default -> false;
        };
    }

    /** 计数器视图（U 档可读；真实门控由 mcounteren/scounteren 决定，本项目按档位直接放行） */
    private static boolean isCounterCsr(int csr) {
        return csr == Rv32.CSR_CYCLE || csr == Rv32.CSR_TIME || csr == Rv32.CSR_INSTRET;
    }

    // ==================== CSR 读写 ====================

    private long readCsr(int csr) {
        return switch (csr) {
            // ---------- M ----------
            case Rv32.CSR_MSTATUS -> mstatus;
            case Rv32.CSR_MISA -> Rv32.misa(config.enableM, config.enableA, false, false);
            case Rv32.CSR_MIE -> mie;
            case Rv32.CSR_MTVEC -> mtvec;
            case Rv32.CSR_MSCRATCH -> mscratch;
            case Rv32.CSR_MEPC -> mepc;
            case Rv32.CSR_MCAUSE -> mcause;
            case Rv32.CSR_MTVAL -> mtval;
            case Rv32.CSR_MIP -> mip | pendingInterruptBits();
            case Rv32.CSR_MCYCLE -> mcycle;
            case Rv32.CSR_MINSTRET -> instructionsRetired;
            case Rv32.CSR_MEDELEG -> medeleg;
            case Rv32.CSR_MIDELEG -> mideleg;
            case Rv32.CSR_MCOUNTEREN -> mcounteren;
            case Rv32.CSR_MVENDORID, Rv32.CSR_MARCHID, Rv32.CSR_MIMPID, Rv32.CSR_MHARTID -> 0;
            // ---------- S（视图：sstatus/sie/sip 是 mstatus/mie/mip 的子集） ----------
            case Rv32.CSR_SSTATUS -> mstatus & Rv32.SSTATUS_MASK;
            case Rv32.CSR_SIE -> mie & Rv32.SIE_MASK;
            case Rv32.CSR_STVEC -> stvec;
            case Rv32.CSR_SCOUNTEREN -> scounteren;
            case Rv32.CSR_SSCRATCH -> sscratch;
            case Rv32.CSR_SEPC -> sepc;
            case Rv32.CSR_SCAUSE -> scause;
            case Rv32.CSR_STVAL -> stval;
            case Rv32.CSR_SIP -> (mip | pendingInterruptBits()) & Rv32.SIE_MASK;
            case Rv32.CSR_SATP -> satp;
            // ---------- 计数器视图 ----------
            case Rv32.CSR_CYCLE -> mcycle;
            case Rv32.CSR_TIME -> mcycle;       // 无独立 time 计数器：SoC 档由 CLINT 提供 time
            case Rv32.CSR_INSTRET -> instructionsRetired;
            default -> 0;   // 宽容模式：未实现 CSR 读 0
        };
    }

    private void writeCsr(int csr, long value) {
        switch (csr) {
            case Rv32.CSR_MSTATUS -> mstatus = value & (Rv32.MSTATUS_MIE | Rv32.MSTATUS_MPIE | Rv32.MSTATUS_MPP
                    | Rv32.MSTATUS_SIE | Rv32.MSTATUS_SPIE | Rv32.MSTATUS_SPP
                    | Rv32.MSTATUS_MPRV | Rv32.MSTATUS_SUM | Rv32.MSTATUS_MXR);
            case Rv32.CSR_MIE -> mie = value & 0xFFFF_FFFFL;   // 自定义 SoC：允许任意中断线 0..31
            case Rv32.CSR_MTVEC -> mtvec = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MSCRATCH -> mscratch = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MEPC -> mepc = value & ~1L & 0xFFFF_FFFFL;
            case Rv32.CSR_MCAUSE -> mcause = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MTVAL -> mtval = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MIP -> mip = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MCYCLE -> mcycle = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MINSTRET -> instructionsRetired = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MEDELEG -> medeleg = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MIDELEG -> mideleg = value & 0xFFFF_FFFFL;
            case Rv32.CSR_MCOUNTEREN -> mcounteren = value & 0xFFFF_FFFFL;
            // ---------- S ----------
            case Rv32.CSR_SSTATUS -> mstatus = (mstatus & ~Rv32.SSTATUS_MASK) | (value & Rv32.SSTATUS_MASK);
            case Rv32.CSR_SIE -> mie = (mie & ~Rv32.SIE_MASK) | (value & Rv32.SIE_MASK);
            case Rv32.CSR_STVEC -> stvec = value & 0xFFFF_FFFFL;
            case Rv32.CSR_SCOUNTEREN -> scounteren = value & 0xFFFF_FFFFL;
            case Rv32.CSR_SSCRATCH -> sscratch = value & 0xFFFF_FFFFL;
            case Rv32.CSR_SEPC -> sepc = value & ~1L & 0xFFFF_FFFFL;
            case Rv32.CSR_SCAUSE -> scause = value & 0xFFFF_FFFFL;
            case Rv32.CSR_STVAL -> stval = value & 0xFFFF_FFFFL;
            case Rv32.CSR_SIP -> mip = (mip & ~Rv32.SIE_MASK) | (value & Rv32.SIE_MASK);
            // satp 只有分页档才生效（MCU 档写入被忽略，避免误开翻译）
            case Rv32.CSR_SATP -> satp = config.paging() ? (value & 0xFFFF_FFFFL) : 0;
            default -> {
                // 宽容模式：未实现 CSR 写入忽略
            }
        }
    }

    // ==================== 中断 ====================

    private long pendingInterruptBits() {
        return interruptController == null ? 0 : interruptController.getPendingInterrupts();
    }

    /** 若可交付中断则转向陷阱，返回是否发生了中断 */
    private boolean checkInterrupt() {
        if (interruptController == null || !interruptsGloballyEnabled) {
            return false;
        }
        // M 档看 MIE；处于 S/U 档（且实现 S）时看 SIE —— 与 RISC-V 语义一致
        final boolean inSOrU = config.hasS() && privilege <= Rv32.PRIV_S;
        if ((mstatus & (inSOrU ? Rv32.MSTATUS_SIE : Rv32.MSTATUS_MIE)) == 0) {
            return false;
        }
        final long pending = interruptController.getPendingInterrupts();
        if (pending == 0) {
            return false;
        }

        // ---------- ① 已委派给 S 的中断（S 档最多） ----------
        if (inSOrU) {
            final long sPending = pending & mie & mideleg & Rv32.SIE_MASK;
            if (sPending != 0) {
                final long sTarget = stvec == 0 ? 0 : (stvec & ~0x3L);
                if (sTarget != 0) {
                    final int irq = Long.numberOfTrailingZeros(sPending);
                    scause = SocFault.INTERRUPT_BIT | (irq & 0x7FFF_FFFF);
                    sepc = pc;
                    stval = 0;
                    enterTrapStateS();
                    privilege = Rv32.PRIV_S;
                    lastTrap = new SocFault((int) scause, 0, sepc, "s-interrupt#" + irq);
                    pc = sTarget;
                    return true;
                }
            }
        }

        // ---------- ② 其余交给 M（委派出去的不再在 M 重复交付） ----------
        final long mVisible = config.hasS() ? (pending & ~mideleg) : pending;
        final long enabled = mVisible & mie;
        if (enabled == 0) {
            return false;
        }
        final int irq = Long.numberOfTrailingZeros(enabled);
        final long target = mtvec == 0 ? 0 : (mtvec & ~0x3L);
        if (target == 0) {
            // 未设 mtvec：无法交付 ⇒ 保持挂起（不算故障，等玩家配置）
            return false;
        }
        // 中断陷阱：mcause 最高位 = 1
        mcause = SocFault.INTERRUPT_BIT | (irq & 0x7FFF_FFFF);
        mepc = pc;
        mtval = 0;
        enterTrapStateM();
        privilege = Rv32.PRIV_M;
        lastTrap = new SocFault((int) mcause, 0, mepc, "interrupt#" + irq);
        pc = target;
        return true;
    }

    // ==================== 陷阱 ====================

    /**
     * 交付一个陷阱（异常或中断）。
     *
     * <p>步骤：① 若满足委派条件（含 S 档 + 当前 ≤ S + medeleg/mideleg 置位）则交 S；
     * ② 否则交 M；③ 两者都没有 handler 时按 {@code haltWithoutHandler} 停机（"芯片死机"）。</p>
     */
    private void trap(long pcAtFault, long tval, int cause, String detail) {
        final int fullCause = cause;
        final boolean isInterrupt = (cause & SocFault.INTERRUPT_BIT) != 0;
        lastTrap = new SocFault(fullCause, tval, pcAtFault, detail);

        // ---------- ① 委派给 S ----------
        if (shouldDelegate(cause, isInterrupt)) {
            final long sHandler = stvec == 0 ? 0 : (stvec & ~0x3L);
            if (sHandler != 0) {
                scause = fullCause & 0xFFFF_FFFFL;
                sepc = pcAtFault & 0xFFFF_FFFFL;
                stval = tval & 0xFFFF_FFFFL;
                enterTrapStateS();
                privilege = Rv32.PRIV_S;
                pc = sHandler;
                return;
            }
            // S handler 未设：继续走 M 路径（比静默卡死对玩家友好）
        }

        // ---------- ② 交给 M ----------
        mcause = fullCause & 0xFFFF_FFFFL;
        mepc = pcAtFault & 0xFFFF_FFFFL;
        mtval = tval & 0xFFFF_FFFFL;
        final long handler = mtvec == 0 ? 0 : (mtvec & ~0x3L);
        if (handler != 0) {
            // 真实语义：交给 trap handler（玩家可写看门狗/错误恢复逻辑）
            enterTrapStateM();
            privilege = Rv32.PRIV_M;
            pc = handler;
            return;
        }
        if (config.haltWithoutHandler) {
            halted = true;
            fault = lastTrap;
            pc = pcAtFault & 0xFFFF_FFFFL;
        }
    }

    // ==================== 工具 ====================

    private void writeReg(int index, long value) {
        if (index != 0) {
            regs[index] = (int) value;   // 32 位符号扩展（x0 恒 0）
        }
    }

    private static boolean branchTaken(int funct3, long a, long b) {
        return switch (funct3) {
            case Rv32.F3_BEQ -> a == b;
            case Rv32.F3_BNE -> a != b;
            case Rv32.F3_BLT -> (int) a < (int) b;
            case Rv32.F3_BGE -> (int) a >= (int) b;
            case Rv32.F3_BLTU -> Long.compareUnsigned(a & 0xFFFF_FFFFL, b & 0xFFFF_FFFFL) < 0;
            case Rv32.F3_BGEU -> Long.compareUnsigned(a & 0xFFFF_FFFFL, b & 0xFFFF_FFFFL) >= 0;
            default -> false;
        };
    }

    private static int loadSize(int funct3) {
        return switch (funct3) {
            case Rv32.F3_LB, Rv32.F3_LBU -> Sizes.SIZE_8;
            case Rv32.F3_LH, Rv32.F3_LHU -> Sizes.SIZE_16;
            case Rv32.F3_LW -> Sizes.SIZE_32;
            default -> throw new IllegalInstructionException(0);
        };
    }

    private static int storeSize(int funct3) {
        return switch (funct3) {
            case Rv32.F3_SB -> Sizes.SIZE_8;
            case Rv32.F3_SH -> Sizes.SIZE_16;
            case Rv32.F3_SW -> Sizes.SIZE_32;
            default -> throw new IllegalInstructionException(0);
        };
    }

    private static long extendLoad(int funct3, long value) {
        return switch (funct3) {
            case Rv32.F3_LB -> (byte) value;
            case Rv32.F3_LH -> (short) value;
            case Rv32.F3_LW -> (int) value;
            case Rv32.F3_LBU -> value & 0xFFL;
            case Rv32.F3_LHU -> value & 0xFFFFL;
            default -> value;
        };
    }

    private static int decodeIImm(int instruction) {
        return instruction >> 20;   // 算术右移 ⇒ 符号扩展
    }

    private static int decodeSImm(int instruction) {
        final int imm = ((instruction >>> 25) << 5) | ((instruction >>> 7) & 0x1F);
        return (imm << 20) >> 20;
    }

    private static int decodeBImm(int instruction) {
        final int imm = (((instruction >>> 31) & 1) << 12)
                | (((instruction >>> 7) & 1) << 11)
                | (((instruction >>> 25) & 0x3F) << 5)
                | (((instruction >>> 8) & 0xF) << 1);
        return (imm << 19) >> 19;
    }

    private static int decodeJImm(int instruction) {
        final int imm = (((instruction >>> 31) & 1) << 20)
                | (((instruction >>> 12) & 0xFF) << 12)
                | (((instruction >>> 20) & 1) << 11)
                | (((instruction >>> 21) & 0x3FF) << 1);
        return (imm << 11) >> 11;
    }
    /**
     * 故障时的 PC（诊断用，2026-09-24）。
     *
     * <p>为什么需要：只有 `isFaulted()` 等于"知道它死了但不知道死在哪"——
     * mini OS 的排查就卡在这里（跑进 main 之后 fault，但没有任何地址信息）。
     * RISC-V 在陷入时会把出错 PC 写进 `mepc`（见本类 trap 处理），所以直接暴露它。</p>
     */
    public long faultPc() {
        return mepc;
    }
}
