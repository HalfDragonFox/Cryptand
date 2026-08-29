package com.hdf.cryptand.circuitsimulation.solver;

/**
 * float 版复数 MNA 装配器（AC 相量，float 求解器用）。
 * <p>
 * 与 {@link ComplexMnaBuilder} 同构：稀疏存储（HashMap 行/列 → float 复数），
 * 对外接口 addY/addB/clearRowCol 不变；输出稠密矩阵视图
 * {@link #toDense()}（float 求解器小网络稠密 LU 用）。
 * 大网络合并求解仍走 native SuperLU（double complex，见 MergedNetworkSolver）。
 */
public final class FloatComplexMnaBuilder {

    private final java.util.Map<Long, FloatComplex> y = new java.util.HashMap<>();
    public final FloatComplex[] b;
    public final int size;

    /** 子矩阵偏移（同网络合并用；单网络求解默认 0） */
    public int offset = 0;

    public FloatComplexMnaBuilder(int size) {
        this.size = size;
        this.b = new FloatComplex[size];
        for (int i = 0; i < size; i++) b[i] = FloatComplex.ZERO;
    }

    /** 累加导纳条目 y[r][c] += v（稀疏，O(1)；自动加 offset）。 */
    public void addY(int r, int c, FloatComplex v) {
        r += offset;
        c += offset;
        if (r < 0 || c < 0 || r >= size || c >= size) return;
        long key = ((long) r << 32) | (c & 0xFFFFFFFFL);
        FloatComplex old = y.get(key);
        y.put(key, old == null ? v : old.add(v));
    }

    /** 累加导纳条目（double 复数 → float，魔法数字入矩阵前转换） */
    public void addY(int r, int c, Complex v) {
        addY(r, c, FloatComplex.fromDouble(v.re, v.im));
    }

    /** 累加右端 b[r] += v（自动加 offset）。 */
    public void addB(int r, FloatComplex v) {
        r += offset;
        if (r >= 0 && r < size) b[r] = b[r].add(v);
    }

    public void addB(int r, Complex v) {
        addB(r, FloatComplex.fromDouble(v.re, v.im));
    }

    /** 清除某行/列的全部导纳条目（接地节点：整行整列置零，含对角）。 */
    public void clearRowCol(int idx) {
        int target = idx + offset;
        y.entrySet().removeIf(e -> {
            long key = e.getKey();
            int r = (int) (key >>> 32);
            int c = (int) (key & 0xFFFFFFFFL);
            return r == target || c == target;
        });
    }

    /** 稠密矩阵视图（float 求解器稠密 LU 输入；对角未含 b）。 */
    public FloatComplex[][] toDense() {
        FloatComplex[][] d = new FloatComplex[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) d[i][j] = FloatComplex.ZERO;
        }
        for (java.util.Map.Entry<Long, FloatComplex> e : y.entrySet()) {
            long key = e.getKey();
            int r = (int) (key >>> 32);
            int c = (int) (key & 0xFFFFFFFFL);
            d[r][c] = e.getValue();
        }
        return d;
    }

    /** 非零条目数（诊断/性能）。 */
    public int nonZeroCount() {
        return y.size();
    }
}
