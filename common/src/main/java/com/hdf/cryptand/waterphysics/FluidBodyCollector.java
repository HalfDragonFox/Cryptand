package com.hdf.cryptand.waterphysics;

import com.hdf.cryptand.fluid.ArrayBody;
import com.hdf.cryptand.fluid.FluidBodyView;
import com.hdf.cryptand.fluid.FluidKind;
import com.hdf.cryptand.fluid.FluidKindMap;

import java.util.Arrays;

/**
 * <b>整片连通水的采集器</b>（纯 Java 零 MC）：点名格集合 → 引擎片（{@link ArrayBody}）。
 *
 * <p><b>为什么需要它</b>：旧路径的工作集是「被点名的格」（{@code RegionSnapshot.active}），
 * 均衡器只看得到局部 ⇒ 算出假平衡 ⇒ 水在几格之间来回搬（源格的水让出去又被搬回来，
 * 看起来「不消耗」）。本采集器把工作集换成<b>整片连通水</b>：从点名格出发沿同种液体的连通性
 * 跨 region 展开 BFS，用<b>世界坐标</b>，region / chunk 概念在这里一次都不出现。
 *
 * <p><b>片内格（交给 {@link ArrayBody#cell}）</b>分两个阶段展开，水格优先：
 * <ol>
 *   <li><b>水连通体</b>：沿 6 邻扩展「同种液体且有量」的格（水与岩浆天然分开 —— 各自的
 *       {@link FluidKind} 不同）。这是「一整块连通水」的骨架，先用预算把它扩到底
 *       （未加载 / 预算用尽为止）；</li>
 *   <li><b>容器域</b>：从水格邻接的空容器（空气 / 可含水方块 / 空的液体格）出发，按与引擎
 *       {@code FluidEngine.pressureField} <b>同款</b>的「水停得住」判据继续扩展：
 *       正上方无条件算容器（水会被压力顶上去），其余方向要求「下方不是已加载的空容器」。
 *       引擎的压力场要用这些格算水面面积（少了它们，1 格水源算出的水面正好是自己的满格 ⇒
 *       一步都不让，这就是「水只铺两三格就停」的第二个根因）。</li>
 * </ol>
 *
 * <p><b>跨 region 语义</b>：片内格一律用世界坐标，可以任意跨越 16 的边界（旧路径的
 * {@code RegionSnapshot} 只覆盖一个 section + 一圈面）。「未加载」是唯一的天然边界：
 * {@code !isLoaded} 的格既不进片内也不进边界 —— 引擎对它的判定与「片外」完全相同
 * （{@link FluidBodyView}：没有边界格 = 未加载，那一侧一滴水都不许出去）。
 *
 * <p><b>预算与 unfinished</b>：{@code budget} 是<b>片内格数上限</b>。用尽就停止扩展并置
 * {@link Piece#unfinished()} —— 那是「这一片没采完」（下一轮接着算），<b>绝不是「水不动了」</b>；
 * 调用方不得据此判收敛。未加载截断<b>不</b>置 unfinished：那是世界真的到头了。
 *
 * <p><b>边界格（交给 {@link ArrayBody#border}）</b>= 片内格 6 邻中不在片内、已加载、且
 * 「能容纳本液体」（{@link FluidKind#accepts}）的格：外面一圈可容纳的空格与已有液体格。
 * 固体与另一种液体格不放 —— 引擎对「不在片里」与「在片里但装不下」的判定逐条相同
 * （见 {@code FluidRegionAssembly} 的等价性结论），不放只是省一份邻接表。
 *
 * <p><b>线程约定</b>：本类只由主线程调用（{@link Source} 读世界会触碰区块加载状态）。
 * 产出的 {@link Piece} 里是<b>自己的一份</b> {@link ArrayBody}（不引用本类的复用缓冲），
 * 冻结之后可以交给求解线程；本类自身不可跨线程共享。实例可复用（缓冲只增不减），
 * 每一次 {@link #collect} 都会先 {@code reset}。
 *
 * <p>离线闸门：{@code WaterphysicsSelfTest#testBodyCollectorCrossRegion} /
 * {@code ...Unloaded} / {@code ...Budget} / {@code ...Conservation} / {@code ...WriteGroups}。
 */
public final class FluidBodyCollector {

    /** 六个轴向邻居（顺序 +x / -x / +z / -z / +y / -y，与引擎 {@code AXIAL} 一致）。 */
    private static final int[][] AXIAL = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
    /** 向上（AXIAL 第 5 项）。 */
    private static final int DIR_UP = 4;
    /** 向下（AXIAL 第 6 项）。 */
    private static final int DIR_DOWN = 5;

    /**
     * 采集数据源（只读、纯数据）：neoforge 侧读 Level + 侧表，离线闸门用数组 / Map 实现。
     *
     * <p>四个方法的格坐标都是<b>世界坐标</b>；{@link #kind} 返回 {@link FluidCellKind} 的 int 常量
     * （未知种类由 {@link FluidKindMap#of} 抛异常 —— 绝不许静默当空气）。
     */
    public interface Source {

        /** 这一格所在区块加载了没有（未加载 = 天然边界）。 */
        boolean isLoaded(int x, int y, int z);

        /** 这一格的介质种类（{@link FluidCellKind} 常量）。 */
        int kind(int x, int y, int z);

        /** 这一格的权威水位（世界方块 + 侧表按 {@code FluidLevels.resolve} 合并后的值）。 */
        int level(int x, int y, int z);

        /** 这一格是不是恒定水源（自然水源在采集侧打标，见 {@code RegionSnapshot}）。 */
        boolean isSource(int x, int y, int z);
    }

    /**
     * 一片的采集结果。
     *
     * @param body      引擎片（片内格 + 边界格）；{@link #empty()} 时为 null
     * @param fluid     这一片的液体种类；null = 这一批种子里没有任何液体（无事可做）
     * @param cells     片内格数
     * @param border    边界格数
     * @param reads     本次采集触碰了多少格（主线程读预算按它扣；含重复访问）
     * @param unfinished 预算用尽被截断（没采完整片）⇒ 调用方不得据此判收敛
     */
    public record Piece(ArrayBody body, FluidKind fluid, int cells, int border, int reads,
                        boolean unfinished) {

        /** 这一片没有液体 ⇒ 真的无事可做。 */
        public boolean empty() {
            return fluid == null;
        }

        /** 片内某格的下标；不在片内返回 -1。 */
        public int cellIndexAt(final int x, final int y, final int z) {
            if (body == null) {
                return -1;
            }
            return body.indexOf(x, y, z);
        }

        /** 边界格下标；不在边界返回 -1。 */
        public int borderIndexAt(final int x, final int y, final int z) {
            if (body == null) {
                return -1;
            }
            final long packed = FluidBodyView.pack(x, y, z);
            for (int j = 0; j < body.borderSize(); j++) {
                if (body.borderPacked(j) == packed) {
                    return j;
                }
            }
            return -1;
        }
    }

    // ---------- 片内格 ----------

    private long[] cellPos = new long[256];
    private byte[] cellKind = new byte[256];
    private int[] cellLevel = new int[256];
    private boolean[] cellSource = new boolean[256];
    private int cells;

    // ---------- 边界格 ----------

    private long[] borderPos = new long[64];
    private byte[] borderKind = new byte[64];
    private int[] borderLevel = new int[64];
    private int borders;

    /**
     * 世界坐标 → 下标 的开放寻址表（无装箱）：
     * {@code val == 0} 空槽、{@code val > 0} 片内 {@code index + 1}、{@code val < 0} 边界
     * {@code -(index + 1)}。一个表同时管两种身份，边界去重不必再来一张表。
     */
    private long[] mapKey = new long[1024];
    private int[] mapVal = new int[1024];
    private int mapMask = 1023;

    /** 水格 BFS 队列（打包坐标，先进先出；优先于容器队列处理）。 */
    private long[] wetQueue = new long[256];
    private int wetHead;
    private int wetTail;

    /** 容器域 BFS 队列（打包坐标）。 */
    private long[] boxQueue = new long[256];
    private int boxHead;
    private int boxTail;

    private Source src;
    private FluidKind fluid;
    private int reads;
    private boolean unfinished;

    /** 阶段 1 采到的水格数（水位 &gt; 0 且同种液体）。 */
    private int wetCells;

    /**
     * 阶段 1 采到的水总量（各格水位之和，单位 = 水位单位）。
     *
     * <p>★ 容器域的<b>物理上界</b>：水位 1 是自由水面的末端（引擎侧 {@code stub}），
     * 这片水最多摊成「单位数」个格 —— 更远的可容纳格永远装不到水。
     * 少了这个界，平坦地形上「下方有支撑的空格」是整个空域（实机片内格数 14 万），
     * 片永远超出读预算 ⇒ unfinished 恒真 ⇒ 每轮整片重排、水面来回搬（抽携）。
     */
    private long wetUnits;

    /**
     * 从点名格出发采一整片。
     *
     * @param source    采集数据源（主线程）
     * @param seeds     点名格的世界坐标（{@link FluidBodyView#pack} 打包）
     * @param seedCount 种子个数
     * @param budget    片内格数上限（&le; 0 时一片都不采，直接 unfinished）
     */
    public Piece collect(final Source source, final long[] seeds, final int seedCount, final int budget) {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为 null");
        }
        if (seeds == null) {
            throw new IllegalArgumentException("seeds 不能为 null");
        }
        if (seedCount < 0 || seedCount > seeds.length) {
            throw new IllegalArgumentException("seedCount 越界: " + seedCount + " / " + seeds.length);
        }
        reset(source);

        // ---- 1) 定片：种子里第一格「有水且是液体」的 kind 就是这一片的液体 ----
        //   引擎按 kind 分片，不同液体不混一片；水与岩浆的 kind 不同 ⇒ 天然分开。
        for (int i = 0; i < seedCount; i++) {
            final long p = seeds[i];
            if (!loaded(p)) {
                continue;
            }
            if (levelOf(p) <= 0) {
                continue;
            }
            final FluidKind k = kindOf(p);
            if (k.isLiquid()) {
                fluid = k;
                break;
            }
        }
        if (fluid == null) {
            return new Piece(null, null, 0, 0, reads, false);
        }

        // ---- 2) 种子里的水格入队（水连通体先展开） ----
        for (int i = 0; i < seedCount; i++) {
            final long p = seeds[i];
            if (!loaded(p)) {
                continue;
            }
            if (kindOf(p) != fluid) {
                continue;                       // 只有同种液体才算这片水的骨架
            }
            if (levelOf(p) > 0) {
                pushWet(p);
            }
        }

        // ---- 3) BFS：水格优先，容器域用剩余预算 ----
        while (wetHead < wetTail || boxHead < boxTail) {
            if (cells >= budget) {
                unfinished = true;              // ★ 预算用尽 ≠ 水不动了
                break;
            }
            final boolean fromWet = wetHead < wetTail;
            // ★ 容器域的物理上界（不是预算截断，所以不置 unfinished）：
            //   水位 1 是末端 ⇒ 最多摊成 wetUnits 格，再远的可容纳格永远装不到水。
            if (!fromWet && wetUnits > 0 && cells - wetCells >= wetUnits) {
                break;
            }
            final long cur = fromWet ? wetQueue[wetHead++] : boxQueue[boxHead++];
            if (mapGet(cur) != 0) {
                continue;                       // 已经作为别人的邻居进过片/边界
            }
            if (!loaded(cur)) {
                continue;                       // 入队后被判定未加载（Source 可以变）
            }
            addCell(cur);
            if (cellLevel[cells - 1] > 0 && FluidKindMap.of(cellKind[cells - 1]) == fluid) {
                wetCells++;
                wetUnits += cellLevel[cells - 1];
            }
            final int x = FluidBodyView.unpackX(cur);
            final int y = FluidBodyView.unpackY(cur);
            final int z = FluidBodyView.unpackZ(cur);
            for (int d = 0; d < 6; d++) {
                final long nb = FluidBodyView.pack(x + AXIAL[d][0], y + AXIAL[d][1], z + AXIAL[d][2]);
                if (mapGet(nb) != 0 || !loaded(nb)) {
                    continue;
                }
                final FluidKind k = kindOf(nb);
                if (k == fluid && levelOf(nb) > 0) {
                    pushWet(nb);
                } else if (k.accepts(fluid) && (d == DIR_UP || holdsBelow(nb))) {
                    pushBox(nb);
                }
            }
        }

        collectBorder();
        return build(fluid);
    }

    // ---------- 内部：展开 ----------

    /**
     * 「水能不能停在这一格」：正上方的判据由调用方短路；这里判其余方向。
     *
     * <p>与引擎 {@code pressureField} 的容器判据同款：候选格的下方若是<b>已加载的空容器</b>
     * ⇒ 这一格不是水的停留层（水会落下去）；未加载 / 固体 / 已有水 ⇒ 停得住。
     * 差别只有一处（已加载但不在片里时本判据更保守），见报告「未确证项」。
     */
    private boolean holdsBelow(final long p) {
        final long below = FluidBodyView.pack(FluidBodyView.unpackX(p),
                FluidBodyView.unpackY(p) - 1, FluidBodyView.unpackZ(p));
        if (!loaded(below)) {
            return true;                        // 未加载：引擎眼里也是「撑着」
        }
        final FluidKind k = kindOf(below);
        if (!k.accepts(fluid)) {
            return true;                        // 固体 / 另一种液体：撑得住
        }
        return levelOf(below) > 0;              // 下方已经有水 ⇒ 撑得住
    }

    /** 外面一圈：片内格 6 邻中不在片内、已加载、且能容纳本液体的格。 */
    private void collectBorder() {
        for (int i = 0; i < cells; i++) {
            final long p = cellPos[i];
            final int x = FluidBodyView.unpackX(p);
            final int y = FluidBodyView.unpackY(p);
            final int z = FluidBodyView.unpackZ(p);
            for (int d = 0; d < 6; d++) {
                final long nb = FluidBodyView.pack(x + AXIAL[d][0], y + AXIAL[d][1], z + AXIAL[d][2]);
                if (mapGet(nb) != 0) {
                    continue;                   // 片内，或已经登记成边界格
                }
                if (!loaded(nb)) {
                    continue;                   // 未加载 ⇒ 天然边界，一滴水都不许出去
                }
                final FluidKind k = kindOf(nb);
                if (!k.accepts(fluid)) {
                    continue;                   // 固体 / 岩浆：对引擎等价于「这一格不存在」
                }
                addBorder(nb, k, levelOf(nb));
            }
        }
    }

    private Piece build(final FluidKind fluid) {
        final ArrayBody body = new ArrayBody(fluid);
        for (int i = 0; i < cells; i++) {
            final long p = cellPos[i];
            body.cell(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p), FluidBodyView.unpackZ(p),
                    FluidKindMap.of(cellKind[i]), cellLevel[i], cellSource[i]);
        }
        for (int j = 0; j < borders; j++) {
            final long p = borderPos[j];
            body.border(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p), FluidBodyView.unpackZ(p),
                    FluidKindMap.of(borderKind[j]), borderLevel[j]);
        }
        return new Piece(body, fluid, cells, borders, reads, unfinished);
    }

    // ---------- 内部：缓冲 / 表 ----------

    private void reset(final Source source) {
        this.src = source;
        this.fluid = null;
        this.reads = 0;
        this.unfinished = false;
        this.wetCells = 0;
        this.wetUnits = 0L;
        this.cells = 0;
        this.borders = 0;
        this.wetHead = 0;
        this.wetTail = 0;
        this.boxHead = 0;
        this.boxTail = 0;
        Arrays.fill(mapVal, 0);
    }

    private void addCell(final long p) {
        if ((cells + borders + 1) * 2 > mapVal.length) {
            growMap();
        }
        if (cells == cellPos.length) {
            final int grown = cells << 1;
            cellPos = Arrays.copyOf(cellPos, grown);
            cellKind = Arrays.copyOf(cellKind, grown);
            cellLevel = Arrays.copyOf(cellLevel, grown);
            cellSource = Arrays.copyOf(cellSource, grown);
        }
        cellPos[cells] = p;
        cellKind[cells] = (byte) src.kind(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p),
                FluidBodyView.unpackZ(p));
        cellLevel[cells] = levelOf(p);
        cellSource[cells] = src.isSource(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p),
                FluidBodyView.unpackZ(p));
        mapPut(p, cells + 1);
        cells++;
    }

    private void addBorder(final long p, final FluidKind kind, final int level) {
        if ((cells + borders + 1) * 2 > mapVal.length) {
            growMap();
        }
        if (borders == borderPos.length) {
            final int grown = borders << 1;
            borderPos = Arrays.copyOf(borderPos, grown);
            borderKind = Arrays.copyOf(borderKind, grown);
            borderLevel = Arrays.copyOf(borderLevel, grown);
        }
        borderPos[borders] = p;
        borderKind[borders] = (byte) src.kind(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p),
                FluidBodyView.unpackZ(p));
        borderLevel[borders] = level;
        mapPut(p, -(borders + 1));
        borders++;
    }

    private void pushWet(final long p) {
        if (wetTail == wetQueue.length) {
            wetQueue = Arrays.copyOf(wetQueue, wetTail << 1);
        }
        wetQueue[wetTail++] = p;
    }

    private void pushBox(final long p) {
        if (boxTail == boxQueue.length) {
            boxQueue = Arrays.copyOf(boxQueue, boxTail << 1);
        }
        boxQueue[boxTail++] = p;
    }

    private boolean loaded(final long p) {
        reads++;
        return src.isLoaded(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p), FluidBodyView.unpackZ(p));
    }

    private FluidKind kindOf(final long p) {
        return FluidKindMap.of(src.kind(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p),
                FluidBodyView.unpackZ(p)));
    }

    private int levelOf(final long p) {
        return src.level(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p), FluidBodyView.unpackZ(p));
    }

    private int mapGet(final long key) {
        int h = hash(key) & mapMask;
        while (true) {
            final int v = mapVal[h];
            if (v == 0) {
                return 0;
            }
            if (mapKey[h] == key) {
                return v;
            }
            h = (h + 1) & mapMask;
        }
    }

    private void mapPut(final long key, final int value) {
        int h = hash(key) & mapMask;
        while (true) {
            final int v = mapVal[h];
            if (v == 0) {
                mapKey[h] = key;
                mapVal[h] = value;
                return;
            }
            if (mapKey[h] == key) {
                mapVal[h] = value;
                return;
            }
            h = (h + 1) & mapMask;
        }
    }

    /** 表按「片内 + 边界」总量增长（负载 ≤ 0.5）；增长必须重建（rehash），否则已有映射会丢。 */
    private void growMap() {
        final int c = mapVal.length << 1;
        final long[] nk = new long[c];
        final int[] nv = new int[c];
        final int mask = c - 1;
        for (int i = 0; i < mapKey.length; i++) {
            final int v = mapVal[i];
            if (v == 0) {
                continue;
            }
            final long key = mapKey[i];
            int h = hash(key) & mask;
            while (nv[h] != 0) {
                h = (h + 1) & mask;
            }
            nk[h] = key;
            nv[h] = v;
        }
        mapKey = nk;
        mapVal = nv;
        mapMask = mask;
    }

    private static int hash(final long key) {
        long z = key * 0x9E3779B97F4A7C15L;
        z ^= z >>> 32;
        return (int) z;
    }
}
