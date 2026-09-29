package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.waterphysics.FluidCellKind;
import com.hdf.cryptand.waterphysics.FluidCellView;
import com.hdf.cryptand.waterphysics.FluidLevels;
import com.hdf.cryptand.waterphysics.NaturalWaterSources;
import com.hdf.cryptand.waterphysics.WaterLevelField;
import com.hdf.cryptand.waterphysics.WaterWorkSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一个 region 的求解快照 —— 求解线程唯一能看到的世界。
 *
 * <p><b>采集方式 = 单格粒度</b>：只读「被点名的格 + 它的 6 个邻居」，
 * 不遍历 section、不读没水的地方。
 *
 * <ul>
 *   <li><b>点名</b>（{@link #mark}）来自原版流体 tick 与自己写回后水位变化的格 —— 这两处正是
 *       点名来源，格坐标一路保留；</li>
 *   <li><b>每个点名格读「自身 + 6 邻居」</b>：邻居必须进快照，因为 {@link WaterLevelField#kind}
 *       未采集时默认 AIR，求解器会把水倒进没采过的格子；</li>
 *   <li><b>不变量：a water cell is only ever solved with its full 6-neighbourhood in the snapshot</b>
 *       —— 因此发现「邻居也是水」时必须把那个邻居也排进本轮（{@link #todo}），沿连通水域
 *       一路扩到底，直到 water 的边界；否则求解会拿默认 AIR 当邻居做决策，水位来回跳；</li>
 *   <li><b>采集分帧</b>：{@link #advance} 每次最多读 {@code budget} 个格，读不完保留游标，
 *       下一 tick 接着读；<b>整个 region 的点名格全部读完（{@link #advance} 返回 true）
 *       才允许提交求解</b>，绝不一半提交。</li>
 * </ul>
 *
 * <p><b>一次计算只算一格</b>（单格粒度，见 {@link SpreadSolver#stepCell}）：
 * 快照把「这一批到期的格 + 它们的邻居」读进来，求解只碰这些格。
 * 世界变更不需要作废整份快照 —— 被外部改过的格由主线程单独标脏（{@code stale}），
 * 写回时跳过它们、并把它们重新排队，其余格的结果照样落地。
 *
 * <p>线程约定：本类只由主线程写（{@link #advance}），提交后只由求解线程读 —— 提交即冻结，
 * 新采集一律用新实例（旧实例在它那一轮结束后作废）。
 */
public final class RegionSnapshot implements FluidCellView {

    /** 一个面的格数。 */
    public static final int FACE_CELLS = 16 * 16;
    /** 面数（+X/-X/+Y/-Y/+Z/-Z）。 */
    public static final int FACES = 6;

    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};
    /** 均衡器扫线用的 4 个水平方向（与求解器的 HORIZONTAL 同序）。 */
    private static final int[] EQ_DX = {0, 1, 0, -1};
    private static final int[] EQ_DZ = {-1, 0, 1, 0};

    private final long regionKey;
    private final int baseX;
    private final int baseY;
    private final int baseZ;

    /**
     * <b>自然水源集合</b>（只读）：这一格若不是「世界生成时就在 + 群系命中」就不打标。
     *
     * <p>由 {@link WaterPhysicsBridge} 在构造时注入（同一个实例也由该维度的 SavedData 持有），
     * 查询是 O(1) 位运算；登记在区块首次加载时就已经做完（{@code NaturalWaterRegistry}）。
     */
    private final NaturalWaterSources naturalSources;
    /** 自然水源总开关（{@code naturalSourceWater}，构造时取一次）。 */
    private final boolean naturalSourceEnabled;

    private final WaterLevelField field = new WaterLevelField();
    private final byte[] initialLevels = new byte[WaterLevelField.CELLS];
    /** 0 = 还没读，1 = 已读，2 = 所在区块未加载（跳过）。 */
    private final byte[] scanned = new byte[WaterLevelField.CELLS];

    private final byte[] faceKind = new byte[FACES * FACE_CELLS];
    private final byte[] faceLevel = new byte[FACES * FACE_CELLS];
    private final byte[] faceScanned = new byte[FACES * FACE_CELLS];

    /** 本轮要读的格（点名格 + 沿连通水域扩散进来的邻居）；cursor 之前的都已读过。 */
    private final WaterWorkSet todo = new WaterWorkSet();
    /**
     * 谁是被「点名」的格（1 = 原版 fluid tick 推过它 / 写回改过它）。
     *
     * <p>★ 只有点名格才进 {@link #active} 参与求解。沿连通水域扩散进来的邻居只是为了让快照
     * 完整（求解时查得到它们），绝不代表它们该动 —— 少了这条区分，一整片静止的湖都会被拉进
     * 求解并被摊平：水位掉下来之后原版桶就再也收不走水（isSource 不再成立），湖面还会来回抖。
     */
    private final byte[] named = new byte[WaterLevelField.CELLS];
    /** 求解真正遍历的格 = 【点名格】中水位 &gt; 0 的那些（工作集）。 */
    private final WaterWorkSet active = new WaterWorkSet();
    /**
     * 所有从世界读进快照的格。
     *
     * <p>★ 差异输出（{@code SpreadSolver.emitChanges}）必须用它而不是 {@link #active}：
     * 水会流进「本身不是点名格、只是水格邻居」的格，那些格的水位在快照里变了，
     * 用 active 比对就会漏掉它们 —— 表现是水倒进邻居后没写回世界（水凭空消失）。
     */
    private final WaterWorkSet scannedCells = new WaterWorkSet();

    /**
     * 均衡器开关与采集视野：0 = 关掉均衡器（只剩逐格流动），非 0 = 启用。
     *
     * <p>均衡器的作用范围是整片连通水域，不再受这个数值限制；它现在决定的是
     * 「快照顺带往外读多远」，以及是不是启用均衡器。
     */
    private final int eqDist;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int cursor;
    private int skippedUnloaded;
    /** 最近一次 {@link #advance} 读了多少个格（探针用）。 */
    private int lastReads;

    public RegionSnapshot(final long regionKey, final int eqDist, final WaterLevelStore store,
                          final NaturalWaterSources naturalSources, final boolean naturalSourceEnabled) {
        this.regionKey = regionKey;
        this.eqDist = eqDist;
        this.store = store;
        this.naturalSources = naturalSources == null ? new NaturalWaterSources() : naturalSources;
        this.naturalSourceEnabled = naturalSourceEnabled;
        this.baseX = SectionCursor.keyX(regionKey) << 4;
        this.baseY = SectionCursor.keyY(regionKey) << 4;
        this.baseZ = SectionCursor.keyZ(regionKey) << 4;
    }

    public long regionKey() {
        return regionKey;
    }

    /** 点名一个格：下一次 {@link #advance} 必须读它，并且它可以参与求解（点名）。 */
    public void mark(final int index) {
        named[index] = 1;
        todo.add(index);
    }

    /** 还有没有没读完的点名格。 */
    public boolean hasRemaining() {
        return cursor < todo.size();
    }

    /**
     * 分帧采集。
     *
     * @param budget 本帧最多读多少个格（主线程门控）
     * @return true = 整个 region 的点名格已经全部采完，可以提交求解
     */
    public boolean advance(final ServerLevel level, final WaterLevelStore store, final int budget) {
        final WaterLevelField side = store.get(regionKey);
        int reads = 0;
        lastReads = 0;
        while (cursor < todo.size() && reads < budget) {
            final int index = todo.get(cursor++);
            if (scanned[index] != 0) {
                // ★ 它已经作为**邻居**被顺带读过了。若它同时是「点名格」且水位 > 0，
                //   此刻仍必须进 active —— 否则它的点名身份被这次顺带扫描吃掉，
                //   永远不参与求解（表现：水只铺出第一层，之后整片水域都查不出该动谁）。
                if (named[index] != 0 && field.level(index) > 0) {
                    active.add(index);
                }
                continue;
            }
            reads += readCell(level, side, index, named[index] != 0);
        }
        lastReads = reads;
        return cursor >= todo.size();
    }

    /** 求解要遍历的工作集（水位 &gt; 0 的点名格）。 */
    public WaterWorkSet active() {
        return active;
    }

    /** 读过世界的全部格（差异输出用，见字段注释）。 */
    public WaterWorkSet scannedCells() {
        return scannedCells;
    }

    public WaterLevelField field() {
        return field;
    }

    public byte[] initialLevels() {
        return initialLevels;
    }

    /** 侧表（主线程采集时会读它来合并 region 外的水位）。 */
    private final WaterLevelStore store;

    /** 均衡器开关与采集视野（构造时从配置取）。 */
    public int eqDist() {
        return eqDist;
    }

    /** 因所在区块未加载而跳过的格数（探针用）。 */
    public int skippedUnloaded() {
        return skippedUnloaded;
    }

    /** 最近一次 {@link #advance} 读了多少个格（探针用）。 */
    public int lastReads() {
        return lastReads;
    }

    // ---------- 采集 ----------

    /** 读一个格：它自己 + 6 个邻居。返回本次实际读了几格。 */
    private int readCell(final ServerLevel level, final WaterLevelField side, final int index,
                         final boolean isNamed) {
        int reads = 0;
        if (readOwn(level, side, index, isNamed)) {
            reads++;
        }
        final int wx = baseX + SectionCursor.localX(index);
        final int wy = baseY + SectionCursor.localY(index);
        final int wz = baseZ + SectionCursor.localZ(index);
        for (int d = 0; d < FACES; d++) {
            final int nx = wx + DX[d];
            final int ny = wy + DY[d];
            final int nz = wz + DZ[d];
            final int nIndex = indexIn(nx, ny, nz);
            if (nIndex >= 0) {
                if (scanned[nIndex] == 0 && readOwn(level, side, nIndex, false)) {
                    reads++;
                }
                // ★ 邻居也是水 ⇒ 它属于同一片水域，必须一起扫完。
                //   少了这一步，那片水里的格只有「自己 + 上一层的邻居」进了快照，
                //   它另外 5 个邻居还是默认值（AIR / 水位 0），求解会拿这个假邻居做决策
                //   —— 把水倒进其实是固体的格子，水位就会来回跳。
                if (scanned[nIndex] == 1 && field.level(nIndex) > 0) {
                    todo.add(nIndex);
                }
            } else if (readFace(level, nx, ny, nz)) {
                reads++;
            }
        }
        return reads;
    }

    /** 读一个 region 内的格；只有 {@code isNamed} 的格才可能进 {@link #active}。 */
    private boolean readOwn(final ServerLevel level, final WaterLevelField side, final int index,
                            final boolean isNamed) {
        final int wx = baseX + SectionCursor.localX(index);
        final int wy = baseY + SectionCursor.localY(index);
        final int wz = baseZ + SectionCursor.localZ(index);
        // ★ 未加载就不读：getBlockState 会触发同步区块加载（实测 2 次 setBlock 花 44.7 ms 的真凶）
        if (!level.isLoaded(pos.set(wx, wy, wz))) {
            scanned[index] = 2;
            skippedUnloaded++;
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        final int sideLevel = side == null ? 0 : side.level(index);
        final boolean sidePresent = side != null && side.isPresent(index);
        int cellLevel = FluidLevels.resolve(fluidLevel(state), sideLevel, sidePresent);
        field.setKind(index, McFluidCellView.kindOf(state));
        field.setLevel(index, cellLevel);
        // ★ 自然水源（群系命中 + 世界生成时就在）：采集时打「恒定水源」标记，并把水位钉在满格。
        //   判据在 section 首次加载时就已定死（NaturalWaterRegistry 登记），这里只做 O(1) 位查询；
        //   玩家之后自己放的水不在登记集合里 ⇒ 到不了这里（这正是用户要的「自己放的没有效果」）。
        if (naturalSourceEnabled) {
            if (cellLevel > 0 && naturalSources.isNaturalSource(regionKey, index)) {
                field.setSource(index, true);
                if (cellLevel < WaterLevelField.MAX_LEVEL && FluidCellKind.canHold(field.kind(index))) {
                    field.setLevel(index, WaterLevelField.MAX_LEVEL);
                    cellLevel = WaterLevelField.MAX_LEVEL;
                }
            } else if (field.isSource(index)) {
                // 世界里的水已经没了（被桶收走 / 被方块顶掉）⇒ 源标记跟着清，
                // 否则这一格会以「无限水源」的身份凭空产水（守恒破掉）。
                field.setSource(index, false);
            }
        }
        initialLevels[index] = (byte) cellLevel;
        // ★ 把「采集到的权威水位」回写侧表：侧表从此一定持有这一格的**基准值**，
        //   写回才有正确的起点 —— 写回现在是增量语义（region 内也一样），
        //   若侧表没有这一格的记录（基准 0），把增量加上去会把世界里的水抹掉。
        store.getOrCreate(regionKey).setLevel(index, cellLevel);
        scanned[index] = 1;
        scannedCells.add(index);
        if (isNamed && cellLevel > 0) {
            active.add(index);
            markEqLine(wx, wy, wz);     // ★ 均衡器的线视野（见 markEqLine）
        }
        return true;
    }

    /**
     * <b>均衡器的线视野</b>：从一个水格沿 4 个水平方向各排 {@code eqDist} 格去读。
     *
     * <p>这些格只被读进快照、<b>不进 active</b>（不参与 {@code stepCell}）—— 它们只是让均衡器
     * 能看到「十几格外的水面平不平」（均衡器直接读世界，我们只能预先采）。
     * 只对点名格做：否则每条线又触发下一条线，采集会链式爆炸。
     */
    private void markEqLine(final int wx, final int wy, final int wz) {
        if (eqDist <= 0) {
            return;
        }
        for (int d = 0; d < 4; d++) {
            for (int step = 1; step <= eqDist; step++) {
                final int x = wx + EQ_DX[d] * step;
                final int z = wz + EQ_DZ[d] * step;
                // ★ 三层都要排：均衡器会沿水面上下起伏（走上水柱 / 下到低一层）——
                //   只排同一层的话，「比自己低一层的洞」永远在快照之外，水就流不进去。
                for (int dy = -1; dy <= 1; dy++) {
                    final int nIndex = indexIn(x, wy + dy, z);
                    if (nIndex < 0) {
                        continue;   // 这一层出 region（跨 region 的线由写回自反馈在下一批走）
                    }
                    if (scanned[nIndex] == 0) {
                        todo.add(nIndex);
                    }
                }
            }
        }
    }

    /** 读一个跨 region 的邻居（落进 6 个面）。返回值无用，仅表示「确实读了一格」。 */
    private boolean readFace(final ServerLevel level, final int x, final int y, final int z) {
        final int slot = faceSlot(x, y, z);
        if (slot < 0 || faceScanned[slot] != 0) {
            return false;
        }
        faceScanned[slot] = 1;
        if (!level.isLoaded(pos.set(x, y, z))) {
            // 未加载：保守当作固体，水不会流进去（也不会去写它）
            faceKind[slot] = (byte) FluidCellKind.SOLID;
            faceLevel[slot] = 0;
            skippedUnloaded++;
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        faceKind[slot] = (byte) McFluidCellView.kindOf(state);
        // ★ 面的水位也要按「世界 + 侧表」合并（与 region 内的 readOwn 同一口径）：
        //   只读世界的投影，会在「算出但还没写回」的窗口里看到旧水位 ⇒ 边界水面跳变 / 水量凭空涨落。
        final WaterLevelField faceSide = store.get(SectionCursor.key(x >> 4, y >> 4, z >> 4));
        final int sideLevel = faceSide == null ? 0
                : faceSide.level(SectionCursor.linearIndex(x & 15, y & 15, z & 15));
        faceLevel[slot] = (byte) FluidLevels.resolve(fluidLevel(state), sideLevel);
        return true;
    }

    // ---------- FluidCellView（求解线程只读） ----------

    @Override
    public int kindAt(final int x, final int y, final int z) {
        final int index = indexIn(x, y, z);
        if (index >= 0) {
            return field.kind(index);
        }
        final int slot = faceSlot(x, y, z);
        return slot < 0 ? FluidCellKind.SOLID : faceKind[slot];
    }

    @Override
    public int levelAt(final int x, final int y, final int z) {
        final int index = indexIn(x, y, z);
        if (index >= 0) {
            return field.level(index);
        }
        final int slot = faceSlot(x, y, z);
        return slot < 0 ? 0 : faceLevel[slot];
    }

    /**
     * 这一格是否真的读过。
     *
     * <p>region 内看 {@code scanned}（0 = 没读，1 = 读过，2 = 区块未加载），
     * region 外看 {@code faceScanned}。求解据此拒绝把水转移到没读过的地方 ——
     * 否则水会被扣掉却写不出去（守恒破掉），实机表现就是「源格变小、周围没有水」。
     */
    @Override
    public boolean known(final int x, final int y, final int z) {
        final int index = indexIn(x, y, z);
        if (index >= 0) {
            return scanned[index] == 1;
        }
        final int slot = faceSlot(x, y, z);
        return slot >= 0 && faceScanned[slot] != 0;
    }

    // ---------- 坐标换算 ----------

    /** region 内坐标 → 线性索引；不在本 region 返回 -1。 */
    private int indexIn(final int x, final int y, final int z) {
        final int lx = x - baseX;
        final int ly = y - baseY;
        final int lz = z - baseZ;
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
            return -1;
        }
        return SectionCursor.linearIndex(lx, ly, lz);
    }

    /** 世界坐标 → 6 个面里的槽位；不在任何一个面里返回 -1。 */
    private int faceSlot(final int x, final int y, final int z) {
        final int dx = x - baseX;
        final int dy = y - baseY;
        final int dz = z - baseZ;
        if (dx == -1 && inside(dy) && inside(dz)) {
            return (dy << 4) + dz;
        }
        if (dx == 16 && inside(dy) && inside(dz)) {
            return FACE_CELLS + (dy << 4) + dz;
        }
        if (dy == -1 && inside(dx) && inside(dz)) {
            return 2 * FACE_CELLS + (dx << 4) + dz;
        }
        if (dy == 16 && inside(dx) && inside(dz)) {
            return 3 * FACE_CELLS + (dx << 4) + dz;
        }
        if (dz == -1 && inside(dx) && inside(dy)) {
            return 4 * FACE_CELLS + (dx << 4) + dy;
        }
        if (dz == 16 && inside(dx) && inside(dy)) {
            return 5 * FACE_CELLS + (dx << 4) + dy;
        }
        return -1;
    }

    private static boolean inside(final int v) {
        return v >= 0 && v < 16;
    }

    private static int fluidLevel(final BlockState state) {
        return state.getFluidState().isEmpty() ? 0 : state.getFluidState().getAmount();
    }

    @Override
    public String toString() {
        return "RegionSnapshot{region=" + regionKey + ", todo=" + todo.size()
                + ", cursor=" + cursor + ", active=" + active.size()
                + ", skippedUnloaded=" + skippedUnloaded + "}";
    }
}
