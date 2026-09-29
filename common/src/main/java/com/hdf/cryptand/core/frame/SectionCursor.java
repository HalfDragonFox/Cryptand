package com.hdf.cryptand.core.frame;

import java.util.NoSuchElementException;

/**
 * 区块段（chunk section，16*16*16 = 4096 格）内的线性游标。
 *
 * <p>它是「主线程每次扫描有上限」的最小单位承载者：一帧只扫一个 section 的一段，
 * 用 {@link #fill(IntBufferGroup)} 把剩余格灌进缓冲组，缓冲组满了就停下，
 * 游标保留位置，下一帧继续。
 *
 * <p>纯 Java：不依赖任何 MC 类型；section 坐标用三个 int 表示，也可打包成一个 long。
 */
public final class SectionCursor implements FrameCursor {

    /** 一个 chunk section 的边长。 */
    public static final int SECTION_SIZE = 16;
    /** 一个 chunk section 的格数（16^3）。 */
    public static final int SECTION_CELLS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private static final int KEY_BITS = 21;
    private static final long KEY_MASK = (1L << KEY_BITS) - 1L;
    private static final int KEY_BIAS = 1 << (KEY_BITS - 1);

    private final int sectionX;
    private final int sectionY;
    private final int sectionZ;
    private int index;

    public SectionCursor(final int sectionX, final int sectionY, final int sectionZ) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
    }

    public static SectionCursor ofKey(final long key) {
        return new SectionCursor(keyX(key), keyY(key), keyZ(key));
    }

    /** 打包 section 坐标（每轴 21 位，带偏移）。 */
    public static long key(final int sectionX, final int sectionY, final int sectionZ) {
        return (((long) (sectionX + KEY_BIAS) & KEY_MASK) << (KEY_BITS * 2))
                | (((long) (sectionY + KEY_BIAS) & KEY_MASK) << KEY_BITS)
                | ((long) (sectionZ + KEY_BIAS) & KEY_MASK);
    }

    public static int keyX(final long key) {
        return (int) ((key >>> (KEY_BITS * 2)) & KEY_MASK) - KEY_BIAS;
    }

    public static int keyY(final long key) {
        return (int) ((key >>> KEY_BITS) & KEY_MASK) - KEY_BIAS;
    }

    public static int keyZ(final long key) {
        return (int) (key & KEY_MASK) - KEY_BIAS;
    }

    /** section 内局部坐标 → 线性索引（0..4095）。 */
    public static int linearIndex(final int lx, final int ly, final int lz) {
        return (lx << 8) | (ly << 4) | lz;
    }

    public static int localX(final int index) {
        return (index >> 8) & 0xF;
    }

    public static int localY(final int index) {
        return (index >> 4) & 0xF;
    }

    public static int localZ(final int index) {
        return index & 0xF;
    }

    public int sectionX() {
        return sectionX;
    }

    public int sectionY() {
        return sectionY;
    }

    public int sectionZ() {
        return sectionZ;
    }

    public long sectionKey() {
        return key(sectionX, sectionY, sectionZ);
    }

    /** 下一个待扫的线性索引。 */
    public int index() {
        return index;
    }

    @Override
    public int used() {
        return index;
    }

    public int remaining() {
        return SECTION_CELLS - index;
    }

    @Override
    public boolean hasRemaining() {
        return index < SECTION_CELLS;
    }

    /** 取下一个线性索引；已扫完抛 {@link NoSuchElementException}。 */
    public int nextIndex() {
        if (index >= SECTION_CELLS) {
            throw new NoSuchElementException("section cursor exhausted: " + sectionKey());
        }
        return index++;
    }

    /**
     * 把本 section 剩余的格灌进 target，直到 target 满或本 section 扫完。
     *
     * @return {@code true} = target 已满且本 section 仍有剩余（下一帧继续）
     * @throws IllegalStateException 传入已满的 target（调用方必须先 flush/clear）
     */
    public boolean fill(final IntBufferGroup target) {
        if (target.isFull()) {
            throw new IllegalStateException("target IntBufferGroup is already full; flush()/clear() first");
        }
        while (index < SECTION_CELLS) {
            final int cell = index++;
            if (target.add(cell)) {
                return index < SECTION_CELLS;
            }
        }
        return false;
    }

    /** 回到 section 起点（重新扫描该 section 时用）。 */
    public void reset() {
        index = 0;
    }

    @Override
    public String toString() {
        return "SectionCursor{sec=" + sectionX + "," + sectionY + "," + sectionZ
                + " index=" + index + "/" + SECTION_CELLS + "}";
    }
}
