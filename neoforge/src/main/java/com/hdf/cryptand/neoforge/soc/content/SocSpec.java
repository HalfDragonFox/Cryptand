package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.board.ChipConfig;
import com.hdf.cryptand.soc.board.ChipType;
import com.hdf.cryptand.soc.board.SocCpuTiers;
import com.hdf.cryptand.soc.board.SocIsa;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ===== 芯片规格的 NBT 载体（2026-09-29 重构：内容本体在 common 的 {@link ChipConfig}）=====
 *
 * <p>用户定案「所有包括芯片种类的等详细信息写入 CPU 中，方便沙箱进行读取来展开」⇒
 * 芯片的完整信息是 common 的 {@link ChipConfig}（纯数据，工厂与沙箱都懂它）；
 * 本记录只是它在 MC 物品上的**载体**：多一个 UUID（固件文件名 / 身份）+ NBT 编解码。</p>
 *
 * <p>⚠ 这里<b>不再有第二份字段</b>：主频、模块、连接、内存等一律委托给 {@link #config()}，
 * 以前那种"规格里存 cyclesPerTick、工厂里存 MHz"的双口径已删除（换算只有
 * {@code SocCpuTiers.specOfMhz} 一处）。</p>
 *
 * @param id     芯片唯一标识（装配到机箱后固件文件即 {@code <id>.bin}）
 * @param config 芯片配置（类型 / 族 / ISA / 主频 / 内存 / 模块集 / 配置连接 / 扩展卡）
 */
public record SocSpec(UUID id, ChipConfig config) {

    public SocSpec {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (config == null) {
            throw new IllegalArgumentException("芯片配置（ChipConfig）必填");
        }
    }

    /** 以配置造规格（UUID 新分配） */
    public static SocSpec of(ChipConfig config) {
        return new SocSpec(UUID.randomUUID(), config);
    }

    // ==================== 委托（保持既有调用点不变）====================

    public ChipType type() { return config.type(); }

    public SocCpuTiers.Family family() { return config.family(); }

    public SocIsa isa() { return config.isa(); }

    /** 每 tick 指令预算（{@code ChipConfig} 里唯一换算） */
    public int cyclesPerTick() { return config.cyclesPerTick(); }

    public int mhz() { return config.mhz(); }

    public int xlen() { return config.xlen(); }

    public int tier() { return config.tier(); }

    public int ramKb() { return config.ramKb(); }

    public int flashKb() { return config.flashKb(); }

    public List<String> cards() { return config.cards(); }

    public List<String> modules() { return config.modules(); }

    public List<String> links() { return config.links(); }

    public String label() { return config.label(); }

    public int effectiveHz() { return config.effectiveHz(); }

    public int effectiveCyclesPerTick() { return config.effectiveCyclesPerTick(); }

    /** 换一份配置连接表（链接层写回 CPU 用） */
    public SocSpec withLinks(List<String> newLinks) {
        return new SocSpec(id, config.withLinks(newLinks));
    }

    // ==================== NBT ====================

    private static ListTag strings(List<String> values) {
        final ListTag list = new ListTag();
        for (final String v : values) {
            list.add(StringTag.valueOf(v));
        }
        return list;
    }

    private static List<String> readStrings(CompoundTag tag, String key) {
        final List<String> out = new ArrayList<>();
        final ListTag list = tag.getList(key, 8);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Type", config.type().id());
        if (config.family() != null) {
            tag.putString("Family", config.family().name());
        }
        if (config.isa() != null) {
            tag.putString("Isa", config.isa().id());
        }
        tag.putInt("Mhz", config.mhz());
        tag.putInt("RamKb", config.ramKb());
        tag.putInt("FlashKb", config.flashKb());
        tag.put("Cards", strings(config.cards()));
        tag.put("Modules", strings(config.modules()));
        tag.put("Links", strings(config.links()));
        return tag;
    }

    /**
     * 回读（芯片物品用）。缺 {@code Type}、类型名非法、CPU 类缺族/ISA、频率越界 —— 一律 {@code null}
     * （调用方按"未编程的芯片"处理，绝不猜架构）。
     */
    public static SocSpec fromTag(CompoundTag tag) {
        final ChipConfig cfg = configFromTag(tag);
        if (cfg == null) {
            return null;
        }
        return new SocSpec(tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID(), cfg);
    }

    /**
     * 只读配置（蓝图与芯片共用同一段 NBT 口径）；不合法返回 {@code null}。
     */
    public static ChipConfig configFromTag(CompoundTag tag) {
        if (tag == null || !tag.contains("Type")) {
            return null;
        }
        final ChipType type = ChipType.byId(tag.getString("Type"));
        if (type == null) {
            return null;
        }
        SocCpuTiers.Family family = null;
        if (tag.contains("Family")) {
            try {
                family = SocCpuTiers.Family.valueOf(tag.getString("Family"));
            } catch (Throwable t) {
                return null;
            }
        }
        final SocIsa isa = tag.contains("Isa") ? SocIsa.byId(tag.getString("Isa")) : null;
        try {
            return new ChipConfig(type, family, isa, tag.getInt("Mhz"),
                    tag.getInt("RamKb"), tag.getInt("FlashKb"),
                    readStrings(tag, "Modules"), readStrings(tag, "Links"), readStrings(tag, "Cards"));
        } catch (IllegalArgumentException bad) {
            return null;   // 频率越界 / CPU 缺族缺 ISA …
        }
    }

    /** UI 摘要（多行） */
    public List<String> displayLines() {
        final List<String> out = new ArrayList<>();
        out.add("类型：" + label() + (isa() == null ? "" : "（" + xlen() + " 位）"));
        out.add("主频：" + mhz() + " MHz（预算 " + cyclesPerTick() + " 周期/tick）");
        out.add("模块（" + modules().size() + "）：" + (modules().isEmpty() ? "无" : String.join(", ", modules())));
        out.add("配置连接：" + (links().isEmpty() ? "无" : links().size() + " 条"));
        out.add("内存：" + ramKb() + " KB · 存储：" + flashKb() + " KB");
        out.add("扩展卡：" + (cards().isEmpty() ? "无" : String.join(", ", cards()))
                + (cards().isEmpty() ? "" : "（预算折扣 → " + effectiveCyclesPerTick() + "）"));
        out.add("UUID：" + id);
        return out;
    }

    /** 显式声明保留（UUID 类型不变） */
    @Override
    public String toString() {
        return "SocSpec[" + label() + " " + mhz() + "MHz " + id + "]";
    }
}
