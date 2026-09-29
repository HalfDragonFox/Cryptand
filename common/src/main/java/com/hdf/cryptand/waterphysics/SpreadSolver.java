package com.hdf.cryptand.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;

/**
 * 逐格扩散求解：重力优先 + 水平均衡。
 *
 * <p><b>粒度 = 一格</b>（粒度就是一个方块坐标）：一次计算只算一格 ——
 * 重力优先向下，否则向所有「比自己低 {@link #MIN_HORIZONTAL_GRADIENT} 级以上」的可容纳邻居
 * 各扩散 {@link #HORIZONTAL_TRANSFER} 级，自己减少对应数量。
 *
 * <p><b>不做动态扩散</b>：算完由调用方（主线程）把「水位变了的格」排进延后表，
 * {@code fluidTickRate} 个 tick 之后它们才到期、才再算一次 —— 延后表就是这个作用。
 * 「一次只走一格」由此天然成立：落点不会在同一次计算里继续往外走。
 *
 * <p>一轮/一层的概念不存在：整片水域的一层是由很多个<b>不同时刻到期</b>的单格计算拼出来的。
 *
 * <p>只改本 region 的快照 field；跨 region 的落点直接记进 {@link FluidWritePlan}；
 * region 内的改动由 {@link #emitChanges} 转成写意图 —— 快照是一次性的，不输出等于没算。
 */
public final class SpreadSolver {

    /**
     * 每个 region 单次遍历的扩散步数上限（离线吞吐闸门用；主链路是单格粒度，不用它）。
     */
    public static final int MAX_SPREAD_STEPS = 16;



    /** 六个轴向邻居：水只在轴向相邻格之间走（斜着穿角不是流体的行为）。 */
    private static final int[][] AXIAL = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};



    /** 均衡器广搜的复用缓冲：每个求解线程一份，避免每个 region 每 tick 重新分配。 */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        final byte[] visited = new byte[WaterLevelField.CELLS];
        final int[] queue = new int[WaterLevelField.CELLS];
        /**
         * 本 sweep 的**缺口距离场**：每一格到最近「缺口格」（excess_num &lt; 0）的轴向步数，-1 = 走不到。
         *
         * <p>平手判据缺了它就只剩 AXIAL 的固定顺序（+x 永远优先）—— 水源在右端时 +x 正是回源方向，
         * 水在两格之间来回搬、左边的真缺口永远填不上（镜像沟槽停在 0 0 0 0 1 1 2 1 2 1）。
         * ★ 每个 sweep 重算，不复用上一 sweep 的结果。
         */
        final int[] gapDist = new int[WaterLevelField.CELLS];
        /** 缺口距离场 BFS 的队列（{@link #queue} 此刻被 sweep 快照占用，另开一条）。 */
        final int[] bfsQueue = new int[WaterLevelField.CELLS];
        /** 上一次 BFS 真正写过 {@link #gapDist} 的格数：下次只需清这些格，不必整片 fill(4096)。 */
        int bfsUsed;
        /** 本 sweep 的净变化量（`delta[cell]` = 该格这一 sweep 的水位净增减），只对 touched 里的格维护。 */
        final short[] delta = new short[WaterLevelField.CELLS];
        /** 本 sweep 已进过 touched 列表的格（sweepToken 标记，避免同一格重复入列）。 */
        final byte[] touchMark = new byte[WaterLevelField.CELLS];
        /**
         * 本次调用「片内格」的位图（bit = 该格属于本次 flood fill 的某一片）。
         *
         * <p>sweep 遍历用它取出片内格，**取出顺序天然是 y↓/x↑/z↑**（与整段三层循环一致）。
         * 片很大（稠密模式）时直接整片 fill 成 -1 ⇒ 同一次遍历自动退化成整段扫描，不必写第二套代码。
         */
        final long[] pieceBits = new long[WaterLevelField.CELLS >> 6];
        /** 本次调用的片内格列表（稀疏模式下写回 field.surface 与 BFS 源点扫描用；顺序无关）。 */
        int[] piecesCur = new int[WaterLevelField.CELLS];
        /** 上一次调用是否走了稠密模式（决定本次开头用整片 fill 还是逐格清理）。 */
        boolean lastDense;

        {
            // ★ gapDist 的「未访问」约定是 -1，而 new int[] 默认是 0 —— 增量清理（只清上次写过的格）
            //   必须先把初值定成 -1，否则第一次 BFS 会把所有格都当成"已访问"（dist 全 0、方向信号丢失）。
            java.util.Arrays.fill(gapDist, -1);
        }

        /**
         * 压力场**分子**：这一格所在水域的平衡水面 H*_num = y*8*area + remaining（单位 = 1/8 格 × area）。
         *
         * <p>用分子/分母而不是整除后的整数：整除会在「按重力堆叠」这一步丢掉 remaining % area 个单位，
         * 水面被截断到层底（10 格沟槽放 8 单位 ⇒ 8/10 = 0 ⇒ H* 落在层底），全片 target 跟着归零，
         * 「水位 1 是末端」于是把所有 level=1 的格钉死，源格剩下的水只能在沟槽里来回平移。
         * 分母是 {@link #surfaceArea}，同一个 index 上两者成对出现。
         */
        final int[] surface = new int[WaterLevelField.CELLS];
        /** 压力场**分母**：{@link #surface} 的 area（堆叠时停住水的那一层容器格数）；0 = 这一格没归片。 */
        final int[] surfaceArea = new int[WaterLevelField.CELLS];
        /** 压力场用：这一格已经归入哪一片水（连通体每片只算一次）。 */
        final byte[] grouped = new byte[WaterLevelField.CELLS];
        /** 压力场用：每一层「水停得住」的格数（按重力堆叠算目标水面用）。 */
        final int[] layerCells = new int[16];
        /**
         * 本批/sweep 内「已经参与过一次转移」的格。
         *
         * <p>用 token 记，而不是靠调用方每轮清空一个传进来的数组 —— 调用方一旦复用同一个数组
         * （离线驱动就是这么写的），标记会跨轮残留，只有头几轮生效，
         * 症状是「水推到通道口就再也不动」。
         */
        final byte[] marks = new byte[WaterLevelField.CELLS];
        int markToken;
        int token;
        int groupToken;
    }

    /** 这一格能不能停住水：可容纳，且下方是地形或已经有水（水停不住的格不算容器）。 */
    private static boolean canHoldCell(final WaterLevelField field, final FluidCellView world,
                                       final int bx, final int by, final int bz, final int index) {
        if (!FluidCellKind.canHold(field.kind(index))) {
            return false;
        }
        final int lx = SectionCursor.localX(index);
        final int ly = SectionCursor.localY(index);
        final int lz = SectionCursor.localZ(index);
        if (!world.known(bx + lx, by + ly + 1, bz + lz)) {
            return false;
        }
        final int dy = ly - 1;
        if (dy >= 0) {
            final int dIdx = SectionCursor.linearIndex(lx, dy, lz);
            if (FluidCellKind.canHold(field.kind(dIdx)) && field.level(dIdx) <= 0) {
                return false;
            }
        }
        return true;
    }

    private SpreadSolver() {
    }

    /**
     * <b>单格任务</b>（单格粒度）：只算这一格。
     *
     * @param index region 内的线性下标
     * @return 本格消耗的运算数（水量转移次数）；0 = 这一格没有可动的水
     */
    public static int stepCell(final WaterLevelField field, final FluidCellView world,
                               final long regionKey, final int index, final FluidWritePlan out) {
        final int level = field.level(index);
        if (level <= 0) {
            return 0;
        }
        final int bx = SectionCursor.keyX(regionKey) << 4;
        final int by = SectionCursor.keyY(regionKey) << 4;
        final int bz = SectionCursor.keyZ(regionKey) << 4;
        final int wx = bx + SectionCursor.localX(index);
        final int wy = by + SectionCursor.localY(index);
        final int wz = bz + SectionCursor.localZ(index);

        // 1) 重力：向下（一次搬到下方那格的空余容量）
        //   ★ 源格保留 1 单位（语义对齐：水柱下泄时源头留一层薄膜）。
        //     少了它，悬空的水源会被自己抽空变成空气 —— 水柱断头、瀑布顶端的水源方块消失。
        //     只在「这一搬会把源格搬空、且源格本来不止 1 单位」时留。
        final int dy = wy - 1;
        final int downKind = kindAt(field, world, bx, by, bz, wx, dy, wz);
        final int downLevel = levelAt(field, world, bx, by, bz, wx, dy, wz);
        final int downCapacity = FluidCellKind.capacity(downKind);
        if (downCapacity > 0 && downLevel < downCapacity) {
            final int room = downCapacity - downLevel;
            int move = Math.min(level, room);
            if (move == level && level > 1) {
                move--;                         // 源格留 1
            }
            if (move > 0) {
                transfer(field, world, bx, by, bz, index, wx, dy, wz, move, out);
                return 1;
            }
        }

        // 2) 水头驱动：把 1 单位让给「水头最低」的那个可容纳邻居（水头 = y*8 + level）。
        //
        //    ★ 一次只走 1 级、只跟相邻一格打交道，水头严格下降 ⇒ 不会来回搬（原版观感）。
        //    ★ 含竖直方向：满格受挤压时水位会往上抬（连通器的抬升就是这么一级一级顶上去的）。
        //    ★ 水位 1 是末端：它不再往外让，只有被重新填到 ≥ 2 才会再次参与。
        if (level <= 1) {
            return 0;
        }
        int best = -1;
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        int bestHead = Integer.MAX_VALUE;
        for (final int[] d : AXIAL) {
            final int nx = wx + d[0];
            final int ny = wy + d[1];
            final int nz = wz + d[2];
            if (!world.known(nx, ny, nz)) {
                continue;                       // 没读过的格不参与（它的水位是默认值）
            }
            if (!FluidCellKind.canHold(kindAt(field, world, bx, by, bz, nx, ny, nz))) {
                continue;
            }
            final int nIdx = indexInRegion(bx, by, bz, nx, ny, nz);
            final int nl = nIdx >= 0 ? field.level(nIdx)
                    : levelAt(field, world, bx, by, bz, nx, ny, nz);
            if (nl >= WaterLevelField.MAX_LEVEL) {
                continue;                       // 邻居已经满了，接不下
            }
            final int nHead = ny * WaterLevelField.MAX_LEVEL + nl;
            // ★ 必须比本格低 2 级以上：差 1 就是「已经平了」（原版语义：水位 1 是末端、
            //   相邻差 1 不再流动）。少了这条，两个差 1 的格会互相给 1 级 ——
            //   离线症状是 pulled > 0 而 changed = false（水在原地来回）。
            if (nHead + 1 >= wy * WaterLevelField.MAX_LEVEL + level) {
                continue;
            }
            if (nHead < bestHead) {
                bestHead = nHead;
                best = nIdx;
                bestX = nx;
                bestY = ny;
                bestZ = nz;
            }
        }
        if (best < 0 && bestHead == Integer.MAX_VALUE) {
            return 0;                           // 没有水头低 2 级以上的邻居 ⇒ 不动
        }
        if (best >= 0) {
            field.setLevel(best, field.level(best) + 1);
        } else {
            out.addLevelDelta(bestX, bestY, bestZ, 1);      // 跨 region：记增量
        }
        field.setLevel(index, level - 1);
        if (field.isSource(index)) {
            // ★ 恒定水源（海洋/河流）：让出去的水立刻补回满格 ⇒ 这类格自己不减少。
            field.setLevel(index, WaterLevelField.MAX_LEVEL);
        }
        return 1;
    }

    /** 不限额度地遍历工作集（离线测试与内部使用）。 */
    public static int step(final WaterLevelField field, final FluidCellView world,
                           final long regionKey, final WaterWorkSet workSet,
                           final FluidWritePlan out) {
        return step(field, world, regionKey, workSet, out, Integer.MAX_VALUE);
    }

    /**
     * 遍历工作集，逐格执行 {@link #stepCell}，最多处理 {@code cellQuota} 个格。
     *
     * <p>额度以<b>格数</b>计：一格要么被完整处理，要么完全不动（不会算半格）。
     *
     * @return 本次消耗的运算数（水量转移次数）
     */
    public static int step(final WaterLevelField field, final FluidCellView world,
                           final long regionKey, final WaterWorkSet workSet,
                           final FluidWritePlan out, final int cellQuota) {
        final int n = workSet.size();
        int used = 0;
        int cells = 0;
        for (int k = 0; k < n; k++) {
            if (cells >= cellQuota) {
                break;
            }
            used += stepCell(field, world, regionKey, workSet.get(k), out);
            cells++;
        }
        return used;
    }

    /**
     * <b>压力均衡器</b>：算出整片连通水域的平衡水面 H*（flood fill + 按重力堆叠，每片只算一次），
     * 再让每个「超出目标水位」的格把 1 单位水让给最近的缺口 —— 横向铺开与竖向压力是同一件事；
     * 一轮内反复 sweep 直到没有格再让出。
     *
     * <p>平手顺序 =（超出量 excess_num，到最近缺口的距离，格位高低，实时水头）：缺口距离由每 sweep
     * 一次的多源 BFS 给出，缺了它就只能退回 AXIAL 的固定顺序（+x 优先）—— 那是回源方向，
     * 水会在两格之间来回搬而铺不平。
     *
     * <p>水位 1 在自由水面上是末端：只准顺着重力往下让，不再横向扩散（原版观感）；
     * 竖向允许 1 单位误差（连通器口径）。
     *
     * @param active  本批到期的格（只决定「这一批有没有活干」，范围本身是整片连通水域）
     * @param maxDist 0 = 关掉均衡器；非 0 = 启用（数值本身不再限制范围，快照的采集视野另算）
     * @return 这一轮一共搬了多少次（净零的那一 sweep 不计进返回值）
     */
    public static int equalizeLines(final WaterLevelField field, final FluidCellView world,
                                    final long regionKey, final WaterWorkSet active,
                                    final int maxDist) {
        if (maxDist <= 0 || active.size() == 0) {
            return 0;
        }
        final int bx = SectionCursor.keyX(regionKey) << 4;
        final int by = SectionCursor.keyY(regionKey) << 4;
        final int bz = SectionCursor.keyZ(regionKey) << 4;
        final Scratch scratch = SCRATCH.get();
        final int[] surface = scratch.surface;
        final int[] surfaceArea = scratch.surfaceArea;
        final int[] queue = scratch.queue;
        final byte[] grouped = scratch.grouped;
        final int[] layerCells = scratch.layerCells;
       final byte[] marks = scratch.marks;
        if (scratch.markToken >= 0x7F) {
            java.util.Arrays.fill(marks, (byte) 0);
            java.util.Arrays.fill(scratch.touchMark, (byte) 0);
            scratch.markToken = 0;
        }
        final byte markToken = (byte) ++scratch.markToken;
        // ---- 1) 压力场：水越深压力越大 ----
        //   每片水只枚举**一次**连通体（flood fill），把水量按重力在「水停得住」的格上堆一遍，
        //   得到该片的目标水面 surface —— 它就是压力基准（离它多深 = 压力多大）。
        //   ★ 连通体每片一次，而不是每格一次广搜：这就是「压力机制省掉 BFS」的落点。
        // ★ 稀疏化 + 稠密模式：
        //   稀疏（片小，实机绝大多数 region）：只清上一次归过片的格 = 位图置位格，清理量 O(片大小)；
        //   稠密（片大，海洋/大湖的 section）：逐格维护比整片 fill 更贵 ⇒ 退回「整段扫描」形态，
        //     用 pieceBits 整片 fill 成 -1 让 sweep 遍历自动退化，不再写第二套遍历代码。
        //   判据用「有水格数」（位图 bitCount，64 次操作）预判；片只会比有水格多 ⇒ 保守下界。
        final long[] pieceBits = scratch.pieceBits;
        final long[] waterBits = field.waterBits();
        int waterCells = 0;
        for (int w = 0; w < waterBits.length; w++) {
            waterCells += Long.bitCount(waterBits[w]);
        }
        final boolean dense = waterCells * 4 >= WaterLevelField.CELLS;
        if (scratch.lastDense) {
            java.util.Arrays.fill(surface, 0);
            java.util.Arrays.fill(surfaceArea, 0);
            java.util.Arrays.fill(pieceBits, 0L);
            if (!dense) {
                for (int i = 0; i < WaterLevelField.CELLS; i++) {
                    field.setSurface(i, 0);     // 上一轮整片写过、本轮只写片内 ⇒ 必须整片撤掉
                }
            }
        } else {
            for (int w = 0; w < pieceBits.length; w++) {
                long bits = pieceBits[w];
                while (bits != 0) {
                    final int c = (w << 6) | Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    surface[c] = 0;
                    surfaceArea[c] = 0;
                    field.setSurface(c, 0);
                }
            }
            java.util.Arrays.fill(pieceBits, 0L);   // 64 次写比逐格清位便宜
        }
        final int[] pieces = scratch.piecesCur;
        int piecesCount = 0;
        if (scratch.groupToken >= 0x7F) {
            java.util.Arrays.fill(grouped, (byte) 0);
            scratch.groupToken = 0;
        }
        final byte groupToken = (byte) ++scratch.groupToken;
        // ★ 稀疏化：起点只枚举「有水格」（WaterLevelField 的位图），不再扫整段 4096。
        //   取出顺序仍是 index 升序（与原来的 for(start = 0..4095) 一致）；连通划分与顺序无关。
        for (int w = 0; w < waterBits.length; w++) {
            long bits = waterBits[w];
            while (bits != 0) {
                final int start = (w << 6) | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                if (field.level(start) <= 0 || grouped[start] == groupToken) {
                    continue;
                }
            java.util.Arrays.fill(layerCells, 0);
            int head = 0;
            int tail = 0;
            int water = 0;
            queue[tail++] = start;
            grouped[start] = groupToken;
            if (!dense) {
                pieces[piecesCount++] = start;
                pieceBits[start >> 6] |= 1L << (start & 63);
            }
            while (head < tail) {
                final int cur = queue[head++];
                water += field.level(cur);
                layerCells[SectionCursor.localY(cur)]++;
                final int lx = SectionCursor.localX(cur);
                final int ly = SectionCursor.localY(cur);
                final int lz = SectionCursor.localZ(cur);
                for (final int[] d : AXIAL) {
                    final int nx = lx + d[0];
                    final int ny = ly + d[1];
                    final int nz = lz + d[2];
                    if ((nx | ny | nz) < 0 || nx > 15 || ny > 15 || nz > 15) {
                        continue;
                    }
                    final int nIdx = SectionCursor.linearIndex(nx, ny, nz);
                    if (grouped[nIdx] == groupToken) {
                        continue;
                    }
                    if (!world.known(bx + nx, by + ny, bz + nz)) {
                        continue;
                    }
                    if (!FluidCellKind.canHold(field.kind(nIdx))) {
                        continue;
                    }
                    // ★ 容器 = 水能占据的空间，分两种：
                    //   - 正上方那格属于同一根水柱：水会被压力顶上去，只要可容纳就算容器
                    //     （少了它，U 形管「暂时空的另一半竖井」会被排除，
                    //      整片水被堆进半个管子 ⇒ 目标水面被高估，症状正是 80 单位算出 surf=40）；
                    //   - 同层/向下：仍要求「停得住」（下方是地形或下方已有水），
                    //     否则整片天空都会被算成容器，目标水面被稀释成 0。
                    if (d[1] <= 0) {
                        final int dy = ny - 1;
                        if (dy >= 0) {
                            final int dIdx = SectionCursor.linearIndex(nx, dy, nz);
                            if (FluidCellKind.canHold(field.kind(dIdx)) && field.level(dIdx) <= 0) {
                                continue;               // 水停不住的格不算容器
                            }
                        }
                    }
                    grouped[nIdx] = groupToken;
                    queue[tail++] = nIdx;
                    if (!dense) {
                        pieces[piecesCount++] = nIdx;
                        pieceBits[nIdx >> 6] |= 1L << (nIdx & 63);
                    }
                }
            }
            if (water <= 0) {
                continue;
            }
            // 按重力把这批水堆一遍，算出目标水面 —— **分子/分母** 两半，一步都不许提前整除：
            //   area       = 水停住的那一层的容器格数（这次堆叠用到的分母）
            //   H*_num     = y*8*area + 余数（余数全部留在这里；后面所有比较都同尺度做）
            //   溢出余量：y*8*area ≤ 15*8*256 = 30720、8*area ≤ 2048，int 富余。
            int remaining = water;
            int surfaceNum = 16 * WaterLevelField.MAX_LEVEL;   // 兜底：水超过整片容量时水面在段顶
            int surfaceAreaValue = 1;
            for (int y = 0; y < 16; y++) {
                final int area = layerCells[y];
                if (area == 0) {
                    continue;
                }
                final int cap = area * WaterLevelField.MAX_LEVEL;
                if (remaining <= cap) {
                    // ★ 本次修的根因就在这一行：旧写法是 y*8 + remaining/area（整除），
                    //   余数在这里被扔掉 ⇒ 水面截断到层底 ⇒ 全片 target 归零 ⇒ level=1 的格全被钉死。
                    surfaceNum = y * WaterLevelField.MAX_LEVEL * area + remaining;
                    surfaceAreaValue = area;
                    break;
                }
                remaining -= cap;
            }
            for (int q = 0; q < tail; q++) {
                final int cell = queue[q];
                surface[cell] = surfaceNum;
                surfaceArea[cell] = surfaceAreaValue;
            }
            }
        }
        if (dense) {
            java.util.Arrays.fill(pieceBits, -1L);      // 整片都算片内 ⇒ sweep 遍历自动退化成整段
        }
        int moved = 0;
        // ---- 2) 压力均衡：每格的**目标水位** target = clamp(H* - 8y, 0, 8) ----
        //   H* = 这片水的平衡水面（前面按重力堆叠算出）= 分层压力基准；离水面越深目标越高。
        //   超出目标的格把 1 单位让给「超出量 excess 最低」的相邻格（扩散）；被让到的格
        //   在同一轮内继续往下让（允许暂时超容量），直到落到真正的缺口上 ⇒ 横向穿通道、
        //   竖直抬水柱是同一件事。excess 严格下降必然收敛，所以不需要任何防抖补丁
        //   （没有「差 1 = 平」、没有「源必须有富余」、没有互相拆台的顶升/补水两套）。
        for (int sweep = 0; sweep < 64; sweep++) {
            if (scratch.markToken >= 0x7F) {
                // ★ token 回绕必须把**所有**用 token 标记的数组一起清。漏掉 visited 就会让复用出来的
                //   旧值和残留碰撞：链式前推误判「这一格走过」⇒ swept=0 返回 0 ⇒ 主线程按 changed==0
                //   判收敛，水永久停住（实机症状「只流动几个就不动了」）。
                java.util.Arrays.fill(marks, (byte) 0);
                java.util.Arrays.fill(scratch.touchMark, (byte) 0);
                java.util.Arrays.fill(scratch.visited, (byte) 0);
                scratch.markToken = 0;
            }
            final byte sweepToken = (byte) ++scratch.markToken;
            final int movedBefore = moved;
            // ★ 净零判定不再整片快照（4096 写 + 4096 比较）：只维护「本 sweep 改动过的格 +
            //   净变化量」，没被碰过的格当然不变；touched 通常只有几十项。
            final int[] touched = scratch.queue;            // 压力场已算完，这个缓冲此刻空着
            final short[] delta = scratch.delta;
            int touchedCount = 0;
            // ---- 1b) 本 sweep 的缺口距离场（多源 BFS，一次算完） ----
            //   平手必须有方向信号：两个候选在 (excess, ny, 实时水头) 上完全一样时，退回 AXIAL 顺序
            //   会固定偏向 +x —— 水源在右端时 +x 正是回源方向，水在两格之间来回搬，左边的真缺口
            //   永远填不上（镜像沟槽停在 0 0 0 0 1 1 2 1 2 1）。有了距离场，水总朝最近的缺口走。
            //   起点 = 所有 excess &lt; 0 的缺口格（距离 0），只走「同一片水、能容纳、已读取」的轴向邻居。
            //   ★ 必须每个 sweep 重算：链式前推当场就把缺口填掉/换了位置，上一 sweep 的距离立刻过时。
            final int[] gapDist = scratch.gapDist;
            final int[] bfs = scratch.bfsQueue;
            // ★ 只清「上一次 BFS 真正写过」的格（= 上次队列的内容），不再每 sweep 整片 fill(4096)：
            //   实机大多数 region 只有几格水，清理量从 4096 降到几。
            for (int i = 0; i < scratch.bfsUsed; i++) {
                gapDist[bfs[i]] = -1;
            }
            int bfsHead = 0;
            int bfsTail = 0;
            // ★ 稀疏化：缺口只可能在「片内格」上（片外格 surfaceArea = 0 ⇒ excess = 0，不是缺口）。
            //   稠密模式没有 pieces 列表 ⇒ 整段扫（与逐格语义一致）。
            final int gapCells = dense ? WaterLevelField.CELLS : piecesCount;
            for (int i = 0; i < gapCells; i++) {
                final int cell = dense ? i : pieces[i];
                final int gapArea = surfaceArea[cell];
                if (gapArea <= 0) {
                    continue;                       // 没归片（不是这片水的容器）⇒ 不是缺口
                }
                if (field.level(cell) * gapArea - targetNum(surface[cell], gapArea, SectionCursor.localY(cell)) < 0) {
                    gapDist[cell] = 0;
                    bfs[bfsTail++] = cell;
                }
            }
            while (bfsHead < bfsTail) {
                final int cur = bfs[bfsHead++];
                final int nextDist = gapDist[cur] + 1;
                final int cx = SectionCursor.localX(cur);
                final int cy = SectionCursor.localY(cur);
                final int cz = SectionCursor.localZ(cur);
                for (final int[] d : AXIAL) {
                    final int nx = cx + d[0];
                    final int ny = cy + d[1];
                    final int nz = cz + d[2];
                    if ((nx | ny | nz) < 0 || nx > 15 || ny > 15 || nz > 15) {
                        continue;
                    }
                    final int nIdx = SectionCursor.linearIndex(nx, ny, nz);
                    if (gapDist[nIdx] >= 0 || grouped[nIdx] != groupToken) {
                        continue;                   // 走过，或不属于同一片水
                    }
                    // ★ 不再重复 world.known / canHold：grouped == groupToken 是 flood fill 在问过
                    //   known 与 canHold 之后才打上的标记，这里再问一遍纯属重复 —— 而 world.known
                    //   是接口虚调用，是这一圈里最贵的一步。
                    gapDist[nIdx] = nextDist;
                    bfs[bfsTail++] = nIdx;
                }
            }
            scratch.bfsUsed = bfsTail;
            int swept = 0;
            // ★ 稀疏化：只遍历「本次片内格」，不再 16×16×16 全扫；取出顺序仍是 y↓ / x↑ / z↑
            //   （顺序一变，平手判定的归属就变 = 改行为，所以必须按原位序取位）。
            for (int y = 15; y >= 0; y--) {
                for (int x = 0; x < 16; x++) {
                    int seg = (int) ((pieceBits[(x << 2) | (y >> 2)] >>> ((y & 3) << 4)) & 0xFFFFL);
                    while (seg != 0) {
                        final int z = Integer.numberOfTrailingZeros(seg);
                        seg &= seg - 1;
                        final int index = SectionCursor.linearIndex(x, y, z);
                        final int level = field.level(index);
                        if (level <= 0) {
                            continue;
                        }
                        final int area = surfaceArea[index];
                        final int exI = level * area - targetNum(surface[index], area, y);
                        if (exI <= 0) {
                            continue;               // 没超过目标 ⇒ 平衡，不动
                        }

                        // 自由水面上的水位 1 是末端：只能顺着重力往下掉，不再横向扩散。
                        //   ★ 同尺度比较（(y+1)*8*area vs H*_num），不再经过整除。
                        // 自由水面上的水位 1 是末端：只能顺着重力往下掉，不再横向扩散。
                        //   ★ 同尺度比较（(y+1)*8*area vs H*_num），不再经过整除。
                        final boolean stub = level <= 1
                                && (y + 1) * WaterLevelField.MAX_LEVEL * area > surface[index];
                        int best = -1;
                        int bestEx = Integer.MAX_VALUE;
                        int bestDist = Integer.MAX_VALUE;
                        int bestHead = Integer.MAX_VALUE;
                        int bestY = 0;
                        for (final int[] d : AXIAL) {
                            if (stub && d[1] != -1) {
                                continue;
                            }
                            final int nx = x + d[0];
                            final int ny = y + d[1];
                            final int nz = z + d[2];
                            if ((nx | ny | nz) < 0 || nx > 15 || ny > 15 || nz > 15) {
                                continue;
                            }
                            final int nIdx = SectionCursor.linearIndex(nx, ny, nz);
                            if (marks[nIdx] == sweepToken || !FluidCellKind.canHold(field.kind(nIdx))
                                    || !world.known(bx + nx, by + ny, bz + nz)) {
                                continue;
                            }
                            final int nArea = surfaceArea[nIdx];
                            final int exN = field.level(nIdx) * nArea - targetNum(surface[nIdx], nArea, ny);
                            if (exN >= exI) {
                                continue;           // 对方不比自己更缺 ⇒ 不给（这条保证收敛）
                            }
                            // ★ 平手顺序 = (超出量, 格位高低 ny, 水头)：先看谁更低 —— 底层还有缺口时
                            //   水不许被顶到上层（Sim 48 单位那种「搬上去又被重力搬回来」的假收敛就是这么来的）。
                            //   ★ 水头必须取**本格实时**值（ny*8 + 水位，与 stepCell 同一口径）：
                            //   旧实现用的是「整轮调用开始时算一次的水柱水面快照」，而链式前推过程中
                            //   刚接到水的格在快照里还算「没水」（0），一旦被当成「水头最低」就会把水
                            //   原路弹回 —— 沟槽场景打满 64 次 sweep、返回 137、第二次调用仍返回 7 就是这么来的。
                            //   反向的同一个错误也不许犯：不能让空气以 head = 0 参与比较。
                            final int nHead = ny * WaterLevelField.MAX_LEVEL + field.level(nIdx);
                            // ★ 平手第二关键字 = 缺口距离：谁离最近的那个缺口近，谁先接这一单位水。
                            //   没有它就只能退回 AXIAL 的固定顺序（+x 优先），而那是回源方向。
                            final int nDist = gapDist[nIdx];
                            final int nDistKey = nDist < 0 ? Integer.MAX_VALUE : nDist;
                            if (exN < bestEx || (exN == bestEx
                                    && (nDistKey < bestDist || (nDistKey == bestDist
                                    && (ny < bestY || (ny == bestY && nHead < bestHead)))))) {
                                bestEx = exN;
                                bestDist = nDistKey;
                                bestHead = nHead;
                                bestY = ny;
                                best = nIdx;
                            }
                        }
                        if (best < 0) {
                            continue;
                        }
                        // ★ 目标接不下就沿同一方向继续往前推：中间格一进一出（净 0），
                        //   所以**不会出现超容量**（起点 -1、终点 +1），也仍然是只跟相邻格打交道。
                        //   横向穿一长条满格通道、竖向把整根水柱顶起来，用的都是这条链。
                        int goal = best;
                        int goalEx = bestEx;
                        int goalHead = bestHead;
                        int goalY = bestY;
                        final byte[] walk = scratch.visited;
                        walk[index] = sweepToken;
                        walk[best] = sweepToken;
                        for (int hop = 0; hop < 64
                                && field.level(goal) >= WaterLevelField.MAX_LEVEL; hop++) {
                            final int gx = SectionCursor.localX(goal);
                            final int gy = SectionCursor.localY(goal);
                            final int gz = SectionCursor.localZ(goal);
                            int next = -1;
                            int nextEx = Integer.MAX_VALUE;
                            int nextDist = Integer.MAX_VALUE;
                            int nextHead = Integer.MAX_VALUE;
                            int nextY = 0;
                            for (final int[] d : AXIAL) {
                                final int nx = gx + d[0];
                                final int ny = gy + d[1];
                                final int nz = gz + d[2];
                                if ((nx | ny | nz) < 0 || nx > 15 || ny > 15 || nz > 15) {
                                    continue;
                                }
                                final int nIdx = SectionCursor.linearIndex(nx, ny, nz);
                                if (walk[nIdx] == sweepToken || !FluidCellKind.canHold(field.kind(nIdx))
                                        || !world.known(bx + nx, by + ny, bz + nz)) {
                                    continue;               // 走过的、装不下的、没读过的都不走
                                }
                                final int nArea = surfaceArea[nIdx];
                                final int exN = field.level(nIdx) * nArea - targetNum(surface[nIdx], nArea, ny);
                                final int nHead = ny * WaterLevelField.MAX_LEVEL + field.level(nIdx);   // 同上：实时水头
                                final int nDist = gapDist[nIdx];
                                final int nDistKey = nDist < 0 ? Integer.MAX_VALUE : nDist;
                                if (exN < nextEx || (exN == nextEx
                                        && (nDistKey < nextDist || (nDistKey == nextDist
                                        && (ny < nextY || (ny == nextY && nHead < nextHead)))))) {
                                    nextEx = exN;
                                    nextDist = nDistKey;
                                    nextHead = nHead;
                                    nextY = ny;
                                    next = nIdx;
                                }
                            }
                            if (next < 0) {
                                break;
                            }
                            goal = next;
                            goalEx = nextEx;
                            goalHead = nextHead;
                            goalY = nextY;
                            walk[goal] = sweepToken;
                        }
                        if (field.level(goal) >= WaterLevelField.MAX_LEVEL) {
                            continue;                   // 链尽头也接不下 ⇒ 这次不给
                        }

                        field.setLevel(index, level - 1);
                        field.setLevel(goal, field.level(goal) + 1);
                        if (field.isSource(index)) {
                            // ★ 恒定水源：立刻补回满格；补回来的这 1 单位要记进 delta，
                            //   否则 delta[index] 恒为 -1 ⇒ netZero 永不成立 ⇒ 每次调用空转到 sweep 上限。
                            field.setLevel(index, WaterLevelField.MAX_LEVEL);
                            delta[index]++;
                        }
                        marks[goal] = sweepToken;
                        if (scratch.touchMark[index] != sweepToken) {
                            scratch.touchMark[index] = sweepToken;
                            touched[touchedCount++] = index;
                        }
                        if (scratch.touchMark[goal] != sweepToken) {
                            scratch.touchMark[goal] = sweepToken;
                            touched[touchedCount++] = goal;
                        }
                        delta[index]--;
                        delta[goal]++;
                        moved++;
                        swept++;
                    }
                }
            }
            if (swept == 0) {
                break;
            }
            // ★ 净零 = 不动点：这一 sweep 全在「A 给 B、B 又给 A」地互相搬运时，水位分布与
            //   sweep 前**完全一致**。目标是「最优解不唯一」（48 单位铺在 9 格上，3 个 6 + 6 个 5
            //   与别的搭配同样最优），搬运只是在最优集合里打转 —— 实测会打满 64 次 sweep
            //   返回 384 而净变化 0。分布没变就说明到位了：停，并且把这一 sweep 的搬运
            //   不计进进度（否则驱动层看到「还有变化」会永远排班）。
            boolean netZero = true;
            for (int i = 0; i < touchedCount; i++) {
                if (delta[touched[i]] != 0) {           // 净变化 0 = 这一格回到了 sweep 前的值
                    netZero = false;
                    break;
                }
            }
            for (int i = 0; i < touchedCount; i++) {
                delta[touched[i]] = 0;                  // 复位（只清本 sweep 碰过的格）
            }
            if (netZero) {
                moved = movedBefore;
                break;
            }
        }

        // ★ 稀疏化：只写回本次归片的格（上一轮归片、本轮不归的格已在开头写成 0）。
        //   稠密模式没有 pieces 列表 ⇒ 整段写回。
        final int writeCells = dense ? WaterLevelField.CELLS : piecesCount;
        for (int i = 0; i < writeCells; i++) {
            final int cell = dense ? i : pieces[i];
            final int area = surfaceArea[cell];
            field.setSurface(cell, area > 0 ? surface[cell] / area : 0);
        }
        scratch.lastDense = dense;
        return moved;
    }



    private static void transfer(final WaterLevelField field, final FluidCellView world,
                                 final int bx, final int by, final int bz,
                                 final int fromIndex, final int toX, final int toY, final int toZ,
                                 final int amount, final FluidWritePlan out) {
        // ★ 守恒闸门：只在「真的读过」的格上落水。
        //   没读过的格在 field 里是默认值（看起来像空气、水位 0，是最诱人的落点），
        //   但它不在 scannedCells 里 ⇒ emitChanges 不会输出它 ⇒ 水被从源格扣掉后哪都没出现。
        if (!world.known(toX, toY, toZ)) {
            return;
        }
        final int toIndex = indexInRegion(bx, by, bz, toX, toY, toZ);
        if (FluidCellKind.fullCubeOnly(kindAt(field, world, bx, by, bz, toX, toY, toZ))) {
            // ★ 目标只接受整格：填不满就一点都不放（水留在原格），避免写出「世界看不见的水位」。
            final int now = toIndex >= 0 ? field.level(toIndex) : 0;
            if (now + amount < WaterLevelField.MAX_LEVEL) {
                return;
            }
        }
        field.setLevel(fromIndex, field.level(fromIndex) - amount);
        if (toIndex >= 0) {
            field.setLevel(toIndex, field.level(toIndex) + amount);
        } else {
            // ★ 跨 region 记**增量**：绝不能用「快照基数 + amount」当最终值 ——
            //   plan 会跨 tick 写回，期间目标格可能被它自己的 region 改过，绝对值会把那次写入覆盖掉（丢水）。
            out.addLevelDelta(toX, toY, toZ, amount);
        }
    }

    /**
     * 把 region 内「求解后的水位」与「捕获时的初始水位」的差异写成写意图。
     *
     * <p>输出的是<b>增量</b>（相对采集时水位；同一格只会有一条，plan 大小有界），
     * 写回侧按「该格当前侧表值 + 增量」落地，因此与跨 region 的转移是同一种语义、与写回顺序无关。
     * 而这份 plan 同时就是「下一批该排谁」的依据（水位变了的格才会继续流）。
     *
     * @param field         求解后的水位场
     * @param initialLevels 捕获快照时复制的水位（长度 {@link WaterLevelField#CELLS}）
     * @param regionKey     region 键
     * @param out           写意图出口
     * @return 写出的格子数
     */
    public static int emitChanges(final WaterLevelField field, final byte[] initialLevels,
                                  final long regionKey, final WaterWorkSet workSet,
                                  final FluidWritePlan out) {
        final int bx = SectionCursor.keyX(regionKey) << 4;
        final int by = SectionCursor.keyY(regionKey) << 4;
        final int bz = SectionCursor.keyZ(regionKey) << 4;
        int emitted = 0;
        // ★ 只比工作集：集合外的格这一次没参与求解，比了也不该写
        for (int k = 0; k < workSet.size(); k++) {
            final int idx = workSet.get(k);
            final int was = initialLevels[idx] & 0xFF;
            final int now = field.level(idx);
            if (now == was) {
                continue;
            }
            // ★ 输出**增量**（相对采集时的水位）—— 与跨 region 的转移同一种语义。
            //   写回侧一律按「该格当前侧表值 + 增量」落地 ⇒ 写回顺序完全无关：
            //   同一格被多个来源写入（本 region 的最终状态 + 邻居的跨区转移）也不会互相覆盖。
            out.addLevelDelta(bx + SectionCursor.localX(idx), by + SectionCursor.localY(idx),
                    bz + SectionCursor.localZ(idx), now - was);
            emitted++;
        }
        return emitted;
    }

    /** region 内坐标 → 线性索引；不在本 region 返回 -1。 */
    public static int indexInRegion(final int bx, final int by, final int bz,
                                    final int x, final int y, final int z) {
        final int lx = x - bx;
        final int ly = y - by;
        final int lz = z - bz;
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
            return -1;
        }
        return SectionCursor.linearIndex(lx, ly, lz);
    }

    private static int levelAt(final WaterLevelField field, final FluidCellView world,
                               final int bx, final int by, final int bz,
                               final int x, final int y, final int z) {
        final int idx = indexInRegion(bx, by, bz, x, y, z);
        return idx >= 0 ? field.level(idx) : world.levelAt(x, y, z);
    }

    private static int kindAt(final WaterLevelField field, final FluidCellView cellView,
                              final int bx, final int by, final int bz,
                              final int x, final int y, final int z) {
        final int idx = indexInRegion(bx, by, bz, x, y, z);
        return idx >= 0 ? field.kind(idx) : cellView.kindAt(x, y, z);
    }

    /**
     * 目标水位（与 {@code level * area} 同尺度的**分子**）：{@code clamp(H*_num − y*8*area, 0, 8*area)}。
     *
     * <p>整格水位乘上 area 就是分子形式的 level，两者相减即「超出量」的整数形式。
     * 余数留在 H*_num 里 ⇒ 这一比较不再被整除截断（旧写法 {@code level − clamp(H* − 8y, 0, 8)}
     * 里的 H* 是整除后的整数，余数早在堆叠那一步就丢了）。
     *
     * @param surfaceNum 该片水的 H*_num
     * @param area       该片水的分母（0 = 这一格没归片 ⇒ target 视为 0，与旧的「水面 0」等价）
     */
    private static int targetNum(final int surfaceNum, final int area, final int y) {
        final int t = surfaceNum - y * WaterLevelField.MAX_LEVEL * area;
        if (t <= 0) {
            return 0;
        }
        final int cap = WaterLevelField.MAX_LEVEL * area;
        return t < cap ? t : cap;
    }
}