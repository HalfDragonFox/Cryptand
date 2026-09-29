package com.hdf.cryptand.soc.bios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ===== BIOS Setup 的**数据模型**（common，纯 Java 零 MC）=====
 *
 * <p>Setup 界面（LDLib2 面板）需要开客户端才能渲染，所以这里把"界面要显示什么、点了要做什么"
 * 抽成纯数据 —— 渲染层将来只剩"把 {@link Option} 摆成控件、把点击喂给 {@link #apply}"。
 * 这条纪律与项目其它地方一致：**能写成纯 Java 的都放 common，MC 侧只留翻译层**。</p>
 *
 * <p>为什么值得单独抽：真实 BIOS Setup 的每一项都有"名字 / 当前值 / 可选值集合"三件事，
 * 而这三件事必须与命令层（`/cryptand soc disk bios …`）和 MCP 工具**完全一致** ——
 * 各写一遍就会出现"命令里有 slot 选项、界面里没有"。</p>
 */
public final class BiosSetupModel {

    /**
     * 一个设置项（界面里就是"一行 + 一个可选值列表"）。
     *
     * @param key     设置键（喂给 {@link BiosConfig#withSetting}）
     * @param label   显示名
     * @param values  可选值（界面按它渲染选项；**第一项不一定是当前值**，当前值在 {@link #current}）
     * @param current 当前值的显示形态
     */
    public record Option(String key, String label, List<String> values, String current) {
    }

    private BiosSetupModel() {
    }

    /** 三个设置项（顺序固定：引导顺序 → 强制槽位 → 机器标签，与真实 BIOS 的排布一致） */
    public static List<Option> options(BiosConfig config) {
        final BiosConfig cfg = config == null ? BiosConfig.defaults() : config;
        final List<Option> out = new ArrayList<>(3);
        out.add(new Option("order", "引导顺序",
                List.of("floppy", "hdd", "slot"),
                cfg.order().name().toLowerCase(Locale.ROOT).replace("_first", "").replace("_order", "")));
        out.add(new Option("forcedslot", "强制槽位",
                List.of("none", "0", "1", "2", "3", "4", "5", "6", "7"),
                cfg.hasForcedSlot() ? String.valueOf(cfg.forcedSlot()) : "none"));
        out.add(new Option("label", "机器标签",
                List.of(),
                cfg.label() == null || cfg.label().isBlank() ? "(空)" : cfg.label()));
        return out;
    }

    /**
     * 应用一次修改（界面点选 / 命令 / MCP 都走它 ⇒ 三条路的语义不可能分叉）。
     *
     * @throws IllegalArgumentException 未知设置项或非法值（界面应把原因显示出来，不要静默忽略）
     */
    public static BiosConfig apply(BiosConfig config, String key, String value) {
        final BiosConfig cfg = config == null ? BiosConfig.defaults() : config;
        return cfg.withSetting(key, value);
    }

    /** 面板顶部的一行摘要（与命令 `bios show` 同一份文本） */
    public static String summary(BiosConfig config) {
        return (config == null ? BiosConfig.defaults() : config).describe();
    }
}
