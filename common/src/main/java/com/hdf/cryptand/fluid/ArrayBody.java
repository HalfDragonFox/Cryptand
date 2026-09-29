package com.hdf.cryptand.fluid;

/**
 * {@link FluidBodyView} 的纯数组实现：离线闸门与转接类都可以直接拿它拼片。
 *
 * <p>用法：先选本片液体（{@link #ArrayBody(FluidKind)}），再逐格 {@link #cell} /
 * {@link #border}。容量由 kind 与液体种类算出（空气对水 = 8、固体 = 0），
 * 与引擎口径一致，不需要调用方自己算。
 */
public final class ArrayBody implements FluidBodyView {

    private final FluidKind fluid;

    private long[] cellPos = new long[16];
    private FluidKind[] cellKind = new FluidKind[16];
    private int[] cellAmount = new int[16];
    private boolean[] cellSource = new boolean[16];
    private int cells;

    private long[] edgePos = new long[8];
    private FluidKind[] edgeKind = new FluidKind[8];
    private int[] edgeAmount = new int[8];
    private int edges;

    public ArrayBody(final FluidKind fluid) {
        if (fluid == null || !fluid.isLiquid()) {
            throw new IllegalArgumentException("本片液体必须是液体种类: " + fluid);
        }
        this.fluid = fluid;
    }

    public FluidKind fluid() {
        return fluid;
    }

    // ---------- 拼片 ----------

    /** 加一个片内格（amount = 当前液体量，0 = 空容器格）。 */
    public ArrayBody cell(final int x, final int y, final int z, final FluidKind kind, final int amount) {
        return cell(x, y, z, kind, amount, false);
    }

    /** 加一个片内格，并指定它是不是恒定水源。 */
    public ArrayBody cell(final int x, final int y, final int z, final FluidKind kind, final int amount,
                          final boolean source) {
        require(kind, "片内格");
        if (cells == cellPos.length) {
            cellPos = java.util.Arrays.copyOf(cellPos, cells << 1);
            cellKind = java.util.Arrays.copyOf(cellKind, cells << 1);
            cellAmount = java.util.Arrays.copyOf(cellAmount, cells << 1);
            cellSource = java.util.Arrays.copyOf(cellSource, cells << 1);
        }
        cellPos[cells] = pack(x, y, z);
        cellKind[cells] = kind;
        cellAmount[cells] = Math.max(0, amount);
        cellSource[cells] = source;
        cells++;
        return this;
    }

    /** 加一个边界格（片外相邻格：空气/固体/别的液体）。 */
    public ArrayBody border(final int x, final int y, final int z, final FluidKind kind, final int amount) {
        require(kind, "边界格");
        if (edges == edgePos.length) {
            edgePos = java.util.Arrays.copyOf(edgePos, edges << 1);
            edgeKind = java.util.Arrays.copyOf(edgeKind, edges << 1);
            edgeAmount = java.util.Arrays.copyOf(edgeAmount, edges << 1);
        }
        edgePos[edges] = pack(x, y, z);
        edgeKind[edges] = kind;
        edgeAmount[edges] = Math.max(0, amount);
        edges++;
        return this;
    }

    // ---------- FluidBodyView ----------

    @Override
    public int size() {
        return cells;
    }

    @Override
    public long packed(final int i) {
        return cellPos[i];
    }

    @Override
    public FluidKind kind(final int i) {
        return cellKind[i];
    }

    @Override
    public int amount(final int i) {
        return cellAmount[i];
    }

    @Override
    public int capacity(final int i) {
        return cellKind[i].capacityFor(fluid);
    }

    @Override
    public boolean source(final int i) {
        return cellSource[i];
    }

    /** 快路：内部就是四个连续数组 ⇒ 四次 {@code arraycopy}（容量仍按 kind 现算）。 */
    @Override
    public void copyCells(final int from, final int to, final FluidKind fluid, final long[] pos,
                          final FluidKind[] kind, final int[] amount, final int[] cap,
                          final boolean[] source, final int dstFrom) {
        final int len = to - from;
        System.arraycopy(cellPos, from, pos, dstFrom, len);
        System.arraycopy(cellKind, from, kind, dstFrom, len);
        System.arraycopy(cellAmount, from, amount, dstFrom, len);
        System.arraycopy(cellSource, from, source, dstFrom, len);
        for (int i = 0; i < len; i++) {
            cap[dstFrom + i] = cellKind[from + i].capacityFor(fluid);
        }
    }

    /** 快路：边界格三样各一次 {@code arraycopy}。 */
    @Override
    public void copyBorders(final int from, final int to, final long[] pos, final FluidKind[] kind,
                            final int[] amount, final int dstFrom) {
        final int len = to - from;
        System.arraycopy(edgePos, from, pos, dstFrom, len);
        System.arraycopy(edgeKind, from, kind, dstFrom, len);
        System.arraycopy(edgeAmount, from, amount, dstFrom, len);
    }

    @Override
    public int borderSize() {
        return edges;
    }

    @Override
    public long borderPacked(final int i) {
        return edgePos[i];
    }

    @Override
    public FluidKind borderKind(final int i) {
        return edgeKind[i];
    }

    @Override
    public int borderAmount(final int i) {
        return edgeAmount[i];
    }

    // ---------- 断言/调试用 ----------

    /** 片内某格的当前量；不在片内返回 -1。 */
    public int amountAt(final int x, final int y, final int z) {
        final int i = indexOf(x, y, z);
        return i < 0 ? -1 : cellAmount[i];
    }

    /** 片内某格的下标；不在片内返回 -1。 */
    public int indexOf(final int x, final int y, final int z) {
        final long p = pack(x, y, z);
        for (int i = 0; i < cells; i++) {
            if (cellPos[i] == p) {
                return i;
            }
        }
        return -1;
    }

    /** 片内总水量。 */
    public int totalAmount() {
        int sum = 0;
        for (int i = 0; i < cells; i++) {
            sum += cellAmount[i];
        }
        return sum;
    }

    public int cellCount() {
        return cells;
    }

    public int borderCount() {
        return edges;
    }

    private static void require(final FluidKind kind, final String what) {
        if (kind == null) {
            throw new IllegalArgumentException(what + "的 kind 不能为 null");
        }
    }

    private static long pack(final int x, final int y, final int z) {
        return FluidBodyView.pack(x, y, z);
    }
}
