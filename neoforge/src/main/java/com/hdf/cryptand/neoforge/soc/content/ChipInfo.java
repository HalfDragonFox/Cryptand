package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.board.SocIsa;

import com.hdf.cryptand.soc.board.SocCpuTiers;
import com.hdf.cryptand.soc.board.SocModule;
import com.hdf.cryptand.soc.board.SocModules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== CPU 信息（2026-09-28 用户定案）=====
 *
 * <p><b>处理器携带的结构化身份</b>：架构、主频、位宽、<b>已装模块集</b>、<b>手动链路表</b>。
 * 它有两个用途：</p>
 * <ul>
 *   <li><b>权威来源</b>：硬件调节器 UI 的连线写回这里，虚拟机接入判据读这里
 *       （用户原话：「CPU 信息数据组件（手动表权威）」）；</li>
 *   <li><b>可读性</b>：物品 tooltip / 机箱面板直接列出"这颗芯片现在是什么、装了什么、连了什么"。</li>
 * </ul>
 *
 * <p><b>为什么存原版 {@link DataComponents#CUSTOM_DATA} 而不注册 DataComponentType</b>：
 * 与 {@link SocPartItem#ADDRESS_KEY} 同一个理由 —— 这份信息只被<b>宿主侧</b>读写
 * （固件看不到），表达力用不上注册表/同步/比较那一整套；等它需要参与同步或比较时再升级，
 * 迁移只是一次读-写。</p>
 *
 * @param arch    架构（{@link SocIsa#label()}，如 RV32IM / RV64IMAC / 8051）
 * @param family  族（MCU / SOC / CPU，取自物品 id 前缀，与 {@link SocCpuTiers.Family} 同名）
 * @param mhz     主频（MHz，与档位表同一口径：spec = MHz × 50000）
 * @param xlen    位宽（32 / 64 / 8）
 * @param modules 已装模块集（模块名，如 GPU0 / UART0 / PCIE0；来自 {@link SocModules#of}）
 * @param links   手动链路表（一行一条链路描述，由硬件调节器写入；空 = 空连接）
 */
public record ChipInfo(String arch, String family, int mhz, int xlen, List<String> modules, List<String> links) {

    /** CustomData 里的键（与 SOC 其它物品键同前缀） */
    public static final String KEY = "cryptand:chip_info";

    /** 链路在手动表里的连接计数标记：两端都连才写进来（见 SocLinkTable#fullyLinked） */
    public static final String LINKS_NOTE = "空连接 = 两边都没连；接入需卡两侧都连上";

    public ChipInfo {
        modules = modules == null ? List.of() : List.copyOf(modules);
        links = links == null ? List.of() : List.copyOf(links);
    }

    // ==================== NBT ====================

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("Arch", arch == null ? "" : arch);
        tag.putString("Family", family == null ? "" : family);
        tag.putInt("Mhz", mhz);
        tag.putInt("Xlen", xlen);
        tag.put("Modules", stringList(modules));
        tag.put("Links", stringList(links));
        return tag;
    }

    public static ChipInfo fromTag(CompoundTag tag) {
        if (tag == null || !tag.contains("Family")) {
            return null;
        }
        return new ChipInfo(
                tag.getString("Arch"),
                tag.getString("Family"),
                Math.max(0, tag.getInt("Mhz")),
                Math.max(0, tag.getInt("Xlen")),
                readStrings(tag.getList("Modules", 8)),
                readStrings(tag.getList("Links", 8)));
    }

    // ==================== 物品读写 ====================

    /**
     * 读 CPU 信息：CustomData 里有就按它（**权威**，含手动链路表）；没有就按物品**派生**一份
     * （不落盘 —— 与 {@link SocPartItem#addressOf} 同一个纪律：读操作不产生副作用）。
     */
    public static ChipInfo of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            final ChipInfo stored = fromTag(data.copyTag());
            if (stored != null) {
                return stored;
            }
        }
        return derive(stack);
    }

    /** 写回（硬件调节器保存手动表时用；**只有服务端**该写，避免两端不一致） */
    public static void write(ItemStack stack, ChipInfo info) {
        if (stack == null || stack.isEmpty() || info == null) {
            return;
        }
        final CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.put(KEY, info.toTag());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** 只改手动链路表（其余字段原样保留） */
    public static ChipInfo withLinks(ItemStack stack, List<String> links) {
        final ChipInfo base = of(stack);
        if (base == null) {
            return null;
        }
        return new ChipInfo(base.arch(), base.family(), base.mhz(), base.xlen(), base.modules(), links);
    }

    /**
     * 按物品派生：族取 id 前缀（mcu/soc/cpu），主频取 spec，架构/位宽取 {@link SocPartItem#isa()}，
     * 已装模块集取 {@link SocModules#of}（= 该族的默认装载集）。
     *
     * <p>非处理器（盘 / 内存 / 扩展卡 / 底板）返回 null —— 它们没有 CPU 信息。</p>
     */
    public static ChipInfo derive(ItemStack stack) {
        if (!(stack.getItem() instanceof SocPartItem part) || part.kind() != SocPartKind.CHIP) {
            return null;
        }
        final String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        final String family = path.startsWith("mcu") ? "MCU"
                : path.startsWith("soc") ? "SOC"
                : path.startsWith("cpu") ? "CPU" : "";
        final SocIsa isa = part.isa();
        return new ChipInfo(
                isa == null ? "" : isa.label(),
                family,
                SocCpuTiers.mhzOfSpec(part.spec()),
                isa == null ? 0 : isa.xlenBits(),
                moduleNames(family),
                List.of());
    }

    /** 该族的默认模块名列表（无族则空） */
    public static List<String> moduleNames(String family) {
        final SocCpuTiers.Family f = familyOf(family);
        if (f == null) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final SocModule m : SocModules.of(f)) {
            out.add(m.name());
        }
        return out;
    }

    public static SocCpuTiers.Family familyOf(String family) {
        if (family == null) {
            return null;
        }
        for (final SocCpuTiers.Family f : SocCpuTiers.Family.values()) {
            if (f.name().equalsIgnoreCase(family)) {
                return f;
            }
        }
        return null;
    }

    // ==================== 显示 ====================

    public List<String> displayLines() {
        final List<String> out = new ArrayList<>();
        out.add("架构：" + dash(arch) + " · 位宽 " + (xlen == 0 ? "-" : xlen + " 位") + " · 族 " + dash(family));
        out.add("主频：" + mhz + " MHz");
        out.add("已装模块（" + modules.size() + "）：" + (modules.isEmpty() ? "无" : String.join(", ", modules)));
        out.add("手动链路（" + links.size() + "）：" + (links.isEmpty() ? "空连接（未接任何一端）" : String.join("；", links)));
        return out;
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    // ==================== 小工具 ====================

    private static ListTag stringList(List<String> in) {
        final ListTag list = new ListTag();
        for (final String s : in) {
            list.add(StringTag.valueOf(s));
        }
        return list;
    }

    private static List<String> readStrings(ListTag list) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }
}
