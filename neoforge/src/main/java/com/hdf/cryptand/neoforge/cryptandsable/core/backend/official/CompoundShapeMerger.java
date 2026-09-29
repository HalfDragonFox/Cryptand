/**
 * ===== 轴合并（凹形组合）纯 Java 工具（2026-09-05 用户定案） =====
 *
 * 「凹形组合」= 把实心方块集合按【轴对齐】贪心合并成若干【大 box】——相邻实心块
 * 连成最大长方体，门洞/窗洞/房间自然断开。相对体素（内存灾难）/每格 1×1×1 box
 * （collider 爆炸）/凸包（填凹槽物理全错），box 数 O(外表面)，Rapier 原生
 * box↔box 窄相，稳定高效。是 Rapier 官方推荐的复合形状建模方式。
 *
 * 本类纯 Java、零 MC 依赖，可在主线程 / 核心（空间任务）任意位置复用：
 *  - {@link #merge(java.util.Set, int)}：实心块(packPos) → 轴合并 box 列表
 *  - {@link #computeInertia(java.util.List, double[])}：box 集 → 9 元惯量张量
 *    （单位密度：质量 = 总体积，与"每方块 1 质量"语义一致；相对 body 原点）。
 *
 * packPos 编码沿用工程既有约定：每轴 21 位，值 > 2_000_000 减 4_194_304 还原
 * 有符号（覆盖主世界坐标范围）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

public final class CompoundShapeMerger {

    private CompoundShapeMerger() {
    }

    /** packPos(x,y,z)：每轴 21 位。 */
    public static long packPos(final long x, final long y, final long z) {
        return ((x & 0x1FFFFF) << 42) | ((y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    private static boolean isSolid(final java.util.Set<Long> solid,
                                   final int x, final int y, final int z) {
        return solid.contains(packPos(x, y, z));
    }

    private static int sx(final long k) {
        final int x = (int) ((k >> 42) & 0x1FFFFF);
        // ★ 2026-09-06 21 位有符号补码（方块数据主世界坐标，可负 ±2^20）
        return x >= 0x100000 ? x - 0x200000 : x;
    }

    private static int sy(final long k) {
        final int y = (int) ((k >> 21) & 0x1FFFFF);
        return y >= 0x100000 ? y - 0x200000 : y;
    }

    private static int sz(final long k) {
        final int z = (int) (k & 0x1FFFFF);
        return z >= 0x100000 ? z - 0x200000 : z;
    }

    /**
     * 轴合并：实心块（packPos 世界坐标）→ 若干轴对齐 box（[minX,minY,minZ,maxX,maxY,maxZ]，
     * 与输入同一坐标空间）。贪心：按最低角排序，逐格生长 X → Y → Z 至最大（受 maxExtent
     * 上限），标记已用。门洞/窗洞/房间因不连续自然断开。
     *
     * @param solid     实心块 packPos 集合（不可变读取）
     * @param maxExtent 单轴最大边长（格）；&lt;=0 不限。默认建议 32
     * @return box 列表（每个 [minX,minY,minZ,maxX,maxY,maxZ]，闭区间含端点）
     */
    public static java.util.List<int[]> merge(final java.util.Set<Long> solid,
                                              final int maxExtent) {
        final java.util.List<int[]> out = new java.util.ArrayList<>();
        if (solid == null || solid.isEmpty()) return out;
        final int cap = maxExtent > 0 ? maxExtent : Integer.MAX_VALUE;
        // 确定性：按 packPos 升序迭代（从最低角开始生长）。
        final java.util.List<Long> sorted = new java.util.ArrayList<>(solid);
        sorted.sort(java.util.Comparator.naturalOrder());
        final java.util.Set<Long> used = new java.util.HashSet<>();
        for (final Long key : sorted) {
            if (used.contains(key)) continue;
            final int x = sx(key), y = sy(key), z = sz(key);
            // ① +X：当前 (y,z) 行连续实心
            int x1 = x;
            while (x1 - x + 1 < cap && isSolid(solid, x1 + 1, y, z)
                    && !used.contains(packPos(x1 + 1, y, z))) {
                x1++;
            }
            // ② +Y：上方各行 [x..x1] 整行实心
            int y1 = y;
            outerY:
            while (y1 - y + 1 < cap) {
                for (int cx = x; cx <= x1; cx++) {
                    if (!isSolid(solid, cx, y1 + 1, z)
                            || used.contains(packPos(cx, y1 + 1, z))) {
                        break outerY;
                    }
                }
                y1++;
            }
            // ③ +Z：前方各层 [x..x1]×[y..y1] 整层实心
            int z1 = z;
            outerZ:
            while (z1 - z + 1 < cap) {
                for (int cx = x; cx <= x1; cx++) {
                    for (int cy = y; cy <= y1; cy++) {
                        if (!isSolid(solid, cx, cy, z1 + 1)
                                || used.contains(packPos(cx, cy, z1 + 1))) {
                            break outerZ;
                        }
                    }
                }
                z1++;
            }
            // 标记已用
            for (int cx = x; cx <= x1; cx++) {
                for (int cy = y; cy <= y1; cy++) {
                    for (int cz = z; cz <= z1; cz++) {
                        used.add(packPos(cx, cy, cz));
                    }
                }
            }
            out.add(new int[]{x, y, z, x1, y1, z1});
        }
        return out;
    }

    /**
     * 由【世界坐标】实心块列表构造 packPos 集合（供 {@link #merge}）。
     *
     * @param blocks 每项 [x,y,z] 世界方块坐标
     */
    public static java.util.Set<Long> toPackSet(final java.util.List<long[]> blocks) {
        final java.util.Set<Long> set = new java.util.HashSet<>();
        if (blocks == null) return set;
        for (final long[] b : blocks) {
            set.add(packPos(b[0], b[1], b[2]));
        }
        return set;
    }

    /**
     * box 集 → 9 元惯量张量（row-major，相对 body 原点/质心）。
     * 单位密度（每格质量 1）→ 质量 = 总体积 = 实心块数；box 不相交所以体积不重复。
     * 每 box 先算自身质心惯量（长方体：Ixx=m/3·(hy²+hz²)…），再平行轴定理移至 body 原点。
     *
     * @param boxes     合并 box 列表（[minX,minY,minZ,maxX,maxY,maxZ]）
     * @param bodyOrigin body 原点（局部坐标，通常=质心）；null → (0,0,0)
     * @return [ixx,ixy,ixz, ixy,iyy,iyz, ixz,iyz,izz]（row-major，对称）
     */
    /**
     * @deprecated 2026-09-05 弃置（材质感知合并后无调用者）：单位密度 box 惯量合成。
     *   重量系统改用 {@link #computeMassProperties}（逐方块质量加权 + 立方体 m/6 + 平行轴）。
     */
    @Deprecated
    public static double[] computeInertia(final java.util.List<int[]> boxes,
                                          final double[] bodyOrigin) {
        final double ox = bodyOrigin != null && bodyOrigin.length >= 1 ? bodyOrigin[0] : 0;
        final double oy = bodyOrigin != null && bodyOrigin.length >= 2 ? bodyOrigin[1] : 0;
        final double oz = bodyOrigin != null && bodyOrigin.length >= 3 ? bodyOrigin[2] : 0;
        double ixx = 0, iyy = 0, izz = 0, ixy = 0, ixz = 0, iyz = 0;
        if (boxes != null) {
            for (final int[] b : boxes) {
                final double hx = (b[3] - b[0] + 1) * 0.5;
                final double hy = (b[4] - b[1] + 1) * 0.5;
                final double hz = (b[5] - b[2] + 1) * 0.5;
                final double m = 8 * hx * hy * hz;   // 体积 = 质量（单位密度）
                final double cx = (b[0] + b[3] + 1) * 0.5 - ox;
                final double cy = (b[1] + b[4] + 1) * 0.5 - oy;
                final double cz = (b[2] + b[5] + 1) * 0.5 - oz;
                // 自质心惯量 + 平行轴
                ixx += m / 3.0 * (hy * hy + hz * hz) + m * (cy * cy + cz * cz);
                iyy += m / 3.0 * (hx * hx + hz * hz) + m * (cx * cx + cz * cz);
                izz += m / 3.0 * (hx * hx + hy * hy) + m * (cx * cx + cy * cy);
                ixy -= m * cx * cy;
                ixz -= m * cx * cz;
                iyz -= m * cy * cz;
            }
        }
        return new double[]{ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz};
    }

    // ===== ★ 2026-09-05 【材质感知合并 + 逐方块重量】（参考原版 sable MassTracker /
    //   PhysicsBlockPropertyTypes，Cryptand 框架"合并大 box 保性能 + 材质保真"更优路径） =====
    //   输入统一为 Map<packPos, materialId>（方块级材质，主线程构图编码）——核心只消费
    //   materialId（int）+ 数值函数，零 MC 依赖。

    /** 材质感知合并结果：一个 box + 该 box 的统一材质（同材质相邻块才合并）。 */
    public record MergedBox(int[] box, int materialId) {
    }

    /** 结构质量汇总（参考原版 MassTracker.build + FloatingBlockController.applyLift）：
     *  - mass/com/inertia   ：逐方块质量加权（质心 = Σm·pos/Σm；惯量 = 立方体 m/6 + 平行轴）
     *  - liftCenter/totalLift：升力方块（liftStrength>0）聚类加权位置（升力中心）
     *  - buoyCenter/totalVolume：浮力方块（volume>0）聚类加权位置（浮力中心）
     *  com/liftCenter/buoyCenter 均为【世界坐标】（与输入 packPos 同坐标空间）。 */
    public record MassResult(double mass,
                             double comX, double comY, double comZ,
                             double[] inertia,
                             double liftX, double liftY, double liftZ, double totalLift,
                             double buoyX, double buoyY, double buoyZ, double totalVolume) {
    }

    /**
     * 材质感知轴合并：仅【同材质】相邻实心块合并成大 box（几何相邻 + 材质一致才扩展）。
     * 相对原版逐体素碰撞：box 数仍 O(外表面)（材质区域聚类），异材质边界自然断开——
     * 性能 ≈ 纯几何合并，材质保真到 box 级。
     *
     * @param solidMat 实心块 packPos → materialId（≥1；不可变读取）
     * @param maxExtent 单轴最大边长（格）；&lt;=0 不限
     * @return MergedBox 列表（每个含 box [minX,minY,minZ,maxX,maxY,maxZ] + materialId）
     */
    public static java.util.List<MergedBox> mergeMat(
            final java.util.Map<Long, Integer> solidMat, final int maxExtent) {
        final java.util.List<MergedBox> out = new java.util.ArrayList<>();
        if (solidMat == null || solidMat.isEmpty()) return out;
        final int cap = maxExtent > 0 ? maxExtent : Integer.MAX_VALUE;
        final java.util.List<Long> sorted = new java.util.ArrayList<>(solidMat.keySet());
        sorted.sort(java.util.Comparator.naturalOrder());
        final java.util.Set<Long> used = new java.util.HashSet<>();
        for (final Long key : sorted) {
            if (used.contains(key)) continue;
            final int x = sx(key), y = sy(key), z = sz(key);
            final Integer mat0 = solidMat.get(key);
            if (mat0 == null) continue;
            final int mat = mat0;
            // ① +X：当前 (y,z) 行同材质连续实心
            int x1 = x;
            while (x1 - x + 1 < cap && matAt(solidMat, x1 + 1, y, z, mat)
                    && !used.contains(packPos(x1 + 1, y, z))) {
                x1++;
            }
            // ② +Y：上方各行 [x..x1] 整行同材质实心
            int y1 = y;
            outerY:
            while (y1 - y + 1 < cap) {
                for (int cx = x; cx <= x1; cx++) {
                    if (!matAt(solidMat, cx, y1 + 1, z, mat)
                            || used.contains(packPos(cx, y1 + 1, z))) {
                        break outerY;
                    }
                }
                y1++;
            }
            // ③ +Z：前方各层 [x..x1]×[y..y1] 整层同材质实心
            int z1 = z;
            outerZ:
            while (z1 - z + 1 < cap) {
                for (int cx = x; cx <= x1; cx++) {
                    for (int cy = y; cy <= y1; cy++) {
                        if (!matAt(solidMat, cx, cy, z1 + 1, mat)
                                || used.contains(packPos(cx, cy, z1 + 1))) {
                            break outerZ;
                        }
                    }
                }
                z1++;
            }
            // 标记已用
            for (int cx = x; cx <= x1; cx++) {
                for (int cy = y; cy <= y1; cy++) {
                    for (int cz = z; cz <= z1; cz++) {
                        used.add(packPos(cx, cy, cz));
                    }
                }
            }
            out.add(new MergedBox(new int[]{x, y, z, x1, y1, z1}, mat));
        }
        return out;
    }

    /** 判断 (x,y,z) 是否 solid 且材质 == mat。 */
    private static boolean matAt(final java.util.Map<Long, Integer> solidMat,
                                 final int x, final int y, final int z, final int mat) {
        final Integer m = solidMat.get(packPos(x, y, z));
        return m != null && m.intValue() == mat;
    }

    /**
     * 结构级综合表面系数：friction = box 体积加权平均、restitution = max。
     * 对应 Rapier / 原版 hooks 碰撞合并规则（friction 平均/相乘、restitution max）。
     * native createCompoundShapeBody 是 per-body 系数——材质感知合并保证 box 级保真，
     * 综合系数作为该 body 的"代表系数"（体积越大越主导摩擦，最弹的方块决定回弹）。
     *
     * @return [friction, restitution]
     */
    public static double[] compositeSurfaceProps(
            final java.util.List<MergedBox> boxes,
            final java.util.function.ToDoubleFunction<Integer> frictionOfMat,
            final java.util.function.ToDoubleFunction<Integer> restitutionOfMat) {
        double f = 0.0, r = 0.0, vol = 0.0;
        if (boxes != null) {
            for (final MergedBox mb : boxes) {
                final int[] b = mb.box();
                final double v = (double) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
                f += v * frictionOfMat.applyAsDouble(mb.materialId());
                vol += v;
                final double rr = restitutionOfMat.applyAsDouble(mb.materialId());
                if (rr > r) r = rr;
            }
        }
        return new double[]{vol > 0 ? f / vol : 0.6, r};
    }

    /**
     * 逐方块质量累加 → 总质量 / 质心 / 惯量 / 升力中心 / 浮力中心。
     * 严格对齐原版 MassTracker.build 两遍扫描：
     *  第一遍 mass = Σmᵢ、com = Σ(mᵢ·posᵢ)/Σmᵢ、升力/浮力中心各自 Σ(wᵢ·posᵢ)/Σwᵢ；
     *  第二遍惯量 = Σ[ 立方体自身 m/6 + fmaInertiaTensor(r, m) 平行轴 ]（r = pos − com）。
     *
     * @param solidMat  packPos → materialId
     * @param massOfMat materialId → 质量 [kpg]
     * @param liftOfMat materialId → 升力强度（>0 计升力中心；无 → 0）
     * @param volOfMat  materialId → 排水体积（>0 计浮力中心；无 → 0）
     */
    public static MassResult computeMassProperties(
            final java.util.Map<Long, Integer> solidMat,
            final java.util.function.ToDoubleFunction<Integer> massOfMat,
            final java.util.function.ToDoubleFunction<Integer> liftOfMat,
            final java.util.function.ToDoubleFunction<Integer> volOfMat) {
        if (solidMat == null || solidMat.isEmpty()) {
            return new MassResult(1.0, 0, 0, 0, new double[9],
                    0, 0, 0, 0, 0, 0, 0, 0);
        }
        double mass = 0.0;
        double comX = 0, comY = 0, comZ = 0;
        double liftX = 0, liftY = 0, liftZ = 0, totalLift = 0;
        double buoyX = 0, buoyY = 0, buoyZ = 0, totalVolume = 0;
        // 第一遍：mass + 质心 + 升力/浮力中心
        for (final java.util.Map.Entry<Long, Integer> e : solidMat.entrySet()) {
            final int x = sx(e.getKey()), y = sy(e.getKey()), z = sz(e.getKey());
            final double m = massOfMat.applyAsDouble(e.getValue());
            final double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            mass += m;
            comX += m * cx;
            comY += m * cy;
            comZ += m * cz;
            final double lift = liftOfMat.applyAsDouble(e.getValue());
            if (lift > 0) {
                totalLift += lift;
                liftX += lift * cx;
                liftY += lift * cy;
                liftZ += lift * cz;
            }
            final double vol = volOfMat.applyAsDouble(e.getValue());
            if (vol > 0) {
                totalVolume += vol;
                buoyX += vol * cx;
                buoyY += vol * cy;
                buoyZ += vol * cz;
            }
        }
        if (mass <= 0) mass = 1.0;
        comX /= mass;
        comY /= mass;
        comZ /= mass;
        if (totalLift > 0) {
            liftX /= totalLift;
            liftY /= totalLift;
            liftZ /= totalLift;
        }
        if (totalVolume > 0) {
            buoyX /= totalVolume;
            buoyY /= totalVolume;
            buoyZ /= totalVolume;
        }
        // 第二遍：惯量（立方体自身 m/6 对角 + 平行轴 fmaInertiaTensor(r, m)）
        double ixx = 0, iyy = 0, izz = 0, ixy = 0, ixz = 0, iyz = 0;
        for (final java.util.Map.Entry<Long, Integer> e : solidMat.entrySet()) {
            final int x = sx(e.getKey()), y = sy(e.getKey()), z = sz(e.getKey());
            final double m = massOfMat.applyAsDouble(e.getValue());
            final double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            final double rx = cx - comX, ry = cy - comY, rz = cz - comZ;
            ixx += m / 6.0 + (ry * ry + rz * rz) * m;
            iyy += m / 6.0 + (rz * rz + rx * rx) * m;
            izz += m / 6.0 + (rx * rx + ry * ry) * m;
            ixy -= rx * ry * m;
            ixz -= rx * rz * m;
            iyz -= ry * rz * m;
        }
        return new MassResult(mass, comX, comY, comZ,
                new double[]{ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz},
                liftX, liftY, liftZ, totalLift,
                buoyX, buoyY, buoyZ, totalVolume);
    }

    /** 从 SectionUpload 列表提取实心块材质表（packPos 世界坐标 → materialId）。
     *  材质 = section 体素编码【高 16 位】（原 colliderValue 位置，compound 路线已改为
     *  materialId；0 → 默认材质 1）。minBounds 补 (boundMinX & 15) 偏移（同 solidBlocks）。 */
    public static java.util.Map<Long, Integer> solidMat(
            final java.util.List<OfficialRapierEngine.SectionUpload> sections,
            final int[] minBounds) {
        final java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        if (sections == null) return out;
        final int foX = minBounds != null && minBounds.length >= 1 ? (minBounds[0] & 15) : 0;
        final int foY = minBounds != null && minBounds.length >= 2 ? (minBounds[1] & 15) : 0;
        final int foZ = minBounds != null && minBounds.length >= 3 ? (minBounds[2] & 15) : 0;
        for (final OfficialRapierEngine.SectionUpload sec : sections) {
            final int[] c = sec.chunk();
            if (c == null) continue;
            final int sx = (sec.secX() << 4) + foX, sy = (sec.secY() << 4) + foY;
            final int sz = (sec.secZ() << 4) + foZ;
            for (int by = 0; by < 16; by++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        final int v = c[bx + (bz << 4) + (by << 8)];
                        if (v != 0) {
                            int mat = (v >>> 16) & 0xFFFF;
                            if (mat == 0) mat = 1;
                            out.put(packPos(sx + bx, sy + by, sz + bz), mat);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 从扫描表（SectionKey → int[4096]）提取实心块材质表（packPos 世界坐标 → materialId）。 */
    public static java.util.Map<Long, Integer> solidMat(
            final java.util.Map<SectionKey, int[]> scanned) {
        final java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        if (scanned == null) return out;
        for (final java.util.Map.Entry<SectionKey, int[]> e : scanned.entrySet()) {
            final int[] c = e.getValue();
            if (c == null) continue;
            final SectionKey k = e.getKey();
            final int sx = k.x() << 4, sy = k.y() << 4, sz = k.z() << 4;
            for (int by = 0; by < 16; by++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        final int v = c[bx + (bz << 4) + (by << 8)];
                        if (v != 0) {
                            int mat = (v >>> 16) & 0xFFFF;
                            if (mat == 0) mat = 1;
                            out.put(packPos(sx + bx, sy + by, sz + bz), mat);
                        }
                    }
                }
            }
        }
        return out;
    }
}
