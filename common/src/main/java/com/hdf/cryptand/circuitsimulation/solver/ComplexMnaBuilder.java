package com.hdf.cryptand.circuitsimulation.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 复数 MNA 装配器（交流相量）。
 *
 * 2026-08-11：内部改为【稀疏存储】（HashMap：行/列 → 复值），适配超大型
 * 网络——稠密 Complex[size][size] 在数万节点时内存爆炸（n² 条目）。
 * 对外接口 {@link #addY}/{@link #addB} 不变（所有元件实现只走这两个方法），
 * 输出两种视图：
 *   - {@link #toDense()} 稠密矩阵（小网络 / native 失败回退）
 *   - {@link #toCsc()}   CSC 列优先稀疏（SuperLU/native 输入）
 */
public final class ComplexMnaBuilder {

    private final Map<Long, Complex> y = new HashMap<>();
    public final Complex[] b;
    public final int size;

    /** 子矩阵偏移（2026-08-12 同网络合并）：stamp 时子网用自身节点号，
     *  addY/addB/clearRowCol 自动加偏移到全局坐标。单网络求解默认 0。 */
    public int offset = 0;

    public ComplexMnaBuilder(int size) {
        this.size = size;
        this.b = new Complex[size];
        for (int i = 0; i < size; i++) b[i] = Complex.ZERO;
    }

    /** 累加导纳条目 y[r][c] += v（稀疏存储，O(1)；自动加 offset）。 */
    public void addY(int r, int c, Complex v) {
        r += offset;
        c += offset;
        if (r < 0 || c < 0 || r >= size || c >= size) return;
        long key = ((long) r << 32) | (c & 0xFFFFFFFFL);
        Complex old = y.get(key);
        y.put(key, old == null ? v : old.add(v));
    }

    /** 累加右端 b[r] += v（自动加 offset）。 */
    public void addB(int r, Complex v) {
        r += offset;
        if (r >= 0 && r < size) b[r] = b[r].add(v);
    }

    /** 清除某行/列的全部导纳条目（接地节点：整行整列置零，含对角；自动加 offset）。 */
    public void clearRowCol(int idx) {
        int target = idx + offset; // effectively final，供 lambda 捕获
        y.entrySet().removeIf(e -> {
            long key = e.getKey();
            int r = (int) (key >>> 32);
            int c = (int) (key & 0xFFFFFFFFL);
            return r == target || c == target;
        });
    }

    /** 稠密矩阵视图（小网络 / 回退路径）。 */
    public Complex[][] toDense() {
        Complex[][] d = new Complex[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) d[i][j] = Complex.ZERO;
        }
        for (Map.Entry<Long, Complex> e : y.entrySet()) {
            long key = e.getKey();
            int r = (int) (key >>> 32);
            int c = (int) (key & 0xFFFFFFFFL);
            d[r][c] = e.getValue();
        }
        return d;
    }

    /** CSC 稀疏视图（列优先；每列行索引升序，满足 SuperLU NC 格式要求）。 */
    public Csc toCsc() {
        @SuppressWarnings("unchecked")
        ArrayList<long[]>[] cols = new ArrayList[size];
        for (int c = 0; c < size; c++) cols[c] = new ArrayList<>();
        for (Map.Entry<Long, Complex> e : y.entrySet()) {
            long key = e.getKey();
            int r = (int) (key >>> 32);
            int c = (int) (key & 0xFFFFFFFFL);
            Complex v = e.getValue();
            cols[c].add(new long[]{r, Double.doubleToLongBits(v.re), Double.doubleToLongBits(v.im)});
        }
        int[] colPtr = new int[size + 1];
        int[] rowIdx = new int[y.size()];
        double[] re = new double[y.size()];
        double[] im = new double[y.size()];
        int k = 0;
        for (int c = 0; c < size; c++) {
            colPtr[c] = k;
            cols[c].sort((a, b) -> Long.compare(a[0], b[0])); // 行索引升序
            for (long[] e : cols[c]) {
                rowIdx[k] = (int) e[0];
                re[k] = Double.longBitsToDouble(e[1]);
                im[k] = Double.longBitsToDouble(e[2]);
                k++;
            }
        }
        colPtr[size] = k;
        return new Csc(colPtr, rowIdx, re, im);
    }

    /** CSC 稀疏矩阵（SuperLU/native 输入格式）。 */
    public static final class Csc {
        public final int[] colPtr;
        public final int[] rowIdx;
        public final double[] re;
        public final double[] im;

        Csc(int[] colPtr, int[] rowIdx, double[] re, double[] im) {
            this.colPtr = colPtr;
            this.rowIdx = rowIdx;
            this.re = re;
            this.im = im;
        }
    }
}
