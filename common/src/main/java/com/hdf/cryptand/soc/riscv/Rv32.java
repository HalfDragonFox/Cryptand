package com.hdf.cryptand.soc.riscv;

/**
 * ===== RISC-V 编码常量表（2026-09-15 建立；2026-09-16 扩特权档位）=====
 *
 * <p>opcode / funct3 / funct7 / CSR 号 / 特权指令编码。按**档位**分层收录 —— 常量齐全，
 * 但**是否实现由 {@code Rv32Core.Config} 决定**（MCU 档不开 S/U 与 MMU）：</p>
 *
 * <ul>
 *   <li><b>基础</b>：RV32I + M 扩展（MCU-μ / MCU-标准 都用）；</li>
 *   <li><b>M-mode 特权集</b>：mstatus/mie/mip/mtvec/mscratch/mepc/mcause/mtval/mcycle…（MCU 档即用）；</li>
 *   <li><b>S/U 特权级与委派</b>（SoC 档，跑 Linux 的前提）：sstatus/sie/stvec/…/satp、
 *       medeleg/mideleg、mcounteren/scounteren、sret；</li>
 *   <li><b>Sv32 MMU</b>（SoC 档）：satp 的 MODE/PPN 位域与 sfence.vma。</li>
 * </ul>
 *
 * <p>⚠ 异常号沿用 {@code api.SocFault} 的常量（内含 {@code INTERRUPT_BIT}），本类不重复定义，
 * 只补"按特权级区分"的那几个（ecall from U/S/M）。</p>
 */
public final class Rv32 {

    private Rv32() {
    }

    // ==================== opcode（bit[6:0]） ====================
    public static final int OP_LOAD = 0x03;
    public static final int OP_MISC_MEM = 0x0F;
    public static final int OP_IMM = 0x13;
    public static final int OP_AUIPC = 0x17;
    public static final int OP_STORE = 0x23;
    public static final int OP_OP = 0x33;
    public static final int OP_LUI = 0x37;
    public static final int OP_BRANCH = 0x63;
    public static final int OP_JALR = 0x67;
    public static final int OP_JAL = 0x6F;
    public static final int OP_SYSTEM = 0x73;
    /** A 扩展（原子）：AMO*.W 走 OP_AMO，LR/SC 走 OP_LOAD/OP_STORE + funct5 */
    public static final int OP_AMO = 0x2F;

    // ==================== funct3：LOAD / STORE ====================
    public static final int F3_LB = 0, F3_LH = 1, F3_LW = 2, F3_LBU = 4, F3_LHU = 5;
    public static final int F3_SB = 0, F3_SH = 1, F3_SW = 2;

    // ==================== funct3：BRANCH ====================
    public static final int F3_BEQ = 0, F3_BNE = 1, F3_BLT = 4, F3_BGE = 5, F3_BLTU = 6, F3_BGEU = 7;

    // ==================== funct3：OP-IMM / OP ====================
    public static final int F3_ADD = 0, F3_SLL = 1, F3_SLT = 2, F3_SLTU = 3;
    public static final int F3_XOR = 4, F3_SR = 5, F3_OR = 6, F3_AND = 7;
    public static final int F3_SUB = 0, F3_SRA = 5;   // 与 ADD/SRL 同 funct3，靠 funct7 区分

    // ==================== funct3：M 扩展（funct7 = 0x01） ====================
    public static final int F3_MUL = 0, F3_MULH = 1, F3_MULHSU = 2, F3_MULHU = 3;
    public static final int F3_DIV = 4, F3_DIVU = 5, F3_REM = 6, F3_REMU = 7;

    // ==================== funct5：A 扩展（bit[31:27]） ====================
    public static final int F5_LR = 0x02, F5_SC = 0x03;
    public static final int F5_AMOSWAP = 0x01, F5_AMOADD = 0x00, F5_AMOXOR = 0x04;
    public static final int F5_AMOAND = 0x0C, F5_AMOOR = 0x08;
    public static final int F5_AMOMIN = 0x10, F5_AMOMAX = 0x14, F5_AMOMINU = 0x18, F5_AMOMAXU = 0x1C;

    // ==================== funct7 ====================
    public static final int F7_BASE = 0x00;
    public static final int F7_ALT = 0x20;        // SUB / SRA
    public static final int F7_MULDIV = 0x01;     // M 扩展

    // ==================== SYSTEM（funct3） ====================
    public static final int F3_PRIV = 0;
    public static final int F3_CSRRW = 1, F3_CSRRS = 2, F3_CSRRC = 3;
    public static final int F3_CSRRWI = 5, F3_CSRRSI = 6, F3_CSRRCI = 7;

    // ==================== SYSTEM（funct12） ====================
    public static final int F12_ECALL = 0x000;
    public static final int F12_EBREAK = 0x001;
    /** S-mode 陷阱返回（SoC 档） */
    public static final int F12_SRET = 0x102;
    public static final int F12_WFI = 0x105;
    public static final int F12_MRET = 0x302;
    /** Sv32 的 TLB 刷新（SoC 档；rs1/rs2 非零时按 vaddr/asid 刷新，本项目实现可整体刷） */
    public static final int F12_SFENCE_VMA = 0x120;

    // ==================== 特权级 ====================
    public static final int PRIV_U = 0;
    public static final int PRIV_S = 1;
    public static final int PRIV_M = 3;

    // ==================== CSR 号：M-mode（MCU 档即用） ====================
    public static final int CSR_MSTATUS = 0x300;
    public static final int CSR_MISA = 0x301;
    public static final int CSR_MEDELEG = 0x302;
    public static final int CSR_MIDELEG = 0x303;
    public static final int CSR_MIE = 0x304;
    public static final int CSR_MTVEC = 0x305;
    public static final int CSR_MCOUNTEREN = 0x306;
    public static final int CSR_MSCRATCH = 0x340;
    public static final int CSR_MEPC = 0x341;
    public static final int CSR_MCAUSE = 0x342;
    public static final int CSR_MTVAL = 0x343;
    public static final int CSR_MIP = 0x344;
    public static final int CSR_MCYCLE = 0xB00;
    public static final int CSR_MINSTRET = 0xB02;
    public static final int CSR_MVENDORID = 0xF11;
    public static final int CSR_MARCHID = 0xF12;
    public static final int CSR_MIMPID = 0xF13;
    public static final int CSR_MHARTID = 0xF14;

    // ==================== CSR 号：S-mode（SoC 档） ====================
    public static final int CSR_SSTATUS = 0x100;
    public static final int CSR_SIE = 0x104;
    public static final int CSR_STVEC = 0x105;
    public static final int CSR_SCOUNTEREN = 0x106;
    public static final int CSR_SSCRATCH = 0x140;
    public static final int CSR_SEPC = 0x141;
    public static final int CSR_SCAUSE = 0x142;
    public static final int CSR_STVAL = 0x143;
    public static final int CSR_SIP = 0x144;
    /** 地址翻译与保护（Sv32 的控制寄存器） */
    public static final int CSR_SATP = 0x180;

    // ==================== CSR 号：计数器视图（U 档只读） ====================
    public static final int CSR_CYCLE = 0xC00;
    public static final int CSR_TIME = 0xC01;
    public static final int CSR_INSTRET = 0xC02;

    // ==================== mstatus / sstatus 位 ====================
    public static final long MSTATUS_SIE = 1L << 1;    // S 档全局中断使能（sstatus 视图）
    public static final long MSTATUS_MIE = 1L << 3;    // 全局中断使能（M 档）
    public static final long MSTATUS_SPIE = 1L << 5;   // 陷入 S 前的中断使能
    public static final long MSTATUS_MPIE = 1L << 7;   // 陷入 M 前的中断使能
    public static final long MSTATUS_SPP = 1L << 8;    // 陷入 S 前的特权级（0=U, 1=S）
    public static final long MSTATUS_MPP = 3L << 11;   // 陷入 M 前的特权级（SoC 档可为 U/S/M）
    public static final long MSTATUS_MPRV = 1L << 17;  // load/store 用 MPP 的特权做翻译
    public static final long MSTATUS_SUM = 1L << 18;   // 允许 S 档访问 U 页
    public static final long MSTATUS_MXR = 1L << 19;   // 允许执行可读页

    /** sstatus 可见的位掩码（S 档能看到的 mstatus 子集） */
    public static final long SSTATUS_MASK =
            MSTATUS_SIE | MSTATUS_SPIE | MSTATUS_SPP | MSTATUS_SUM | MSTATUS_MXR;

    // ==================== mie / mip 位（M + S 两档） ====================
    public static final long MIE_MSIE = 1L << 3;   // M 软件中断
    public static final long MIE_MTIE = 1L << 7;   // M 定时器中断
    public static final long MIE_MEIE = 1L << 11;  // M 外部中断
    public static final long MIE_SSIE = 1L << 1;   // S 软件中断（Linux 的 IPI）
    public static final long MIE_STIE = 1L << 5;   // S 定时器中断
    public static final long MIE_SEIE = 1L << 9;   // S 外部中断

    /** S 档能操作的 mie/mip 位掩码 */
    public static final long SIE_MASK = MIE_SSIE | MIE_STIE | MIE_SEIE;

    // ==================== satp（Sv32） ====================
    /** MODE=1 表示 Sv32（MODE=0 为 Bare，不翻译） */
    public static final long SATP_MODE_SV32 = 1L << 31;
    /** Sv32 的 PPN 占 [21:0] */
    public static final long SATP_PPN_MASK = 0x003F_FFFFL;
    /** 页大小 4KiB 与页内偏移位数 */
    public static final int PAGE_SHIFT = 12;
    public static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    public static final long PAGE_MASK = PAGE_SIZE - 1;
    /** 大页（Sv32 第二级超级页 = 4MiB；RISC-V 规范里 Sv32 的 megapage 为 4MiB） */
    public static final int MEGA_SHIFT = 22;
    /** 页表项有效位 */
    public static final long PTE_V = 1L << 0;
    public static final long PTE_R = 1L << 1;
    public static final long PTE_W = 1L << 2;
    public static final long PTE_X = 1L << 3;
    public static final long PTE_U = 1L << 4;
    public static final long PTE_G = 1L << 5;
    public static final long PTE_A = 1L << 6;
    public static final long PTE_D = 1L << 7;
    /** 物理地址位宽（Sv32：34 位物理地址，PPN 为 22 位） */
    public static final int PTE_PPN_SHIFT = 10;

    // ==================== misa ====================
    /** RV32 的 MXL 位（1 = 32 位） */
    public static final long MISA_MXL_32 = 1L << 30;

    /** 按档位组装 misa（I 恒有；M/A/C/F 由配置决定） */
    public static long misa(boolean m, boolean a, boolean c, boolean f) {
        long v = MISA_MXL_32 | (1L << 8);          // I
        if (m) {
            v |= 1L << 12;                         // M
        }
        if (a) {
            v |= 1L << 0;                          // A
        }
        if (c) {
            v |= 1L << 2;                          // C
        }
        if (f) {
            v |= 1L << 5;                          // F
        }
        return v;
    }

    /** 旧名保留：RV32IM 的 ISA 位图（MCU 档默认档位） */
    public static final long MISA_RV32IM = misa(true, false, false, false);

    // ==================== 异常号：按特权级区分的 ecall ====================
    // ⚠ 其余异常号（非法指令 / 取指 / 访存故障 / 断点 / 页错误）沿用 api.SocFault 的常量
    public static final int CAUSE_ECALL_FROM_U = 8;
    public static final int CAUSE_ECALL_FROM_S = 9;
    public static final int CAUSE_ECALL_FROM_M = 11;
    /** 页错误（L2 用；与 SocFault 若重复以同名同值为准） */
    public static final int CAUSE_INST_PAGE_FAULT = 12;
    public static final int CAUSE_LOAD_PAGE_FAULT = 13;
    public static final int CAUSE_STORE_PAGE_FAULT = 15;

    /** 特权级可读名（日志/UI 用） */
    public static String privName(int priv) {
        return switch (priv) {
            case PRIV_U -> "U";
            case PRIV_S -> "S";
            case PRIV_M -> "M";
            default -> "?";
        };
    }
}
