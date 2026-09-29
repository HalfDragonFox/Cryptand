package com.hdf.cryptand.soc.board;

/**
 * ===== 处理器 ISA / 架构声明（2026-09-18 用户定案）=====
 *
 * <p>用户规则：「芯片物品 id 一律带位宽后缀 {@code _32}/{@code _64}」「位宽由 <b>CPU 部件</b> 决定
 * （不是 EEPROM、不是盘）」，且沙箱的长期定位是「<b>多 ISA 的虚拟机平台</b>」（RV32 / RV64 / 8051 /
 * Rust 产物都插在同一个外壳里）。位宽/架构不能只写在 id 字符串里 —— 必须有<b>结构化字段</b>供架构判断，
 * 否则 64 位芯片会被静默按 RV32 跑（"64 位芯片跑 32 位程序"），排查成本极高。
 * 本枚举就是那个字段：{@link SocPartItem#isa()}。</p>
 *
 * <h3>为什么 {@code rv32ec} 的后缀仍是 {@code _32}、8051 却不是 {@code _8}</h3>
 * <ul>
 *   <li>{@code rv32ec}：XLEN 就是 32 ⇒ 后缀 {@code _32}，<b>不能</b>写成 {@code _16}
 *       （那会被误读成 16 位 CPU，以后排查被自己的命名坑）；"16 寄存器 / 小地址窗口"只写进显示名与提示。</li>
 *   <li>{@code mcu3_8051}：8051 的位宽是 8，但 {@code _8} 会被读成"8 位 RISC-V"（RISC-V 里
 *       根本没有这个位宽）⇒ 用<b>架构族名后缀</b> {@code _8051}，与 RISC-V 的位宽后缀刻意区分开。</li>
 * </ul>
 *
 * <h3>内核实现状态（事实核查，2026-09-18；判定依据是代码而不是命名）</h3>
 * <ul>
 *   <li>纯 Java 内核 {@code common/.../soc/riscv/Rv32Core.java}：{@code private final long[] regs = new long[32]}
 *       —— 固定 32 个通用寄存器；{@code misa} 由 {@code Rv32.misa(enableM, enableA, false, false)} 组装
 *       —— <b>无 E、无 C、无 RV64</b>；</li>
 *   <li>C++ native 内核 {@code excode/cryptand-rv32/src/rv32_core.cpp}：{@code misa_ = MXL=32|'I'|'M'}，
 *       指令解码走 {@code instr & 0x7F}（32 位定长，没有压缩指令分支）—— 同样<b>无 E / 无 C / 无 RV64</b>；</li>
 *   <li>8051：仓库内<b>没有任何 MCS-51 内核</b>（{@code soc/} 下只有 riscv 与 nativebridge 两个包）⇒ 只有物品与声明。</li>
 * </ul>
 *
 * <p>由此定出三档支持度（{@link KernelSupport}）：{@code rv32im} 完整可用；含 A/C 的档位<b>可开机</b>
 * 但扩展缺失（用到就触发非法指令陷阱 ⇒ 明确报错，不是静默错跑）；{@code rv32ec} / {@code rv64*} /
 * {@code mcs51} 属于<b>位宽或架构根本不同</b>，一旦放行就是静默降级 ⇒ 架构侧 {@code initialize()}
 * <b>直接拒绝开机</b>（见 {@code CryptandOcArchitecture}）。</p>
 */
public enum SocIsa {

    /**
     * 极低端：RV32E + C（等价 Cortex-M0 级别）。
     *
     * <p>⚠ 当前内核是 <b>32 寄存器</b>模型 ⇒ RV32E「x16–x31 非法」规则未实现。
     * 若按普通 RV32 放行，用 x16–x31 的程序会"能跑"（静默降级）⇒ 本档为<b>占位</b>：
     * 有物品、有 ISA 声明、运行时报"未实现"并拒绝开机。</p>
     */
    RV32EC("rv32ec", "RV32EC", 32, "16 个通用寄存器（x0–x15；x16–x31 非法）", 64,
            KernelSupport.UNIMPLEMENTED, "RV32E 的 16 寄存器规则（x16–x31 非法）"),

    /** 入门：RV32I + M（当前内核的完整能力） */
    RV32IM("rv32im", "RV32IM", 32, "32 个通用寄存器", 0, KernelSupport.FULL, ""),

    /** 标准：RV32I + M + C；内核无压缩指令解码 ⇒ 可开机但 C 缺失 */
    RV32IMC("rv32imc", "RV32IMC", 32, "32 个通用寄存器", 0,
            KernelSupport.EXTENSIONS_MISSING, "C 扩展（压缩指令）"),

    /** 应用：RV32I + M + A + C；内核无 A/C ⇒ 可开机但 A/C 缺失 */
    RV32IMAC("rv32imac", "RV32IMAC", 32, "32 个通用寄存器", 0,
            KernelSupport.EXTENSIONS_MISSING, "A / C 扩展"),

    /** 64 位应用：RV64I + M + A + C；内核只有 RV32 ⇒ 位宽未实现，拒绝开机 */
    RV64IMAC("rv64imac", "RV64IMAC", 64, "32 个通用寄存器（64 位字长）", 0,
            KernelSupport.UNIMPLEMENTED, "RV64 位宽（XLEN=64）"),

    /**
     * 8051（Intel MCS-51，8 位单片机）—— <b>占位</b>。
     *
     * <p>用户 2026-09-18："MCU 增加 8051，模拟器也使用 c 或 c++ 实现，也进入沙箱……然后增加 sdcc 的编译器，
     * 当然也可以玩家在 keil c51 中编译完成后放入里面"。本轮<b>只做物品 / 语言 / 模型 / ISA 声明 + 架构分支</b>，
     * 不实现内核（那是后续独立任务）。</p>
     *
     * <p>id 后缀用 {@code _8051} 而不是 {@code _8}：8051 的 8 位与 RISC-V 的 XLEN 体系无关，
     * 写 {@code _8} 会被误读成"8 位 RISC-V"。</p>
     */
    MCS51("mcs51", "8051", 8, "ACC + 4 组 R0–R7 工作寄存器（8 位）", 64,
            KernelSupport.UNIMPLEMENTED, "8051（MCS-51）内核未实现");

    /** ===== 内核对该 ISA / 架构的支持度（"绝不静默降级"的判定口径）===== */
    public enum KernelSupport {
        /** 内核完整实现（可开机，无警告） */
        FULL,
        /**
         * 可开机，但声明的扩展内核没实现。
         *
         * <p>不属于"静默降级"：用到的指令会触发<b>非法指令陷阱</b>并停机（有日志、有故障码），
         * 而普通 RV32IM 指令照常执行 ⇒ 允许开机，但开机日志给 WARN。</p>
         */
        EXTENSIONS_MISSING,
        /**
         * 内核的位宽 / 寄存器模型 / 指令集与本 ISA <b>根本不同</b> ⇒ 放行就等于静默降级
         * （RV64 程序按 RV32 解释、RV32E 的 x16–x31 被当成合法寄存器、8051 机器码被当 RISC-V 解码）
         * ⇒ <b>拒绝开机</b>。
         */
        UNIMPLEMENTED
    }

    private final String id;
    private final String label;
    private final int xlenBits;
    private final String registerModel;
    private final int addressWindowKb;
    private final KernelSupport support;
    private final String supportNote;

    SocIsa(String id, String label, int xlenBits, String registerModel, int addressWindowKb,
           KernelSupport support, String supportNote) {
        this.id = id;
        this.label = label;
        this.xlenBits = xlenBits;
        this.registerModel = registerModel;
        this.addressWindowKb = addressWindowKb;
        this.support = support;
        this.supportNote = supportNote;
    }

    /** 规范小写名（{@code rv32im} / {@code rv64imac} / {@code mcs51}）：日志与工具提示都用它 */
    public String id() {
        return id;
    }

    /**
     * 按规范小写名回查（{@code rv32im} / {@code rv64imac} / {@code mcs51}）。
     *
     * <p>成品芯片的规格以 id 字符串落 NBT，回读时用它还原 —— <b>查不到返回 {@code null}</b>，
     * 调用方必须明确报错，绝不猜一个 ISA 出来（「绝不静默按 RV32 跑」）。</p>
     */
    public static SocIsa byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (final SocIsa isa : values()) {
            if (isa.id.equals(id)) {
                return isa;
            }
        }
        return null;
    }

    /** 展示名（{@code RV32IM} / {@code 8051}） */
    public String label() {
        return label;
    }

    /**
     * 位宽 / 字长。
     *
     * <p>⚠ 它<b>不是</b> id 后缀的唯一来源：RISC-V 档位用 {@code _32}/{@code _64}（= 本值），
     * 8051 用 {@code _8051}（架构族名），见类注释。</p>
     */
    public int xlenBits() {
        return xlenBits;
    }

    /** 寄存器模型的人话描述（RV32E 的 16 个 / RV64 的 64 位字长 / 8051 的 ACC+工作寄存器组） */
    public String registerModel() {
        return registerModel;
    }

    /** 规格地址窗口上限（KB；0 = 沿用机箱装配出来的窗口）。仅规格/文案用途，见类注释 */
    public int addressWindowKb() {
        return addressWindowKb;
    }

    public KernelSupport support() {
        return support;
    }

    /** 未实现时的说明（"哪个部分没实现"），用于日志与工具提示的括号内容 */
    public String supportNote() {
        return supportNote;
    }

    /**
     * 未实现时的<b>标题文案</b>。
     *
     * <p>用户 2026-09-18 明确要求两种表述都在日志/提示里出现，因为排查方向完全不同：
     * 「该<b>位宽</b>内核未实现」（RV64/RV32E ⇒ 要扩 XLEN 或寄存器模型）与
     * 「该<b>架构</b>内核未实现」（8051 ⇒ 要接一整套新指令集内核）。</p>
     */
    public String unimplementedHeadline() {
        return switch (this) {
            case MCS51 -> "该架构内核未实现";
            case RV32EC, RV64IMAC -> "该位宽内核未实现";
            default -> "该内核未实现";
        };
    }

    /**
     * 能不能开机。
     *
     * <p>⚠ 只有 {@link KernelSupport#UNIMPLEMENTED} 返回 false —— 这是「绝不静默按 RV32 跑」的闸门。
     * 架构侧必须用它决定是否创建沙箱，而不是"猜一个默认位宽"。</p>
     */
    public boolean kernelRunnable() {
        return support != KernelSupport.UNIMPLEMENTED;
    }
}
