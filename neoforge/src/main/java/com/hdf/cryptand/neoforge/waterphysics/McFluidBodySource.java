package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.waterphysics.FluidBodyCollector;
import com.hdf.cryptand.waterphysics.FluidCellKind;
import com.hdf.cryptand.waterphysics.FluidLevels;
import com.hdf.cryptand.waterphysics.NaturalWaterSources;
import com.hdf.cryptand.waterphysics.WaterLevelField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 转接（2b 整片采集）：把 MC 世界读成 {@link FluidBodyCollector.Source}（<b>纯数据接口</b>）。
 *
 * <p><b>只做取数，不做判断</b>：连通展开 / 容器域 / 边界取舍 / 预算全在 common 的
 * {@link FluidBodyCollector}（纯 Java，离线闸门 {@code WaterphysicsSelfTest#testBodyCollector*}），
 * 本类只是「哪一格怎么读」的翻译层，与 {@code RegionSnapshot.readOwn / readFace} 同口径：
 * <ul>
 *   <li><b>水位</b>：{@link FluidLevels#resolve(int, int, boolean)} 合并「世界的 LEVEL 投影」与
 *       「侧表」；侧表的 present 位必须一起传 —— 否则「侧表记录恰好是 0」会退回世界的旧投影
 *       （源方块恒为 8）⇒ 水位回跳；</li>
 *   <li><b>种类</b>：{@link McFluidCellView#kindOf(BlockState)}（只认水标签的流体，岩浆算固体）；</li>
 *   <li><b>恒定水源</b>：与 {@code RegionSnapshot} 同一条判据 ——「自然水源集合命中」且「这一格
 *       现在真有水』，命中时水位钉在满格（自然水源不会减少）。</li>
 * </ul>
 *
 * <p><b>线程约定</b>：只允许主线程使用（读区块会触碰 Level 的加载状态；{@code isLoaded} 不触发
 * 同步加载）。求解线程只吃采集冻结后的 {@code ArrayBody}。
 */
public final class McFluidBodySource implements FluidBodyCollector.Source {

    private final ServerLevel level;
    private final WaterLevelStore store;
    private final NaturalWaterSources naturalSources;
    private final boolean naturalSourceEnabled;
    /** 复用坐标载体：每格读一次，避免 per-cell 的 BlockPos 分配。 */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public McFluidBodySource(final ServerLevel level, final WaterLevelStore store,
                             final NaturalWaterSources naturalSources,
                             final boolean naturalSourceEnabled) {
        if (level == null || store == null) {
            throw new IllegalArgumentException("level / store 都不能为 null");
        }
        this.level = level;
        this.store = store;
        this.naturalSources = naturalSources == null ? new NaturalWaterSources() : naturalSources;
        this.naturalSourceEnabled = naturalSourceEnabled;
    }

    @Override
    public boolean isLoaded(final int x, final int y, final int z) {
        // ★ 未加载一律不读也不写：getBlockState 会触发同步区块加载
        //   （实测「2 次 setBlock 花 44.7 ms」的真凶）。它是采集的天然边界。
        return level.isLoaded(cursor.set(x, y, z));
    }

    @Override
    public int kind(final int x, final int y, final int z) {
        return McFluidCellView.kindOf(level.getBlockState(cursor.set(x, y, z)));
    }

    @Override
    public int level(final int x, final int y, final int z) {
        final BlockState state = level.getBlockState(cursor.set(x, y, z));
        final FluidState fluid = state.getFluidState();
        final int worldLevel = fluid.isEmpty() ? 0 : fluid.getAmount();
        final WaterLevelField field = store.get(sectionKey(x, y, z));
        final int index = localIndex(x, y, z);
        final int sideLevel = field == null ? 0 : field.level(index);
        final boolean present = field != null && field.isPresent(index);
        int merged = FluidLevels.resolve(worldLevel, sideLevel, present);
        if (naturalSourceEnabled && merged > 0
                && FluidCellKind.canHold(McFluidCellView.kindOf(state))
                && naturalSources.isNaturalSource(sectionKey(x, y, z), index)) {
            merged = WaterLevelField.MAX_LEVEL;      // 采集侧把自然水源钉在满格（与 RegionSnapshot 同口径）
        }
        return merged;
    }

    @Override
    public boolean isSource(final int x, final int y, final int z) {
        if (!naturalSourceEnabled) {
            return false;
        }
        final BlockState state = level.getBlockState(cursor.set(x, y, z));
        final FluidState fluid = state.getFluidState();
        final int worldLevel = fluid.isEmpty() ? 0 : fluid.getAmount();
        if (worldLevel <= 0) {
            return false;                           // 世界里的水已经没了（桶收走 / 被方块顶掉）⇒ 不是源
        }
        final WaterLevelField field = store.get(sectionKey(x, y, z));
        final int index = localIndex(x, y, z);
        final int sideLevel = field == null ? 0 : field.level(index);
        final boolean present = field != null && field.isPresent(index);
        if (FluidLevels.resolve(worldLevel, sideLevel, present) <= 0) {
            return false;                           // 侧表判定这一格没有水（外部清除）⇒ 不是源
        }
        return FluidCellKind.canHold(McFluidCellView.kindOf(state))
                && naturalSources.isNaturalSource(sectionKey(x, y, z), index);
    }

    private static long sectionKey(final int x, final int y, final int z) {
        return SectionCursor.key(x >> 4, y >> 4, z >> 4);
    }

    private static int localIndex(final int x, final int y, final int z) {
        return SectionCursor.linearIndex(x & 15, y & 15, z & 15);
    }
}
