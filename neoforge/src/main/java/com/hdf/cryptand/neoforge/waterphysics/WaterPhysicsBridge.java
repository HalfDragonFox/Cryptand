package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.core.concurrent.Txn;
import com.hdf.cryptand.core.concurrent.TxnExec;
import com.hdf.cryptand.core.concurrent.TxnKind;
import com.hdf.cryptand.core.concurrent.TxnPool;
import com.hdf.cryptand.core.concurrent.TxnScheduler;
import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.fluid.ArrayBody;
import com.hdf.cryptand.fluid.FluidBodyView;
import com.hdf.cryptand.fluid.FluidKind;
import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;
import com.hdf.cryptand.waterphysics.ComputeBudget;
import com.hdf.cryptand.waterphysics.FluidBodyCollector;
import com.hdf.cryptand.waterphysics.FluidCellKind;
import com.hdf.cryptand.waterphysics.FluidWritePlan;
import com.hdf.cryptand.waterphysics.NaturalWaterSources;
import com.hdf.cryptand.waterphysics.SpreadSolver;
import com.hdf.cryptand.waterphysics.WaterLevelField;
import com.hdf.cryptand.waterphysics.WaterWorkSet;
import com.hdf.cryptand.waterphysics.WaterphysicsProbe;
import com.hdf.cryptand.waterphysics.WriteBackQueue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 流体物理主线程门面 —— 搬到主线程 + 门控。
 *
 * <p><b>格子来源</b>（= 点名一个格）：
 * <ul>
 *   <li><b>外部变更</b>（{@link #postCell}：倒桶 / 挖放方块 / {@code /setblock} / 活塞 / 落沙）
 *       —— 世界真的变了，该 region <b>当前那一批</b>采集/求解作废重采；</li>
 *   <li><b>原版流体 tick</b>（{@link #postCellTick}）—— 只是提醒，不作废；</li>
 *   <li><b>自己写回</b>（{@link #postCellKey}，由 {@link FluidApplier} 调用）—— 水位档位变了，
 *       只把这一格排进延后表。</li>
 * </ul>
 *
 * <p><b>一次计算 = 一格</b>（单格粒度，见 {@link SpreadSolver#stepCell}）：
 * 到期的格被采集进快照、派给工作线程逐格求解；水位变了的格由写回路径重新排进
 * <b>延后表</b>（{@code dueIn[index] = fluidTickRate}），{@code fluidTickRate} 个 tick 之后到期再算
 * —— 这就是延后表。「一次只走一格」「没算完的下一个 tick 继续」都由它天然成立，
 * 没有「整轮」这个必须原子完成的东西，也就没有「整轮被作废 / 卡住」的土壤。
 *
 * <p><b>每 tick 给两样东西，各一轮</b>：
 * <ol>
 *   <li><b>一轮写主世界</b>（MAIN+WRITE）：一次写回事务，条数受 writeBudget 限制；
 *       没写完的下一 tick 接着写同一个 region。写没有落干净时<b>不开新采集</b>
 *       （否则会读到「算了一半」的世界）；</li>
 *   <li><b>一次整个计算</b>（ASYNC+READ）：这一 tick 采完的所有 region 一次性派发给工作线程，
 *       每个任务随带自己的<b>运算额度（格数）</b>。</li>
 * </ol>
 *
 * <p>只由主线程调用 tick()；postCell / postCellTick / postCellKey 也都在主线程调用。
 */
public final class WaterPhysicsBridge {

    /** 一个 region 的工作状态：延后表 + 本批到期格 + 采集/求解进度（跨 tick 保留）。 */
    /**
     * 在途整片的认领表：{@code 片指纹 → 认领它的 region key}。
     *
     * <p>写回队列排空（世界与侧表已一致）时统一释放，见 {@link #onWrite}。
     */
    private final Map<Long, Long> pieceClaims = new java.util.HashMap<>();

    /**
     * 片指纹：<b>与 BFS 顺序无关</b>（同一片无论从哪个 region 出发采，指纹都一样）。
     *
     * <p>用「格坐标混合后的异或 + 格数」而不是顺序哈希 —— 两个 work 的采集起点不同，
     * 展开顺序必然不同，顺序哈希会让它们算出不同指纹，互斥就失效了。
     */
    private static long fingerprintOf(final FluidBodyCollector.Piece piece) {
        final FluidBodyView body = piece.body();
        final int n = body.size();
        if (n <= 0) {
            return 0L;
        }
        long h = 0L;
        for (int i = 0; i < n; i++) {
            long p = body.packed(i);
            p ^= p >>> 33;
            p *= 0xff51afd7ed558ccdL;
            p ^= p >>> 33;
            h ^= p;
        }
        return h ^ (n * 0x9E3779B97F4A7C15L);
    }

    private static final class RegionWork {
        final long regionKey;
        /**
         * <b>延后表</b>：{@code dueIn[index]} = 还有几个 tick 到期，0 = 不在队列。
         */
        final int[] dueIn = new int[WaterLevelField.CELLS];
        /** 本批到期的格；采集/求解消费它，没轮到的会被重新排回 {@link #dueIn}。 */
        final WaterWorkSet due = new WaterWorkSet();
        /** 延后表里还排着多少个格（0 ⇒ 这一批算完就空闲）。 */
        int queued;
        /** 采集中（分帧）的快照；null = 没在采。 */
        RegionSnapshot collecting;
        /** 采集完成、等待或正在求解的快照；null = 没有可算的那一批（旧路径）。 */
        RegionSnapshot snapshot;
        /**
         * <b>整片采集结果</b>（阶段 2b 新路径）：从本 region 的点名格出发跨 region 展开的
         * 「一整片连通水」（片内格 + 一圈边界格）。
         *
         * <p>null = 没有待算的片。它由主线程采集（{@link #collectPiece}）后冻结，
         * 求解线程只读；求解结束（{@link #drainResults}）后置回 null。
         */
        FluidBodyCollector.Piece piece;
        /** 这一片的采集器（缓冲复用，只由主线程碰）。 */
        FluidBodyCollector collector;
        /**
         * 本片的<b>种子</b>（本次点名格的 section 内索引）。
         *
         * <p>★ 只有「没算完」（引擎被预算打断 / 采集被片格数预算截断）时用它整片重排：
         * 这一批的每一格都已经交给过引擎，只按「算了几个」重排会让它们一格都不排队
         * ⇒ 水位变了却不再算、水永久静止（见 {@link #drainResults}）。
         */
        final WaterWorkSet seeds = new WaterWorkSet();
        /** 求解任务是否在途（在途期间不得重采、不得改写 snapshot / piece）。 */
        boolean inFlight;
        /** 在途任务占用的求解槽（-1 = 没占）。 */
        int slot = -1;
        /**
         * 本 work 认领的整片指纹（0 = 没认领）。
         *
         * <p>★ 阶段 2b 的调度单位是 region，计算单位却是<b>跨 region 的一整片水</b>：同一片会被
         * 它跨到的每个 region 各采一次。两份在途结果先后落地，同一份水就被铺了两遍 ——
         * 实机一桶 8 单位涨到 24 单位（= 8×3）就是这么来的。指纹是「一片同时只有一个在途 owner」的凭据。
         */
        long claimFingerprint;
        /**
         * 上一次采到的片格数（-1 = 还没采过）。采集排序用它实现「小片先算」。
         */
        int sizeHint = -1;
        /** 连续多少个 tick 欠着采集没被选中（防大片 / 远片被源源不断的小片饿死）。 */
        int starveTicks;

        RegionWork(final long regionKey) {
            this.regionKey = regionKey;
        }
    }

    /**
     * 工作线程 → 主线程的一批结果（{@code processed} = 这一批「交给引擎」的格数）。
     *
     * <p>{@code unfinished} = 这一批没算完（新引擎旁路被额度 / sweep 护栏打断，见
     * {@code FluidDelta#unfinished()}）：此时<b>变化集为空也不代表不动点</b>，
     * 驱动层必须整批重排、绝不许据此把这个 region 判成收敛（旧路径恒为 false）。
     */
    private record SolveResult(long regionKey, FluidWritePlan plan, int used, int processed,
                               boolean unfinished) {
    }

    private final WaterLevelStore store = new WaterLevelStore();

    /**
     * <b>自然水源集合</b>（该维度一份，与 {@code NaturalWaterSourcesSavedData} 共享同一个实例）。
     *
     * <p>由 {@link WaterPhysicsModule#bridge} 在维度加载时注入；采集（{@link RegionSnapshot}）
     * 用它把「世界生成时就在 + 群系命中」的水格打成恒定水源。未注入时是空集合
     * ⇒ 没有任何自然水源（不会误打标）。
     */
    private NaturalWaterSources naturalSources = new NaturalWaterSources();
    private final Map<Long, RegionWork> pending = new LinkedHashMap<>();
    /** 整片采集的种子缓冲（世界坐标打包；只主线程用，按点名格数增长）。 */
    private long[] seedScratch = new long[64];
    /** 写回分组的临时容器（section 键 → 该 section 的写意图）；只主线程用，每批清一次。 */
    /** 求解线程交回的一批结果。 */
    private final ConcurrentLinkedQueue<SolveResult> results = new ConcurrentLinkedQueue<>();
    private final WriteBackQueue writeBack = new WriteBackQueue();
    /** 每 tick 发放的运算额度（对齐其它子包的心跳预算：重置而不是累加）。 */
    private final ComputeBudget computeBudget = new ComputeBudget();

    // ---- 事务框架：采集 = MAIN+READ / 求解 = ASYNC+READ / 写回 = MAIN+WRITE ----
    private static final long[] NO_KEYS = new long[0];
    private static final int SOLVE_SLOTS = 64;

    /**
     * 一次 {@code bridge.tick()} 内最多推进多少个调度阶段。
     *
     * <p>{@link TxnScheduler#tick()} 每次只推进一步（派发读 / 等屏障 / 派发写 / 主线程周期），
     * 而一个完整的「采集 → 求解 → 写回」周期要好几步。若每 tick 只推一步，
     * 水每秒钟就只能挪四五次 —— 倒一桶水看起来就是「不动」。
     * 所以在同一 tick 内反复推进，直到真的在等外部异步（屏障未归零）或无事可做。
     */
    private static final int MAX_PHASE_ROUNDS_PER_TICK = 8;

    /**
     * <b>每 tick 只给一轮写主世界</b>。
     *
     * <p>这是新契约里的节流阀：节流的位置从「每 tick 每区域只做 1 次转移」
     * 搬到了「每 tick 只落地一轮结果」。轮内条数由 writeBudget 限制；
     * 一轮没写完（预算用尽）就下一 tick 接着写同一个 region，不丢。
     */
    private static final int WRITE_ROUNDS_PER_TICK = 1;

    /** 本 tick 已经写了几轮（主线程计数）。 */
    private int writeRoundsThisTick;
    /** 本 tick 已经读了多少格（读上限必须按 tick 算，不能每轮都发一份满额）。 */
    private int readBudgetUsedThisTick;
    /** 本 tick 每个 region 的额度（按「真正要算的 region 数」均分剩余额度，至少 1）。 */
    private int quotaPerRegionThisTick;



    private static final java.util.concurrent.Executor PHYSICS = r -> ThreadDispatchers.submitPhysics(r);

    private final TxnPool txnPool = new TxnPool(64);
    private final TxnScheduler txn = new TxnScheduler(txnPool, PHYSICS, this::onRead, this::onWrite);
    private final RegionWork[] solveWorks = new RegionWork[SOLVE_SLOTS];
    private final int[] solveQuota = new int[SOLVE_SLOTS];

    /**
     * 空闲求解槽池 —— <b>槽随任务生命周期分配，绝不复用还在跑的槽</b>。
     *
     * <p>★ 旧写法是 {@code solveSlotNext++ % SOLVE_SLOTS}：大水域跨很多 region 时，
     * 一 tick 的提交量可能超过槽数，旧 work 的槽被顶掉 ⇒ 它永远收不到结果 ⇒
     * {@code inFlight} 永远为真 ⇒ 那个 region 再也不会被采集/派发（永久静止、不可恢复）。
     * 取不到槽就不派发，等下一 tick。
     */
    private final java.util.ArrayDeque<Integer> freeSolveSlots = new java.util.ArrayDeque<>();

    /** 待唤醒的 section（区块加载后把「侧表里还有水」的格重新点名）。 */
    private final java.util.ArrayDeque<Long> wakeQueue = new java.util.ArrayDeque<>();

    /** 每 tick 最多唤醒几个 section（一个 section 4096 格，摊开做避免加载时卡顿）。 */
    private static final int WAKE_SECTIONS_PER_TICK = 4;

    public WaterPhysicsBridge() {
        for (int i = 0; i < SOLVE_SLOTS; i++) {
            freeSolveSlots.add(i);
        }
    }

    // 主线程单线程 ⇒ 用普通字段把本轮上下文传给 body，不必给 Txn 加引用字段
    private ServerLevel activeLevel;
    private int readBudget;
    private int writeBudget;
    private int consumedThisTick;
    private int appliedThisTick;
    private int skippedThisTick;
    private int readCellsThisTick;
    private long writeNanosThisTick;
    private long captureNanosThisTick;

    /** 性能探针：debug 时每 1 秒（20 tick）打印一次分段统计（全英文，中文在日志里会乱码）。 */
    private final WaterphysicsProbe probe = new WaterphysicsProbe();
    private static final int PROBE_REPORT_TICKS = 20;
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("cryptand.waterphysics");

    /** 正在分帧采集的那个 region（null = 没有）。 */
    private RegionWork scanning;
    private long tickCount;

    /** 记录本 tick 的采集/写回与总耗时；debug 时定期打印探针报告。 */
    private void finishTick(final long tickStart) {
        if (consumedThisTick > 0 || appliedThisTick > 0 || skippedThisTick > 0) {
            probe.addWriteBack(consumedThisTick, appliedThisTick, skippedThisTick, writeNanosThisTick);
        }
        if (readCellsThisTick > 0) {
            probe.addCapture(readCellsThisTick, captureNanosThisTick);
        }
        final long totalNanos = System.nanoTime() - tickStart;
        probe.addTick(totalNanos);
        if (!ConfigWaterphysics.DEBUG.get()) {
            return;
        }
        if (tickCount % PROBE_REPORT_TICKS == 0) {
            LOGGER.info("{} | pending={} scanning={} writeback={} quotaLeft={}",
                    probe.report(), pending.size(), scanning != null ? 1 : 0,
                    writeBack.size(), computeBudget.remaining());
            probe.reset();
        }
    }

    public WaterLevelStore store() {
        return store;
    }

    /** 该维度的自然水源集合（登记写入与采集查询共用）。 */
    public NaturalWaterSources naturalSources() {
        return naturalSources;
    }

    /** 注入该维度的自然水源集合（由 {@link WaterPhysicsModule#bridge} 在维度加载时调用）。 */
    public void setNaturalSources(final NaturalWaterSources sources) {
        this.naturalSources = sources == null ? new NaturalWaterSources() : sources;
    }

    /**
     * <b>外部变更</b>入口：这一格（连同六邻居）需要重新采集（点名）。
     *
     * <p>★ 作废可被配置关掉（{@code enableEpochInvalidation=false}）—— 那是给「连续多格水异常」
     * 做 A/B 判别的诊断开关：关掉后不再推进世代、也不再丢弃在途结果。
     *
     * <p>★ 必须连同六邻居一起点名：{@link RegionSnapshot} 的约定是「只有点名格才进
     * {@code active} 参与求解，沿连通水域扩散进来的邻居只是为了让快照完整（守恒）」——
     * 而改变一格的世界状态，真正会动的是<b>它的邻居</b>：
     * <ul>
     *   <li>打掉水下方的方块 → 要落下的是<b>上方那格水</b>；</li>
     *   <li>往水里放方块 → 要挤开的是<b>侧面那几格水</b>。</li>
     * </ul>
     *
     * <p>★ 同时把该 region 的世代 +1：这一格的世界状态变了，正在算的那一轮（可能跨了
     * 好几个 tick）用的是旧世界，必须作废重算。
     */
    public void postCell(final int x, final int y, final int z) {
        postAround(x, y, z, true);
    }

    /**
     * <b>原版流体 tick 的提醒</b>入口（{@code FlowingFluidTickMixin} 调）：这一格水该被重新考虑，
     * <b>但世界并没有变</b> —— 原版 tick 已在 HEAD 处被取消，真正改水位的活是我们的求解器干的。
     *
     * <p>★ 绝不能走 {@link #postCell}：那会推进 region 世代。原版流体 tick <b>每 tick 都在水格上
     * 触发</b>，一旦推进世代，正在算的那一轮每 tick 都被作废 ⇒ 串永不算完、水冻住
     * （2026-09-28 的「水源静止」回归就是这条）。
     */
    public void postCellTick(final int x, final int y, final int z) {
        postAround(x, y, z, false);
    }

    /**
     * 自身 + 六邻居各点名一次。
     *
     * @param external true = 世界真的变了（要推进世代、作废在途那一轮）；
     *                 false = 只是提醒/自反馈（只点名）
     */
    private void postAround(final int x, final int y, final int z, final boolean external) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            // ★ 总开关关掉后：外部入口一律不点名（否则桶/活塞/落沙/放置/破坏这些
            //   「只点名」的注入点会持续堆待办，而 tick 已被门禁 ⇒ pending 只增不减）。
            //   注意写回路径走的是 postCellKey，不经过这里 —— 关开关不影响的写回语义。
            return;
        }
        if (external) {
            // ★ 先挤水：这一格马上要被方块占住，先把这里的水摊给邻居（语义对齐：原版的挤水置换）。
            //   少了它，放方块/活塞推/落沙就是「把水直接抹掉」—— 水量不守恒，玩家看到水凭空消失。
            displaceWater(x, y, z);
            // ★ 世界真的变了 ⇒「自己 + 六邻居」的侧表都过期了，必须清掉。
            //   FluidLevels.resolve 的规则是「世界有水 && 侧表非 0 ⇒ 取侧表」，
            //   不清的话新放的水源会被侧表的旧水位盖住（症状：在已有水里放水源，不涨也不铺开）。
            forgetSide(x, y, z);
            forgetSide(x, y - 1, z);
            forgetSide(x, y + 1, z);
            forgetSide(x, y, z - 1);
            forgetSide(x, y, z + 1);
            forgetSide(x - 1, y, z);
            forgetSide(x + 1, y, z);
        }
        postOne(x, y, z, external);
        postOne(x, y - 1, z, external);
        postOne(x, y + 1, z, external);
        postOne(x, y, z - 1, external);
        postOne(x, y, z + 1, external);
        postOne(x - 1, y, z, external);
        postOne(x + 1, y, z, external);
    }

    /** 让某一格退回「世界说了算」：清掉它的侧表水位。 */
    private void forgetSide(final int x, final int y, final int z) {
        store.forget(SectionCursor.key(x >> 4, y >> 4, z >> 4),
                SectionCursor.linearIndex(x & 15, y & 15, z & 15));
    }

    /** 6 个轴向邻居（挤水用）。 */
    private static final int[][] DISPLACE_DIRS = {
            {0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1},
    };

    /** 挤水时实在没地方放、只能丢弃的水量（诊断用）。 */
    private int displacedLost;

    /**
     * <b>挤水</b>：外部方块要占住这一格时，先把这里的水摊给邻居。
     *
     * <p>做法：本格水位先清零，然后按 6 个轴向邻居轮流填 —— 每格只填到水位上限，
     * 基准用「世界 + 侧表」合并后的真实水位；填一间就点名，让求解器接手继续往外摊。
     * 真的挤不下的部分只能丢，计入 {@link #displacedLost}（挤空的极端情形才会发生）。
     */
    private void displaceWater(final int x, final int y, final int z) {
        final ServerLevel level = activeLevel;
        if (level == null) {
            return;
        }
        final long key = SectionCursor.key(x >> 4, y >> 4, z >> 4);
        final WaterLevelField field = store.get(key);
        if (field == null) {
            return;
        }
        final int index = SectionCursor.linearIndex(x & 15, y & 15, z & 15);
        int remaining = field.level(index);
        if (remaining <= 0) {
            return;
        }
        field.setLevel(index, 0);
        final McFluidCellView view = new McFluidCellView(level, store);
        for (final int[] d : DISPLACE_DIRS) {
            if (remaining <= 0) {
                break;
            }
            final int nx = x + d[0];
            final int ny = y + d[1];
            final int nz = z + d[2];
            final int kind = view.kindAt(nx, ny, nz);
            if (kind != FluidCellKind.AIR && kind != FluidCellKind.FLUID) {
                continue;                       // 只往「本来就能装水」的格挤
            }
            final int base = view.levelAt(nx, ny, nz);
            final int room = WaterLevelField.MAX_LEVEL - base;
            if (room <= 0) {
                continue;                       // 这一格也满了
            }
            final int move = Math.min(remaining, room);
            final long nKey = SectionCursor.key(nx >> 4, ny >> 4, nz >> 4);
            final int nIndex = SectionCursor.linearIndex(nx & 15, ny & 15, nz & 15);
            store.getOrCreate(nKey).setLevel(nIndex, base + move);
            postCellKey(nKey, nIndex);
            remaining -= move;
        }
        displacedLost += remaining;
    }

    private void postOne(final int x, final int y, final int z, final boolean external) {
        final long key = SectionCursor.key(x >> 4, y >> 4, z >> 4);
        final int index = SectionCursor.linearIndex(x & 15, y & 15, z & 15);
        if (external && ConfigWaterphysics.ENABLE_EPOCH_INVALIDATION.get()) {
            // ★ 世界真的变了：这一批（可能正被采集/求解）用的是旧世界 ⇒ 丢弃重采。
            //   开关（enableEpochInvalidation）可以关掉它做 A/B：关掉后不丢在途结果。
            //   在途任务仍会跑完，但结果回收时发现 snapshot 已被丢弃，会投一个「放弃」结果把
            //   inFlight 复位 —— 绝不能让它悬着（那是「一旦静止就永远不动」的根因）。
            probe.addBump();
            final RegionWork work = pending.get(key);
            if (work != null) {
                work.collecting = null;
                work.snapshot = null;
                // ★ 阶段 2b：整片是在**旧世界**上采的，外部变更同样必须作废它 ——
                //   少了这一句，在途求解会拿旧片的结果写回，把玩家的改动盖掉。
                //   在途任务照旧跑完，回收时发现 piece 已被丢弃 ⇒ 投一个「放弃」结果复位 inFlight。
                work.piece = null;
                if (scanning == work) {
                    scanning = null;
                }
            }
        }
        postCellKey(key, index);
    }

    /**
     * <b>写回后的内部唤醒</b>（由 {@link FluidApplier} 在投影水位后调用）：<b>自己 + 六邻居</b>，
     * 不推进世代。
     *
     * <p>★ 为什么必须连邻居一起叫醒：一次水位变化真正改变的是<b>邻居能不能动</b> ——
     * 下方水位降了，<b>上方那一格</b>才有空间下泄；这一格降了，同层更高的邻居才能继续往外分。
     * 只叫醒自己的话，<b>本轮水位没变的格就永久退出求解</b> —— 实机症状正是
     * 「高水位一侧的水柱不往下泄、连通器一端高一端低」。
     *
     * <p>与外部入口 {@link #postCell} 的唯一区别是<b>不推世代</b>：写回是我们自己的结果落地，
     * 不是外部变更；推世代会把正在算的那一轮每 tick 作废一次。
     */
    public void postApplied(final int x, final int y, final int z) {
        postAround(x, y, z, false);
    }

    /**
     * <b>自己写回</b>入口（由 {@link FluidApplier} 在投影水位后调用）：只点名，不推进世代。
     *
     * <p>写回本身就是「我们自己的结果落地」，它是下一轮的输入，不是外部变更；
     * 若这里也推进世代，每写一次就把自己作废一次，永远算不完。
     */
    public void postCellKey(final long regionKey, final int index) {
        probe.addPost();
        final RegionWork work = pending.computeIfAbsent(regionKey, RegionWork::new);
        if (work.collecting != null) {
            // 正在采集这个 region ⇒ 直接补进去，本帧一起扫完再提交（不丢事件）
            work.collecting.mark(index);
        }
        schedule(work, index, ConfigWaterphysics.FLUID_TICK_RATE.get());
    }

    /**
     * <b>排进延后表</b>：{@code delay} 个 tick 之后到期（= 排进延后表）。
     *
     * <p>已经在队列里的格取<b>更早</b>的到期时间 —— 提醒/重排不该把已经在等的那一格往后推。
     */
    private void schedule(final RegionWork work, final int index, final int delay) {
        final int cur = work.dueIn[index];
        if (cur == 0) {
            work.queued++;
            work.dueIn[index] = delay;
        } else if (delay < cur) {
            work.dueIn[index] = delay;
        }
    }

    /**
     * 每 tick 推进延后表：到期（{@code dueIn == 1}）的格进入本批，其余递减。
     *
     * <p>只遍历「还有格排队」的 region（{@code queued > 0}）—— 空闲 region 一帧都不花。
     */
    private void tickDue() {
        for (final RegionWork work : pending.values()) {
            if (work.queued == 0) {
                continue;
            }
            final int[] dueIn = work.dueIn;
            for (int i = 0; i < dueIn.length; i++) {
                final int v = dueIn[i];
                if (v == 0) {
                    continue;
                }
                if (v <= 1) {
                    dueIn[i] = 0;
                    work.queued--;
                    work.due.add(i);
                } else {
                    dueIn[i] = v - 1;
                }
            }
        }
    }

    /**
     * 还有没有活要干 —— 待采集的点名格、正在采集的快照、没写完的写回、或没算完的一轮。
     *
     * <p>★ 必须包含写回：采集队列空但写回没干净时若被判成「无事可做」而提前退出，
     * 写回就永远停在那里。
     *
     * <p>★ 也必须包含唤醒队列（{@link #wakeChunk}）：它由「区块加载」投递，
     * 而 {@link WaterPhysicsModule#tick} 是先问 hasWork 再进 {@link #tick} 的 ——
     * 少这一条，重进世界（或区块重载）时那一批「侧表里还有水」的格永远等不到
     * {@link #drainWakeQueue}，水就一直静止。
     */
    public boolean hasWork() {
        return scanning != null || !pending.isEmpty() || !writeBack.isEmpty()
                || !results.isEmpty() || !wakeQueue.isEmpty()
                || txn.backlog() > 0 || txn.pendingReads() > 0;
    }

    public long tickCount() {
        return tickCount;
    }

    /**
     * 主线程每 tick 调用一次。
     *
     * @param readBudget  本 tick 最多读多少个格（读上限，来自 maxWorkPerTick）
     * @param writeBudget 本 tick 那一轮写最多写回多少条变更
     */
    public void tick(final ServerLevel level, final int readBudget, final int writeBudget) {
        final long tickStart = System.nanoTime();
        tickCount++;
        // ★ 先把延后表推进一步：到期的格进入本批，然后才开始采集/派发。
        tickDue();
        // ★ 区块加载唤醒（每 tick 只做几个 section，避免重进世界时卡一下）。
        //   我们在卸载时会丢掉全部内存待办（与「原版把待办交还原版 fluid tick 队列」不同），
        //   所以重载后必须自己把「侧表里还有水」的格重新点名 —— 否则静止的水永远不再流动。
        drainWakeQueue(WAKE_SECTIONS_PER_TICK);
        // ★ 心跳：每 tick 把运算额度重置为配置值（与上一 tick 剩多少无关）
        computeBudget.grant(ConfigWaterphysics.COMPUTE_BUDGET_PER_TICK.get());
        // ★ 额度按「真正要算的 region 数」均分：不能让队首 region 吃光整 tick 额度，否则后面的
        //   region 拿到 quota == 0，sweep 一步都不走；也不能用 pending.size() —— 里面躺着大量
        //   空壳（没点名格、没快照），会把每份额度摊薄到 1，大地水域就慢得像静止。
        quotaPerRegionThisTick = computeBudget.share(liveRegionCount());

        // 本轮上下文（主线程单线程，body 直接读字段）
        this.activeLevel = level;
        this.readBudget = readBudget;
        this.writeBudget = writeBudget;
        this.consumedThisTick = 0;
        this.appliedThisTick = 0;
        this.skippedThisTick = 0;
        this.readCellsThisTick = 0;
        this.writeNanosThisTick = 0L;
        this.captureNanosThisTick = 0L;
        this.writeRoundsThisTick = 0;
        this.readBudgetUsedThisTick = 0;

        // ★ 一次 tick 内尽可能推进多个阶段（框架的 tick() 每调用只推进一步）。
        for (int round = 0; round < MAX_PHASE_ROUNDS_PER_TICK; round++) {
            // 回收求解结果（工作线程可能在循环中途就交回结果）
            drainResults();

            // 采集：写回没落干净时不开新采集（否则会读到「算了一半」的世界）。
            //   ★ 每 tick 只提交一条：以前在 8 轮里每轮都提交，而异步求解一旦积压（stage 停在
            //   PARALLELIZING），主线程读桶（64）会在 8 tick 内被顶满 ⇒ 背压丢弃/过去的溢出卡死。
            if (round == 0 && txn.mainBacklog() == 0 && writeBack.isEmpty() && hasCaptureWork()) {
                txn.submit(NO_KEYS, 0, TxnKind.READ, TxnExec.MAIN, 0);
            }
            // 写回：每 tick 只给一轮。
            if (!writeBack.isEmpty() && writeRoundsThisTick < WRITE_ROUNDS_PER_TICK) {
                txn.submit(NO_KEYS, 0, TxnKind.WRITE, TxnExec.MAIN, 0);
            }
            // 上一 tick 额度用尽而没派发的已采快照 ⇒ 本 tick 补派发（否则它永远停在那里）
            dispatchReadySolves();

            if (round > 0 && txn.backlog() == 0
                    && txn.pendingReads() == 0 && txn.pendingWrites() == 0) {
                break;   // 无事可做，且不在等任何异步
            }
            txn.tick();  // 第 0 轮无条件走一次：collect() 要先把已提交的收进待办桶
        }

        // ★ 采集饥饿计时：本 tick 还欠着采集的 region 记一笔（防大片 / 远片被小片饿死）。
        for (final RegionWork work : pending.values()) {
            if (work.snapshot == null && work.piece == null && work.collecting == null
                    && !work.inFlight && !work.due.isEmpty()) {
                work.starveTicks++;
            } else {
                work.starveTicks = 0;
            }
        }
        reapIdleRegions();
        finishTick(tickStart);
    }

    /** READ 事务的 body：主线程侧 = 采集，工作线程侧 = 求解。 */
    private void onRead(final Txn t) {
        if (t.exec() == TxnExec.ASYNC) {
            solveTick(t.payload());
        } else {
            captureTick();
        }
    }

    /**
     * WRITE 事务的 body：把已算好的写意图按写预算落地 —— <b>每 tick 只允许一轮</b>。
     *
     * <p>本轮已经写过就直接返回：这一轮没写完的 plan 留在队首，下一 tick 接着写。
     */
    private void onWrite(final Txn t) {
        if (writeRoundsThisTick >= WRITE_ROUNDS_PER_TICK) {
            return;
        }
        writeRoundsThisTick++;
        final long start = System.nanoTime();
        while (!writeBack.isEmpty()) {
            final WriteBackQueue.Entry head = writeBack.peek();
            final FluidWritePlan plan = head.plan();
            final int pending = plan.pendingLevels() + plan.pendingBlocks();
            // ★ 片是原子单位（用户 2026-09-29 口径）：小片一次消费干净（水位段 + 方块段），
            //   只有整片条数超过写预算时才退回分帧，下一 tick 接着写同一个片。
            final int used = pending <= writeBudget
                    ? FluidApplier.applyAll(activeLevel, store, plan, null)
                    : FluidApplier.apply(activeLevel, store, plan, writeBudget);
            consumedThisTick += used;
            appliedThisTick += FluidApplier.lastApplied();
            skippedThisTick += FluidApplier.lastSkipped();
            if (!writeBack.completeIfDrained()) {
                break; // 预算用尽，下一 tick 接着写同一个 region
            }
        }
        writeNanosThisTick += System.nanoTime() - start;
        // ★ 写回队列排空 ⇒ 世界与侧表已经一致 ⇒ 释放全部片认领（下一轮重新采、重新认领）。
        if (writeBack.isEmpty()) {
            pieceClaims.clear();
        }
    }

    // ==================================================================
    // 采集（主线程，分帧）
    // ==================================================================

    /** 本 tick 真正要算的 region 数（有点名格 / 有快照）—— 空壳不该摊薄别人的额度。 */
    private int liveRegionCount() {
        int n = 0;
        for (final RegionWork work : pending.values()) {
            if (!work.due.isEmpty() || work.queued > 0 || work.snapshot != null
                    || work.collecting != null || work.piece != null) {
                n++;
            }
        }
        return n;
    }

    /** 还有没有 region 需要采集。 */
    private boolean hasCaptureWork() {
        if (scanning != null) {
            return true;
        }
        for (final RegionWork work : pending.values()) {
            if (work.snapshot == null && work.piece == null && work.collecting == null
                    && !work.inFlight && !work.due.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 采集（旧路径）：正在扫的那个 region 扫完才提交求解，绝不一半提交。
     *
     * <p>阶段 2b 打开新引擎（{@code useFluidEngine}，默认开）时改走 {@link #capturePieceTick}：
     * 工作集从「点名格」换成「跨 region 的整片连通水」。
     */
    private void captureTick() {
        if (ConfigWaterphysics.USE_FLUID_ENGINE.get()) {
            capturePieceTick();
            return;
        }
        final long start = System.nanoTime();
        final int budget = readBudget - readBudgetUsedThisTick;
        if (budget <= 0) {
            return;   // 本 tick 读额度已用尽，下一 tick 接着采
        }
        if (scanning == null) {
            beginScan();
        }
        if (scanning != null) {
            final RegionWork work = scanning;
            final RegionSnapshot snapshot = work.collecting;
            final boolean doneScan = snapshot.advance(activeLevel, store, budget);
            readCellsThisTick += snapshot.lastReads();
            readBudgetUsedThisTick += snapshot.lastReads();
            if (doneScan) {
                scanning = null;
                work.collecting = null;
                work.snapshot = snapshot;
                // 轮转到队尾：否则 pending 里第一个 region 会一直霸着名额，其它 region 永远轮不到
                rotateToTail(work.regionKey);
                submitSolve(work);
            }
        }
        captureNanosThisTick += System.nanoTime() - start;
    }

    /**
     * <b>整片采集（阶段 2b）</b>：从本 region 的点名格出发，沿同种液体的连通性跨 region 展开
     * 「一整片连通水」，交给求解线程。
     *
     * <p>与旧路径的三点差别（都在本方法与 {@link #collectPiece} 里）：
     * <ol>
     *   <li>工作集 = 整片连通水（世界坐标，region 概念不出现），不再是局部点名格 ——
     *       均衡器因此看得到整片，假平衡消失（用户 2026-09-29 的口径）；</li>
     *   <li>采完即冻结派发（一次性、不分帧）：片格数超过本帧读预算时截断并置 unfinished，
     *       由 {@link #drainResults} 整片重排、下一轮接着算；</li>
     *   <li>片可以任意跨 region ⇒ 写回<b>不拆组</b>：整片一份 plan 直接入队，由
     *       {@code FluidApplier.applyAll} 一次落地（片是原子单位，只有超预算才分帧）。</li>
     * </ol>
     */
    private void capturePieceTick() {
        final long start = System.nanoTime();
        // ★ 片内格预算：读一格的片内格要连带看它自己 + 6 个邻居（平均触世界 4~6 次），
        //   所以按剩余读额度的 1/4 估片预算；真正扣账用 piece.reads()（真实触碰格数）。
        final int left = readBudget - readBudgetUsedThisTick;
        if (left <= 0) {
            return;     // 本 tick 读额度已用尽，下一 tick 接着采
        }
        final int budget = Math.max(1, left / 4);
        final RegionWork work = pickForCapture();
        if (work == null) {
            return;
        }
        final int used = collectPiece(work, budget);
        readCellsThisTick += used;
        readBudgetUsedThisTick += used;
        captureNanosThisTick += System.nanoTime() - start;

        // ★ due 在这里才清：它已经被**交接**给片了（片自己带格清单，采完即冻结）。
        //   绝不能在 drainResults 里清 —— 那里会吞掉「tickDue 刚移入、本 tick 还没轮到采集」的格，
        //   而 dueIn 已被置 0，丢了就再也回不来（实机表现：「水有时流有时不流」）。
        work.due.clear();
        if (work.piece != null && !work.piece.empty()) {
            // 轮转到队尾：否则 pending 里第一个 region 会一直霸着名额，其它 region 永远轮不到
            rotateToTail(work.regionKey);
            submitSolve(work);
        } else {
            work.piece = null;      // 这一批里没有液体 ⇒ 真的无事可做
            probe.addSolve(0, true);
        }
    }

    /**
     * 采一整片并把「采集到的权威水位」写成侧表基准；返回本次触碰的格数（主线程读预算按它扣）。
     */
    private int collectPiece(final RegionWork work, final int budget) {
        final long regionKey = work.regionKey;
        final int baseX = SectionCursor.keyX(regionKey) << 4;
        final int baseY = SectionCursor.keyY(regionKey) << 4;
        final int baseZ = SectionCursor.keyZ(regionKey) << 4;
        final int n = work.due.size();
        if (seedScratch.length < n) {
            seedScratch = new long[Math.max(64, Integer.highestOneBit(n) << 1)];
        }
        for (int i = 0; i < n; i++) {
            final int index = work.due.get(i);
            seedScratch[i] = FluidBodyView.pack(baseX + SectionCursor.localX(index),
                    baseY + SectionCursor.localY(index), baseZ + SectionCursor.localZ(index));
        }
        if (work.collector == null) {
            work.collector = new FluidBodyCollector();
        }
        final McFluidBodySource source = new McFluidBodySource(activeLevel, store, naturalSources,
                ConfigWaterphysics.NATURAL_SOURCE_WATER.get());
        final FluidBodyCollector.Piece piece = work.collector.collect(source, seedScratch, n, budget);
        work.piece = piece;

        // 种子留存：没算完时整片重排（下一轮接着算，见 drainResults）
        work.seeds.clear();
        for (int i = 0; i < n; i++) {
            work.seeds.add(work.due.get(i));
        }
        if (!piece.empty()) {
            seedSideBase(piece);
        }
        // ★ 片级互斥：同一片连通水会被它跨到的每个 region 各采一次。若都派发求解，两份结果
        //   先后落地就把同一份水铺了两遍（实机：一桶 8 单位 → 24 单位）。这里用片指纹认领，
        //   「已经有人在算这一片」就不再派发；下一轮（owner 写回、水位已变）指纹自然不同。
        if (!piece.empty()) {
            final long fingerprint = fingerprintOf(piece);
            final Long owner = pieceClaims.get(fingerprint);
            if (owner != null && owner != regionKey) {
                probe.addDiscard();
                work.piece = null;
                for (int i = 0; i < work.seeds.size(); i++) {
                    schedule(work, work.seeds.get(i), ConfigWaterphysics.FLUID_TICK_RATE.get());
                }
                work.seeds.clear();
                return piece.reads();
            }
            pieceClaims.put(fingerprint, regionKey);
            work.claimFingerprint = fingerprint;
            work.sizeHint = piece.body().size();         // ★ 供「小片先算」排序

        }
        return piece.reads();
    }

    /**
     * 把这一片「采集到的权威水位」写进侧表，作为写回增量的<b>基准</b>。
     *
     * <p>★ 少了这一步，写回按「侧表当前值 + 增量」落地时基准是 0：增量 = 新量 − 采集时的量，
     * 加上 0 之后世界里的水就被抹成增量值（丢水 / 水位倒退）。旧路径由
     * {@code RegionSnapshot.readOwn} 在采集时写基准，新路径在这里补。
     *
     * <p>边界格只补「本来就有水」的基准：那些格可能还没有侧表记录（它的 region 还没采过），
     * 而写回同样按增量落地 —— 不补基准就会把它们的水抹掉。
     */
    private void seedSideBase(final FluidBodyCollector.Piece piece) {
        final ArrayBody body = piece.body();
        for (int i = 0; i < body.size(); i++) {
            writeSideBase(body.packed(i), body.kind(i), body.amount(i));
        }
        for (int j = 0; j < body.borderSize(); j++) {
            if (body.borderAmount(j) > 0) {
                writeSideBase(body.borderPacked(j), body.borderKind(j), body.borderAmount(j));
            }
        }
    }

    /** 侧表写一格基准（kind + level）：kind 必须一起写，否则 level &gt; 0 会被 {@code setLevel} 拒掉。 */
    private void writeSideBase(final long packed, final FluidKind engineKind, final int level) {
        final long key = FluidWritePlan.sectionKeyOf(packed);
        final int index = SectionCursor.linearIndex(FluidWritePlan.unpackX(packed) & 15,
                FluidWritePlan.unpackY(packed) & 15, FluidWritePlan.unpackZ(packed) & 15);
        final WaterLevelField field = store.getOrCreate(key);
        field.setKind(index, cellKindOf(engineKind));
        field.setLevel(index, level);
    }

    /** 引擎 kind → 旧口径的格子种类（侧表只认 {@link FluidCellKind}）。 */
    private static int cellKindOf(final FluidKind kind) {
        if (kind.isLiquid()) {
            return FluidCellKind.FLUID;
        }
        if (kind == FluidKind.WATERLOGGABLE) {
            return FluidCellKind.WATERLOGGABLE;
        }
        if (kind == FluidKind.AIR) {
            return FluidCellKind.AIR;
        }
        return FluidCellKind.SOLID;     // 固体（PASSABLE 在映射表里也归这里）
    }

    /** 取下一个待处理的 region，把点名格灌进新快照（采集从这里开始，可跨 tick）。 */
    private void beginScan() {
        final RegionWork work = pickForCapture();
        if (work == null) {
            return;
        }
        // ★ 采集不能清空点名：region 要一直留在 pending 里，直到「一整轮一步都没动」才摘掉。
        //   代价是未稳定的 region 每轮重采同一批格 —— 幂等的重复劳动，换「一定到得了效果」。
        final RegionSnapshot snapshot = new RegionSnapshot(work.regionKey,
                ConfigWaterphysics.MAX_EQ_DIST.get(), store, naturalSources,
                ConfigWaterphysics.NATURAL_SOURCE_WATER.get());
        for (int i = 0; i < work.due.size(); i++) {
            snapshot.mark(work.due.get(i));
        }
        // ★ due 在这里才清：它已经被**交接**给快照了（快照自己带 todo，可跨 tick 采完）。
        //   绝不能在 drainResults 里清 —— 那里会吞掉「tickDue 刚移入、本 tick 还没轮到采集」的格，
        //   而 dueIn 已被置 0，丢了就再也回不来（实机表现：「水有时流有时不流」）。
        work.due.clear();
        work.collecting = snapshot;
        scanning = work;
    }

    /**
     * 补派发「已经采完、但上一 tick 因为额度用尽而没派发」的 region。
     *
     * <p>★ 少了这一步，一次 {@code submitSolve} 遇到 {@code quota == 0} 就会把快照永久留在这里
     * —— 采集不会再采它（快照非 null），也没有别的路径派发它。
     */
    private void dispatchReadySolves() {
        if (computeBudget.remaining() <= 0) {
            return;
        }
        for (final RegionWork work : pending.values()) {
            // ★ 旧路径看 snapshot、阶段 2b 看 piece：两条路径的「采完待算」是同一个语义。
            if (!work.inFlight && work.collecting == null
                    && (work.snapshot != null || work.piece != null)) {
                submitSolve(work);
            }
        }
    }

    /** 「片大小未知」时的排序提示（当作中等大小的片）。 */
    private static final int SIZE_HINT_UNKNOWN = 64;
    /** 欠采集超过这么多 tick ⇒ 插队（防大片 / 远片被小片饿死）。 */
    private static final int STARVE_TICKS = 40;

    /**
     * 采集顺序：★ <b>小片先算</b>，同级再按「离最近的玩家最近」。
     *
     * <p>小片（玩家眼前的一桶水、局部水洼）一片就能在预算内走完「采集 → 算完 → 落地」，
     * 先它们可以让反馈立刻可见，单位预算完成的片数也更多；大片（海洋）本来就要分帧，排在后面。
     *
     * <p>★ 防饥饿：欠采集超过 {@link #STARVE_TICKS} 的 region 无条件插队，否则大片 / 远片
     * 会被源源不断的小片永久饿死。
     */
    private RegionWork pickForCapture() {
        RegionWork best = null;
        long bestSize = 0;
        int bestStarve = 0;
        double bestDist = 0.0;
        for (final RegionWork work : pending.values()) {
            if (work.snapshot != null || work.piece != null || work.collecting != null
                    || work.inFlight || work.due.isEmpty()) {
                continue;
            }
            final int starve = work.starveTicks;
            final long size = work.sizeHint < 0 ? SIZE_HINT_UNKNOWN : work.sizeHint;
            final double dist = regionDistanceSq(work.regionKey);
            final boolean better;
            if (best == null) {
                better = true;
            } else if ((starve >= STARVE_TICKS) != (bestStarve >= STARVE_TICKS)) {
                better = starve >= STARVE_TICKS;             // ★ 饿着的插队
            } else if (size != bestSize) {
                better = size < bestSize;                    // ★ 小片先算
            } else {
                better = dist < bestDist;                    // 同级：离玩家最近优先
            }
            if (better) {
                best = work;
                bestSize = size;
                bestStarve = starve;
                bestDist = dist;
            }
        }
        return best;
    }

    /** region 中心到最近玩家的平方距离（没有玩家/没有世界时给 0，等价于「最先采」）。 */
    private double regionDistanceSq(final long regionKey) {
        if (activeLevel == null) {
            return 0.0;
        }
        final double cx = (SectionCursor.keyX(regionKey) << 4) + 8.0;
        final double cy = (SectionCursor.keyY(regionKey) << 4) + 8.0;
        final double cz = (SectionCursor.keyZ(regionKey) << 4) + 8.0;
        double best = Double.MAX_VALUE;
        for (final ServerPlayer player : activeLevel.players()) {
            final double dx = player.getX() - cx;
            final double dy = player.getY() - cy;
            final double dz = player.getZ() - cz;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    private void rotateToTail(final long regionKey) {
        final RegionWork work = pending.remove(regionKey);
        if (work != null) {
            pending.put(regionKey, work);
        }
    }

    // ==================================================================
    // 求解（工作线程）
    // ==================================================================

    /**
     * 整个 region 采完 ⇒ 排成 ASYNC+READ 事务交给工作线程，并<b>把这一次计算的额度
     * 随任务一起下发</b>（额度单位 = 水量转移次数，也就是「能算多少格」）。
     */
    private void submitSolve(final RegionWork work) {
        if (ConfigWaterphysics.USE_FLUID_ENGINE.get()) {
            submitPieceSolve(work);
            return;
        }
        final RegionSnapshot snapshot = work.snapshot;
        if (snapshot.active().isEmpty()) {
            // 到期了但没有一格是水 ⇒ 这一批没有可算的东西，别白派一次线程。
            // 快照与这一批一并丢掉（否则 dispatchReadySolves 每一轮都会再捡起它）。
            work.snapshot = null;
            work.due.clear();
            probe.addSolve(0, true);
            return;
        }
        // ★ 运算额度（单位 = 格数）必须在主线程取（ComputeBudget 不是线程安全的），且按区域限额：
        //   一次 take(remaining()) 会让第一个 region 吃光整 tick 额度，后面的 quota=0。
        final int quota = computeBudget.take(Math.min(quotaPerRegionThisTick, computeBudget.remaining()));
        if (quota <= 0) {
            // 本 tick 额度已空：不派发，保留采好的快照，下一 tick 拿新额度再算
            return;
        }
        final Integer slot = freeSolveSlots.poll();
        if (slot == null) {
            // 在途任务已占满全部槽 ⇒ 本 tick 不派发（保留快照），下一 tick 再试
            return;
        }
        probe.addSubmit(snapshot.active().size(), snapshot.scannedCells().size(), snapshot.skippedUnloaded());
        work.inFlight = true;
        work.slot = slot;
        solveWorks[slot] = work;
        solveQuota[slot] = quota;
        txn.submit(NO_KEYS, 0, TxnKind.READ, TxnExec.ASYNC, slot);
    }

    /**
     * 派发一个<b>整片</b>（阶段 2b）：额度单位同样是「搬运次数」，随任务一起下发。
     *
     * <p>与旧路径共用同一套槽位与额度：本 tick 额度已空（{@code quota == 0}）或没有空闲槽时
     * <b>不派发</b>，片留在 {@code work.piece} 里等下一 tick（{@link #dispatchReadySolves} 会再来捡）。
     */
    private void submitPieceSolve(final RegionWork work) {
        final FluidBodyCollector.Piece piece = work.piece;
        if (piece == null || piece.empty()) {
            work.piece = null;              // 没有液体 ⇒ 这一批没有可算的东西，别白派一次线程
            probe.addSolve(0, true);
            return;
        }
        // ★ 运算额度必须在主线程取（ComputeBudget 不是线程安全的），且按区域限额。
        final int quota = computeBudget.take(Math.min(quotaPerRegionThisTick, computeBudget.remaining()));
        if (quota <= 0) {
            return;                         // 本 tick 额度已空：保留采好的片，下一 tick 拿新额度再算
        }
        final Integer slot = freeSolveSlots.poll();
        if (slot == null) {
            return;                         // 在途任务已占满全部槽 ⇒ 下一 tick 再试
        }
        probe.addSubmit(piece.cells(), piece.border(), 0);
        work.inFlight = true;
        work.slot = slot;
        solveWorks[slot] = work;
        solveQuota[slot] = quota;
        txn.submit(NO_KEYS, 0, TxnKind.READ, TxnExec.ASYNC, slot);
    }

    /**
     * 一次计算（工作线程）：对本批到期的格逐个 {@link SpreadSolver#stepCell}（单格任务
     * 就是一格），直到额度用尽或本批算完。
     *
     * <p>★ 没轮到的格由主线程重新排回延后表，下一个 tick 继续 —— 这就是「没有计算完成的
     * 下一个 tick 继续」。
     */
    private void solveTick(final int slot) {
        final RegionWork work = solveWorks[slot];
        final int quota = solveQuota[slot];
        if (work == null) {
            return; // 槽是空的（槽只由主线程在回收时归还，正常不会发生）
        }
        if (ConfigWaterphysics.USE_FLUID_ENGINE.get()) {
            solvePieceTick(work, quota);
            return;
        }
        final RegionSnapshot snapshot = work.snapshot;
        if (snapshot == null) {
            // ★ 主线程已把这一批丢弃（外部变更）。这里**必须投一个空结果**把 work.inFlight 复位 ——
            //   直接 return 会让 inFlight 永远为真，那个 region 从此再也唤不醒
            //   （症状：水一旦静止就永远不再动）。
            results.add(new SolveResult(work.regionKey, new FluidWritePlan(), 0, 0, false));
            return;
        }
        if (ConfigWaterphysics.DEBUG.get()) {
            logFirstActive(snapshot);
        }
        final FluidWritePlan plan = new FluidWritePlan();
        final WaterWorkSet active = snapshot.active();
        int used = 0;
        int processed = 0;
        boolean unfinished = false;
        if (ConfigWaterphysics.USE_FLUID_ENGINE.get()) {
            // ★ 旁路（阶段 2a，默认关）：单个 region 拼成一片交给新引擎算一步，输出仍是同一份
            //   FluidWritePlan（增量语义）—— 采集与写回链路一个字都没动，只是「怎么算」换了引擎。
            //   跨区采集不在这里做：片只到 region 外一圈（边界格照旧由 faceKind/faceLevel 提供）。
            final FluidBridge.Result result = FluidBridge.solve(snapshot, plan, quota);
            used = result.moved();
            // 整片一次性交给引擎（引擎内部按自己的预算分片）⇒ 本批的每一格都算「交给过引擎」。
            processed = active.size();
            // ★ 没算完的标记必须带走：额度用尽（或打满 sweep 护栏）时变化集可能是空的，
            //   那是「没走遍整片」而不是「动不了」（FluidDelta 的契约）。
            unfinished = result.unfinished();
        } else {
            for (int k = 0; k < active.size(); k++) {
                if (processed >= quota) {
                    break;
                }
                used += SpreadSolver.stepCell(snapshot.field(), snapshot, snapshot.regionKey(),
                        active.get(k), plan);
                processed++;
            }
            // ★ 压力均衡器：算出这片水的平衡水面 H*，超出目标的格朝最近的缺口让 1 单位
            //   （横向铺开与竖向压力是同一件事），一轮内反复 sweep 直到静止；
            //   缺口距离场由均衡器在每个 sweep 自己重算（见 SpreadSolver.equalizeLines）。
            if (processed > 0) {
                SpreadSolver.equalizeLines(snapshot.field(), snapshot, snapshot.regionKey(),
                        active, snapshot.eqDist());
            }
            // ★ 差异输出覆盖「读过世界的全部格」，不只是一批 ——
            //   水会流进非点名的邻居格，漏了它们就等于水凭空消失（守恒破掉）。
            SpreadSolver.emitChanges(snapshot.field(), snapshot.initialLevels(),
                    snapshot.regionKey(), snapshot.scannedCells(), plan);
        }
        results.add(new SolveResult(snapshot.regionKey(), plan, used, processed, unfinished));
    }

    /**
     * 一次计算（工作线程，阶段 2b）：把<b>整片</b>交给 {@code FluidEngine} 算一步。
     *
     * <p>引擎是无状态的：跨轮状态在世界里、不在引擎里。本步只搬 {@code quota} 次左右
     * （最小分片单位 = 一个完整 sweep），没搬完由 {@code unfinished} 带走 —— 主线程
     * （{@link #drainResults}）会整片重排、下一轮重新采集并接着算。
     *
     * <p>写意图仍走 2a 的口径（{@link com.hdf.cryptand.fluid.FluidWriteIntent}：绝对新量 → 增量），
     * 只是这一份可能跨 region，由主线程按 section 拆开后再落地。
     */
    private void solvePieceTick(final RegionWork work, final int quota) {
        final FluidBodyCollector.Piece piece = work.piece;
        if (piece == null || piece.empty()) {
            // 主线程已经把这个片丢掉（外部变更 / 没水）⇒ 必须投一个空结果把 inFlight 复位 ——
            // 直接 return 会让 inFlight 永远为真，那个 region 从此再也唤不醒（水一旦静止就永不再动）。
            results.add(new SolveResult(work.regionKey, new FluidWritePlan(), 0, 0, false));
            return;
        }
        final FluidWritePlan plan = new FluidWritePlan();
        final FluidBridge.Result result = FluidBridge.solvePiece(piece, plan, quota);
        results.add(new SolveResult(work.regionKey, plan, result.moved(), piece.cells(),
                result.unfinished()));
    }

    /** 回收工作线程交回的一批结果：把没轮到的格排回延后表，有净变化就进写回队列。 */
    private void drainResults() {
        SolveResult result;
        while ((result = results.poll()) != null) {
            final RegionWork work = pending.get(result.regionKey());
            if (work == null) {
                continue;
            }
            work.inFlight = false;
            releaseSlot(work);
            if (ConfigWaterphysics.USE_FLUID_ENGINE.get()) {
                // ---- 阶段 2b：整片 ----
                final FluidBodyCollector.Piece piece = work.piece;
                work.piece = null;
                if (piece == null) {
                    // ★ 这一片在计算期间被外部变更丢弃了。work.seeds 里是「本 tick 点名要算」的格，
                    //   它们的 dueIn 已被 tickDue 置 0、due 也已在采集时清空 —— 这里必须重排回去，
                    //   否则这批唤醒永久丢失，水会抽搐一阵后突然静止（实机已见）。
                    probe.addDiscard();
                    for (int k = 0; k < work.seeds.size(); k++) {
                        schedule(work, work.seeds.get(k), 0);
                    }
                } else if (result.unfinished()) {
                    // ★ 「没算完」（引擎被额度 / sweep 护栏打断，或**采集侧**被片格数预算截断）必须
                    //   <b>整片</b>重排：这一片的每一格都已经交给过引擎，只按「算了几个」重排会让这一片
                    //   一格都不排队 ⇒ 水位变了却不再算 ⇒ region 被摘掉、水永久静止。
                    //   下一轮以这些点名格为种子重新展开整片（引擎无状态，跨轮状态在世界里）。
                    for (int k = 0; k < work.seeds.size(); k++) {
                        schedule(work, work.seeds.get(k), ConfigWaterphysics.FLUID_TICK_RATE.get());
                    }
                }
                work.seeds.clear();
            } else {
                final RegionSnapshot snapshot = work.snapshot;
                work.snapshot = null;
                if (snapshot != null) {
                    // ★ 这一批算完（可能没轮完）：没轮到的格排回延后表，下一个 tick 继续。
                    //   已经算过的格若水位变了，会由写回路径（FluidApplier → postCellKey）重新排队。
                    final WaterWorkSet active = snapshot.active();
                    // ★ 「没算完」（新引擎被额度 / sweep 护栏打断）必须<b>整批</b>重排：这一批的每一格都
                    //   已经交给过引擎（processed == active.size()），只按 processed 重排会让这一批一格都
                    //   不排队 ⇒ 水位变了却不再算 ⇒ region 被摘掉、水永久静止。unfinished ⇒ 从 0 重排。
                    final int from = result.unfinished() ? 0 : result.processed();
                    for (int k = from; k < active.size(); k++) {
                        schedule(work, active.get(k), ConfigWaterphysics.FLUID_TICK_RATE.get());
                    }
                } else {
                    probe.addDiscard();     // 这一批在计算期间被外部变更丢弃了
                }
            }
            // ★ 这里**不能**清 due：本 tick 由 tickDue 移入、但还没轮到 beginScan 的格必须留着，
            //   否则唤醒被吞、region 静默（见 beginScan 的注释）。
            final int changed = result.plan().levelCount() + result.plan().blockCount();
            // ★ 收敛判据 =「一步没变」<b>且</b>「这一批真的算完了」：被额度打断（unfinished）时变化集
            //   为空也不代表不动点（FluidDelta 的契约），据此摘 region 会让水永久冻住。
            if (changed == 0 && !result.unfinished()) {
                // 水位一步没动 ⇒ 不再排队，这个 region 自然收敛（延后表空 ⇒ reapIdle 摘掉）
                probe.addSolve(0, true);
            } else {
                probe.addSolve(changed, false);
                if (changed > 0) {
                    enqueuePlan(result.regionKey(), result.plan());
                }
            }
        }
    }

    /**
     * 把一份写意图送进写回队列。
     *
     * <p><b>旧路径</b>：一份 plan 就是一个 region 的，直接入队。
     *
     * <p><b>阶段 2b</b>：整片可能跨 region，但<b>不再按目标 section 拆组</b> —— 采集整片、
     * 整片算完、整片一次落地（片是原子单位，2026-09-29 用户口径）。以前拆开分别入队，
     * 落地就被拆成「一块一块」，观感上有的 section 先动、有的后动。
     */
    private void enqueuePlan(final long ownerRegionKey, final FluidWritePlan plan) {
        // ★ 片是原子单位（2026-09-29 用户口径）：采集整片 → 整片算完 → 一次性落地。
        //   以前按目标 section 拆成多份 plan 分别入队，落地就被拆成「一块一块」，
        //   观感上有的 section 先动、有的后动。片内格本来就是一次算完的，写回也一次走完。
        writeBack.enqueue(ownerRegionKey, plan);
    }

    /** 归还求解槽（幂等：没占槽时什么也不做）。 */
    private void releaseSlot(final RegionWork work) {
        final int slot = work.slot;
        if (slot < 0) {
            return;
        }
        work.slot = -1;
        solveWorks[slot] = null;
        freeSolveSlots.add(slot);
    }

    /** 没有点名、没有快照、没有在途任务的 region ⇒ 摘出 pending（避免空壳常驻）。 */
    private void reapIdleRegions() {
        final Iterator<Map.Entry<Long, RegionWork>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            final RegionWork work = it.next().getValue();
            if (!work.inFlight && work.snapshot == null && work.piece == null
                    && work.collecting == null && work.due.isEmpty() && work.queued == 0
                    && !writeBack.isInFlight(work.regionKey)) {
                it.remove();
            }
        }
    }

    /**
     * 诊断：把工作集第一格和它四个水平邻居的 (kind, level) 打一行出来。
     *
     * <p>用来一次定位「四周不流」：如果水格 level 明显高于邻居而邻居 kind 又不是 SOLID，
     * 求解却没有转移，那问题在求解侧；如果邻居 kind 是 SOLID/未采集，问题在采集侧。
     * 仅在 debug=true 时输出，全英文（中文进日志会乱码）。
     */
    private void logFirstActive(final RegionSnapshot snapshot) {
        final WaterWorkSet active = snapshot.active();
        if (active.isEmpty()) {
            LOGGER.info("solve rk={} active=0", snapshot.regionKey());
            return;
        }
        final WaterLevelField f = snapshot.field();
        final int idx = active.get(0);
        final int lx = SectionCursor.localX(idx);
        final int ly = SectionCursor.localY(idx);
        final int lz = SectionCursor.localZ(idx);
        final int bx = SectionCursor.keyX(snapshot.regionKey()) << 4;
        final int by = SectionCursor.keyY(snapshot.regionKey()) << 4;
        final int bz = SectionCursor.keyZ(snapshot.regionKey()) << 4;
        final StringBuilder sb = new StringBuilder();
        final int[][] dirs = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}};
        for (final int[] d : dirs) {
            final int ni = SpreadSolver.indexInRegion(bx, by, bz, bx + lx + d[0], by + ly, bz + lz + d[2]);
            if (ni < 0) {
                sb.append(" [-1:-1]");
            } else {
                sb.append(" [k").append(f.kind(ni)).append(":l").append(f.level(ni)).append(']');
            }
        }
        LOGGER.info("solve rk={} active={} firstLocal=({},{},{}) level={} N:{}",
                snapshot.regionKey(), active.size(), lx, ly, lz, f.level(idx), sb);
    }

    /**
     * 把写回队列里**还没落地的水位**全部应用掉（世界保存 / 维度卸载前调用）。
     *
     * <p>★ 少了它，「已算出但还没写回」的那份水位会静默丢失 ——
     * 侧表是随存档保存的，而这份水位还没进侧表。
     */
    public void flushWriteBack(final ServerLevel level) {
        flushWriteBack(level, null);
    }

    /**
     * 同上，但额外**钉住**一个「正在卸载、已经从 chunk source 摘掉、紧接着就要落盘」的区块。
     *
     * <p>时序依据（1.21.1 + NeoForge 21.1.231，javap 实测 {@code ChunkMap} 的卸载 Runnable）：
     * <pre>
     *   visibleChunkMap.remove() → setLoaded(false) → post(ChunkEvent.Unload) → save(chunk) → level.unload(chunk)
     * </pre>
     * 事件是在 {@code save(chunk)} **之前**抛的，所以此刻写进这个区块实例的水位会被紧随其后的
     * 存档带上；而此刻它已经不在 visibleChunkMap 里，{@code ServerChunkCache.getChunkNow} 返回 null ——
     * 不钉住它，这批水位只会在「写不进去」里被静默跳过。
     *
     * @param pinned 正在卸载的区块（null = 没有；只在坐标匹配时使用）
     */
    public void flushWriteBack(final ServerLevel level, final LevelChunk pinned) {
        this.activeLevel = level;
        while (!writeBack.isEmpty()) {
            final WriteBackQueue.Entry head = writeBack.peek();
            // ★ 预算由 FluidApplier.applyAll 自己取「恰好待消费条数」——
            //   这里绝不能再传 Integer.MAX_VALUE：FluidWritePlan 求消费上界用的是
            //   {@code cursor + budget}，游标非 0 时 MAX_VALUE 会整数溢出成负数 ⇒
            //   一条都不消费 ⇒ plan 永远 hasPending ⇒ 队列永久堵死、整份水位被静默丢掉
            //   （「卸载 / 关服丢水」的根因）。预算恒等于剩余条数 ⇒ 不可能溢出，且必然一次清空。
            FluidApplier.applyAll(level, store, head.plan(), pinned);
            if (!writeBack.completeIfDrained()) {
                // 走到这里说明这一条没被消费干净（applyAll 的预算就是剩余条数，正常不可能发生）：
                // 保留队首、退出本次 flush —— 绝不在这里把它丢掉。
                return;
            }
        }
    }

    /**
     * 区块加载：把这一 chunk 里「侧表有记录」的 section 排进唤醒队列。
     *
     * <p>只有我们接管过的 section 才会有侧表记录，所以这个判断天然把「没碰过的区块」排除在外。
     */
    public void wakeChunk(final int chunkX, final int chunkZ, final int minSection, final int maxSection) {
        for (int sy = minSection; sy <= maxSection; sy++) {
            final long key = SectionCursor.key(chunkX, sy, chunkZ);
            if (store.get(key) != null) {
                wakeQueue.add(key);
            }
        }
    }

    /** 处理唤醒队列：把 section 里水位 &gt; 0 的格重新点名（它们是「本该继续流」的水）。 */
    private void drainWakeQueue(final int quota) {
        for (int n = 0; n < quota && !wakeQueue.isEmpty(); n++) {
            final long key = wakeQueue.poll();
            final WaterLevelField field = store.get(key);
            if (field == null) {
                continue;
            }
            for (int i = 0; i < WaterLevelField.CELLS; i++) {
                if (field.level(i) > 0) {
                    postCellKey(key, i);
                }
            }
        }
    }

    /** 世界卸载时清空（水位持久化由 SavedData 负责）。 */
    public void clear() {
        store.clear();
        pending.clear();
        results.clear();
        writeBack.clear();
        wakeQueue.clear();
        java.util.Arrays.fill(solveWorks, null);
        freeSolveSlots.clear();
        for (int i = 0; i < SOLVE_SLOTS; i++) {
            freeSolveSlots.add(i);
        }
        scanning = null;
    }
}
