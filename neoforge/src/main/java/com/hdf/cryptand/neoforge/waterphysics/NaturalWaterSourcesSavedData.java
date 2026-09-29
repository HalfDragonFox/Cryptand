package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.waterphysics.NaturalWaterSources;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;

/**
 * 自然水源集合的落盘载体（每维度一份，挂在该维度的 {@code DimensionDataStorage}）。
 *
 * <p>持久化两份数据（都是纯 long，NBT 写起来最省）：
 * <ul>
 *   <li>{@code sections}：{@code sectionKey → long[64]}，只对<b>含自然水源</b>的 section 建条目
 *       （4096 位 = 512 B / section；全 0 位图会被 NBT 的压缩吃掉）；</li>
 *   <li>{@code registered}：{@code long[]}，「已经看过一次」的 section 列表 ——
 *       命中群系的段落哪怕一格水都没有也要记住「看过了」，否则玩家之后在那放的水又会被
 *       当成生成时就在的。</li>
 * </ul>
 *
 * <p>落盘节奏：登记（区块加载）时就地改内容并 {@code setDirty()}；真正的写盘交给
 * {@code LevelEvent.Save} 之后已有的 {@code getDataStorage().save()}
 * （见 {@code WaterPhysicsModule#onLevelSave}），本类不额外触发写盘。
 */
public final class NaturalWaterSourcesSavedData extends SavedData {

    private static final String DATA_NAME = "cryptand_waterphysics_natural_sources";
    private static final String TAG_SECTIONS = "sections";
    private static final String TAG_REGISTERED = "registered";

    private final NaturalWaterSources sources = new NaturalWaterSources();

    /** 取（或加载）该维度的自然水源存档。 */
    public static NaturalWaterSourcesSavedData get(final ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NaturalWaterSourcesSavedData::new, NaturalWaterSourcesSavedData::load),
                DATA_NAME);
    }

    public static NaturalWaterSourcesSavedData load(final CompoundTag tag,
                                                    final HolderLookup.Provider registries) {
        final NaturalWaterSourcesSavedData data = new NaturalWaterSourcesSavedData();
        final CompoundTag sectionsTag = tag.getCompound(TAG_SECTIONS);
        for (final String key : sectionsTag.getAllKeys()) {
            try {
                data.sources.putBitSection(Long.parseLong(key), sectionsTag.getLongArray(key));
            } catch (final NumberFormatException ignored) {
                // 非法键（外部破坏的存档）：跳过该条，不影响其余数据
            }
        }
        for (final long key : tag.getLongArray(TAG_REGISTERED)) {
            data.sources.putRegistered(key);
        }
        return data;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final CompoundTag sectionsTag = new CompoundTag();
        for (final Map.Entry<Long, long[]> entry : sources.bitSections().entrySet()) {
            sectionsTag.putLongArray(Long.toString(entry.getKey()), entry.getValue());
        }
        tag.put(TAG_SECTIONS, sectionsTag);
        tag.putLongArray(TAG_REGISTERED, sources.registeredKeys());
        return tag;
    }

    /** 该维度的自然水源集合（与 Bridge 里注入的是同一个实例）。 */
    public NaturalWaterSources sources() {
        return sources;
    }

    public int bitSectionCount() {
        return sources.bitSectionCount();
    }

    public int registeredCount() {
        return sources.registeredCount();
    }
}
