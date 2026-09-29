package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.soc.block.SocAssemblerBlockEntity;
import com.hdf.cryptand.neoforge.soc.net.SocAssemblerPayload;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * ===== 组装台面板（LDLib2，2026-09-15 设计稿对齐版）=====
 *
 * <p>结构（对应 `info/AI/soc-ui-design.html` ③）：标题栏 → 部件分组（芯片/底板/内存/存储）→
 * 扩展卡分组 → 输出分组 → 预览分组 → 底部按钮行。</p>
 */
public final class SocAssemblerUi {

    private SocAssemblerUi() {
    }

    public static ModularUI build(BlockUIHolder holder, SocAssemblerBlockEntity be) {
        final BlockPos pos = holder.pos;
        final ItemStackHandler h = be != null ? be.handler() : new ItemStackHandler(9);

        final Label state = SocUiKit.dim("等待服务端…");
        final UIElement header = SocUiKit.titleBar(SocUiKit.title("▧ 组装台"), state);

        // ---------- 部件（4 槽 + 名称） ----------
        final UIElement partsRow = new UIElement();
        partsRow.layout(l -> l.widthPercent(100).gapAll(6).flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        partsRow.addChildren(
                labelled(h, SocAssemblerBlockEntity.SLOT_CHIP, "芯片"),
                labelled(h, SocAssemblerBlockEntity.SLOT_BOARD, "底板"),
                labelled(h, SocAssemblerBlockEntity.SLOT_RAM, "内存"),
                labelled(h, SocAssemblerBlockEntity.SLOT_FLASH, "存储"));
        final UIElement partsGroup = SocUiKit.group("部件（缺芯片或底板不可组装）", partsRow,
                SocUiKit.dim("芯片 + 底板必填；内存与闪存可选"));

        // ---------- 扩展卡 ----------
        final UIElement cardRow = new UIElement();
        cardRow.layout(l -> l.widthPercent(100).gapAll(4).flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        for (int i = SocAssemblerBlockEntity.SLOT_CARD_FIRST; i < SocAssemblerBlockEntity.SLOT_OUTPUT; i++) {
            final ItemSlot s = new ItemSlot().bind(h, i);
            s.layout(l -> l.width(18).height(18));
            cardRow.addChild(s);
        }
        final UIElement cardGroup = SocUiKit.group("扩展卡（数量 ≤ 底板槽位）", cardRow,
                SocUiKit.dim("GPIO / PWM / ADC / UART，底板决定上限"));

        // ---------- 输出 ----------
        final ItemSlot outSlot = new ItemSlot().bind(h, SocAssemblerBlockEntity.SLOT_OUTPUT);
        outSlot.layout(l -> l.width(18).height(18));
        final Label outHint = SocUiKit.dim("组装后出现在此，取走即可装入机箱");
        final UIElement outGroup = SocUiKit.group("输出（成品芯片）", SocUiKit.row(outSlot, outHint));

        // ---------- 预览 ----------
        final Label preview = SocUiKit.accent("预览：等待服务端…");
        final UIElement previewGroup = SocUiKit.group("预览", preview);

        SocClientState.listenAssembler(pos, () -> {
            final java.util.List<String> lines = SocClientState.assemblerLines(pos);
            if (!lines.isEmpty()) {
                SocUiKit.set(preview, lines.get(lines.size() - 1), ChatFormatting.AQUA);
                SocUiKit.set(state, lines.size() > 4 ? "已更新" : "就绪", ChatFormatting.GREEN);
            }
        });

        // ---------- 按钮 ----------
        final Button assemble = new Button().setText(Component.literal("组装"))
                .setOnClick(e -> SocAssemblerPayload.send(pos, SocAssemblerPayload.ACTION_ASSEMBLE));
        final Button take = new Button().setText(Component.literal("取出全部"))
                .setOnClick(e -> SocAssemblerPayload.send(pos, SocAssemblerPayload.ACTION_TAKE));
        final Button refresh = new Button().setText(Component.literal("刷新"))
                .setOnClick(e -> SocAssemblerPayload.send(pos, SocAssemblerPayload.ACTION_STATUS));

        final UIElement panel = SocUiKit.panel(440, 520);
        panel.addChildren(header,
                SocUiKit.body(
                        SocUiKit.dim("放入部件 → 组装出完整芯片"),
                        partsGroup, cardGroup, outGroup, previewGroup),
                SocUiKit.row(assemble, take, refresh),
                SocUiKit.group("玩家物品栏（从这里拖部件进上方槽位）",
                        SocUiKit.playerInventory(holder.player)));

        SocAssemblerPayload.send(pos, SocAssemblerPayload.ACTION_STATUS);
        SocUiInspector.remember("assembler", panel);   // 登记根元素：布局可导出给 AI 阅读
        return new ModularUI(SocUiKit.createUI(panel), holder.player);
    }

    /** 槽位 + 下方名称 */
    private static UIElement labelled(ItemStackHandler handler, int index, String name) {
        final ItemSlot slot = new ItemSlot().bind(handler, index);
        slot.layout(l -> l.width(18).height(18));
        final Label label = SocUiKit.groupTitle(name);
        final UIElement col = new UIElement();
        col.layout(l -> l.gapAll(3));
        col.addChildren(slot, label);
        return col;
    }
}
