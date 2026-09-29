package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.soc.block.AdvancedAnalyzerBlockEntity;
import com.hdf.cryptand.soc.board.SandboxInspect;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 高级分析器面板（LDLib2，2026-09-27）=====
 *
 * <p>按 {@link SandboxInspect#SECTIONS} 分区显示，**每一个字都来自同一份
 * {@link SandboxInspect.Snapshot}**（面板里不另算任何数值）。快照由服务端在右键时采集、
 * 经方块实体同步到客户端（见 {@link AdvancedAnalyzerBlockEntity} 的说明：LDLib2 的面板
 * 两侧各建一棵树、渲染的是客户端那棵，所以数据必须随方块实体过来）。</p>
 *
 * <p>面板登记给 aiauto（{@code SocUiInspector.remember("advanced_analyzer", root)}）⇒
 * {@code ui_list} / {@code ui_dump} / {@code ui_screenshot} 直接可用，无人化验证有现成手段。</p>
 *
 * <p>刷新语义（用户要求：每秒更新一次）：服务端每秒重采一份快照（{@code AdvancedAnalyzerBlockEntity.tick}，
 * 20 tick 一次）并且**只在七个分区文本真的变了时才发包**；客户端这边**不重建任何元素**，
 * 每帧（{@code UIEvents.TICK}）拿方块实体里的分区文本跟 Label 现在的字比一比，不同就
 * {@code SocUiKit.set(...)} 换掉这一段的文本（标题栏同理）—— 同步包换了客户端方块实体的字段，
 * 下一帧面板自己就跟着变了。右下角那行小字写的就是这套口径，用户不用猜"数字为什么不动"。</p>
 */
public final class AdvancedAnalyzerPanel {

    private AdvancedAnalyzerPanel() {
    }

    public static ModularUI build(BlockUIHolder holder, @Nullable AdvancedAnalyzerBlockEntity be) {
        final Level level = holder.player.level();

        final Label state = SocUiKit.dim(header(be));
        // 标题栏同样要跟着同步走（客户端先建树、数据后到 ⇒ 否则一直停在"等待服务端…"）
        if (level.isClientSide() && be != null) {
            state.addEventListener(UIEvents.TICK, event -> {
                final String now = header(be);
                if (!now.equals(state.getText().getString())) {
                    SocUiKit.set(state, now, ChatFormatting.DARK_GRAY);
                }
            });
        }
        final List<UIElement> groups = new ArrayList<>();
        for (int i = 0; i < SandboxInspect.SECTIONS.size(); i++) {
            final int index = i;
            final String section = SandboxInspect.SECTIONS.get(i);
            final Label text = SocUiKit.text(initial(be, index));
            // 分区内容是"多行、按显示宽度补好位的整块文本"，要换行显示而不是单行截断
            text.textStyle(style -> style.textWrap(TextWrap.WRAP));

            // 面板侧的刷新点：每帧比对一次。两个作用合在一起 ——
            // ① 首帧时同步包可能还没到（先建树后收数据），到了就补上，不停在"等待服务端…"；
            // ② 服务端每秒那份自动刷新送来的新文本也是从这里显出来（**换文本，不重建元素**：
            //    Label 是既有元素，setValue 之后 LDLib2 自己会重新排版，重建反而会丢滚动/焦点）。
            // 只跟 Label 现在的字比，是因为"服务端只在变化时才发"——这里再比一次就零成本。
            if (level.isClientSide() && be != null) {
                text.addEventListener(UIEvents.TICK, event -> {
                    final String now = be.section(index);
                    if (!now.isEmpty() && !now.equals(text.getText().getString())) {
                        SocUiKit.set(text, now, ChatFormatting.WHITE);
                    }
                });
            }
            groups.add(SocUiKit.group(section, text));
        }

        final UIElement root = SocUiKit.panel(620, 470);
        root.addChildren(
                SocUiKit.titleBar(SocUiKit.title("▤ 高级分析器"), state),
                SocUiKit.body(groups.toArray(new UIElement[0])),
                SocUiKit.dim("数据 = 服务端沙箱快照，每秒自动重采一次、内容有变化才推送（面板打开期间）"
                        + "；同一份文本也可由 MCP 工具 soc_inspect 取到；重新右键方块 = 重扫目标并立即刷新"));
        // 登记根元素：无人化工具（ui_list / ui_dump / ui_screenshot）靠它找到这个面板
        SocUiInspector.remember("advanced_analyzer", root);
        return new ModularUI(SocUiKit.createUI(root), holder.player);
    }

    /** 标题栏右侧那句话（目标机 + 采集时刻） */
    private static String header(@Nullable AdvancedAnalyzerBlockEntity be) {
        if (be == null) {
            return "找不到方块实体";
        }
        final String note = be.note() == null || be.note().isBlank() ? "等待服务端…" : be.note();
        return be.collected() ? note + " · 快照于 t=" + be.collectedAt() : note;
    }

    /** 面板首次绘制时的分区文本（服务端那份已经采过；客户端要等方块实体同步到达） */
    private static String initial(@Nullable AdvancedAnalyzerBlockEntity be, int index) {
        if (be == null) {
            return "（找不到方块实体）";
        }
        final String text = be.section(index);
        return text == null || text.isEmpty() ? "等待服务端…" : text;
    }
}
