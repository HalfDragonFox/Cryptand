package com.hdf.cryptand.neoforge.soc.content;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * ===== 完整芯片物品（2026-09-15）=====
 *
 * <p>组装台的产物：一颗带完整规格（内核/内存/存储/扩展卡/UUID）的芯片，装入机箱方块即运行。
 * 规格存于 {@link DataComponents#CUSTOM_DATA}（1.21.1 的数据组件机制）。</p>
 */
public class SocAssembledItem extends Item {

    /** 示例芯片/蓝图产物的说明（工具提示最后一行；空则不显示） */
    private final String description;

    /** 空白芯片（组装台输出槽；规格由 {@link #create(SocSpec)} 写入） */
    public SocAssembledItem() {
        this(null, "");
    }

    /**
     * 带<b>默认规格</b>的芯片：规格直接写进物品的 CUSTOM_DATA ⇒ 拿到芯片即拿到全部信息
     * （用户 2026-09-29："所有包括芯片种类的等详细信息写入 CPU 中"）。
     */
    public SocAssembledItem(SocSpec defaultSpec, String description) {
        super(defaultSpec == null
                ? new Item.Properties().stacksTo(1)
                : new Item.Properties().stacksTo(1)
                        .component(DataComponents.CUSTOM_DATA, CustomData.of(defaultSpec.toTag())));
        this.description = description == null ? "" : description;
    }

    /** 读取规格（缺失返回 null） */
    public static SocSpec spec(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : SocSpec.fromTag(data.copyTag());
    }

    /** 以规格创建成品芯片 */
    public static ItemStack create(SocSpec spec) {
        final ItemStack stack = new ItemStack(SocContent.SOC_ASSEMBLED.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(spec.toTag()));
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        final SocSpec spec = spec(stack);
        if (spec == null) {
            tooltip.add(Component.literal("未编程的芯片（需在组装台成组）").withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.add(Component.literal(spec.label() + " · 完整芯片（" + spec.mhz() + " MHz · "
                + spec.xlen() + " 位）").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("预算 " + spec.effectiveCyclesPerTick() + " /tick  ≈ "
                + (spec.effectiveHz() / 1000) + " kHz").withStyle(ChatFormatting.GRAY));
        if (!spec.modules().isEmpty()) {
            tooltip.add(Component.literal("模块（" + spec.modules().size() + "）："
                    + String.join(", ", spec.modules())).withStyle(ChatFormatting.DARK_AQUA));
        }
        tooltip.add(Component.literal("RAM " + spec.ramKb() + "KB · Flash " + spec.flashKb() + "KB")
                .withStyle(ChatFormatting.GRAY));
        if (!spec.cards().isEmpty()) {
            tooltip.add(Component.literal("扩展：" + String.join(", ", spec.cards()))
                    .withStyle(ChatFormatting.DARK_GREEN));
        }
        if (!spec.links().isEmpty()) {
            tooltip.add(Component.literal("配置连接：" + spec.links().size() + " 条")
                    .withStyle(ChatFormatting.DARK_GREEN));
        }
        tooltip.add(Component.literal("装入 OC 机箱的 CPU 槽即可运行").withStyle(ChatFormatting.DARK_GRAY));
        if (!description.isEmpty()) {
            tooltip.add(Component.literal(description).withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
