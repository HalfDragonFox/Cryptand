package com.hdf.cryptand.soc.riscv;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== RV32 汇编器（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>面向测试与工具链的极简<b>指令编码器</b>（不是文本汇编器）：以语义化方法
 * 直接产出机器码，支持分支/跳转偏移回填。用途：</p>
 * <ul>
 *   <li>自测固件构造（{@code SocSelfTest}）；</li>
 *   <li>将来游戏内"软核/固件"工具（生成最小启动代码、trap handler 模板）。</li>
 * </ul>
 *
 * <p>仅覆盖 RV32I + M 扩展 + 最小 M-mode 特权指令（与 {@link Rv32Core} 对齐）。</p>
 */
public final class Rv32Asm {

    private final List<Integer> code = new ArrayList<>();

    // ==================== 编码原语 ====================

    /** R 型 */
    public static int r(int funct7, int rs2, int rs1, int funct3, int rd, int opcode) {
        return ((funct7 & 0x7F) << 25) | ((rs2 & 0x1F) << 20) | ((rs1 & 0x1F) << 15)
                | ((funct3 & 0x7) << 12) | ((rd & 0x1F) << 7) | (opcode & 0x7F);
    }

    /** I 型 */
    public static int i(int imm, int rs1, int funct3, int rd, int opcode) {
        return ((imm & 0xFFF) << 20) | ((rs1 & 0x1F) << 15) | ((funct3 & 0x7) << 12)
                | ((rd & 0x1F) << 7) | (opcode & 0x7F);
    }

    /** S 型 */
    public static int s(int imm, int rs2, int rs1, int funct3, int opcode) {
        return (((imm >> 5) & 0x7F) << 25) | ((rs2 & 0x1F) << 20) | ((rs1 & 0x1F) << 15)
                | ((funct3 & 0x7) << 12) | ((imm & 0x1F) << 7) | (opcode & 0x7F);
    }

    /** B 型（imm 必须 2 字节对齐） */
    public static int b(int imm, int rs2, int rs1, int funct3, int opcode) {
        return (((imm >> 12) & 1) << 31) | (((imm >> 5) & 0x3F) << 25) | ((rs2 & 0x1F) << 20)
                | ((rs1 & 0x1F) << 15) | ((funct3 & 0x7) << 12) | (((imm >> 1) & 0xF) << 8)
                | (((imm >> 11) & 1) << 7) | (opcode & 0x7F);
    }

    /** U 型（imm 为已右移 12 位的 20 位值） */
    public static int u(int imm20, int rd, int opcode) {
        return ((imm20 & 0xFFFFF) << 12) | ((rd & 0x1F) << 7) | (opcode & 0x7F);
    }

    /** J 型（imm 必须 2 字节对齐） */
    public static int j(int imm, int rd, int opcode) {
        return (((imm >> 20) & 1) << 31) | (((imm >> 1) & 0x3FF) << 21) | (((imm >> 11) & 1) << 20)
                | (((imm >> 12) & 0xFF) << 12) | ((rd & 0x1F) << 7) | (opcode & 0x7F);
    }

    // ==================== 发射 / 定位 ====================

    /** 当前指令索引（= 相对基址的指令序数，用于计算分支偏移） */
    public int index() {
        return code.size();
    }

    /** 当前字节偏移 */
    public int byteOffset() {
        return code.size() * 4;
    }

    public Rv32Asm emit(int instruction) {
        code.add(instruction);
        return this;
    }

    /** 回填 B 型（分支）偏移：{@code targetIndex} - {@code atIndex} */
    public Rv32Asm patchB(int atIndex, int targetIndex, int rs2, int rs1, int funct3) {
        code.set(atIndex, b((targetIndex - atIndex) * 4, rs2, rs1, funct3, Rv32.OP_BRANCH));
        return this;
    }

    /** 回填 I 型立即数（如 auipc+addi 定位 handler） */
    public Rv32Asm patchI(int atIndex, int imm, int rs1, int funct3, int rd, int opcode) {
        code.set(atIndex, i(imm, rs1, funct3, rd, opcode));
        return this;
    }

    /** 回填 J 型（jal）偏移 */
    public Rv32Asm patchJ(int atIndex, int targetIndex, int rd) {
        code.set(atIndex, j((targetIndex - atIndex) * 4, rd, Rv32.OP_JAL));
        return this;
    }

    /** 预留一条占位指令并返回其索引 */
    public int reserve() {
        code.add(0);
        return code.size() - 1;
    }

    // ==================== 语义化指令 ====================

    public Rv32Asm lui(int rd, int imm20) {
        return emit(u(imm20, rd, Rv32.OP_LUI));
    }

    /** 装入 32 位立即数（lui + addi 组合） */
    public Rv32Asm li(int rd, int value) {
        final int hi = (value + 0x800) >> 12;
        final int lo = value - (hi << 12);
        if (hi != 0) {
            lui(rd, hi & 0xFFFFF);
        }
        if (lo != 0 || hi == 0) {
            addi(rd, hi != 0 ? rd : 0, lo);
        }
        return this;
    }

    public Rv32Asm addi(int rd, int rs1, int imm) {
        return emit(i(imm, rs1, Rv32.F3_ADD, rd, Rv32.OP_IMM));
    }

    public Rv32Asm andi(int rd, int rs1, int imm) {
        return emit(i(imm, rs1, Rv32.F3_AND, rd, Rv32.OP_IMM));
    }

    public Rv32Asm ori(int rd, int rs1, int imm) {
        return emit(i(imm, rs1, Rv32.F3_OR, rd, Rv32.OP_IMM));
    }

    public Rv32Asm xori(int rd, int rs1, int imm) {
        return emit(i(imm, rs1, Rv32.F3_XOR, rd, Rv32.OP_IMM));
    }

    public Rv32Asm slli(int rd, int rs1, int shamt) {
        return emit(i(shamt & 0x1F, rs1, Rv32.F3_SLL, rd, Rv32.OP_IMM));
    }

    public Rv32Asm srli(int rd, int rs1, int shamt) {
        return emit(i(shamt & 0x1F, rs1, Rv32.F3_SR, rd, Rv32.OP_IMM));
    }

    public Rv32Asm srai(int rd, int rs1, int shamt) {
        return emit(i((0x20 << 5) | (shamt & 0x1F), rs1, Rv32.F3_SR, rd, Rv32.OP_IMM));
    }

    public Rv32Asm add(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_BASE, rs2, rs1, Rv32.F3_ADD, rd, Rv32.OP_OP));
    }

    public Rv32Asm sub(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_ALT, rs2, rs1, Rv32.F3_SUB, rd, Rv32.OP_OP));
    }

    public Rv32Asm and(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_BASE, rs2, rs1, Rv32.F3_AND, rd, Rv32.OP_OP));
    }

    public Rv32Asm or(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_BASE, rs2, rs1, Rv32.F3_OR, rd, Rv32.OP_OP));
    }

    public Rv32Asm xor(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_BASE, rs2, rs1, Rv32.F3_XOR, rd, Rv32.OP_OP));
    }

    // ---- M 扩展 ----
    public Rv32Asm mul(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_MULDIV, rs2, rs1, Rv32.F3_MUL, rd, Rv32.OP_OP));
    }

    public Rv32Asm mulh(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_MULDIV, rs2, rs1, Rv32.F3_MULH, rd, Rv32.OP_OP));
    }

    public Rv32Asm div(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_MULDIV, rs2, rs1, Rv32.F3_DIV, rd, Rv32.OP_OP));
    }

    public Rv32Asm divu(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_MULDIV, rs2, rs1, Rv32.F3_DIVU, rd, Rv32.OP_OP));
    }

    public Rv32Asm rem(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_MULDIV, rs2, rs1, Rv32.F3_REM, rd, Rv32.OP_OP));
    }

    public Rv32Asm remu(int rd, int rs1, int rs2) {
        return emit(r(Rv32.F7_MULDIV, rs2, rs1, Rv32.F3_REMU, rd, Rv32.OP_OP));
    }

    // ---- 访存 ----
    public Rv32Asm lw(int rd, int offset, int rs1) {
        return emit(i(offset, rs1, Rv32.F3_LW, rd, Rv32.OP_LOAD));
    }

    public Rv32Asm lh(int rd, int offset, int rs1) {
        return emit(i(offset, rs1, Rv32.F3_LH, rd, Rv32.OP_LOAD));
    }

    public Rv32Asm lb(int rd, int offset, int rs1) {
        return emit(i(offset, rs1, Rv32.F3_LB, rd, Rv32.OP_LOAD));
    }

    public Rv32Asm lbu(int rd, int offset, int rs1) {
        return emit(i(offset, rs1, Rv32.F3_LBU, rd, Rv32.OP_LOAD));
    }

    public Rv32Asm sw(int rs2, int offset, int rs1) {
        return emit(s(offset, rs2, rs1, Rv32.F3_SW, Rv32.OP_STORE));
    }

    public Rv32Asm sh(int rs2, int offset, int rs1) {
        return emit(s(offset, rs2, rs1, Rv32.F3_SH, Rv32.OP_STORE));
    }

    public Rv32Asm sb(int rs2, int offset, int rs1) {
        return emit(s(offset, rs2, rs1, Rv32.F3_SB, Rv32.OP_STORE));
    }

    // ---- 分支 / 跳转 ----
    /** 发射 bne 占位（偏移 0），返回索引供 {@link #patchB} 回填 */
    public int bne(int rs1, int rs2) {
        final int idx = index();
        emit(b(0, rs2, rs1, Rv32.F3_BNE, Rv32.OP_BRANCH));
        return idx;
    }

    public int beq(int rs1, int rs2) {
        final int idx = index();
        emit(b(0, rs2, rs1, Rv32.F3_BEQ, Rv32.OP_BRANCH));
        return idx;
    }

    public int blt(int rs1, int rs2) {
        final int idx = index();
        emit(b(0, rs2, rs1, Rv32.F3_BLT, Rv32.OP_BRANCH));
        return idx;
    }

    public int bge(int rs1, int rs2) {
        final int idx = index();
        emit(b(0, rs2, rs1, Rv32.F3_BGE, Rv32.OP_BRANCH));
        return idx;
    }

    public Rv32Asm jal(int rd, int offset) {
        return emit(j(offset, rd, Rv32.OP_JAL));
    }

    public Rv32Asm jalr(int rd, int rs1, int imm) {
        return emit(i(imm, rs1, 0, rd, Rv32.OP_JALR));
    }

    // ---- 特权 / CSR（M-mode） ----
    /** csrw csr, rs1（= csrrw x0, csr, x1） */
    public Rv32Asm csrw(int csr, int rs1) {
        return emit(i(csr, rs1, Rv32.F3_CSRRW, 0, Rv32.OP_SYSTEM));
    }

    /** csrs csr, rs1（置位） */
    public Rv32Asm csrs(int csr, int rs1) {
        return emit(i(csr, rs1, Rv32.F3_CSRRS, 0, Rv32.OP_SYSTEM));
    }

    /** csrr rd, csr（= csrrs rd, csr, x0） */
    public Rv32Asm csrr(int rd, int csr) {
        return emit(i(csr, 0, Rv32.F3_CSRRS, rd, Rv32.OP_SYSTEM));
    }

    /** csrwi csr, imm（5 位立即数） */
    public Rv32Asm csrwi(int csr, int imm5) {
        return emit(i(csr, imm5 & 0x1F, Rv32.F3_CSRRWI, 0, Rv32.OP_SYSTEM));
    }

    public Rv32Asm mret() {
        return emit(i(Rv32.F12_MRET, 0, Rv32.F3_PRIV, 0, Rv32.OP_SYSTEM));
    }

    public Rv32Asm wfi() {
        return emit(i(Rv32.F12_WFI, 0, Rv32.F3_PRIV, 0, Rv32.OP_SYSTEM));
    }

    public Rv32Asm ecall() {
        return emit(i(Rv32.F12_ECALL, 0, Rv32.F3_PRIV, 0, Rv32.OP_SYSTEM));
    }

    public Rv32Asm ebreak() {
        return emit(i(Rv32.F12_EBREAK, 0, Rv32.F3_PRIV, 0, Rv32.OP_SYSTEM));
    }

    // ==================== 输出 ====================

    /** 指令数 */
    public int size() {
        return code.size();
    }

    /** 小端字节序机器码 */
    public byte[] toBytes() {
        final byte[] out = new byte[code.size() * 4];
        for (int idx = 0; idx < code.size(); idx++) {
            final int instr = code.get(idx);
            out[idx * 4] = (byte) instr;
            out[idx * 4 + 1] = (byte) (instr >>> 8);
            out[idx * 4 + 2] = (byte) (instr >>> 16);
            out[idx * 4 + 3] = (byte) (instr >>> 24);
        }
        return out;
    }

    /** 把已发射的指令写入目标数组（偏移处） */
    public void writeInto(byte[] target, int byteOffset) {
        final byte[] bytes = toBytes();
        System.arraycopy(bytes, 0, target, byteOffset, bytes.length);
    }
}
