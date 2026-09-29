package com.hdf.cryptand.soc.board;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ===== 屏幕拼合闸门（照原版 OC 逻辑，纯 Java 零 MC，2026-09-26）=====
 *
 * <p>覆盖：单块 / 2×1 / 1×2 / 2×2 / 不同朝向不并 / 不连通不并 / L 形拒绝（不是完整矩形）/</p>
 * <p>偏移与整屏坐标换算（触摸用）/ 地板与天花板（xz 平面）/ 非法输入明确报错。</p>
 *
 * <p><b>坐标口径（2026-09-26 统一）：图像约定</b> —— 原点在屏幕左上角、行自上而下（贴墙屏 v = -世界 y）
 * ⇒ origin 是世界 y **最大**那块、行 0 是最上面一行。竖着两块时 (0,0) 落在上面那块。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runScreenWallTest}</p>
 */
public final class ScreenWallSelfTest {

    private static int passed;
    private static int failed;

    private static final ScreenWall.Facing N = ScreenWall.Facing.NORTH;
    private static final ScreenWall.Facing S = ScreenWall.Facing.SOUTH;
    private static final ScreenWall.Pitch WALL = ScreenWall.Pitch.WALL;
    private static final ScreenWall.Pitch FLOOR = ScreenWall.Pitch.UP;

    public static void main(String[] args) {
        single();
        wide();
        tall();
        wall2x2();
        rejects();
        floorPlane();
        coordinates();

        System.out.println("[SCREENWALL] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static ScreenWall.Cell cell(int x, int y, int z) {
        return new ScreenWall.Cell(x, y, z, N, WALL);
    }

    private static ScreenWall.Cell cell(int x, int y, int z, ScreenWall.Facing f, ScreenWall.Pitch p) {
        return new ScreenWall.Cell(x, y, z, f, p);
    }

    private static Set<ScreenWall.Cell> set(ScreenWall.Cell... cells) {
        final Set<ScreenWall.Cell> out = new LinkedHashSet<>();
        for (final ScreenWall.Cell c : cells) {
            out.add(c);
        }
        return out;
    }

    // ==================== 1. 单块 ====================

    private static void single() {
        final var cells = set(cell(0, 64, 0));
        final var wall = ScreenWall.compute(cells, cell(0, 64, 0), 80, 25);
        check("单块屏 ⇒ 1×1", wall.blocksW() == 1 && wall.blocksH() == 1);
        check("单块分辨率 = 每块分辨率", wall.cols() == 80 && wall.rows() == 25);
        check("origin 就是它自己", wall.origin().equals(cell(0, 64, 0)));
        check("块数 = 1", wall.blockCount() == 1);
    }

    // ==================== 2. 横向 2×1 ====================

    private static void wide() {
        final var a = cell(0, 64, 0);
        final var b = cell(1, 64, 0);
        final var wall = ScreenWall.compute(set(a, b), a, 80, 25);
        check("相邻两块（同朝向）⇒ 2×1", wall.blocksW() == 2 && wall.blocksH() == 1);
        check("总分辨率 = 2 × 80 = 160 列", wall.cols() == 160 && wall.rows() == 25);
        check("origin = 最小角那块（左）", wall.origin().equals(a));
        check("右块偏移 = (80, 0)", wall.offsetOf(b)[0] == 80 && wall.offsetOf(b)[1] == 0);
        check("网格位置：左 (0,0)、右 (1,0)",
                wall.gridOf(a)[0] == 0 && wall.gridOf(b)[0] == 1 && wall.gridOf(b)[1] == 0);
        check("摘要可读", wall.summary().contains("2x1") && wall.summary().contains("160x25"));
    }

    // ==================== 3. 纵向 1×2 ====================

    private static void tall() {
        final var bottom = cell(0, 64, 0);
        final var top = cell(0, 65, 0);
        final var wall = ScreenWall.compute(set(bottom, top), bottom, 80, 25);
        check("上下两块 ⇒ 1×2", wall.blocksW() == 1 && wall.blocksH() == 2);
        check("总分辨率 = 2 × 25 = 50 行", wall.rows() == 50 && wall.cols() == 80);
        // 图像约定（左上角为原点、行自上而下）：世界 y 大的那块在**上面** ⇒ 行 0 / 偏移 0
        check("origin = 图像左上角（世界 y 大的那块 = 上面那块）", wall.origin().equals(top));
        check("上块偏移 = (0, 0)（图像第 0 行）", wall.offsetOf(top)[0] == 0 && wall.offsetOf(top)[1] == 0);
        check("下块偏移 = (0, 25)", wall.offsetOf(bottom)[0] == 0 && wall.offsetOf(bottom)[1] == 25);
        check("网格：上 (0,0)、下 (0,1)",
                wall.gridOf(top)[0] == 0 && wall.gridOf(top)[1] == 0
                        && wall.gridOf(bottom)[0] == 0 && wall.gridOf(bottom)[1] == 1);
        check("cellAt(0,0) = 上块、cellAt(0,1) = 下块",
                wall.cellAt(0, 0).equals(top) && wall.cellAt(0, 1).equals(bottom));
    }

    // ==================== 4. 2×2 ====================

    private static void wall2x2() {
        final var a = cell(0, 64, 0);
        final var b = cell(1, 64, 0);
        final var c = cell(0, 65, 0);
        final var d = cell(1, 65, 0);
        final var wall = ScreenWall.compute(set(a, b, c, d), a, 80, 25);
        check("四块 ⇒ 2×2 且总数 4", wall.blocksW() == 2 && wall.blocksH() == 2 && wall.blockCount() == 4);
        check("总分辨率 160x50", wall.cols() == 160 && wall.rows() == 50);
        // 图像约定：y=65 是**上**排（行 0）、y=64 是下排（行 1）；x 越大越靠右
        check("origin = 图像左上角（y 大 + x 小那块 c）", wall.origin().equals(c));
        check("左上 c 偏移 = (0, 0)、右上 d 偏移 = (80, 0)",
                wall.offsetOf(c)[0] == 0 && wall.offsetOf(c)[1] == 0
                        && wall.offsetOf(d)[0] == 80 && wall.offsetOf(d)[1] == 0);
        check("左下 a 偏移 = (0, 25)", wall.offsetOf(a)[0] == 0 && wall.offsetOf(a)[1] == 25);
        check("cellAt(1,1) = 右下块 b（图像第 1 行第 1 列）", wall.cellAt(1, 1).equals(b));
        check("cellAt(0,0) = 左上块 c", wall.cellAt(0, 0).equals(c));
        check("cellAt 越界 ⇒ null", wall.cellAt(2, 0) == null && wall.cellAt(0, -1) == null);
    }

    // ==================== 5. 该拒绝的 ====================

    private static void rejects() {
        // L 形：三块，外接 2x2 但缺一块
        final var l = set(cell(0, 64, 0), cell(1, 64, 0), cell(0, 65, 0));
        check("L 形拼合被拒绝（不是完整矩形）",
                fails(() -> ScreenWall.compute(l, cell(0, 64, 0), 80, 25), "不是完整矩形"));

        // 朝向不同：不并
        final var mixed = set(cell(0, 64, 0), cell(1, 64, 0, S, WALL));
        final var wall = ScreenWall.compute(mixed, cell(0, 64, 0), 80, 25);
        check("朝向不同的相邻块不并（只有同朝向才算一块逻辑屏）",
                wall.blocksW() == 1 && wall.blockCount() == 1);

        // 不连通：远处另一块不并进来
        final var far = set(cell(0, 64, 0), cell(5, 64, 0));
        check("不连通的屏不并进来",
                ScreenWall.compute(far, cell(0, 64, 0), 80, 25).blockCount() == 1);

        // 对角相邻不算相邻
        final var diag = set(cell(0, 64, 0), cell(1, 65, 0));
        check("对角不算相邻（只认六个面）",
                ScreenWall.compute(diag, cell(0, 64, 0), 80, 25).blockCount() == 1);

        check("种子不在候选集合里 ⇒ 明确报错",
                fails(() -> ScreenWall.compute(set(cell(0, 64, 0)), cell(9, 9, 9), 80, 25), "种子单元不在"));
        check("每块分辨率非法 ⇒ 明确报错",
                fails(() -> ScreenWall.compute(set(cell(0, 64, 0)), cell(0, 64, 0), 0, 25), "每块分辨率非法"));
    }

    // ==================== 6. 地板/天花板平面 ====================

    private static void floorPlane() {
        // 地板屏：列沿 x、行沿 z（与朝向一致）⇒ 两块沿 x 排开就是 2×1
        final var a = cell(0, 64, 0, N, FLOOR);
        final var b = cell(1, 64, 0, N, FLOOR);
        final var wall = ScreenWall.compute(set(a, b), a, 80, 25);
        check("放在地板上的相邻两块也并（平面 = xz）", wall.blocksW() == 2 && wall.blocksH() == 1);
        check("地板拼合的总分辨率 160x25", wall.cols() == 160);
    }

    // ==================== 7. 整屏坐标换算（触摸/点击用）====================

    private static void coordinates() {
        final var a = cell(0, 64, 0);
        final var b = cell(1, 64, 0);
        final var wall = ScreenWall.compute(set(a, b), a, 8, 4);
        final int[] left = wall.toScreenCoordinates(0, 0);
        check("整屏 (0,0) ⇒ 左块局部 (0,0)", left[0] == 0 && left[1] == 0 && left[2] == 0 && left[3] == 0);
        final int[] right = wall.toScreenCoordinates(8, 0);
        check("整屏 (8,0) ⇒ 右块局部 (0,0)", right[0] == 1 && right[2] == 0 && right[3] == 0);
        final int[] corner = wall.toScreenCoordinates(15, 3);
        check("整屏 (15,3) ⇒ 右块局部 (7,3)", corner[0] == 1 && corner[2] == 7 && corner[3] == 3);
        check("越界 ⇒ null", wall.toScreenCoordinates(16, 0) == null
                && wall.toScreenCoordinates(0, 4) == null && wall.toScreenCoordinates(-1, 0) == null);
        check("每块 8 格 ⇒ 两块共 16 格（分辨率和屏位对应）", wall.cols() == 16 && wall.rows() == 4);

        // 纵向 2 块：图像行自上而下 ⇒ (0,0) 落在**上面**那块（世界 y 大）
        final var bottom = cell(0, 64, 0);
        final var top = cell(0, 65, 0);
        final var tall = ScreenWall.compute(set(bottom, top), bottom, 8, 4);
        final int[] upper = tall.toScreenCoordinates(0, 0);
        check("纵向：整屏 (0,0) ⇒ 上块局部 (0,0)",
                upper[0] == 0 && upper[1] == 0 && upper[2] == 0 && upper[3] == 0);
        final int[] lower = tall.toScreenCoordinates(0, 4);
        check("纵向：整屏 (0,4) ⇒ 下块局部 (0,0)",
                lower[0] == 0 && lower[1] == 1 && lower[2] == 0 && lower[3] == 0);
        check("纵向：cellAt(0,0) = 上面那块（左上角为原点）", tall.cellAt(0, 0).equals(top));
    }

    // ==================== 工具 ====================

    private static boolean fails(Runnable action, String keyword) {
        try {
            action.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(keyword);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
