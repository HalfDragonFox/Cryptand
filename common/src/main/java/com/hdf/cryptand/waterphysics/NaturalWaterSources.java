package com.hdf.cryptand.waterphysics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * <b>自然水源集合</b> —— 「世界生成时就在」的水源方块的登记表（纯 Java，零 MC 依赖）。
 *
 * <p>语义（用户口径 2026-09-29）：只有<b>世界自动生成的水源方块</b>才算恒定水源；
 * 玩家（或其它 mod）在海洋等群系里<b>自己放</b>的水没有效果。判据因此有两层，
 * 两层都在这里交汇：
 * <ol>
 *   <li><b>群系过滤</b>（MC 侧）：只有配置列表里的群系（海洋/河流…）才值得登记 ——
 *       由 {@link NaturalWaterScanner} 的列命中表表达；</li>
 *   <li><b>「生成时就在」</b>：一个 section <b>第一次被看到</b>时，扫到的水就是生成时就在的
 *       （老存档同理）；之后再放的水发生在<b>已登记</b>的 section 上，不会再补登记。</li>
 * </ol>
 *
 * <p>数据结构：每个 section 一张 4096 位的位图（{@code long[64]} = 512 B），
 * 只对「含自然水源」的 section 建条目；另外用一个 sectionKey 集合记「已经看过一次的 section」
 * （{@code registered}）。两者分开是为了体积：
 * <ul>
 *   <li>命中的群系里，每个 section 都要记「看过了」——哪怕它一格水都没有（高空 section），
 *       否则玩家之后在那里放的水又会被当成生成时就在的；这份「看过」只要 1 个 key；</li>
 *   <li>位图只在真的含有自然水源时才需要（海洋竖直方向几十格水也才几个 section）。</li>
 * </ul>
 *
 * <p>查询 {@link #isNaturalSource(long, int)} 是 O(1)：一次 map 查询 + 一次位运算，
 * 采集热路径可以逐格问。
 *
 * <p>只由主线程访问（采集在主线程、登记也在主线程），内部不做同步。
 * 持久化见 neoforge 侧的 {@code NaturalWaterSourcesSavedData}。
 */
public final class NaturalWaterSources {

    /** 一个 section 的格数（与 {@link WaterLevelField#CELLS} 同源）。 */
    public static final int CELLS = WaterLevelField.CELLS;
    /** 位图字数（4096 / 64）。 */
    public static final int WORDS = CELLS >>> 6;

    /** 含自然水源的 section：sectionKey → 4096 位图（bit = 这一格是自然水源）。 */
    private final Map<Long, long[]> bits = new HashMap<>();
    /** 已经「看过一次」的 section（且群系命中）：之后不再扫描、也不再补登记。 */
    private final Set<Long> registered = new HashSet<>();

    /** 这个 section 是不是已经登记过（登记过 ⇒ 不再扫描，玩家后放的水永远不补登记）。 */
    public boolean isRegistered(final long sectionKey) {
        return registered.contains(sectionKey);
    }

    /** 标记 section 已登记（扫描策略在「群系命中」时调用；见 {@link NaturalWaterScanner}）。 */
    public void markRegistered(final long sectionKey) {
        registered.add(sectionKey);
    }

    /** 这一格是不是自然水源（O(1)）。未登记 / 没位图 / 位为 0 一律 false。 */
    public boolean isNaturalSource(final long sectionKey, final int index) {
        final long[] words = bits.get(sectionKey);
        return words != null && (words[index >>> 6] & (1L << (index & 63))) != 0;
    }

    /** 登记一格自然水源（同时保证该 section 已登记）。 */
    public void markNaturalSource(final long sectionKey, final int index) {
        registered.add(sectionKey);
        bits.computeIfAbsent(sectionKey, k -> new long[WORDS])[index >>> 6] |= 1L << (index & 63);
    }

    /** 含自然水源的 section 数（探针 / 统计用）。 */
    public int bitSectionCount() {
        return bits.size();
    }

    /** 已登记的 section 数（探针 / 统计用）。 */
    public int registeredCount() {
        return registered.size();
    }

    public void clear() {
        bits.clear();
        registered.clear();
    }

    // ---------- 持久化（neoforge 侧 SavedData 读写用；本类不认识 NBT） ----------

    /** 含自然水源的 section 位图（只读视图，序列化用）。 */
    public Map<Long, long[]> bitSections() {
        return Collections.unmodifiableMap(bits);
    }

    /** 已登记的 section 键（序列化用）。 */
    public long[] registeredKeys() {
        final long[] out = new long[registered.size()];
        int i = 0;
        for (final Long key : registered) {
            out[i++] = key;
        }
        return out;
    }

    /** 从存档灌入一张 section 位图（长度不符一律忽略：外部破坏的存档不影响其余数据）。 */
    public void putBitSection(final long sectionKey, final long[] words) {
        if (words == null || words.length != WORDS) {
            return;
        }
        bits.put(sectionKey, words);
        registered.add(sectionKey);
    }

    /** 从存档灌入一个「已登记」的 section 键。 */
    public void putRegistered(final long sectionKey) {
        registered.add(sectionKey);
    }

    @Override
    public String toString() {
        return "NaturalWaterSources{sections=" + bits.size() + ", registered=" + registered.size() + "}";
    }
}
