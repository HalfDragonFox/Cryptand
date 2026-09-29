package com.hdf.cryptand.soc.bios;

import com.hdf.cryptand.soc.os.BootPlan;

/**
 * ===== BIOS 配置（= CMOS，common 纯 Java）=====
 *
 * <p>用户 2026-09-24："甚至可以后面加入 UI 系统和真正 BIOS 一样进行底层配置"。</p>
 *
 * <p><b>关键语义</b>：BIOS 配置必须<b>按机器持久化</b>（真机的 CMOS 就是这样），
 * 而不是写进全局 `config/cryptand/*.toml` —— 否则"这台机器从软盘启动、那台从硬盘启动"
 * 根本无法表达。所以这个类是纯数据 + 文本往返，由**机器自己**存档与读回。</p>
 *
 * <p>文本格式刻意用简单的 `key=value` 行（与项目里 `disk.json` 的手写解析一致）：
 * 不引入任何序列化依赖，且人眼可读、可直接改（配置文件坏了也能手修）。</p>
 */
public record BiosConfig(BootPlan.BootOrder order, int forcedSlot, String label) {

    /** 出厂默认：软盘优先、不强制槽位、空卷标 */
    public static BiosConfig defaults() {
        return new BiosConfig(BootPlan.BootOrder.FLOPPY_FIRST, -1, "");
    }

    /** 是否强制从某个槽位启动（-1 = 不强制，按 boot order 选） */
    public boolean hasForcedSlot() {
        return forcedSlot >= 0;
    }

    public BiosConfig withOrder(BootPlan.BootOrder o) {
        return new BiosConfig(o == null ? BootPlan.BootOrder.FLOPPY_FIRST : o, forcedSlot, label);
    }

    public BiosConfig withForcedSlot(int slot) {
        return new BiosConfig(order, slot, label);
    }

    public BiosConfig withLabel(String text) {
        return new BiosConfig(order, forcedSlot, text == null ? "" : text);
    }

    public String toText() {
        return "order=" + order.name() + "\n"
                + "forcedSlot=" + forcedSlot + "\n"
                + "label=" + label + "\n";
    }

    /** 解析失败一律退回默认值（配置坏了不该让机器起不来） */
    public static BiosConfig fromText(String text) {
        final BiosConfig d = defaults();
        if (text == null || text.isBlank()) {
            return d;
        }
        BootPlan.BootOrder order = d.order();
        int slot = d.forcedSlot();
        String label = d.label();
        for (final String raw : text.split("\n")) {
            final String line = raw.trim();
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String k = line.substring(0, eq).trim();
            final String v = line.substring(eq + 1).trim();
            switch (k) {
                case "order" -> {
                    try {
                        order = BootPlan.BootOrder.valueOf(v.toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        // 未知顺序 ⇒ 保留默认（不抛：BIOS 配置损坏不该挡住开机）
                    }
                }
                case "forcedSlot" -> {
                    try {
                        slot = Integer.parseInt(v);
                    } catch (NumberFormatException ignored) {
                        // 同上
                    }
                }
                case "label" -> label = v;
                default -> {
                    // 未知键忽略：向前兼容（以后加配置项时旧固件/旧存档不会炸）
                }
            }
        }
        return new BiosConfig(order, slot, label);
    }

    @Override
    public String toString() {
        return "BiosConfig[order=" + order + " forcedSlot=" + forcedSlot + " label=" + label + "]";
    }

    /**
     * 按"设置项"改一项配置 —— **BIOS Setup 命令层与将来的界面共用这一份解析**。
     *
     * <p>为什么解析放 common：三条设置项的合法值、非法值的拒绝、以及回显文本，
     * 命令层与界面必须完全一致 —— 各写一遍就会出现"命令接受的值界面不接受"这种对不上账。</p>
     *
     * @throws IllegalArgumentException 未知设置项 / 非法值（调用方应当回显原因，**不要**静默忽略）
     */
    public BiosConfig withSetting(String key, String value) {
        final String k = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        final String v = value == null ? "" : value.trim();
        switch (k) {
            case "order", "boot", "bootorder":
                return withOrder(parseOrder(v));
            case "forcedslot", "slot", "forced":
                return withForcedSlot(parseSlot(v));
            case "label", "name":
                return withLabel(v);
            default:
                throw new IllegalArgumentException("unknown BIOS setting '" + key
                        + "' (order / forcedslot / label)");
        }
    }

    /** 设置项回显（命令 show 与界面共用同一份文本） */
    public String describe() {
        return "order=" + order() + "  forcedSlot="
                + (hasForcedSlot() ? String.valueOf(forcedSlot()) : "none")
                + "  label=" + (label() == null || label().isBlank() ? "(empty)" : label());
    }

    /** 解析 boot order：floppy | hdd | slot（大小写无关）；非法值明确拒绝 */
    public static com.hdf.cryptand.soc.os.BootPlan.BootOrder parseOrder(String text) {
        final String v = text == null ? "" : text.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "FLOPPY", "FLOPPY_FIRST", "F" -> com.hdf.cryptand.soc.os.BootPlan.BootOrder.FLOPPY_FIRST;
            case "HDD", "HDD_FIRST", "H", "DISK" -> com.hdf.cryptand.soc.os.BootPlan.BootOrder.HDD_FIRST;
            case "SLOT", "SLOT_ORDER", "S" -> com.hdf.cryptand.soc.os.BootPlan.BootOrder.SLOT_ORDER;
            default -> throw new IllegalArgumentException("bad boot order '" + text
                    + "' (floppy | hdd | slot)");
        };
    }

    /** 解析强制槽位：none / off / -1 或空 ⇒ 不强制；其余必须是非负整数 */
    public static int parseSlot(String text) {
        final String v = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty() || "none".equals(v) || "off".equals(v) || "-1".equals(v)) {
            return -1;
        }
        final int n;
        try {
            n = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad forced slot '" + text + "' (none | 0..N)");
        }
        if (n < 0) {
            throw new IllegalArgumentException("bad forced slot '" + text + "' (none | 0..N)");
        }
        return n;
    }
}
