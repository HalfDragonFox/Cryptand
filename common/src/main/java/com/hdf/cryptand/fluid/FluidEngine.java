package com.hdf.cryptand.fluid;

import java.util.Arrays;
import java.util.Objects;

/**
 * 液体物理引擎（纯 Java、零 MC、零 region 概念）。
 *
 * <p><b>数据流</b>：转接类把世界拼成一整片液体（{@link FluidBodyView}）→ 本引擎算一步 →
 * {@link FluidDelta}（只记变了的格，值是新量）→ 转接类按区块批量写回世界。
 *
 * <p><b>本引擎只认识两件事</b>：世界坐标（{@link FluidBodyView#pack} 打包的格）与格之间的关系
 * （由 6 邻居邻接表表达的连通性）。region / chunk / 方块类型都不出现 ——
 * 片可以任意跨越区块边界。
 *
 * <p><b>一步 = 整体塌落 + 重力 + 分层压力场</b>（重力 / 压力规则逐条搬自旧 {@code SpreadSolver} 的
 * {@code stepCell} 重力分支与 {@code equalizeLines}）：
 * <ol>
 *   <li><b>整体塌落</b>（前置阶段，用户 2026-09-29）：悬空水体（下方撑不住的水格按 6 连通成块）
 *       <b>整块下落到最低可达高度</b> —— 一次就落到被地形 / 已落定的液体 / 未加载挡住为止，
 *       各格相对量分布不变（保持形状）。它是纯搬位置 ⇒ 总量逐单位不变；
 *       落定后才交给后面的阶段摊平。恒定水源不参与（它位置不动，它上方的水由它撑住）。</li>
 *   <li><b>重力</b>（单趟）：一格的水一次最多往下搬一格（源格留 1 单位）；
 *       它负责「悬空水下落」，压力场不负责这件事（压力场的容器判据要求下方停得住）。</li>
 *   <li><b>分层压力场</b>：每个连通体（flood fill，容器判据含「正上方格无条件算容器」）
 *       把总水量按重力在各层堆一遍，得到平衡水面 H*（分子/分母两半，绝不在中途整除 ——
 *       整除会把余数丢掉，水面被截断到层底，整片 target 归零、水永远铺不开）。</li>
 *   <li><b>超额扩散 + 链式推挤</b>：目标水位 target = clamp(H* − 8y, 0, 8)；
 *       超出目标的格把 1 单位让给「超出量最低」的相邻格（平手序 = 超出量 → 到最近缺口的
 *       轴向距离 → 格位高低 → 实时水头）；目标满格时沿同方向继续往前找第一个接得下的格
 *       （横向穿满格通道、竖向把水柱顶起来用的是同一条链）。
 *       <b>一次搬运只改两个格</b>：源格 −1、终点 +1 —— 链上的中间格只是查找时「走过去」的
 *       通道，它们的量在这次搬运里一点也不变（引擎每次只搬 1 单位，搬运没有中间格参与；
 *       中间格之所以不会超容量，是因为走到终点才落这一单位）。</li>
 *   <li><b>自由水面末端</b>：水位 ≤ 1 且已经在水面上的格只准顺着重力往下让，不再横向扩散
 *       （没有它，薄薄一层水会在两格之间无限滑动）。</li>
 * </ol>
 *
 * <p><b>整格容器（waterlogged / 含水方块）</b>：{@link FluidKind#fullCubeOnly()} 的格
 * <b>只接受整格</b>（0 或满格），中间档一律不许（写 1..7 级水位在世界里看不见 ⇒ 必然丢水/产水）。
 * 所有注入路径都走同一个 {@link #canReceive(FluidKind, int, int, int)} 判定；这类格也
 * <b>不让出</b>水（原版语义：含水方块里的水不会流出去）—— 两条合起来保证它的量恒为 0 或满格。
 *
 * <p><b>输入校验</b>：kind 一律不许为 null（入口抛 {@link IllegalArgumentException}，热路径不再逐处兜底）；
 * 容量与水量在入口被消毒到 {@code [0, fill]}，出口写回前再断言一次
 * （绝不让超容量水位写回世界）。
 *
 * <p><b>复用与性能</b>：所有缓冲放在每线程一份的 {@link Scratch} 里
 * （照旧 {@code SpreadSolver} 的 Scratch 模式：实机是异步多线程求解），一步之内
 * 不再新建数组、不再装箱（邻接表用开放寻址 long→int 表）；遍历顺序用<b>一次</b>基数排序
 * （键 = 12 位 y | 26 位 x | 26 位 z）+ 层块倒拼得到，不再每步两次归并排序。
 *
 * <p><b>节奏与收敛</b>：{@link #step} 一次调用 = <b>一次 sweep</b>（2026-09-29 用户口径：
 * 一轮推进一格，形态上看得见「一格一格往前推」）。收敛不由引擎判定 —— 引擎只保证
 * 「返回 0 ⇔ 整片再也动不了」；驱动层看写回计划是否为空来决定还要不要再排一轮。
 * 引擎<b>不会</b>在内部循环到不动点，也不会因为「还在变」就置未完成标记。
 *
 * <p><b>守恒</b>：每一单位水要么还在原格、要么记在 {@link FluidDelta} 里；引擎不改输入视图。
 * 唯一的例外是恒定水源（{@link FluidBodyView#source}）：让出后立刻补满，这类片的总量会增长
 * （海洋/河流的语义）。重力路径与链式路径都会补。
 *
 * <p><b>恒定水源的三条语义</b>（用户 2026-09-29 定案）：
 * <ol>
 *   <li><b>位置永不被搬运</b>：整体塌落不算它（它是一块的支撑 —— 它上方的水由它撑住）；
 *       任何阶段都不许把它当普通格搬空。</li>
 *   <li><b>水位始终保持满格</b>：入口就补满，让出去的份立刻补回（凭空产水 = 守恒的唯一例外）。</li>
 *   <li><b>供水方向 = 下 + 四水平（5 个），不给正上方</b>：源格沿 +Y 的<b>直接</b>供水被关掉；
 *       <b>其它格</b>的压力顶升（连通器抬升、水柱上顶、链式推挤向上）一点也不受影响 ——
 *       源格把水交给满格邻格后，那一格自己往上顶不受这条限制。</li>
 * </ol>
 */
public final class FluidEngine {

    /** 六个轴向邻居（顺序 +x / -x / +z / -z / +y / -y，与旧实现一致：全平手时 +x 优先）。 */
    private static final int[][] AXIAL = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

    /** 向上（AXIAL 第 5 项）。 */
    private static final int DIR_UP = 4;
    /** 向下（AXIAL 第 6 项）。 */
    private static final int DIR_DOWN = 5;

    /** 链式推挤最多推多少跳。 */
    private static final int MAX_CHAIN_HOPS = 64;
    /**
     * 稠密盒索引的体积上限（格数）。超过就退回哈希路 —— 盒是每线程长期占用的一份 int[]，
     * 不能让一片稀疏的大片把内存顶爆（(1<<20) 格 = 4 MB / 线程）。
     */
    private static final int MAX_BOX_CELLS = 1 << 20;
    /**
     * sweep token 回绕阈值：到达就把所有用 token 的数组整体清零。
     *
     * <p>token 记在 {@link Scratch} 上、<b>跨 step 持久</b>（标记数组是复用的，每次 step 从 1
     * 重来必然与上一 step 的残留碰撞）⇒ 这个回绕是真会走到的路径，不是死代码。
     */
    private static final int TOKEN_LIMIT = 0x200000;

    /** 每线程一份的复用缓冲（照旧 {@code SpreadSolver} 的 Scratch 模式）。 */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * <b>仅供闸门</b>：强制关掉稠密盒、走稀疏哈希路。
     *
     * <p>两条路（稠密盒 / 开放寻址表）都是正经实现，判据在 {@link Run} 构造里 —— 但生产片
     * 永远命中稠密路，稀疏路会因此完全没有回归覆盖。这个包内开关让闸门对同一个片把两条路各跑
     * 一步、逐格对拍（见 {@code FluidEngineSelfTest#testDenseSparseEquivalent}）。
     * <b>真机永远不设置它</b>（默认 false，且没有对外的设置入口）。
     */
    static boolean forceSparse;

    private FluidEngine() {
    }

    /**
     * 「自由水面末端」：水位 ≤ 1 的薄层。
     *
     * <p>★ 全引擎唯一的末端判据 —— 重力（源格留 1）与水平 sweep（stub）必须共用它，
     * 否则两条路对「末端」的理解分叉：一条让它整体下沉、另一条还让它横向分水，
     * 同一格就会在两轮之间来回换向（实机抽搐）。
     */
    private static boolean isFreeSurfaceEnd(final int amount) {
        return amount <= 1;
    }

    /**
     * 算一步（= 一次 sweep，不限预算）。
     *
     * @return 本步的搬运次数；<b>返回 0 ⇔ 整片再也动不了</b>（此时 {@code out} 必为空）
     */
    public static int step(final FluidBodyView body, final FluidDelta out) {
        return step(body, out, Integer.MAX_VALUE);
    }

    /**
     * 算一步，最多搬运 {@code transferBudget} 次左右（分片迭代用）。
     *
     * <p><b>最小分片单位是「一个完整 sweep」</b>：sweep 内部绝不因为预算而截断 ——
     * 截断会让一轮对称搬运只做了一半（实测：10 格沟槽的水在 <code>2 3 3 0…</code> 与
     * <code>3 2 3 0…</code> 之间来回搬，500 轮都不收敛，因为「谁给谁」的平手判定按
     * 遍历顺序走完一整轮才有意义）。所以这一步会跑完整数 sweep、直到累计搬运量 ≥ budget
     * 才停；单个 sweep 的搬运量不受限，返回值因此可能略大于 budget。
     *
     * <p>片很大、一轮算不完时调用方给一个小预算：这一步只搬这么多，写回后下一轮拿
     * 「更新过的世界」重新拼片、再调一次，剩下的自然接着来（引擎是无状态的，
     * 跨轮状态在世界里，不在引擎里）。
     *
     * <p><b>「没算完」与「动不了」必须分开</b>：预算 ≤ 0、预算中途用尽、或打满 sweep 护栏时，
     * 本方法会在 {@code out} 上置「未完成」标记（{@link FluidDelta#markUnfinished()}）。
     * 驱动层的收敛判据因此只能是「<b>返回 0 且 {@code out.unfinished() == false}</b>」——
     * 只看返回值 0 会把「预算为 0」误判成不动点，水会永久停住。不限预算的重载永远不带这个标记，
     * 于是它保持老语义：<b>返回 0 ⇔ 整片再也动不了</b>。
     *
     * <p>{@code out} 在入口被 {@link FluidDelta#clear()}：同一个 delta 可以连着给多步用，
     * 里面只会剩本步的输出（不会串味）。
     *
     * @return 本步的搬运次数；返回 0 时看 {@link FluidDelta#unfinished()} 才知道是不是真的不动了
     */
    public static int step(final FluidBodyView body, final FluidDelta out, final int transferBudget) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(out, "out");
        out.clear();                                        // ★ 复用同一个 delta 连跑多步不串味
        final FluidKind fluid = findFluid(body);            // ★ 入口统一校验 kind（null 直接非法）
        if (fluid == null) {
            return 0;                                       // 这一片没有液体 ⇒ 真的无事可做
        }
        if (transferBudget <= 0) {
            out.markUnfinished();                           // ★ 预算用尽 ≠ 动不了
            return 0;
        }
        return new Run(SCRATCH.get(), body, fluid, out, transferBudget).run();
    }

    /**
     * 这一格（容量 {@code capacity}）现在有 {@code current} 单位，能不能再接收 {@code incoming} 单位。
     *
     * <p><b>所有注入路径（重力 / 超额扩散 / 链式推挤）统一走这一个判定</b>：
     * <ul>
     *   <li>装得下：{@code current + incoming ≤ capacity}；</li>
     *   <li>{@link FluidKind#fullCubeOnly()} 的格只接受整格 ⇒ 结果只能是 0 或 capacity，
     *       中间档（1..capacity−1）一律不许（写进世界也看不见 ⇒ 丢水/产水）。</li>
     * </ul>
     *
     * @param capacity 该格消毒后的容量（{@code [0, fill]}，见 {@code Run} 构造）
     */
    static boolean canReceive(final FluidKind kind, final int capacity, final int current, final int incoming) {
        if (incoming <= 0) {
            return false;
        }
        final int after = current + incoming;
        if (after > capacity) {
            return false;
        }
        if (kind.fullCubeOnly()) {
            return after == capacity;
        }
        return true;
    }

    /** 本片液体 = 第一个「有量的液体格」的 kind（片内液体同 kind，引擎按 kind 分片）。 */
    private static FluidKind findFluid(final FluidBodyView body) {
        final int n = Math.max(0, body.size());
        for (int i = 0; i < n; i++) {
            final FluidKind k = kindOf(body, i);
            if (k.isLiquid() && body.amount(i) > 0) {
                return k;
            }
        }
        return null;
    }

    /** 片内格 kind：null 一律非法（全文件唯一的口径，热路径不再兜底）。 */
    private static FluidKind kindOf(final FluidBodyView body, final int i) {
        final FluidKind k = body.kind(i);
        if (k == null) {
            throw new IllegalArgumentException("片内格 " + i + " 的 kind 为 null（kind 一律不许为 null）");
        }
        return k;
    }

    // ---------- 每线程复用的缓冲 ----------

    /**
     * 一步之内用到的全部数组，<b>每线程一份、跨 step 复用</b>（大小按片长只增不减）。
     *
     * <p>标记类数组（{@code marks} / {@code walk} / {@code touchMark}）用 token 比较，
     * token 记在这里并跨 step 递增 —— 每次 step 从 1 重来会与上一 step 的残留碰撞
     * （链式推挤误判「这一格走过」⇒ 一次都不搬 ⇒ 驱动端按「返回 0」判收敛 ⇒ 水永久停住）。
     */
    private static final class Scratch {

        /** 已分配的元素个数（所有按格分配的数组都是这个长度）。 */
        int allocated;

        long[] pos = new long[16];
        FluidKind[] kind = new FluidKind[16];
        int[] amt = new int[16];
        int[] initial = new int[16];
        int[] cap = new int[16];
        boolean[] src = new boolean[16];
        int[] adj = new int[16 * 6];

        long[] surfaceNum = new long[16];
        int[] surfaceArea = new int[16];
        int[] grouped = new int[16];
        int[] gapDist = new int[16];
        int[] marks = new int[16];
        int[] walk = new int[16];
        int[] touchMark = new int[16];
        int[] touched = new int[16];
        int[] netDelta = new int[16];
        int[] queue = new int[16];
        int[] bfsQueue = new int[16];

        /** 整体塌落用（与其它阶段错开借用）：下方撑不撑得住 / 本块的格标记 / 目标格 / 待落量。 */
        boolean[] grounded = new boolean[16];
        int[] fallSeen = new int[16];
        int[] fallDest = new int[16];
        int[] fallAmt = new int[16];

        int[] orderAsc = new int[16];
        int[] orderDesc = new int[16];
        long[] sortKey = new long[16];
        int[] sortTmp = new int[16];
        final int[] bucket = new int[256];
        int[] layerCells = new int[1];

        /** 世界坐标 → 格下标 的开放寻址表（无装箱）：val 存 index + 1，0 = 空槽。 */
        long[] mapKey = new long[16];
        int[] mapVal = new int[16];
        int mapMask = 15;

        /** 稠密盒索引（布局命中稠密判据时用；稀疏片不用它）。 */
        int[] box = new int[0];

        /** 链式推挤「这一条链走过哪些格」的临时清单（走完立刻清零标记，见 {@code sweepOnce}）。 */
        final int[] chainWalk = new int[MAX_CHAIN_HOPS + 2];

        /** sweep token（跨 step 持久；见类注释）。 */
        int sweepToken;

        /** 整体塌落的块标记 token（跨 step 持久，理由同 {@link #sweepToken}）。 */
        int fallToken;

        /** 把按格分配的数组扩到至少 {@code total} 个（重新分配 = 全 0，标记数组因此天然干净）。 */
        void ensureCells(final int total) {
            if (total <= allocated) {
                return;
            }
            int c = Math.max(16, allocated);
            while (c < total) {
                c <<= 1;
            }
            allocated = c;
            pos = new long[c];
            kind = new FluidKind[c];
            amt = new int[c];
            initial = new int[c];
            cap = new int[c];
            src = new boolean[c];
            adj = new int[c * 6];
            surfaceNum = new long[c];
            surfaceArea = new int[c];
            grouped = new int[c];
            gapDist = new int[c];
            marks = new int[c];
            walk = new int[c];
            touchMark = new int[c];
            touched = new int[c];
            netDelta = new int[c];
            queue = new int[c];
            bfsQueue = new int[c];
            grounded = new boolean[c];
            fallSeen = new int[c];
            fallDest = new int[c];
            fallAmt = new int[c];
            orderAsc = new int[c];
            orderDesc = new int[c];
            sortKey = new long[c];
            sortTmp = new int[c];
        }

        /** 稠密盒按体积分配（只增不减；内容每次重建前必须整片填 -1）。 */
        void ensureBox(final int cells) {
            if (cells > box.length) {
                box = new int[cells];
            }
        }

        /** 层计数数组按片高层数分配（只有它按「层数」而不是「格数」算大小）。 */
        void ensureLayers(final int height) {
            if (height > layerCells.length) {
                layerCells = new int[height];
            }
        }

        /** 建表：容量取 ≥ 2×need 的 2 的幂（负载 ≤ 0.5，线性探测平均 1.5 次以内）；复用旧表时只清 val。 */
        void mapBegin(final int need) {
            int c = 16;
            while (c < need * 2) {
                c <<= 1;
            }
            if (mapKey.length < c) {
                mapKey = new long[c];
                mapVal = new int[c];
            } else {
                Arrays.fill(mapVal, 0, c, 0);
            }
            mapMask = c - 1;
        }

        void mapPut(final long key, final int value) {
            int h = hash(key) & mapMask;
            while (true) {
                final int v = mapVal[h];
                if (v == 0) {
                    mapKey[h] = key;
                    mapVal[h] = value + 1;
                    return;
                }
                if (mapKey[h] == key) {
                    mapVal[h] = value + 1;
                    return;
                }
                h = (h + 1) & mapMask;
            }
        }

        void mapPutIfAbsent(final long key, final int value) {
            int h = hash(key) & mapMask;
            while (true) {
                final int v = mapVal[h];
                if (v == 0) {
                    mapKey[h] = key;
                    mapVal[h] = value + 1;
                    return;
                }
                if (mapKey[h] == key) {
                    return;
                }
                h = (h + 1) & mapMask;
            }
        }

        int mapGet(final long key) {
            int h = hash(key) & mapMask;
            while (true) {
                final int v = mapVal[h];
                if (v == 0) {
                    return -1;
                }
                if (mapKey[h] == key) {
                    return v - 1;
                }
                h = (h + 1) & mapMask;
            }
        }

        private static int hash(final long key) {
            long z = key * 0x9E3779B97F4A7C15L;
            z ^= z >>> 32;
            return (int) z;
        }
    }

    // ---------- 一次 step 的全部状态（缓冲借用 Scratch；算完即丢） ----------

    private static final class Run {

        private final Scratch s;
        private final FluidKind fluid;
        private final FluidDelta out;
        private final int budget;

        private final int n;
        private final int b;
        private final int total;
        private final int height;
        private final int minY;
        private final int minX;
        private final int minZ;

        private final long[] pos;
        private final FluidKind[] kind;
        private final int[] amt;
        private final int[] initial;
        private final int[] cap;
        private final boolean[] src;
        private final int[] adj;

        private final long[] surfaceNum;
        private final int[] surfaceArea;
        private final int[] grouped;
        private final int[] gapDist;
        private final int[] queue;
        private final int[] bfsQueue;
        private final int[] marks;
        private final int[] walk;
        private final int[] touchMark;
        private final int[] touched;
        private final int[] netDelta;
        private final int[] layerCells;
        private final boolean[] grounded;
        private final int[] fallSeen;
        private final int[] fallDest;
        private final int[] fallAmt;

        private int[] orderAsc;
        private int[] orderDesc;

        /**
         * 稠密盒索引（本步布局命中稠密判据时非 null）：盒内局部坐标 → 格下标（-1 = 这个坐标没有格）。
         *
         * <p>生产片是「一个 chunk section + 六面一圈边界」，bbox 密度接近 100% ⇒ 邻接查表
         * 从「每格 6 次哈希探测」降成「一次数组寻址」，遍历序也可以直接三重循环生成（省整趟基数排序）。
         * 稀疏 / 跨 region 拉长的片照旧走 {@link Scratch} 的开放寻址表。
         */
        private final int[] box;
        /** 本步是否走稠密盒（判据见 {@link #denseEligible}）。 */
        private final boolean dense;
        /** 盒在世界坐标下的原点与三边长度。 */
        private final int boxMinX;
        private final int boxMinY;
        private final int boxMinZ;
        private final int boxDx;
        private final int boxDy;
        private final int boxDz;

        private int bfsUsed;
        private int moved;
        /** 入口给恒定水源补满的量（照常算进搬运次数：一个字都没变就不可能 > 0）。 */
        private int sourceFilled;
        private boolean outOfBudget;

        Run(final Scratch s, final FluidBodyView body, final FluidKind fluid,
            final FluidDelta out, final int budget) {
            this.s = s;
            this.fluid = fluid;
            this.out = out;
            this.budget = budget;
            this.n = Math.max(0, body.size());
            final int b = Math.max(0, body.borderSize());
            this.b = b;
            this.total = n + b;
            s.ensureCells(total);

            this.pos = s.pos;
            this.kind = s.kind;
            this.amt = s.amt;
            this.initial = s.initial;
            this.cap = s.cap;
            this.src = s.src;
            this.adj = s.adj;
            this.surfaceNum = s.surfaceNum;
            this.surfaceArea = s.surfaceArea;
            this.grouped = s.grouped;
            this.gapDist = s.gapDist;
            this.queue = s.queue;
            this.bfsQueue = s.bfsQueue;
            this.marks = s.marks;
            this.walk = s.walk;
            this.touchMark = s.touchMark;
            this.touched = s.touched;
            this.netDelta = s.netDelta;
            this.grounded = s.grounded;
            this.fallSeen = s.fallSeen;
            this.fallDest = s.fallDest;
            this.fallAmt = s.fallAmt;

            final long tScan = FluidPhase.start();
            // 取数走批量快路（每格一次接口调用都不再发生）⇒ 下面这一趟只有数组访问与消毒。
            body.copyCells(0, n, fluid, pos, kind, amt, cap, src, 0);
            int loY = Integer.MAX_VALUE;
            int hiY = Integer.MIN_VALUE;
            int loX = Integer.MAX_VALUE;
            int hiX = Integer.MIN_VALUE;
            int loZ = Integer.MAX_VALUE;
            int hiZ = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                final FluidKind k = kind[i];
                if (k == null) {
                    throw new IllegalArgumentException("片内格 " + i + " 的 kind 为 null（kind 一律不许为 null）");
                }
                // ★ B4：调用方报的容量先消毒到 [0, fill]；装不下本液体的格一律 0
                //   （同种液体格也走这一条：它的上报值本来就是 fill，消毒是幂等的）
                final int c = k.accepts(fluid) ? Math.min(Math.max(0, cap[i]), fluid.fill()) : 0;
                int a = amt[i];
                if (a < 0) {
                    a = 0;
                } else if (a > c) {
                    a = c;                                  // ★ B4：入口 clamp 到 [0, capacity]
                }
                cap[i] = c;
                amt[i] = a;
                surfaceNum[i] = 0L;                         // 复用数组：本步的初值
                surfaceArea[i] = 0;
                grouped[i] = 0;
                gapDist[i] = -1;
                final long p = pos[i];
                final int x = FluidBodyView.unpackX(p);
                final int y = FluidBodyView.unpackY(p);
                final int z = FluidBodyView.unpackZ(p);
                if (y < loY) {
                    loY = y;
                }
                if (y > hiY) {
                    hiY = y;
                }
                if (x < loX) {
                    loX = x;
                }
                if (x > hiX) {
                    hiX = x;
                }
                if (z < loZ) {
                    loZ = z;
                }
                if (z > hiZ) {
                    hiZ = z;
                }
            }
            body.copyBorders(0, b, pos, kind, amt, n);
            for (int j = 0; j < b; j++) {
                final int i = n + j;
                final FluidKind k = kind[i];
                if (k == null) {
                    throw new IllegalArgumentException("边界格 " + j + " 的 kind 为 null（kind 一律不许为 null）");
                }
                final int c = k.accepts(fluid)
                        ? Math.min(Math.max(0, k.capacityFor(fluid)), fluid.fill()) : 0;
                int a = amt[i];
                if (a < 0) {
                    a = 0;
                } else if (a > c) {
                    a = c;
                }
                cap[i] = c;
                amt[i] = a;
                surfaceNum[i] = 0L;
                surfaceArea[i] = 0;
                final long p = pos[i];
                final int x = FluidBodyView.unpackX(p);
                final int y = FluidBodyView.unpackY(p);
                final int z = FluidBodyView.unpackZ(p);
                if (y < loY) {
                    loY = y;
                }
                if (y > hiY) {
                    hiY = y;
                }
                if (x < loX) {
                    loX = x;
                }
                if (x > hiX) {
                    hiX = x;
                }
                if (z < loZ) {
                    loZ = z;
                }
                if (z > hiZ) {
                    hiZ = z;
                }
            }
            FluidPhase.end(FluidPhase.SCAN, tScan);
            this.minY = loY == Integer.MAX_VALUE ? 0 : loY;
            this.minX = loX == Integer.MAX_VALUE ? 0 : loX;
            this.minZ = loZ == Integer.MAX_VALUE ? 0 : loZ;
            this.height = hiY == Integer.MIN_VALUE ? 1 : hiY - this.minY + 1;
            s.ensureLayers(height);
            this.layerCells = s.layerCells;

            // ---- 稠密盒判据：bbox 体积 ≤ 片格数的 4 倍、且不超内存上限 ⇒ 走数组寻址 ----
            //   生产片（一个 section + 一圈边界）密度接近 100% ⇒ 必中；被拉长的跨 region 片退回哈希。
            this.boxMinX = this.minX;
            this.boxMinY = this.minY;
            this.boxMinZ = this.minZ;
            this.boxDx = (hiX == Integer.MIN_VALUE ? this.minX : hiX) - this.minX + 1;
            this.boxDy = this.height;
            this.boxDz = (hiZ == Integer.MIN_VALUE ? this.minZ : hiZ) - this.minZ + 1;
            final long boxCells = (long) this.boxDx * this.boxDy * this.boxDz;
            this.dense = !forceSparse && total > 0 && boxCells <= 4L * total && boxCells <= MAX_BOX_CELLS;
            if (dense) {
                s.ensureBox((int) boxCells);
                this.box = s.box;
                Arrays.fill(this.box, 0, (int) boxCells, -1);
                for (int i = 0; i < n; i++) {
                    this.box[boxIndex(pos[i])] = i;
                }
                for (int j = 0; j < b; j++) {
                    final int at = boxIndex(pos[n + j]);
                    if (this.box[at] < 0) {
                        this.box[at] = n + j;                   // 片内格优先（等价于哈希路的 mapPutIfAbsent）
                    }
                }
            } else {
                this.box = null;
            }

            System.arraycopy(s.amt, 0, s.initial, 0, total);
            final long tAdj = FluidPhase.start();
            buildAdjacency(this);
            FluidPhase.end(FluidPhase.ADJACENCY, tAdj);
            final long tOrder = FluidPhase.start();
            buildOrders(this);
            FluidPhase.end(FluidPhase.ORDER, tOrder);
            this.orderAsc = s.orderAsc;
            this.orderDesc = s.orderDesc;
        }

        int run() {
            long tPhase = FluidPhase.start();
            fillSources();                          // ★ 恒定水源：入口补满（水位始终保持在最高）
            FluidPhase.end(FluidPhase.SOURCE, tPhase);
            tPhase = FluidPhase.start();
            collapse();                             // ★ 悬空水体整块下落（保持形状）
            FluidPhase.end(FluidPhase.COLLAPSE, tPhase);
            if (!outOfBudget) {
                tPhase = FluidPhase.start();
                gravity();
                FluidPhase.end(FluidPhase.GRAVITY, tPhase);
            }
            if (!outOfBudget) {
                tPhase = FluidPhase.start();
                pressureField();
                FluidPhase.end(FluidPhase.PRESSURE, tPhase);
            }
            if (!outOfBudget) {
                sweeps();
            }
            if (outOfBudget) {
                out.markUnfinished();           // ★ 预算/护栏中断 ⇒ 没算完，驱动层不准据此判收敛
            }
            return flush();
        }

        // ---------- 0) 恒定水源：位置不动 + 水位始终保持满格 ----------

        /**
         * 恒定水源入口补满：水位始终保持在最高（满格）。
         *
         * <p>{@link FluidBodyView#source} 的语义是「让出去立刻补满」，这里把它推广到入口 ——
         * 无论谁拼片时把水源格报成多少，引擎眼里它一律是满格（先补满再算，后面的阶段就不会
         * 基于一个半空的水源去算水面）。补出来的量照常记进输出（{@link #flush()} 把它算进搬运
         * 次数），否则「这一格的新量」永远写不回世界。水源是守恒的唯一例外 —— 它凭空产水。
         */
        private void fillSources() {
            for (int i = 0; i < n; i++) {
                if (src[i] && amt[i] < cap[i]) {
                    amt[i] = cap[i];
                    sourceFilled++;
                }
            }
        }

        // ---------- 0b) 整体塌落：悬空水体整块下落（用户 2026-09-29 新语义） ----------

        /**
         * 悬空水体<b>整块下落</b>（前置阶段，先于重力 / 压力场 / 超额扩散）。
         *
         * <p>把「下方撑不住」的水格按 6 连通分组，每块<b>整体平移到最低可达高度</b>：一次落到
         * 被地形、已落定的液体、未加载（片外）挡住为止，<b>各格相对量分布不变</b>（保持形状）。
         * 落定之后照旧交给重力 / 压力场 / 超额扩散去摊平。
         *
         * <p><b>守恒</b>：整块下移只是把量换个位置（目标格必是片内空格）⇒ 总量逐单位精确不变。
         * <b>恒定水源</b>不参与：水源格永远算「撑得住」（位置不动），它正上方的水由它撑住；
         * 同一块里别的悬空格照常落（水源被排除在块外，不会因为块里有水源就整体不动）。
         *
         * <p><b>落点</b>：只落进片内格（已加载、能容纳本液体、当前为空、且容量装得下整格
         * —— 保证不会写出超容量水位）。片外 / 未加载 / 地形 / 已有液体都算挡住：
         * 「落到地形或水面上就停」，不穿透、不合并（合并交给后面的压力场）。
         *
         * <p><b>不动点仍成立</b>：塌落只看当前布局（确定性），且只在真的有格换位置时才搬
         * （一块的可落高度为 0 就一个都不动）；布局没变 ⇒ 下一步还是同一布局。
         *
         * <p><b>不分配</b>：缓冲全部在 {@link Scratch} 里复用（本块的 BFS 借用 {@code queue}；
         * 这一阶段跑在 sweep 之前 ⇒ 不与缺口距离场抢缓冲）。
         */
        private void collapse() {
            // (a) grounded[i]：这一格下面撑不撑得住。按 y 升序算 —— 下方格的结论已经算好。
            for (int oi = 0; oi < n; oi++) {
                final int i = orderAsc[oi];
                if (src[i]) {
                    grounded[i] = true;                 // 恒定水源：位置不动（它上方的水由它撑住）
                    continue;
                }
                final int b = adj[i * 6 + DIR_DOWN];
                if (b < 0 || !kind[b].accepts(fluid)) {
                    grounded[i] = true;                 // 未加载 / 地形：撑得住
                } else if (amt[b] > 0) {
                    // 下面有水：同种液体与它共命运（它掉我也掉）；别的介质（边界格 / 含水方块）算撑得住
                    grounded[i] = b >= n || kind[b] != fluid ? true : grounded[b];
                } else {
                    grounded[i] = false;                // 下面是能落进去的空格：撑不住
                }
            }
            if (++s.fallToken >= TOKEN_LIMIT) {
                Arrays.fill(fallSeen, 0);
                s.fallToken = 1;
            }
            final int seen = s.fallToken;
            // (b) 连通块整体下移。按 y 升序处理 ⇒ 先搬低的块；高的块随后能看到低块的新位置，
            //     于是「上面的块落到下面的块身上」也是一步到位（不用等下一轮）。
            for (int oi = 0; oi < n; oi++) {
                final int start = orderAsc[oi];
                if (grounded[start] || fallSeen[start] == seen || kind[start] != fluid
                        || amt[start] <= 0 || src[start]) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                queue[tail++] = start;
                fallSeen[start] = seen;
                while (head < tail) {
                    final int cur = queue[head++];
                    for (int d = 0; d < 6; d++) {
                        final int nb = adj[cur * 6 + d];
                        if (nb < 0 || nb >= n || fallSeen[nb] == seen) {
                            continue;                   // 片外 / 未加载 / 已经在块里
                        }
                        if (grounded[nb] || kind[nb] != fluid || amt[nb] <= 0 || src[nb]) {
                            continue;                   // 撑得住的 / 不是本液体 / 空的 / 水源：不进块
                        }
                        fallSeen[nb] = seen;
                        queue[tail++] = nb;
                    }
                }
                // 整块共同可落高度 = 各格「向下连续可走格数」的最小值。
                //   本块的格随块一起走 ⇒ 也算可走；别的有量格 / 地形 / 未加载 / 片外 / 装不下整格的格都挡。
                int drop = Integer.MAX_VALUE;
                for (int q = 0; q < tail && drop > 0; q++) {
                    final int c = queue[q];
                    final int cx = FluidBodyView.unpackX(pos[c]);
                    final int cy = FluidBodyView.unpackY(pos[c]);
                    final int cz = FluidBodyView.unpackZ(pos[c]);
                    int d = 0;
                    for (int yy = cy - 1; ; yy--) {
                        final int b = cellAt(cx, yy, cz);
                        if (b < 0 || b >= n) {
                            break;                      // 未加载 / 片外 / 边界格：到此为止
                        }
                        if (fallSeen[b] == seen) {
                            d++;                        // 本块的格：随块一起走
                            continue;
                        }
                        if (amt[b] > 0 || !kind[b].accepts(fluid) || kind[b].fullCubeOnly()
                                || cap[b] < fluid.fill()) {
                            break;                      // 已有液体 / 装不下 / 只收整格：挡住
                        }
                        d++;
                    }
                    if (d < drop) {
                        drop = d;
                    }
                }
                if (drop <= 0) {
                    continue;                           // 落不动：一个都不动（不动点判据的一部分）
                }
                // 先把这一块的量取下来，再整体落到新位置 —— 目标格可能就是本块别的格，
                // 所以必须先整块清零（每个目标格只会被写一次，块的映射是单射）。
                for (int q = 0; q < tail; q++) {
                    final int c = queue[q];
                    fallAmt[q] = amt[c];
                    fallDest[q] = cellAt(FluidBodyView.unpackX(pos[c]),
                            FluidBodyView.unpackY(pos[c]) - drop, FluidBodyView.unpackZ(pos[c]));
                    amt[c] = 0;
                }
                for (int q = 0; q < tail; q++) {
                    amt[fallDest[q]] = fallAmt[q];
                }
                moved += tail;                          // 整块搬运：逐格算一次搬运（照旧进预算）
            }
            if (moved >= budget) {
                outOfBudget = true;                     // 与 sweep 一样：分片只停在阶段边界
            }
        }

        // ---------- 1) 重力（单趟：一格的水一次只往下走一格） ----------

        private void gravity() {
            for (int oi = 0; oi < n; oi++) {
                final int i = orderAsc[oi];
                if (amt[i] <= 0 || kind[i] != fluid) {
                    continue;                                       // ★ B5：同种液体才可搬（混片不搬岩浆）
                }
                final int down = adj[i * 6 + DIR_DOWN];
                if (down < 0 || !kind[down].accepts(fluid)) {
                    continue;
                }
                final int room = cap[down] - amt[down];
                if (room <= 0) {
                    continue;
                }
                int move = Math.min(amt[i], room);
                if (move == amt[i] && !isFreeSurfaceEnd(amt[i])) {
                    move--;                     // 源格留 1（水柱下泄时源头留一层薄膜，水柱不会断头）
                }
                if (move <= 0) {
                    continue;
                }
                if (!canReceive(kind[down], cap[down], amt[down], move)) {
                    continue;                   // ★ B2：装不下 / 只接受整格却没正好填满 ⇒ 一点都不放
                }
                amt[i] -= move;
                amt[down] += move;
                if (src[i]) {
                    amt[i] = cap[i];            // ★ B6：恒定水源让出后立刻补满（两级路径都补）
                }
                moved++;
                if (moved >= budget) {
                    outOfBudget = true;
                    return;
                }
            }
        }

        // ---------- 2) 分层压力场 ----------

        private void pressureField() {
            int groupToken = 0;
            for (int oi = 0; oi < n; oi++) {
                final int start = orderAsc[oi];
                if (amt[start] <= 0 || grouped[start] != 0) {
                    continue;
                }
                groupToken++;
                Arrays.fill(layerCells, 0, height, 0);
                int head = 0;
                int tail = 0;
                long water = 0;
                int groupMinY = Integer.MAX_VALUE;
                int groupMaxY = Integer.MIN_VALUE;
                queue[tail++] = start;
                grouped[start] = groupToken;
                while (head < tail) {
                    final int cur = queue[head++];
                    water += amt[cur];
                    final int cy = FluidBodyView.unpackY(pos[cur]);
                    if (cy < groupMinY) {
                        groupMinY = cy;
                    }
                    if (cy > groupMaxY) {
                        groupMaxY = cy;
                    }
                    layerCells[cy - minY]++;
                    for (int d = 0; d < 6; d++) {
                        final int nb = adj[cur * 6 + d];
                        if (nb < 0 || nb >= n) {
                            continue;           // 只在片内扩展：边界格不属于这片水
                        }
                        if (grouped[nb] != 0) {
                            continue;
                        }
                        if (!kind[nb].accepts(fluid)) {
                            continue;
                        }
                        // ★ 不顶升（2026-09-29 用户口径）：正上方不再无条件算容器 —— 水只走
                        //   「下 + 四水平」。被活塞 / 方块挤出来的水由 bridge.displaceWater 直接
                        //   塞进邻近格（含上方），下一轮它作为水格被阶段 1 采到，不必在这里
                        //   为空竖井预留容器。
                        final int below = adj[nb * 6 + DIR_DOWN];
                        if (below >= 0 && kind[below].accepts(fluid) && amt[below] <= 0) {
                            continue;
                        }
                        grouped[nb] = groupToken;
                        queue[tail++] = nb;
                    }
                }
                if (water <= 0) {
                    continue;
                }
                // 按重力把这批水堆一遍：area = 水停住的那一层容器格数，H* 用**分子**表示
                //   H*_num = 相对层号 * 满格量 * area + 余量
                // 余量留在分子里 ⇒ 后面的目标水位比较不被整除截断（整除会把余数丢掉，
                // 8 单位 / 10 格 ⇒ 0 ⇒ 整片 target 归零 ⇒ level=1 的格被钉死、水铺不开）。
                long remaining = water;
                long surfaceNumValue = (long) (groupMaxY - minY + 1) * fluid.fill();
                int surfaceAreaValue = 1;
                for (int ly = groupMinY; ly <= groupMaxY; ly++) {
                    final int area = layerCells[ly - minY];
                    if (area == 0) {
                        continue;
                    }
                    final long layerCap = (long) area * fluid.fill();
                    if (remaining <= layerCap) {
                        surfaceNumValue = (long) (ly - minY) * fluid.fill() * area + remaining;
                        surfaceAreaValue = area;
                        break;
                    }
                    remaining -= layerCap;
                }
                for (int q = 0; q < tail; q++) {
                    final int cell = queue[q];
                    surfaceNum[cell] = surfaceNumValue;
                    surfaceArea[cell] = surfaceAreaValue;
                }
            }
        }

        // ---------- 3) sweep：缺口距离场 + 超额扩散 + 链式推挤 ----------

        private void sweeps() {
            if (++s.sweepToken >= TOKEN_LIMIT) {
                // ★ token 回绕必须把所有用 token 标记的数组一起清：
                //   漏掉 walk 就会让复用出来的旧值碰撞，链式推挤误判「这一格走过」
                //   ⇒ 一次都不搬 ⇒ 驱动端按「返回 0」判收敛 ⇒ 水永久停住。
                Arrays.fill(marks, 0);
                Arrays.fill(walk, 0);
                Arrays.fill(touchMark, 0);
                s.sweepToken = 1;
            }
            // ★ 一轮 = 一次 sweep（用户 2026-09-29 口径）：每 tick 只推进一格
            //   （剖面 5 4 3 2 1 → 4 3 2 1 1 那样逐轮推进），跑到收敛交给「多轮 + 写回唤醒」。
            //   ★ 因此「一次 sweep 后仍有变化」绝不算没算完：那正是正常节奏。
            //     置 outOfBudget 会让驱动层按 unfinished 整片重排 ⇒ 每轮重算同一片 ⇒ 抽搐。
            //   「不动了」由驱动层按写回变化集为空判定（flush 出的 plan 为空）。
            sweepOnce();
            if (moved >= budget) {
                outOfBudget = true;             // ★ 只有额度真的用尽才算「没算完」
            }
        }

        /** 一个 sweep；返回 false = 已经不动了（早停）。 */
        private boolean sweepOnce() {
            final int token = s.sweepToken;
            final int movedBefore = moved;

            // ---- 3a) 缺口距离场（多源 BFS，每个 sweep 重算） ----
            //   平手必须有方向信号：两个候选在 (超出量, 格位高低, 实时水头) 上完全一样时，
            //   退回 AXIAL 的固定顺序会固定偏向 +x；水源在右端时 +x 正是回源方向，
            //   水在两格之间来回搬、左边的真缺口永远填不上。
            final long tBfs = FluidPhase.start();
            for (int i = 0; i < bfsUsed; i++) {
                gapDist[bfsQueue[i]] = -1;
            }
            int bfsHead = 0;
            int bfsTail = 0;
            for (int oi = 0; oi < n; oi++) {
                final int c = orderAsc[oi];
                final int area = surfaceArea[c];
                if (area <= 0) {
                    continue;                       // 没归片（不是这片水的容器）⇒ 不是缺口
                }
                final long excess = (long) amt[c] * area
                        - targetNum(surfaceNum[c], area, FluidBodyView.unpackY(pos[c]) - minY);
                // 缺口 = 「能真的接住这 1 单位」的格：整格容器（waterlogged）的中间档不算缺口，
                // 否则水的方向信号会指向一个永远进不去的地方。
                if (excess < 0 && canReceive(kind[c], cap[c], amt[c], 1)) {
                    gapDist[c] = 0;
                    bfsQueue[bfsTail++] = c;
                }
            }
            while (bfsHead < bfsTail) {
                final int cur = bfsQueue[bfsHead++];
                final int nextDist = gapDist[cur] + 1;
                for (int d = 0; d < 6; d++) {
                    final int nb = adj[cur * 6 + d];
                    if (nb < 0 || nb >= n) {
                        continue;                   // 缺口距离只在片内传播
                    }
                    if (gapDist[nb] >= 0 || grouped[nb] != grouped[cur]) {
                        continue;                   // 已访问，或不属于同一片水
                    }
                    gapDist[nb] = nextDist;
                    bfsQueue[bfsTail++] = nb;
                }
            }
            bfsUsed = bfsTail;
            FluidPhase.end(FluidPhase.SWEEP_BFS, tBfs);

            // ---- 3b) 超额扩散 ----
            final long tScan = FluidPhase.start();
            int swept = 0;
            int touchedCount = 0;
            for (int oi = 0; oi < n; oi++) {
                final int i = orderDesc[oi];
                final int amount = amt[i];
                if (amount <= 0) {
                    continue;
                }
                final int area = surfaceArea[i];
                if (area <= 0) {
                    continue;
                }
                if (kind[i].fullCubeOnly()) {
                    continue;                       // ★ B2：整格容器不漏水（量恒为 0 或满格）
                }
                final int y = FluidBodyView.unpackY(pos[i]);
                final int relY = y - minY;
                final long exI = (long) amount * area - targetNum(surfaceNum[i], area, relY);
                if (exI <= 0) {
                    continue;                       // 没超过目标 ⇒ 平衡，不动
                }
                // 自由水面上的水位 ≤ 1 是末端：只能顺着重力往下掉，不再横向扩散（薄层不许无限滑动）。
                // ★ 与面积解耦：面积是压力场的产物，不该反过来决定「末端」；判据只有「水位 ≤ 1」。
                // ★ 与 gravity 共用 isFreeSurfaceEnd：两条路对「末端」的理解必须一致，否则同一格会被
                //   一条路判成该整体下沉、另一条路还让它横向分水 ⇒ 两轮来回换向（实机抽搐）。
                final boolean stub = isFreeSurfaceEnd(amount);

                int best = -1;
                long bestEx = Long.MAX_VALUE;
                int bestDist = Integer.MAX_VALUE;
                int bestNy = 0;
                long bestHead = Long.MAX_VALUE;
                for (int d = 0; d < 6; d++) {
                    if (d == DIR_UP) {
                        continue;                   // ★ 不顶升：水只走「下 + 四水平」（用户 2026-09-29 口径）
                    }
                    if (stub && d != DIR_DOWN) {
                        continue;                   // 自由水面末端（水位 ≤ 1）：只准落下
                    }
                    final int nb = adj[i * 6 + d];
                    if (nb < 0 || marks[nb] == token || !kind[nb].accepts(fluid)) {
                        continue;                   // 未加载 / 本 sweep 已填过 / 装不下
                    }
                    if (amt[nb] < cap[nb] && !canReceive(kind[nb], cap[nb], amt[nb], 1)) {
                        continue;                   // ★ B2：既不满（当不了推挤通道）又接不下这 1 单位
                    }
                    final int nArea = surfaceArea[nb];
                    final int ny = FluidBodyView.unpackY(pos[nb]);
                    final long exN = (long) amt[nb] * nArea - targetNum(surfaceNum[nb], nArea, ny - minY);
                    if (exN >= exI && !(src[i] && amt[nb] >= cap[nb])) {
                        // 对方不比自己更缺 ⇒ 不给（这条保证收敛）。
                        // 例外：恒定水源是恒定水头（它自己不是「一罐有限的水」）—— 满格邻格对它来说
                        // 是「管道」：可以顺着后面的链式推挤把水送到更远的缺口去（源源不断）。
                        // 非满格的邻格照旧走上面这条比较（水源只给「比自己更缺」的那一侧）。
                        continue;
                    }
                    final int nDist = nb < n ? gapDist[nb] : -1;
                    final int nDistKey = nDist < 0 ? Integer.MAX_VALUE : nDist;
                    final long nHead = (long) ny * fluid.fill() + amt[nb];
                    if (exN < bestEx || (exN == bestEx
                            && (nDistKey < bestDist || (nDistKey == bestDist
                            && (ny < bestNy || (ny == bestNy && nHead < bestHead)))))) {
                        bestEx = exN;
                        bestDist = nDistKey;
                        bestNy = ny;
                        bestHead = nHead;
                        best = nb;
                    }
                }
                if (best < 0) {
                    continue;
                }

                // ---- 3c) 链式推挤：目标满格就沿同方向继续往前找第一个接得下的格 ----
                //   「走过」标记（walk）只属于这一条链：走完立刻清掉（见下面的 chainWalk），
                //   否则同一 sweep 里后处理的格会被前面格的路径挡住 —— 恒定水源靠满格邻格
                //   当管道往外送水，被挡住就会空转（整坑永远填不上）。
                //   中间格只是被「走过去」的通道：本次搬运只改源格与终点，中间格的量一个都不动
                //   （终点落这一单位前已经确认它接得下，所以不存在超容量；竖向顶水柱、横向穿满格
                //   通道用的都是这条链）。目标不是满格却又接不下（waterlogged 的中间档）时不往前走
                //   —— 那会变成「跳过一格把水放到更远处」，是跳跃不是推挤。
                int goal = best;
                walk[i] = token;
                walk[best] = token;
                s.chainWalk[0] = i;
                s.chainWalk[1] = best;
                int chainUsed = 2;
                for (int hop = 0; hop < MAX_CHAIN_HOPS && amt[goal] >= cap[goal]; hop++) {
                    int next = -1;
                    long nextEx = Long.MAX_VALUE;
                    int nextDist = Integer.MAX_VALUE;
                    int nextNy = 0;
                    long nextHead = Long.MAX_VALUE;
                    for (int d = 0; d < 6; d++) {
                        final int nb = adj[goal * 6 + d];
                        if (nb < 0 || walk[nb] == token || !kind[nb].accepts(fluid)) {
                            continue;               // 走过的、装不下的、没读过的都不走
                        }
                        final int nArea = surfaceArea[nb];
                        final int ny = FluidBodyView.unpackY(pos[nb]);
                        final long exN = (long) amt[nb] * nArea - targetNum(surfaceNum[nb], nArea, ny - minY);
                        final long nHead = (long) ny * fluid.fill() + amt[nb];
                        final int nDist = nb < n ? gapDist[nb] : -1;
                        final int nDistKey = nDist < 0 ? Integer.MAX_VALUE : nDist;
                        if (exN < nextEx || (exN == nextEx
                                && (nDistKey < nextDist || (nDistKey == nextDist
                                && (ny < nextNy || (ny == nextNy && nHead < nextHead)))))) {
                            nextEx = exN;
                            nextDist = nDistKey;
                            nextNy = ny;
                            nextHead = nHead;
                            next = nb;
                        }
                    }
                    if (next < 0) {
                        break;
                    }
                    goal = next;
                    walk[goal] = token;
                    s.chainWalk[chainUsed++] = goal;
                }
                // ★ 「走过」标记只属于这一条链：走完立刻清掉。留着会让同一 sweep 里后处理的格
                //   （含恒定水源 —— 它要靠满格邻格当管道把水送到更远的缺口）被前面格的路径挡住：
                //   实测 4×4×8 的深坑里水源会空转（moved=0）永远填不上。
                for (int k = 0; k < chainUsed; k++) {
                    walk[s.chainWalk[k]] = 0;
                }
                if (!canReceive(kind[goal], cap[goal], amt[goal], 1)) {
                    continue;                       // ★ B2：链尽头也接不下 ⇒ 这次不给
                }

                // ---- 3d) 搬运 1 单位（源 → 终点） ----
                amt[i] = amount - 1;
                amt[goal] += 1;
                if (src[i]) {
                    // 恒定水源：让出去的水立刻补回满格（海洋/河流不会自干）。
                    //   补回来的部分记进净变化，否则这一格的 netDelta 恒为 -1、netZero 永不成立。
                    amt[i] = cap[i];
                }
                marks[goal] = token;
                if (touchMark[i] != token) {
                    touchMark[i] = token;
                    touched[touchedCount++] = i;
                }
                if (touchMark[goal] != token) {
                    touchMark[goal] = token;
                    touched[touchedCount++] = goal;
                }
                netDelta[i]--;
                netDelta[goal]++;
                if (src[i]) {
                    netDelta[i] += cap[i] - (amount - 1);
                }
                moved++;
                swept++;
            }

            // ---- 3e) 净零判定：这一 sweep 的搬运让每个碰过的格都回到原值 ⇒ 已经到位 ----
            //   目标是「最优解不唯一」时会出现（48 单位铺在 9 格上，3 个 6 + 6 个 5 与别的搭配同样最优），
            //   搬运只是在最优集合里打转。分布没变就停，并且这一 sweep 的搬运不计进进度
            //   （否则驱动层看到「还有变化」会永远排班）。
            FluidPhase.end(FluidPhase.SWEEP_SCAN, tScan);
            if (swept == 0) {
                return false;
            }
            boolean netZero = true;
            for (int k = 0; k < touchedCount; k++) {
                if (netDelta[touched[k]] != 0) {
                    netZero = false;
                    break;
                }
            }
            for (int k = 0; k < touchedCount; k++) {
                netDelta[touched[k]] = 0;
            }
            if (netZero) {
                moved = movedBefore;
                return false;
            }
            return true;
        }

        // ---------- 输出 ----------

        /**
         * 输出：只写「最终量与输入不同」的格。
         *
         * <p>写回前断言每个格都在 {@code [0, capacity]} 内（B4：绝不让超容量水位出去）；
         * 越界就是引擎自己的不变量破裂，直接抛。
         *
         * <p>返回值 = 有净变化时的搬运次数（含整体塌落搬过的格数，以及入口给恒定水源补满的量）；
         * <b>一个格都没变就返回 0</b> —— 即使这一步内部搬过又搬回来（重力与压力场互相抵消的
         * ping-pong）。布局没变 ⇒ 下一步必然还是这个布局（每个阶段都只看当前布局、确定性）
         * ⇒ 真的到不动点了，这正是 {@code step 返回 0 ⇔ 整片再也动不了} 的判据。
         */
        private int flush() {
            final long tFlush = FluidPhase.start();
            boolean changed = false;
            for (int i = 0; i < total; i++) {
                final int v = amt[i];
                final int c = cap[i];
                if (v < 0 || v > c) {
                    throw new IllegalStateException("fluid 引擎不变量破裂：格 ("
                            + FluidBodyView.unpackX(pos[i]) + "," + FluidBodyView.unpackY(pos[i]) + ","
                            + FluidBodyView.unpackZ(pos[i]) + ") 的最终量 " + v + " 越界 [0," + c + "]");
                }
                if (v != initial[i]) {
                    out.set(pos[i], v);
                    changed = true;
                }
            }
            FluidPhase.end(FluidPhase.FLUSH, tFlush);
            return changed ? moved + sourceFilled : 0;
        }

        /**
         * 目标水位（与 {@code amount * area} 同尺度的分子）：
         * {@code clamp(H*_num − relY*8*area, 0, 8*area)}。
         *
         * @param surface H*_num（该片水的平衡水面分子）
         * @param area    该片水的分母（0 = 这一格没归片 ⇒ target 视为 0）
         * @param relY    相对层号（世界 y − 片内最小 y）
         */
        private long targetNum(final long surface, final int area, final int relY) {
            if (area <= 0) {
                return 0;
            }
            final long t = surface - (long) relY * fluid.fill() * area;
            if (t <= 0) {
                return 0;
            }
            final long capHere = (long) fluid.fill() * area;
            return Math.min(t, capHere);
        }

        /** 世界坐标（打包）→ 稠密盒线性下标（不查越界：调用方保证这一格在盒内）。 */
        private int boxIndex(final long p) {
            return (FluidBodyView.unpackX(p) - boxMinX)
                    + (FluidBodyView.unpackZ(p) - boxMinZ) * boxDx
                    + (FluidBodyView.unpackY(p) - boxMinY) * boxDx * boxDz;
        }

        /**
         * 世界坐标 → 格下标（稠密盒寻址 / 开放寻址两路；找不到 = -1）。
         *
         * <p>整体塌落找落点用它，一步里可能问很多次 —— 稠密路是一次数组寻址，
         * 哈希路是 pack + 探测。两路的<b>答案集合完全相同</b>：盒里登记的就是
         * 哈希表里登记的那些格（片内格优先、边界格只在空位补），盒外的坐标两路都给 -1。
         */
        private int cellAt(final int x, final int y, final int z) {
            if (dense) {
                final int lx = x - boxMinX;
                final int ly = y - boxMinY;
                final int lz = z - boxMinZ;
                if (lx < 0 || ly < 0 || lz < 0 || lx >= boxDx || ly >= boxDy
                        || lz >= boxDz) {
                    return -1;
                }
                return box[lx + lz * boxDx + ly * boxDx * boxDz];
            }
            return s.mapGet(FluidBodyView.pack(x, y, z));
        }
    }

    // ---------- 邻接表 / 遍历顺序（每个 step 建一次，缓冲复用） ----------

    /**
     * 邻接表：世界坐标 → 格下标（片内优先；未加载/片外 = -1）。两条路：
     *
     * <ul>
     *   <li><b>稠密盒</b>（{@code r.dense}）：bbox 体积 ≤ 4×片格数 ⇒ 邻接是一次数组寻址
     *       （±1 / ±dx / ±dx·dz，越界即 -1）。生产片必中（一个 section + 一圈边界）。</li>
     *   <li><b>开放寻址 long→int 表</b>（稀疏 / 被拉长的跨 region 片）：不用
     *       {@code HashMap<Long,Integer>}，因为每个格要查 6 次邻居，装箱是旧实现最重的分配。</li>
     * </ul>
     * 两路登记的都是同一批格（片内格优先、边界格只在空位补），因此邻接答案逐格相同。
     */
    private static void buildAdjacency(final Run r) {
        final long[] pos = r.pos;
        final int[] adj = r.adj;
        final int n = r.n;
        final int b = r.b;
        final int total = r.total;
        if (r.dense) {
            // 稠密路：邻接 = 盒里 ±1 / ±dx / ±dx*dz 三次寻址（越界 = -1），不再 pack、不再哈希探测。
            final int[] box = r.box;
            final int dx = r.boxDx;
            final int dz = r.boxDz;
            final int strideY = dx * dz;
            for (int i = 0; i < total; i++) {
                final long p = pos[i];
                final int lx = FluidBodyView.unpackX(p) - r.boxMinX;
                final int ly = FluidBodyView.unpackY(p) - r.boxMinY;
                final int lz = FluidBodyView.unpackZ(p) - r.boxMinZ;
                final int at = lx + lz * dx + ly * strideY;
                final int out = i * 6;
                adj[out] = lx + 1 < dx ? box[at + 1] : -1;
                adj[out + 1] = lx > 0 ? box[at - 1] : -1;
                adj[out + 2] = lz + 1 < dz ? box[at + dx] : -1;
                adj[out + 3] = lz > 0 ? box[at - dx] : -1;
                adj[out + 4] = ly + 1 < r.boxDy ? box[at + strideY] : -1;
                adj[out + 5] = ly > 0 ? box[at - strideY] : -1;
            }
            return;
        }
        final Scratch s = r.s;
        s.mapBegin(total);
        for (int i = 0; i < n; i++) {
            s.mapPut(pos[i], i);
        }
        for (int j = 0; j < b; j++) {
            s.mapPutIfAbsent(pos[n + j], n + j);
        }
        for (int i = 0; i < total; i++) {
            final int gx = FluidBodyView.unpackX(pos[i]);
            final int gy = FluidBodyView.unpackY(pos[i]);
            final int gz = FluidBodyView.unpackZ(pos[i]);
            final int base = i * 6;
            for (int d = 0; d < 6; d++) {
                adj[base + d] = s.mapGet(FluidBodyView.pack(
                        gx + AXIAL[d][0], gy + AXIAL[d][1], gz + AXIAL[d][2]));
            }
        }
    }

    /**
     * 排片内格索引：{@code orderAsc} = (y↑, x↑, z↑)，{@code orderDesc} = (y↓, x↑, z↑)。
     *
     * <p>顺序不是风格问题：全平手时谁先动手由它决定（旧实现同样是 y↓/x↑/z↑，换顺序 = 换行为）。
     *
     * <p>升序两条路：<b>稠密盒</b>直接三重循环（y↑ / x↑ / z↑）走出来（与键序逐格等价，省整趟排序）；
     * 稀疏路用键 = {@code (y−minY)<<52 | (x−minX)<<26 | (z−minZ)}（64 位无符号序就是目标顺序）
     * 做<b>一次</b> LSD 基数排序（8 位一桶 8 趟，同位的趟直接跳过）。
     * 降序两条路共用：升序里同一层的格是连续的一段，把层段整体倒着拼一次即可（段内仍是 x↑ / z↑）。
     */
    private static void buildOrders(final Run r) {
        final Scratch s = r.s;
        final long[] pos = r.pos;
        final int n = r.n;
        final int[] asc = s.orderAsc;
        if (r.dense) {
            // 稠密路：三重循环（y↑ / x↑ / z↑）直接生成升序 —— 与基数排序的键序逐格等价，
            //   省掉整趟排序。降序仍走下面的「层段倒拼」，两条路共用。
            final int[] box = r.box;
            final int dx = r.boxDx;
            final int dz = r.boxDz;
            final int strideY = dx * dz;
            int w = 0;
            for (int ly = 0; ly < r.boxDy; ly++) {
                for (int lx = 0; lx < dx; lx++) {
                    final int row = lx + ly * strideY;
                    for (int lz = 0; lz < dz; lz++) {
                        final int idx = box[row + lz * dx];
                        if (idx >= 0 && idx < n) {
                            asc[w++] = idx;
                        }
                    }
                }
            }
            if (w != n) {
                throw new IllegalStateException("稠密盒遍历序漏格：" + w + " != " + n);
            }
        } else {
            final long[] key = s.sortKey;
            for (int i = 0; i < n; i++) {
                final long p = pos[i];
                final long y = FluidBodyView.unpackY(p) - r.minY;
                final long x = (FluidBodyView.unpackX(p) - r.minX) & 0x3FFFFFFL;
                final long z = (FluidBodyView.unpackZ(p) - r.minZ) & 0x3FFFFFFL;
                key[i] = (y << 52) | (x << 26) | z;
            }
            radixSort(s, key, asc, n);
        }
        final int[] desc = s.orderDesc;
        int write = 0;
        int end = n;
        while (end > 0) {
            final int y = FluidBodyView.unpackY(pos[asc[end - 1]]);
            int start = end - 1;
            while (start > 0 && FluidBodyView.unpackY(pos[asc[start - 1]]) == y) {
                start--;
            }
            System.arraycopy(asc, start, desc, write, end - start);
            write += end - start;
            end = start;
        }
    }

    /** LSD 基数排序（8 位一桶、8 趟，64 位无符号键升序）。 */
    private static void radixSort(final Scratch s, final long[] key, final int[] order, final int count) {
        if (count < 2) {
            if (count == 1) {
                order[0] = 0;
            }
            return;
        }
        final int[] bucket = s.bucket;
        int[] src = order;
        int[] dst = s.sortTmp;
        for (int i = 0; i < count; i++) {
            src[i] = i;
        }
        for (int shift = 0; shift < 64; shift += 8) {
            Arrays.fill(bucket, 0);
            for (int i = 0; i < count; i++) {
                bucket[(int) ((key[src[i]] >>> shift) & 0xFFL)]++;
            }
            if (bucket[(int) ((key[src[0]] >>> shift) & 0xFFL)] == count) {
                continue;                       // 这一趟所有键的同位段相同 ⇒ 稳定排序是恒等变换
            }
            int sum = 0;
            for (int bi = 0; bi < 256; bi++) {
                final int c = bucket[bi];
                bucket[bi] = sum;
                sum += c;
            }
            for (int i = 0; i < count; i++) {
                final int e = src[i];
                dst[bucket[(int) ((key[e] >>> shift) & 0xFFL)]++] = e;
            }
            final int[] t = src;
            src = dst;
            dst = t;
        }
        if (src != order) {
            System.arraycopy(src, 0, order, 0, count);
        }
    }
}
