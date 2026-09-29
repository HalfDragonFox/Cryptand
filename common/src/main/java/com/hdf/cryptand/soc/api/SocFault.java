package com.hdf.cryptand.soc.api;

/**
 * ===== SoC 故障态（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>模拟真实 MCU 的异常模型：进入故障态后 <b>停止执行</b>，记录
 * {@code mcause / mtval / mepc}，等待宿主（UI/玩家）复位。这是芯片的
 * <b>一等公民行为</b>，不是"程序崩溃"——游戏与其它芯片完全不受影响。</p>
 */
public final class SocFault {

    /** 无故障 */
    public static final SocFault NONE = new SocFault(0, 0, 0, "");

    /** RISC-V 异常号（mcause 低 31 位） */
    public static final int CAUSE_INSTRUCTION_ADDRESS_MISALIGNED = 0;
    public static final int CAUSE_INSTRUCTION_ACCESS_FAULT = 1;
    public static final int CAUSE_ILLEGAL_INSTRUCTION = 2;
    public static final int CAUSE_BREAKPOINT = 3;
    public static final int CAUSE_LOAD_ADDRESS_MISALIGNED = 4;
    public static final int CAUSE_LOAD_ACCESS_FAULT = 5;
    public static final int CAUSE_STORE_ADDRESS_MISALIGNED = 6;
    public static final int CAUSE_STORE_ACCESS_FAULT = 7;
    public static final int CAUSE_ECALL_FROM_M = 11;
    /** 中断位（mcause 最高位） */
    public static final int INTERRUPT_BIT = 0x80000000;

    private final int cause;
    private final long tval;
    private final long epc;
    private final String detail;

    public SocFault(int cause, long tval, long epc, String detail) {
        this.cause = cause;
        this.tval = tval;
        this.epc = epc;
        this.detail = detail == null ? "" : detail;
    }

    public boolean isFaulted() {
        return this != NONE && cause != 0;
    }

    /** mcause（含中断位） */
    public int getCause() {
        return cause;
    }

    /** mtval（出错地址/指令编码） */
    public long getTval() {
        return tval;
    }

    /** mepc（出错指令地址） */
    public long getEpc() {
        return epc;
    }

    /** 人类可读说明（UI 显示） */
    public String getDetail() {
        return detail;
    }

    /** 异常码 → 名称（UI/诊断） */
    public static String causeName(int cause) {
        boolean interrupt = (cause & INTERRUPT_BIT) != 0;
        int code = cause & ~INTERRUPT_BIT;
        if (interrupt) {
            return "Interrupt#" + code;
        }
        return switch (code) {
            case CAUSE_INSTRUCTION_ADDRESS_MISALIGNED -> "InstructionAddressMisaligned";
            case CAUSE_INSTRUCTION_ACCESS_FAULT -> "InstructionAccessFault";
            case CAUSE_ILLEGAL_INSTRUCTION -> "IllegalInstruction";
            case CAUSE_BREAKPOINT -> "Breakpoint";
            case CAUSE_LOAD_ADDRESS_MISALIGNED -> "LoadAddressMisaligned";
            case CAUSE_LOAD_ACCESS_FAULT -> "LoadAccessFault";
            case CAUSE_STORE_ADDRESS_MISALIGNED -> "StoreAddressMisaligned";
            case CAUSE_STORE_ACCESS_FAULT -> "StoreAccessFault";
            case CAUSE_ECALL_FROM_M -> "EcallFromM";
            default -> "Cause#" + code;
        };
    }

    @Override
    public String toString() {
        return "SocFault[" + causeName(cause) + " tval=0x" + Long.toHexString(tval)
                + " epc=0x" + Long.toHexString(epc) + (detail.isEmpty() ? "" : " " + detail) + "]";
    }
}
