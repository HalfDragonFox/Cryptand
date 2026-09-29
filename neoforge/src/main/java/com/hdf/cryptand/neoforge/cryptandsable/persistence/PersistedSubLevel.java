/**
 * ===== 亚层持久化数据模型（2026-08-31） =====
 *
 * 一个亚层的完整持久化快照：UUID + runtimeId + 锚 + 包围盒 + 方块快照列表。
 * 方块快照（{@link BlockSnapshot}）含：BlockState（NBT 序列化）+ 世界位置 + 方块实体 tag。
 *
 * NBT 布局（与 sable 原版方向一致：SubLevelSerializer 风格）：
 * {
 *   "id": "uuid(字符串)",
 *   "runtimeId": int,
 *   "anchor": [x, y, z] (int[]),
 *   "bounds": [minX, minY, minZ, maxX, maxY, maxZ] (int[]),
 *   "blocks": [
 *     { "state": <BlockState NBT>, "pos": [x, y, z], "be": <CompoundTag|null> }
 *     ...
 *   ]
 * }
 */
package com.hdf.cryptand.neoforge.cryptandsable.persistence;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 亚层完整持久化数据。 */
public record PersistedSubLevel(
        UUID subLevelId,
        int runtimeId,
        BlockPos anchor,
        int boundMinX, int boundMinY, int boundMinZ,
        int boundMaxX, int boundMaxY, int boundMaxZ,
        List<PersistedBlock> blocks,
        // ★ 2026-09-05 【关系表持久化】该亚层所属物理空间关系（空间 id + 原点；-1=未分配）。
        int spaceId,
        double spaceOriginX, double spaceOriginY, double spaceOriginZ
) {

    public static final String TAG_ID = "id";
    public static final String TAG_RUNTIME = "runtimeId";
    public static final String TAG_ANCHOR = "anchor";
    public static final String TAG_BOUNDS = "bounds";
    public static final String TAG_BLOCKS = "blocks";
    public static final String TAG_SPACE_ID = "spaceId";
    public static final String TAG_SPACE_ORIGIN = "spaceOrigin";

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putString(TAG_ID, subLevelId.toString());
        tag.putInt(TAG_RUNTIME, runtimeId);
        tag.putIntArray(TAG_ANCHOR, new int[]{anchor.getX(), anchor.getY(), anchor.getZ()});
        tag.putIntArray(TAG_BOUNDS, new int[]{boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ});
        final ListTag blocks = new ListTag();
        for (final PersistedBlock b : blocks0()) {
            blocks.add(b.toTag());
        }
        tag.put(TAG_BLOCKS, blocks);
        // ★ 2026-09-05 关系表持久化（空间 id + 原点；恢复后重建空间关系）
        if (spaceId != -1) {
            tag.putInt(TAG_SPACE_ID, spaceId);
            tag.putIntArray(TAG_SPACE_ORIGIN,
                    new int[]{(int) Math.round(spaceOriginX), (int) Math.round(spaceOriginY),
                            (int) Math.round(spaceOriginZ)});
        }
        return tag;
    }

    private List<PersistedBlock> blocks0() {
        return blocks == null ? List.of() : blocks;
    }

    public static PersistedSubLevel fromTag(final CompoundTag tag) {
        final UUID uuid = UUID.fromString(tag.getString(TAG_ID));
        final int runtimeId = tag.getInt(TAG_RUNTIME);
        final int[] anchor = tag.getIntArray(TAG_ANCHOR);
        final BlockPos anchorPos = anchor.length == 3 ? new BlockPos(anchor[0], anchor[1], anchor[2]) : BlockPos.ZERO;
        final int[] bounds = tag.getIntArray(TAG_BOUNDS);
        final List<PersistedBlock> blocks = new ArrayList<>();
        final ListTag list = tag.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
        for (final Tag t : list) {
            if (t instanceof final CompoundTag ct) {
                blocks.add(PersistedBlock.fromTag(ct));
            }
        }
        // ★ 2026-09-05 关系表恢复：spaceId（-1=未分配；历史存档无 → -1）+ 原点
        final int spaceId = tag.contains(TAG_SPACE_ID) ? tag.getInt(TAG_SPACE_ID) : -1;
        final int[] so = tag.getIntArray(TAG_SPACE_ORIGIN);
        final double sox = so.length == 3 ? so[0] : 0;
        final double soy = so.length == 3 ? so[1] : 0;
        final double soz = so.length == 3 ? so[2] : 0;
        return new PersistedSubLevel(
                uuid, runtimeId, anchorPos,
                bounds.length == 6 ? bounds[0] : 0,
                bounds.length == 6 ? bounds[1] : 0,
                bounds.length == 6 ? bounds[2] : 0,
                bounds.length == 6 ? bounds[3] : 0,
                bounds.length == 6 ? bounds[4] : 0,
                bounds.length == 6 ? bounds[5] : 0,
                blocks, spaceId, sox, soy, soz);
    }
}
