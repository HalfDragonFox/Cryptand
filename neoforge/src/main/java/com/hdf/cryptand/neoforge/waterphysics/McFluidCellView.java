package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.waterphysics.FluidCellKind;
import com.hdf.cryptand.waterphysics.FluidCellView;
import com.hdf.cryptand.waterphysics.WaterLevelField;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 把 Minecraft 世界翻译成 common 的 {@link FluidCellView}（只读快照视图）。
 *
 * <p>线程约定：只允许主线程使用（读区块会触碰 Level 的加载状态）。
 * 求解线程不会拿到本类实例 —— 求解只吃「已拷贝出去的纯数据」。
 */
public final class McFluidCellView implements FluidCellView {

    private final Level level;
    private final WaterLevelStore store;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public McFluidCellView(final Level level, final WaterLevelStore store) {
        this.level = level;
        this.store = store;
    }

    @Override
    public int kindAt(final int x, final int y, final int z) {
        return kindOf(level.getBlockState(cursor.set(x, y, z)));
    }

    /** 方块状态 → 格子种类（实时视图与快照拷贝共用同一套判定，避免两处漂移）。 */
    public static int kindOf(final BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            // ★ 只认**水**：岩浆的 fluidState 也非空，把它判成 FLUID 会连锁两个守恒破 ——
            //   写回 stateFor 只认 Blocks.WATER ⇒ 水扣了落不了地（水消失）；而岩浆格的水位
            //   被写进侧表后，点名唤醒会让它当水源供水（凭空产水）。岩浆一律当固体。
            return state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                    ? FluidCellKind.FLUID
                    : FluidCellKind.SOLID;
        }
        if (state.isAir()) {
            return FluidCellKind.AIR;
        }
        if (state.getBlock() instanceof SimpleWaterloggedBlock) {
            return FluidCellKind.WATERLOGGABLE;
        }
        if (!state.canOcclude()) {
            return FluidCellKind.PASSABLE;
        }
        return FluidCellKind.SOLID;
    }

    /**
     * 水位：优先读 side table；side table 没有该 section 时退化为读原版流体量
     * （把尚未接管的区域也算进来）。
     */
    @Override
    public int levelAt(final int x, final int y, final int z) {
        final FluidState fluid = level.getFluidState(cursor.set(x, y, z));
        final int worldLevel = fluid.isEmpty() ? 0 : fluid.getAmount();
        final WaterLevelField field = store.get(SectionCursor.key(x >> 4, y >> 4, z >> 4));
        if (field == null) {
            return worldLevel;
        }
        // 世界没水 ⇒ 外部清除（桶收走/被方块顶掉），侧表必须跟着清零
        return com.hdf.cryptand.waterphysics.FluidLevels.resolve(worldLevel,
                field.level(SectionCursor.linearIndex(x & 15, y & 15, z & 15)));
    }
}
