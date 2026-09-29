package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * waterphysics 子包在 neoforge 侧的总装：事件入口 + 每 tick 驱动 + 每维度一个桥接器。
 *
 * <p>读上限与写上限来自配置（maxWorkPerTick / writeBudgetPerTick），
 * 由 {@link WaterPhysicsBridge#tick} 在四段式里执行。
 */
public final class WaterPhysicsModule {

    private static final Map<ResourceKey<Level>, WaterPhysicsBridge> BRIDGES = new ConcurrentHashMap<>();

    private WaterPhysicsModule() {
    }

    public static void register(final IEventBus bus) {
        WaterPhysicsGameEvents.register(bus);
    }

    /** 已从存档灌入内存的维度（防止重复读取）。 */
    private static final Set<ResourceKey<Level>> LOADED = ConcurrentHashMap.newKeySet();

    /** 取已存在的桥接器（不存在返回 null）—— 方块更新热路径上只查不建。 */
    public static WaterPhysicsBridge bridgeOrNull(final Level level) {
        return BRIDGES.get(level.dimension());
    }

    /** 每个维度一个桥接器（各自持有该维度的水位 side table）。 */
    public static WaterPhysicsBridge bridge(final Level level) {
        final ResourceKey<Level> dimension = level.dimension();
        final WaterPhysicsBridge created = BRIDGES.computeIfAbsent(dimension, k -> new WaterPhysicsBridge());
        if (level instanceof ServerLevel serverLevel && LOADED.add(dimension)) {
            WaterLevelStoreSavedData.get(serverLevel).applyTo(created.store());
            // ★ 自然水源集合也要在这里注入（同一个实例由该维度的 SavedData 持有）：
            //   采集（RegionSnapshot）在打「恒定水源」标记时查它，登记（onChunkLoad）写它。
            created.setNaturalSources(NaturalWaterSourcesSavedData.get(serverLevel).sources());
        }
        return created;
    }

    /** 主线程每 tick 驱动（由子包 tick(ServerLevel) 调用）。 */
    public static void tick(final ServerLevel level) {
        if (!ConfigWaterphysics.ENABLE_WATERPHYSICS.get()) {
            return;
        }
        final WaterPhysicsBridge bridge = BRIDGES.get(level.dimension());
        if (bridge == null || !bridge.hasWork()) {
            return;
        }
        final int interval = ConfigWaterphysics.FLUID_TICK_INTERVAL.get();
        if (interval > 1 && level.getGameTime() % interval != 0) {
            return;
        }
        bridge.tick(level,
                ConfigWaterphysics.MAX_WORK_PER_TICK.get(),
                ConfigWaterphysics.WRITE_BUDGET_PER_TICK.get());
    }

    /**
     * 维度加载：建桥接器 —— 它同时完成「把存档水位灌回侧表」（见 {@link #bridge}）。
     *
     * <p>★ 这一步必须由事件驱动，并且必须在任何区块加载之前完成：
     * 热路径（区块加载、原版流体 tick、写回）走的都是 {@link #bridgeOrNull}（只查不建）。
     * 没有这一下，进世界后直到第一个方块事件之前桥接器一直是 null —— 存档里的水位读不回来，
     * 区块加载唤醒（{@link WaterPhysicsBridge#wakeChunk}）也不会发生，重进世界的水就永远是静止的。
     */
    public static void onLevelLoad(final ServerLevel level) {
        bridge(level);
    }

    /**
     * <b>区块加载</b>（{@code ChunkEvent.Load}，主线程）：两件事一起做。
     *
     * <ol>
     *   <li>{@link WaterPhysicsBridge#wakeChunk}：把侧表里还有水的 section 重新点名
     *       （重进世界后静止的水继续流）；</li>
     *   <li><b>自然水源首次登记</b>（{@link NaturalWaterRegistry}）：这个 chunk 里
     *       「群系命中且还没看过」的 section 扫一次 —— 此刻扫到的水就是<b>世界生成时就在</b>的水，
     *       之后玩家/其它 mod 放的水落在已登记的 section 上，永远不会被补登记。</li>
     * </ol>
     *
     * <p>时机已核实（javap：{@code ChunkStatusTasks.method_60553}）：事件在主线程抛，
     * 且 {@code LevelChunk} 已经由 {@code ProtoChunk} 构造完成（含 FEATURES 阶段的水体）。
     */
    public static void onChunkLoad(final ServerLevel level, final LevelChunk chunk) {
        final WaterPhysicsBridge bridge = BRIDGES.get(level.dimension());
        if (bridge == null) {
            return;
        }
        bridge.wakeChunk(chunk.getPos().x, chunk.getPos().z, level.getMinSection(), level.getMaxSection());
        final NaturalWaterSourcesSavedData data = NaturalWaterSourcesSavedData.get(level);
        if (NaturalWaterRegistry.registerChunk(level, chunk, data.sources(),
                ConfigWaterphysics.NATURAL_SOURCE_WATER_BIOMES.get())) {
            data.setDirty();
        }
    }

    /**
     * 世界保存：先把「已算出但还没写回」的水位落地，再把两个载体各自写盘。
     *
     * <p>水位有<b>两个</b>载体（世界方块里的投影 + 侧表），
     * 缺一个都会在下次加载时被 {@code FluidLevels.resolve} 判成「外部注入 / 外部清除」
     * 而多出水或少掉水，所以四步缺一不可：
     * <ol>
     *   <li>{@code flushWriteBack} 必须排在 {@code captureFrom} <b>之前</b> ——
     *       否则这一批水位既不在世界里、也不在存档的侧表里（静默丢水）；</li>
     *   <li>{@code chunkSource.save(true)} 补一次区块落盘：{@code LevelEvent.Save} 是
     *       {@code ServerLevel.save} 的<b>最后一步</b>（javap 实测：
     *       {@code saveLevelData()} → {@code chunkSource.save()} → 抛事件），原版那次区块保存
     *       已经过去了 —— 不补，刚写进区块的水位投影就留不在盘上，而侧表却在盘上，
     *       下次加载两边对不上；</li>
     *   <li>{@code captureFrom} 只是把内容拷进内存存档对象并 {@code setDirty()}；</li>
     *   <li>{@code getDataStorage().save()} 必须自己再调一次：同上，
     *       {@code DimensionDataStorage} 此刻已经写完了 —— 不再写一次，这一轮水位就要等
     *       下一个保存周期；关服时最后一次保存之后紧接着就是 Unload +
     *       {@code ServerLevel.close()}（它不碰数据存储），等于永远不落盘。</li>
     * </ol>
     */
    public static void onLevelSave(final ServerLevel level) {
        final WaterPhysicsBridge bridge = BRIDGES.get(level.dimension());
        if (bridge == null) {
            return;
        }
        bridge.flushWriteBack(level);
        level.getChunkSource().save(true);
        WaterLevelStoreSavedData.get(level).captureFrom(bridge.store());
        level.getDataStorage().save();
    }

    /** 维度卸载：先把没落地的东西落地，再落盘，最后清空桥接器。 */
    public static void onLevelUnload(final ServerLevel level) {
        LOADED.remove(level.dimension());
        final WaterPhysicsBridge bridge = BRIDGES.remove(level.dimension());
        if (bridge != null) {
            // ★ 顺序很重要：flushWriteBack 会把「已算出但还没写回」的水位写进世界与侧表，
            //   必须排在 captureFrom **之前** —— 否则这一批水位进了世界却没进存档侧表，
            //   下次加载时这些格的侧表基准就与世界不一致。
            bridge.flushWriteBack(level);
            // ★ 同 onLevelSave：这一次 flush 写进区块的水位投影必须跟着落盘，
            //   否则盘上是「侧表有新水位、方块还是旧投影」，下次加载两边对不上
            //   （resolve 会把它读成外部注入/外部清除）。
            level.getChunkSource().save(true);
            WaterLevelStoreSavedData.get(level).captureFrom(bridge.store());
            // ★ 同 onLevelSave：LevelEvent.Unload 之后没有任何一步会替我们写盘
            //   （ServerLevel.close() 只关实体管理器，不碰 DimensionDataStorage）。
            level.getDataStorage().save();
            bridge.clear();
        }
    }

    /**
     * 区块卸载前：把写回队列里还没落地、<b>且属于这个区块</b>的水位写进这个正要卸载的区块。
     *
     * <p>★ 为什么要把区块实例一路钉进写回器：卸载路径上它已经被摘出 visibleChunkMap
     * （javap 实测 {@code ChunkMap}：{@code remove → setLoaded(false) → post(ChunkEvent.Unload)}
     * → {@code save(chunk)}），写回自己用 {@code getChunkNow} 查不到它，整批水位会被静默跳过；
     * 而它紧接着就会被 {@code ChunkMap.save} 落盘 —— 这是这批水位唯一不丢的窗口。
     */
    public static void onChunkUnload(final ServerLevel level, final LevelChunk chunk) {
        final WaterPhysicsBridge bridge = BRIDGES.get(level.dimension());
        if (bridge == null) {
            return;
        }
        bridge.flushWriteBack(level, chunk);
    }
}
