package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.waterphysics.FluidWritePlan;
import com.hdf.cryptand.waterphysics.WaterLevelField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;

/**
 * 主线程写回器 —— 核心算完的 {@link FluidWritePlan} 只在这里落地。
 *
 * <p><b>线程约定：本类只允许主线程调用</b>（对照 powergrid/engine/PipelinePostProcess.java）。
 *
 * <p><b>水位怎么呈现给世界</b>：写进原版水方块的 {@code LEVEL} 属性
 * （{@code level = 8 - 水位}）。这样原版渲染高度、碰撞形状、以及其它 mod 读
 * {@code FluidState.getAmount()} 得到的都是真实水位 —— 不需要自定义方块、不需要网络同步、
 * 也不需要渲染 mixin。侧表（{@link WaterLevelStore}）是求解用的权威副本，方块状态是它的投影。
 *
 * <p>写预算：每 tick 至多消费 budget 条，剩下的留在 plan 里下一 tick 继续。
 */
public final class FluidApplier {

    private FluidApplier() {
    }

    /** 上一次 apply 的探针数据（仅主线程调用，无需同步）。 */
    private static int lastApplied;
    private static int lastSkipped;
    private static int lastNanos;

    /**
     * 写回投影进行中。
     *
     * <p>{@link com.hdf.cryptand.neoforge.waterphysics.mixin.LiquidBlockMixin} 据此跳过
     * {@code LiquidBlock.onPlace} 里的 {@code scheduleTick} —— 否则每次投影都会排一次原版流体 tick，
     * 下一 tick {@code FlowingFluid.tick} 又把这一格 {@code postCell} 回来，
     * 形成「写 → scheduleTick → 原版 tick → 再采集」的自持环路，把批量省下的时间全吃回去。
     *
     * <p>写回全程在主线程且是同步的，中间不会插入别人的放置，所以一个布尔标志就够。
     */
    private static boolean applying;

    public static boolean isApplying() { return applying; }

    public static int lastApplied() { return lastApplied; }
    public static int lastSkipped() { return lastSkipped; }
    public static int lastNanos() { return lastNanos; }

    /**
     * 按预算消费写意图。
     *
     * @return 本次消费条数
     */
    public static int apply(final ServerLevel level, final WaterLevelStore store,
                            final FluidWritePlan plan, final int budget) {
        if (budget <= 0) {
            return 0;
        }
        final long startNanos = System.nanoTime();
        lastApplied = 0;
        lastSkipped = 0;
        beginBatch();
        applying = true;
        int used;
        try {
            used = plan.consumeLevels(budget, new FluidWritePlan.LevelSink() {
                @Override
                public void accept(final long packedPos, final int newLevel) {
                    applyLevel(level, store, packedPos, newLevel);
                }

                @Override
                public void acceptDelta(final long packedPos, final int delta) {
                    applyLevelDelta(level, store, packedPos, delta);
                }
            });
            if (used < budget) {
                used += plan.consumeBlocks(budget - used,
                        packedPos -> applyBlock(level, store, packedPos));
            }
        } finally {
            applying = false;
        }
        lastNanos = (int) Math.min(Integer.MAX_VALUE, System.nanoTime() - startNanos);
        return used; // 统计由 WaterPhysicsBridge 的探针统一汇总打印，避免刷屏
    }

    /**
     * 把一条写意图<b>一次消费干净</b>（水位段 + 方块段），不跨 tick、不分帧。
     *
     * <p>只有落盘 / 卸载路径用它：那些路径必须把「已算出但还没写回」的水位全部落地；
     * 分帧是正常工作循环（{@link WaterPhysicsBridge#tick}）的事。
     *
     * <p>★ <b>两次调用的预算都必须是「恰好待消费条数」，绝不能用 {@code Integer.MAX_VALUE}</b>：
     * {@link FluidWritePlan#consumeLevels(int, FluidWritePlan.LevelSink)} 与
     * {@link FluidWritePlan#consumeBlocks(int, FluidWritePlan.PosSink)} 求上界用的是
     * {@code cursor + budget} —— 游标非 0 时 {@code MAX_VALUE} 直接整数溢出成负数，
     * {@code Math.min} 取到负数 ⇒ while 一条都不走 ⇒ 返回 0 条 ⇒ plan 仍然 {@code hasPending()}
     * ⇒ 写回队列<b>永远出不了队</b>，那份水位既没进世界也没进侧表，被静默丢掉
     * （「卸载 / 关服丢水」的根因）。预算恒等于剩余条数，就永远碰不到 int 上限，也就不存在溢出。
     *
     * <p>顺序：水位段先走完（预算 = 水位剩余条数 ⇒ {@code used == budget} ⇒ {@link #apply} 内部
     * 这一次跳过方块段），方块段再单独走一次（此时水位已清空，预算 = 方块剩余条数）。
     *
     * @param pinned 已从 chunk source 摘掉、但紧接着就要落盘的区块：{@link #chunkAt} 用它替代
     *               {@code getChunkNow}（那时已经查不到它了）；null = 全部走正常查找
     * @return 本次消费条数
     */
    public static int applyAll(final ServerLevel level, final WaterLevelStore store,
                               final FluidWritePlan plan, final LevelChunk pinned) {
        pinnedChunk = pinned;
        try {
            int used = apply(level, store, plan, plan.pendingLevels());
            used += apply(level, store, plan, plan.pendingBlocks());
            return used;
        } finally {
            pinnedChunk = null;
        }
    }

    /** 水位落地：更新侧表并把水位投影到方块状态。 */
    private static void applyLevel(final ServerLevel level, final WaterLevelStore store,
                                   final long packedPos, final int newLevel) {
        final WaterLevelField field = store.getOrCreate(WaterLevelStore.sectionKeyOf(packedPos));
        final int index = WaterLevelStore.localIndexOf(packedPos);
        final int oldLevel = field.level(index);
        if (oldLevel == newLevel) {
            return;
        }
        // ★ 先确认「这一格真的能把水位落进世界」，落不进去就一步都不做。
        //   如果先写侧表、世界却没写成功，下一批采集会按 FluidLevels.resolve(世界水位 = 0, 侧表) = 0
        //   把这份水位判成「外部清除」⇒ 水凭空消失（守恒破掉）。宁可水留在原格，等下一批再算。
        if (!setWaterBlock(level, packedPos, newLevel)) {
            return;
        }
        field.setLevel(index, newLevel);
        // ★ 水位变了 ⇒ 把这一格排进延后表（fluidTickRate 个 tick 后到期再算）——
        //   这就是「水继续流」的唯一驱动（重新排队）。
        //   某一格不再产生任何变化时它自然不再入队（收敛）。
        //   ★ 走 postCellKey（内部入口）而不是 postCell：这是「我们自己的结果落地」，
        //   不是外部变更，绝不能丢弃在途那一批。
        //   ★ 唤醒范围是「自己 + 六邻居」而不是只唤醒自己：水位变化真正改变的是邻居的处境 ——
        //   下方水位降了，上方那一格才有空间下泄；这一格降了，同层更高的邻居才能继续往外分。
        //   只叫醒自己的话，本轮水位没变的格就永久退出求解（实机症状：水柱不下泄、连通器一端高）。
        //   走 postApplied 而不是 postCell：这是我们自己的结果落地，绝不能推世代。
        final WaterPhysicsBridge awake = WaterPhysicsModule.bridgeOrNull(level);
        if (awake != null) {
            awake.postApplied(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));
        }
    }

    /**
     * 跨 region 的增量落水：按该格**当前**水位（侧表是权威）加 delta 再落地。
     *
     * <p>★ 不能用「产出这条增量时的快照值」当基准 —— 那条 plan 可能已经等了好几个 tick，
     * 期间这一格会被它自己 region 的任务改写。
     */
    private static void applyLevelDelta(final ServerLevel level, final WaterLevelStore store,
                                        final long packedPos, final int delta) {
        final WaterLevelField field = store.getOrCreate(WaterLevelStore.sectionKeyOf(packedPos));
        final int index = WaterLevelStore.localIndexOf(packedPos);
        final int now = field.level(index);
        final int target = Math.max(0, Math.min(WaterLevelField.MAX_LEVEL, now + delta));
        if (target == now) {
            return;
        }
        applyLevel(level, store, packedPos, target);
    }

    /** 显式方块变更：按侧表当前水位把方块状态对齐。 */
    private static void applyBlock(final ServerLevel level, final WaterLevelStore store,
                                   final long packedPos) {
        final WaterLevelField field = store.get(WaterLevelStore.sectionKeyOf(packedPos));
        final int waterLevel = field == null ? 0 : field.level(WaterLevelStore.localIndexOf(packedPos));
        setWaterBlock(level, packedPos, waterLevel);
    }

    // ==================================================================
    // 批量写回：一个 region = 一个 section = 最多一个 chunk
    // ==================================================================
    // 逐格写回每条都要 getChunkAt / getBlockState / new BlockPos 三次查找一次分配；
    // 同一个 region 的变更全在同一个 section 里，所以把 chunk 与 section 缓存下来复用到整批。
    // 这就是「一条事务 = 一批变更」落到实处的部分。

    private static LevelChunk cachedChunk;
    private static long cachedChunkKey = Long.MIN_VALUE;
    private static LevelChunkSection cachedSection;
    private static int cachedSectionIndex = Integer.MIN_VALUE;

    /**
     * <b>正在卸载的区块</b>（{@link #applyAll} 调用期间有效，null = 没有）。
     *
     * <p>1.21.1 + NeoForge 21.1.231 的卸载路径（javap 实测 {@code ChunkMap}）：
     * <pre>
     *   visibleChunkMap.remove() → setLoaded(false) → post(ChunkEvent.Unload) → save(chunk) → level.unload(chunk)
     * </pre>
     * 事件抛出时区块<b>已经不在 visibleChunkMap 里</b>，{@code ServerChunkCache.getChunkNow}
     * 对它返回 null，写回会整批落空；但它就在事件参数里，并且紧接着（同一个 Runnable 内）
     * 就会被 {@code ChunkMap.save} 落盘 —— 所以钉住它写，是这批水位唯一不丢的落地方式。
     */
    private static LevelChunk pinnedChunk;

    /** 复用坐标载体，消除 per-block 的 BlockPos 分配。 */
    private static final BlockPos.MutableBlockPos MUT = new BlockPos.MutableBlockPos();

    /** 批量开始：作废缓存，保证不会把上一批的 chunk 用错。 */
    public static void beginBatch() {
        cachedChunkKey = Long.MIN_VALUE;
        cachedSectionIndex = Integer.MIN_VALUE;
        cachedChunk = null;
        cachedSection = null;
    }

    /** 已加载的 chunk 或 null —— 用 getChunkNow 而不是 isLoaded+getChunkAt，一次查询且**绝不触发同步加载**。 */
    private static LevelChunk chunkAt(final ServerLevel level, final int x, final int z) {
        final long key = ChunkPos.asLong(x >> 4, z >> 4);
        // ★ 正在卸载的那一个区块：先认它（它已经不在 chunk source 里，getChunkNow 查不到），
        //   且不写进缓存 —— 缓存只装「从 chunk source 查来的」区块。
        if (pinnedChunk != null && pinnedChunk.getPos().toLong() == key) {
            return pinnedChunk;
        }
        if (key != cachedChunkKey) {
            cachedChunkKey = key;
            cachedSectionIndex = Integer.MIN_VALUE;
            cachedChunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        }
        return cachedChunk;
    }

    /**
     * 把水位投影到方块状态。
     *
     * @return true = 世界已经和这个水位对齐（或本来就一样）；false = 这一格落不了地
     *         （区块没加载 / 方块装不下水位）—— 调用方此时**不能**更新侧表
     */
    private static boolean setWaterBlock(final ServerLevel level, final long packedPos, final int waterLevel) {
        final int x = BlockPos.getX(packedPos);
        final int y = BlockPos.getY(packedPos);
        final int z = BlockPos.getZ(packedPos);

        // ★ 区块没加载就不读也不写：setBlock 会触发同步区块加载（实测「2 次 setBlock 花 44.7 ms」的真凶）
        final LevelChunk chunk = chunkAt(level, x, z);
        if (chunk == null) {
            lastSkipped++;
            return false;
        }
        if (cachedSectionIndex != (y >> 4)) {
            cachedSectionIndex = y >> 4;
            cachedSection = chunk.getSection(chunk.getSectionIndex(y));
        }
        final LevelChunkSection section = cachedSection;
        final int lx = x & 15;
        final int ly = y & 15;
        final int lz = z & 15;

        final BlockState old = section.getBlockState(lx, ly, lz);
        final BlockState now = stateFor(old, waterLevel);
        if (now == null) {
            lastSkipped++;
            return false;       // 这一格装不下水位（固体 / 非含水方块）⇒ 落不了地
        }
        if (now == old) {
            lastSkipped++;
            return true;        // 世界本来就是这个水位 ⇒ 无需改动，但算落地成功
        }
        final boolean presenceChanged = (old.getBlock() == Blocks.WATER)
                != (now.getBlock() == Blocks.WATER);
        MUT.set(x, y, z);
        if (presenceChanged) {
            // 慢路径：变了「是不是水」⇒ hasOnlyAir / heightmap / 邻居都要跟，交给原版完整处理。
            //   updateNeighborsAt 必须收不可变坐标（NeighborUpdater 会把它存进更新队列）。
            chunk.setBlockState(MUT, now, false);
            level.sendBlockUpdated(MUT, old, now, 2);
            level.updateNeighborsAt(BlockPos.of(packedPos), now.getBlock());
        } else {
            // 快路径：纯水位档位变化 ⇒ 不改 hasOnlyAir、不涉方块实体、heightmap 也不变
            //   （heightmap 看的是「是不是空气」，水位档位不改变这一点），
            //   因此直接写 section 即可，完全跳过 onPlace/onRemove 与光照链。
            section.setBlockState(lx, ly, lz, now);
            level.sendBlockUpdated(MUT, old, now, 2);
        }
        chunk.setUnsaved(true);   // 变更要落盘（LevelChunk.setBlockState 自己不标脏）
        lastApplied++;
        return true;
    }

    /**
     * 这一格的方块能否被水位投影写回 —— 即 {@link #stateFor} 对它有没有可用的结果。
     *
     * <p><b>流体接管门必须问这里，不许问 {@code FluidTags.WATER}</b>
     * （见 {@code mixin/FlowingFluidTickMixin}）：{@code ci.cancel()} 一旦落下，这一格的流体推进
     * 就只剩我们这一条路。而标签是可被数据包与其它 mod 改写的，一旦「标签说是水、这一格的方块
     * 却装不下水位」，那一格既没人推进、也写不回去 ⇒ 永久冻结。门与写回共用同一判定，才不会再漂移。
     *
     * @return true = 这一格是水容器（水方块 / 空气 / 带 {@code WATERLOGGED} 的含水方块）
     */
    public static boolean canApply(final BlockState old) {
        return isWaterContainer(old);
    }

    /** 水容器的唯一判定 —— {@link #canApply} 与 {@link #stateFor} 共用，两处口径同源。 */
    private static boolean isWaterContainer(final BlockState old) {
        return old.getBlock() == Blocks.WATER
                || old.isAir()
                || (old.getBlock() instanceof SimpleWaterloggedBlock
                    && old.hasProperty(BlockStateProperties.WATERLOGGED));
    }

    /**
     * 水位 → 方块状态。
     *
     * @return null 表示这一格不需要改动 / 装不下水位
     */
    private static BlockState stateFor(final BlockState old, final int waterLevel) {
        if (!isWaterContainer(old)) {
            return null;    // 固体、或别的 mod 的自定义水体方块：水位无处可放
        }
        if (old.getBlock() instanceof SimpleWaterloggedBlock
                && old.hasProperty(BlockStateProperties.WATERLOGGED)) {
            final boolean water = waterLevel > 0;
            return old.getValue(BlockStateProperties.WATERLOGGED) == water
                    ? old
                    : old.setValue(BlockStateProperties.WATERLOGGED, water);
        }
        if (waterLevel <= 0) {
            return old.getBlock() == Blocks.WATER ? Blocks.AIR.defaultBlockState() : null;
        }
        // 走到这里 old 只可能是空气或水方块（其它类型已被上面的守卫挡掉）
        final int blockLevel = Math.max(0, Math.min(8, 8 - waterLevel));
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, blockLevel);
    }
}
