package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

/**
 * ★ 2026-09-02 准确的 section 坐标键（替代 packKey 位域压缩）。
 *
 * <p>packKey（SectionPos.asLong 同构）把 (secX,secY,secZ) 压进一个 long，有符号/掩码
 * 边界极易出错（dump 曾出现 x 位域被 2^20/2^21 边界值污染、key 无法 round-trip）。
 * 本类型直接记录三个 int 坐标：可读、可自检、equals/hashCode 由 record 自动生成，
 * 可直接作为 HashMap 键（与 long 键同效率）。
 */
public record SectionKey(int x, int y, int z) {

    public static SectionKey of(final int x, final int y, final int z) {
        return new SectionKey(x, y, z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}