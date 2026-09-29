package com.hdf.cryptand.soc.board;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ===== 屏幕拼合（按原版 OC 的逻辑重写，common 纯 Java 零 MC，2026-09-26）=====
 *
 * <p>原版逻辑（源码 {@code OpenComputers/common/tileentity/Screen.scala} + 新版
 * {@code common/blockentity/Screen.scala}）：屏幕方块放在世界里是**一块一块的**，但相邻的、
 * **朝向一致**的屏会被并成**一块逻辑屏（MultiBlock）**：</p>
 * <ul>
 *   <li>其中一块成为 <b>origin</b>（拼合体的锚点），整块逻辑屏由 origin 代表（组件、渲染、锁都在它上面）；</li>
 *   <li>总分辨率 = 每块分辨率 × 块数（横向块数 × 纵向块数）；</li>
 *   <li>触摸/点击要能把"世界里的命中点"换算成**整块屏上的坐标**（{@code toScreenCoordinates}）；</li>
 *   <li>拼合体必须是**完整矩形且同朝向**，否则拒绝（绝不静默按单块工作）。</li>
 * </ul>
 *
 * <h3>坐标口径（2026-09-26 统一为**图像约定**）</h3>
 * <p><b>原点在屏幕左上角：横向 u 向右（列）、纵向 v 向下（行）</b>。贴墙屏 v = <b>-世界 y</b>
 * （世界 y 向上 ⇒ 图像行自上而下），于是 origin = 左上角那块、行 0 = 最上面一行。
 * 这与渲染器把 DynamicTexture 的 v=0 画在屏幕顶（世界 y 最大）**同一个口径** —— 不再互为镜像，
 * 竖着拼两块时"点上面那块"不会算成下面那块。地板/天花板屏平面是 xz（没有"上下"），u = x、v = z。</p>
 *
 * <p>本类只做**拼合逻辑本身**（纯数据 + 纯几何），不碰世界也不碰 OC —— 于是可以离线测
 * （{@code runScreenWallTest}）：平台层只要把"世界里相邻的屏方块"喂进来、把结果拿去用即可。</p>
 *
 * <p>⚠ 与 {@link ScreenGroup} 的分工：那个是**已拼合后**的组（屏幕设备 + 组级锁 + 组坐标写像素）；
 * 本类是**从世界布局算出这个组**的那一步（谁能并、谁当 origin、每块的偏移、触摸换算）。</p>
 */
public final class ScreenWall {

    /** 水平朝向（照原版 yaw；用我们自己的枚举，common 不引 MC 的 Direction） */
    public enum Facing {
        NORTH, SOUTH, EAST, WEST
    }

    /** 俯仰（照原版 pitch：贴墙 / 朝上 / 朝下） */
    public enum Pitch {
        UP, DOWN, WALL
    }

    /**
     * 世界里的一个屏方块单元。
     *
     * @param x,y,z  方块坐标
     * @param facing 水平朝向（yaw）
     * @param pitch  俯仰（贴墙/朝上/朝下）
     */
    public record Cell(int x, int y, int z, Facing facing, Pitch pitch) {
    }

    /**
     * 拼合结果。
     *
     * @param origin  锚点单元（整块逻辑屏由它代表；**图像意义上的左上角**：列最小 + 行最小 = 世界 y 最大那块）
     * @param blocksW 横向块数、blocksH 纵向块数
     * @param cells   组成这块逻辑屏的全部单元（含 origin）
     * @param cellCols / cellRows 每块的分辨率（字符格或像素，由设备决定）
     */
    public record Wall(Cell origin, int blocksW, int blocksH, List<Cell> cells, int cellCols, int cellRows) {

        /** 整块逻辑屏的分辨率 */
        public int cols() {
            return blocksW * cellCols;
        }

        public int rows() {
            return blocksH * cellRows;
        }

        public int blockCount() {
            return cells.size();
        }

        /** 某块在逻辑屏里的偏移（以"每块分辨率"为单位；行自上而下；未包含返回 -1,-1） */
        public int[] offsetOf(Cell cell) {
            final int[] at = gridOf(cell);
            return at == null ? new int[]{-1, -1} : new int[]{at[0] * cellCols, at[1] * cellRows};
        }

        /** 某块在拼合网格里的 (列, 行)（未包含返回 null） */
        public int[] gridOf(Cell cell) {
            for (int row = 0; row < blocksH; row++) {
                for (int col = 0; col < blocksW; col++) {
                    final Cell c = cells.get(row * blocksW + col);
                    if (c.x() == cell.x() && c.y() == cell.y() && c.z() == cell.z()) {
                        return new int[]{col, row};
                    }
                }
            }
            return null;
        }

        /**
         * 整块屏坐标 → 落在哪一块 + 块内坐标（照原版 {@code toScreenCoordinates} 的语义）。
         *
         * <p>坐标就是**图像坐标**：x 向右、y 向下（左上角为原点），与网格同一口径。</p>
         *
         * @return {@code [gridCol, gridRow, localX, localY]}；越界返回 null
         */
        public int[] toScreenCoordinates(int screenX, int screenY) {
            if (screenX < 0 || screenY < 0 || screenX >= cols() || screenY >= rows()) {
                return null;
            }
            final int gridCol = screenX / cellCols;
            final int gridRow = screenY / cellRows;
            return new int[]{gridCol, gridRow, screenX % cellCols, screenY % cellRows};
        }

        /** 逻辑屏上 (gridCol,gridRow) 的单元 */
        public Cell cellAt(int gridCol, int gridRow) {
            if (gridCol < 0 || gridRow < 0 || gridCol >= blocksW || gridRow >= blocksH) {
                return null;
            }
            return cells.get(gridRow * blocksW + gridCol);
        }

        public String summary() {
            return "wall " + blocksW + "x" + blocksH + " blocks ⇒ " + cols() + "x" + rows()
                    + " cells（origin=" + origin.x() + "," + origin.y() + "," + origin.z() + "）";
        }
    }

    private ScreenWall() {
    }

    /**
     * 从"整个世界里同一张屏的全部单元"里，算出包含 {@code seed} 的那一块逻辑屏（照原版 checkMultiBlock）。
     *
     * <p>规则：只并**同朝向、同俯仰**且**面相邻**的单元；结果必须是**完整矩形**（行列都填满），
     * 否则拒绝 —— 原版对"不成矩形"的拼合也是不认的（会出现空洞 ⇒ 无从定义逻辑屏坐标）。</p>
     *
     * @param allCells 世界里所有候选单元（可多张屏混在一起；只取与 seed 连通的那一片）
     * @throws IllegalStateException 不成矩形 / 不连通 / 每块分辨率非法
     */
    public static Wall compute(Set<Cell> allCells, Cell seed, int cellCols, int cellRows) {
        if (allCells == null || seed == null || !allCells.contains(seed)) {
            throw new IllegalStateException("拼合失败：种子单元不在候选集合里");
        }
        if (cellCols <= 0 || cellRows <= 0) {
            throw new IllegalStateException("拼合失败：每块分辨率非法 " + cellCols + "x" + cellRows);
        }
        // ① 同朝向 + 面相邻 的洪水填充（原版 canConnect：朝向不同的屏不是同一个逻辑屏）
        final Set<Cell> visited = new java.util.LinkedHashSet<>();
        final ArrayDeque<Cell> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);
        while (!queue.isEmpty()) {
            final Cell cur = queue.poll();
            for (final Cell next : allCells) {
                if (visited.contains(next)) {
                    continue;
                }
                if (next.facing() != seed.facing() || next.pitch() != seed.pitch()) {
                    continue;
                }
                if (!adjacent(cur, next)) {
                    continue;
                }
                visited.add(next);
                queue.add(next);
            }
        }
        // ② 建网格：用相对 seed 的偏移当作坐标（同一平面内只会有两个轴变化）
        final Map<Long, Cell> grid = new LinkedHashMap<>();
        int minU = Integer.MAX_VALUE;
        int minV = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int maxV = Integer.MIN_VALUE;
        for (final Cell c : visited) {
            final int[] uv = uv(seed.facing(), seed.pitch(), c, seed);
            final long key = key(uv[0], uv[1]);
            if (grid.putIfAbsent(key, c) != null) {
                throw new IllegalStateException("拼合失败：单元重合 " + c);
            }
            minU = Math.min(minU, uv[0]);
            minV = Math.min(minV, uv[1]);
            maxU = Math.max(maxU, uv[0]);
            maxV = Math.max(maxV, uv[1]);
        }
        final int w = maxU - minU + 1;
        final int h = maxV - minV + 1;
        if ((long) w * h != visited.size()) {
            throw new IllegalStateException("拼合失败：不是完整矩形（外接 " + w + "x" + h + " 块，实际 "
                    + visited.size() + " 块）—— 有空洞/不连续，原版也不认这种拼合");
        }
        // ③ 规范化成 cells[row * w + col]（行 = 纵向，列 = 横向），origin = 左上角那块（原版以最小角为锚点）
        final List<Cell> ordered = new ArrayList<>(w * h);
        Cell origin = null;
        final Cell[] slot = new Cell[w * h];
        for (final Map.Entry<Long, Cell> e : grid.entrySet()) {
            final int u = (int) (e.getKey() >> 32) - minU;
            final int v = (int) (e.getKey() & 0xFFFF_FFFFL) - minV;
            slot[v * w + u] = e.getValue();
        }
        for (int i = 0; i < slot.length; i++) {
            if (slot[i] == null) {
                throw new IllegalStateException("拼合失败：网格有空洞（第 " + i + " 格为空）");
            }
            ordered.add(slot[i]);
        }
        origin = ordered.get(0);
        return new Wall(origin, w, h, List.copyOf(ordered), cellCols, cellRows);
    }

    /** 面相邻（照原版的相邻判定：只认六个面，不认对角） */
    private static boolean adjacent(Cell a, Cell b) {
        final int dx = Math.abs(a.x() - b.x());
        final int dy = Math.abs(a.y() - b.y());
        final int dz = Math.abs(a.z() - b.z());
        return dx + dy + dz == 1;
    }

    /**
     * 把单元投影到该朝向的**屏幕平面坐标**（u = 横向、v = 纵向），照原版的 project/unproject 语义。
     *
     * <p>口径 = **图像约定**（左上角为原点、行自上而下）：</p>
     * <ul>
     *   <li>贴南北墙：u = x（世界 +x 向右）、v = <b>-y</b>（世界 +y 向上 ⇒ 图像行向下）；</li>
     *   <li>贴东西墙：u = z、v = <b>-y</b>；</li>
     *   <li>地板/天花板：屏幕平面 = xz，u = x、v = z（水平面没有"上下"，直接用网格轴）。</li>
     * </ul>
     */
    private static int[] uv(Facing facing, Pitch pitch, Cell c, Cell base) {
        final int dx = c.x() - base.x();
        final int dy = c.y() - base.y();
        final int dz = c.z() - base.z();
        if (pitch != Pitch.WALL) {
            // 地板/天花板：屏幕平面 = xz，u 取 x、v 取 z（朝向只决定画面方向，不影响拼合网格）
            return new int[]{dx, dz};
        }
        return switch (facing) {
            case NORTH, SOUTH -> new int[]{dx, -dy};    // 贴南北墙：u = x、v = -y（图像行自上而下）
            case EAST, WEST -> new int[]{dz, -dy};      // 贴东西墙：u = z、v = -y
        };
    }

    private static long key(int u, int v) {
        return ((long) u << 32) | (v & 0xFFFF_FFFFL);
    }
}
