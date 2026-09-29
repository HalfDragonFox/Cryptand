package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.soc.block.ChipDesignerBlockEntity;
import com.hdf.cryptand.soc.board.ChipType;
import com.hdf.cryptand.soc.board.SocCpuTiers;
import com.hdf.cryptand.soc.board.SocIsa;
import com.hdf.cryptand.soc.factory.ChipDraft;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 芯片蓝图设计机面板（前端，2026-09-29）=====
 *
 * <p>用户定案：「蓝图设计机 UI 是前端，后端是虚拟与 MC 无关内容放 common」——
 * 所以这里<b>不算任何数据</b>：组件表、预算、可选族/频率/ISA、校验问题全部来自服务端方块实体里的
 * {@code ChipDraft}（common 的 CPU 制作工厂句柄），面板只把它画出来并把点击送回服务端。</p>
 *
 * <p>交互全部走 {@code setOnServerClick}：服务端改句柄 → {@code afterAction()} 刷新快照 → 重开面板，
 * 两端因此读的是同一份快照（前端只是壳，换前端不影响语义）。</p>
 */
public final class ChipDesignerUi {

    private ChipDesignerUi() {
    }

    public static ModularUI build(BlockUIHolder holder, ChipDesignerBlockEntity be) {
        final UIElement root = SocUiKit.panel(620, 500);
        if (be == null) {
            root.addChildren(SocUiKit.titleBar(SocUiKit.title("▣ 芯片蓝图设计机"), SocUiKit.err("方块实体不可用")));
            root.addChildren(SocUiKit.body(SocUiKit.dim("请关掉面板再右键一次。")));
            return new ModularUI(SocUiKit.createUI(root), holder.player);
        }
        final CompoundTag snap = be.snapshot();
        final boolean designing = snap.getBoolean("Designing");
        final String typeId = snap.getString("Type");
        final String familyName = snap.getString("Family");
        final String isaId = snap.getString("Isa");
        final int mhz = snap.getInt("Mhz");
        final int budget = snap.getInt("Budget");
        final int used = snap.getInt("Used");
        final List<String> table = strings(snap, "Table");
        final List<String> chosen = strings(snap, "Modules");
        final List<String> problems = strings(snap, "Problems");

        final Label state = designing
                ? SocUiKit.ok(describe(typeId, familyName, isaId, mhz, chosen.size(), used, budget))
                : SocUiKit.warn("未开始制作（点下面任意参数按钮就会向工厂申请一个句柄）");
        root.addChildren(SocUiKit.titleBar(SocUiKit.title("▣ 芯片蓝图设计机"), state));

        // ---------- 左栏：组件表（可装载模块）----------
        final List<UIElement> moduleButtons = new ArrayList<>();
        if (table.isEmpty()) {
            moduleButtons.add(SocUiKit.dim("（还没有句柄：先点右边的类型按钮）"));
        } else {
            for (final String m : table) {
                final boolean on = chosen.contains(m);
                final Button b = new Button().setText(Component.literal((on ? "● " : "○ ") + m));
                b.layout(l -> l.width(88).height(14));
                b.style(s -> s.background(new ColorRectTexture(on ? 0xFF2E7D32 : 0xFF3A3A3A)));
                b.setOnServerClick(e -> apply(holder, "module", m));
                moduleButtons.add(b);
            }
        }
        final UIElement left = SocUiKit.column(60,
                SocUiKit.groupTitle("组件表（" + table.size() + " 项，已选 " + chosen.size() + "）"),
                SocUiKit.row(moduleButtons.toArray(new UIElement[0])));

        // ---------- 右栏：参数 + 动作 ----------
        final List<UIElement> right = new ArrayList<>();
        right.add(SocUiKit.groupTitle("芯片类型"));
        right.add(SocUiKit.row(
                btn(holder, "CPU", "type", "cpu", designing && "cpu".equals(typeId)),
                btn(holder, "GPU", "type", "gpu", designing && "gpu".equals(typeId))));
        right.add(SocUiKit.groupTitle("族（芯片种类）"));
        right.add(SocUiKit.row(
                btn(holder, "MCU", "family", "MCU", "MCU".equals(familyName)),
                btn(holder, "SOC", "family", "SOC", "SOC".equals(familyName)),
                btn(holder, "CPU", "family", "CPU", "CPU".equals(familyName))));
        right.add(SocUiKit.groupTitle("频率（" + mhz + " MHz）"));
        right.add(SocUiKit.row(btn(holder, "◀ 降", "mhz", "-1", false), btn(holder, "升 ▶", "mhz", "+1", false)));
        right.add(SocUiKit.groupTitle("ISA / 位宽"));
        right.add(SocUiKit.row(btn(holder, isaId == null || isaId.isEmpty() ? "（未选）" : isaId, "isa", "next", false)));
        right.add(SocUiKit.groupTitle("动作"));
        right.add(SocUiKit.row(
                btn(holder, "使用默认组件表", "default", "", false),
                btn(holder, "写入蓝图", "write", "", false)));
        right.add(SocUiKit.row(btn(holder, "停止制作（销毁句柄）", "stop", "", false)));
        right.add(SocUiKit.dim("蓝图：" + snap.getString("BlueprintName")));
        if (snap.getBoolean("HasBlueprint")) {
            right.add(snap.getBoolean("BlueprintComplete")
                    ? SocUiKit.ok("蓝图已完成：拿出来右键可直接变芯片")
                    : SocUiKit.dim("蓝图还是空的：点「写入蓝图」把当前设计存进去"));
        } else {
            right.add(SocUiKit.warn("槽里没有蓝图：手持蓝图右键本方块即可放入"));
        }
        for (final String p : problems) {
            right.add(SocUiKit.err("✖ " + p));
        }
        final UIElement rightCol = SocUiKit.column(40, right.toArray(new UIElement[0]));

        root.addChildren(SocUiKit.body(SocUiKit.twoColumns(left, rightCol)));
        SocUiInspector.remember("chip_designer", root);
        return new ModularUI(SocUiKit.createUI(root), holder.player);
    }

    // ==================== 服务端动作 ====================

    private static void apply(BlockUIHolder holder, String action, String arg) {
        if (!(holder.player instanceof ServerPlayer sp)) {
            return;   // 服务端点击只应在服务端执行
        }
        if (!(sp.level().getBlockEntity(holder.pos) instanceof ChipDesignerBlockEntity be)) {
            return;
        }
        if ("type".equals(action)) {
            be.beginType("gpu".equals(arg) ? ChipType.GPU : ChipType.CPU, sp);
            reopen(holder);
            return;
        }
        final ChipDraft d = be.draft();
        if (d == null) {
            be.openDraft(sp);   // 还没申请 ⇒ 先申请（用户：申请制作返回句柄）
        }
        final ChipDraft draft = be.draft();
        if (draft == null) {
            return;
        }
        switch (action) {
            case "family" -> draft.selectFamily(SocCpuTiers.Family.valueOf(arg));
            case "mhz" -> {
                final List<Integer> options = draft.mhzOptions();
                if (!options.isEmpty()) {
                    final int idx = Math.max(0, options.indexOf(draft.mhz()));
                    final int next = Math.max(0, Math.min(options.size() - 1,
                            idx + ("+1".equals(arg) ? 1 : -1)));
                    draft.setMhz(options.get(next));
                }
            }
            case "isa" -> {
                final List<SocIsa> options = draft.isaOptions();
                if (!options.isEmpty()) {
                    final int idx = Math.max(0, options.indexOf(draft.isa()));
                    draft.selectIsa(options.get((idx + 1) % options.size()));
                }
            }
            case "module" -> draft.toggleModule(arg);
            case "default" -> draft.useDefaultModules();
            case "write" -> be.writeBlueprint();
            case "stop" -> be.stopDraft();
            default -> { }
        }
        be.afterAction();
        reopen(holder);
    }

    /** 重开面板：两侧重新读同一份快照（句柄状态已变） */
    private static void reopen(BlockUIHolder holder) {
        if (!(holder.player instanceof ServerPlayer sp)) {
            return;
        }
        sp.closeContainer();
        com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.openUI(sp, holder.pos);
    }

    // ==================== 小工具 ====================

    /** 一个动作按钮：点击落到服务端（holder 直接捕获，客户端那份只用于渲染） */
    private static Button btn(BlockUIHolder holder, String text, String action, String arg, boolean active) {
        final Button b = new Button().setText(Component.literal(text));
        b.layout(l -> l.width(action.equals("stop") ? 170 : 62).height(14));
        b.style(s -> s.background(new ColorRectTexture(active ? 0xFF1565C0 : 0xFF3A3A3A)));
        b.setOnServerClick(e -> apply(holder, action, arg));
        return b;
    }

    private static String describe(String typeId, String familyName, String isaId, int mhz,
                                   int moduleCount, int used, int budget) {
        return (typeId == null || typeId.isEmpty() ? "?" : typeId.toUpperCase())
                + " · " + (familyName == null || familyName.isEmpty() ? "未选族" : familyName)
                + " · " + (isaId == null || isaId.isEmpty() ? "未选 ISA" : isaId)
                + " · " + mhz + " MHz · 模块 " + moduleCount + " · 槽位 " + used + "/" + budget;
    }

    private static List<String> strings(CompoundTag tag, String key) {
        final List<String> out = new ArrayList<>();
        final ListTag list = tag.getList(key, 8);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }
}
