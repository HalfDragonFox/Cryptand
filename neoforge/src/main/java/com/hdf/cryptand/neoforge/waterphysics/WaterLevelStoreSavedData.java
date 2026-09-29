package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.waterphysics.WaterLevelField;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 水位 side table 的落盘载体（每维度一份）。
 *
 * <p>只存非零 section：键 = {@code SectionCursor.key}（long），值 = 4096 字节的水位（0..8）。
 * 全零 section 不写入，所以空世界不产生任何存档体积。
 *
 * <p>落盘节奏：只在世界保存/维度卸载时 {@link #captureFrom}（拷贝 4096×N 字节很贵，
 * 绝不每 tick 做）；写完调 {@code setDirty()}（SavedData 的 dirty 是 private，必须走它）。
 */
public final class WaterLevelStoreSavedData extends SavedData {

    private static final String DATA_NAME = "cryptand_waterphysics_levels";
    private static final String TAG_SECTIONS = "sections";

    private final Map<Long, byte[]> sections = new HashMap<>();

    /** 取（或创建）该维度的水位存档。 */
    public static WaterLevelStoreSavedData get(final ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WaterLevelStoreSavedData::new, WaterLevelStoreSavedData::load),
                DATA_NAME);
    }

    public static WaterLevelStoreSavedData load(final CompoundTag tag,
                                                final HolderLookup.Provider registries) {
        final WaterLevelStoreSavedData data = new WaterLevelStoreSavedData();
        final CompoundTag sectionsTag = tag.getCompound(TAG_SECTIONS);
        for (final String key : sectionsTag.getAllKeys()) {
            final byte[] levels = sectionsTag.getByteArray(key);
            if (levels.length != WaterLevelField.CELLS) {
                continue;
            }
            try {
                data.sections.put(Long.parseLong(key), levels);
            } catch (final NumberFormatException ignored) {
                // 非法键（外部破坏的存档）：跳过该条，不影响其余数据
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final CompoundTag sectionsTag = new CompoundTag();
        for (final Map.Entry<Long, byte[]> entry : sections.entrySet()) {
            sectionsTag.putByteArray(Long.toString(entry.getKey()), entry.getValue());
        }
        tag.put(TAG_SECTIONS, sectionsTag);
        return tag;
    }

    /** 从内存水位表抓取快照（只保留非零 section）。 */
    public void captureFrom(final WaterLevelStore store) {
        sections.clear();
        for (final Long key : store.sectionKeys()) {
            final WaterLevelField field = store.get(key);
            if (field == null) {
                continue;
            }
            final byte[] levels = new byte[WaterLevelField.CELLS];
            boolean any = false;
            for (int i = 0; i < WaterLevelField.CELLS; i++) {
                final int level = field.level(i);
                levels[i] = (byte) level;
                if (level != 0) {
                    any = true;
                }
            }
            if (any) {
                sections.put(key, levels);
            }
        }
        setDirty();
    }

    /** 把存档水位灌回内存水位表。 */
    public void applyTo(final WaterLevelStore store) {
        for (final Map.Entry<Long, byte[]> entry : sections.entrySet()) {
            final WaterLevelField field = store.getOrCreate(entry.getKey());
            final byte[] levels = entry.getValue();
            for (int i = 0; i < WaterLevelField.CELLS; i++) {
                if (levels[i] != 0) {
                    field.setLevel(i, levels[i]);
                }
            }
        }
    }

    public int sectionCount() {
        return sections.size();
    }
}
