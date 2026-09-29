/**
 * ===== 单个被搬走方块的持久化快照（2026-08-31） =====
 *
 * BlockState（NBT 序列化）+ 世界位置 + 方块实体 tag。
 * 独立文件：跨包（CryptandSubLevelApi 用）可访问。
 */
package com.hdf.cryptand.neoforge.cryptandsable.persistence;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

public record PersistedBlock(BlockPos worldPos, BlockState state, CompoundTag blockEntityTag) {

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.put("state", NbtUtils.writeBlockState(state));
        tag.putIntArray("pos", new int[]{worldPos.getX(), worldPos.getY(), worldPos.getZ()});
        if (blockEntityTag != null) {
            tag.put("be", blockEntityTag);
        }
        return tag;
    }

    public static PersistedBlock fromTag(final CompoundTag tag) {
        final BlockState state = NbtUtils.readBlockState(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                tag.getCompound("state"));
        final int[] pos = tag.getIntArray("pos");
        final BlockPos blockPos = pos.length == 3
                ? new BlockPos(pos[0], pos[1], pos[2])
                : BlockPos.ZERO;
        final CompoundTag be = tag.contains("be", Tag.TAG_COMPOUND)
                ? tag.getCompound("be") : null;
        return new PersistedBlock(blockPos, state, be);
    }
}
