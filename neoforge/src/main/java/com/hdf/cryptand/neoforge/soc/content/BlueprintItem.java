package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.board.ChipConfig;
import com.hdf.cryptand.soc.board.ChipType;
import com.hdf.cryptand.soc.factory.ChipBlueprint;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * ===== 芯片蓝图物品（2026-09-29，用户定案）=====
 *
 * <p>用户原话：「定义蓝图物品：右键可放到无蓝图的设计机里，shift+空手右键把蓝图卸载下来；已有蓝图时
 * 不管手持什么右键进第一个 UI；蓝图被载入后所有相关变更要保存到此蓝图物品的信息中，拿出来天然有信息」、
 * 「完成的蓝图物品暂时右键可以直接变成相关芯片」。</p>
 *
 * <p>所以本物品的行为只有两条：</p>
 * <ol>
 *   <li><b>空白蓝图</b>（没有配置）：右键不产出，只提示"拿去芯片蓝图设计机设计"；</li>
 *   <li><b>完成的蓝图</b>：右键直接把手里这张换成对应的<b>芯片</b>（{@link SocAssembledItem} +
 *       {@code SocSpec.of(config)}）—— 这是「暂时」的简化产出方式，等设计机/生产链接上后会改成产线流程。</li>
 * </ol>
 *
 * <p>内容（{@link ChipBlueprint}）存在 {@code CUSTOM_DATA}：名字 + 类型 + 完整配置（与设计机、沙箱同一份口径）。</p>
 */
public class BlueprintItem extends Item {

    private static final String TAG_NAME = "BlueprintName";
    private static final String TAG_TYPE = "BlueprintType";

    public BlueprintItem() {
        super(new Item.Properties().stacksTo(1));
    }

    // ==================== 读写 ====================

    /** 读出蓝图内容（缺失/坏数据 ⇒ 空白蓝图，绝不猜） */
    public static ChipBlueprint blueprint(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ChipBlueprint.blank();
        }
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return ChipBlueprint.blank();
        }
        final CompoundTag tag = data.copyTag();
        final String name = tag.getString(TAG_NAME);
        final ChipType type = ChipType.byId(tag.getString(TAG_TYPE));
        final ChipConfig config = SocSpec.configFromTag(tag);   // 与芯片共用同一段 NBT 口径
        return new ChipBlueprint(name, config != null ? config.type() : type, config);
    }

    /** 以蓝图内容创建物品 */
    public static ItemStack create(ChipBlueprint blueprint) {
        final ItemStack stack = new ItemStack(SocContent.BLUEPRINT.get());
        write(stack, blueprint);
        return stack;
    }

    /** 把蓝图内容写进物品（设计机每次改动都调它 ⇒ 拿出来天然有信息） */
    public static void write(ItemStack stack, ChipBlueprint blueprint) {
        final CompoundTag tag = blueprint.config() == null
                ? new CompoundTag()
                : SocSpec.of(blueprint.config()).toTag();
        tag.remove("Id");                       // 蓝图不持有芯片 UUID（产出时再分配）
        if (blueprint.name() != null && !blueprint.name().isEmpty()) {
            tag.putString(TAG_NAME, blueprint.name());
        }
        if (blueprint.type() != null) {
            tag.putString(TAG_TYPE, blueprint.type().id());
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ==================== 右键 ====================

    /** 完成的蓝图右键 ⇒ 直接变成对应芯片（空白蓝图只提示） */
    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level, Player player,
                                                                      InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        final ChipBlueprint blueprint = blueprint(stack);
        if (!blueprint.complete()) {
            if (!level.isClientSide()) {
                player.displayClientMessage(Component.literal(
                        "[蓝图] 这是一张空白蓝图：把它放进芯片蓝图设计机里设计，完成后右键可直接变芯片")
                        .withStyle(ChatFormatting.YELLOW), false);
            }
            return net.minecraft.world.InteractionResultHolder.success(stack);
        }
        if (!level.isClientSide()) {
            final ItemStack chip = SocAssembledItem.create(SocSpec.of(blueprint.config()));
            stack.shrink(1);
            if (stack.isEmpty()) {
                player.setItemInHand(hand, chip);
            } else if (!player.getInventory().add(chip)) {
                player.drop(chip, false);
            }
            player.displayClientMessage(Component.literal("[蓝图] 已产出芯片：" + blueprint.config().label()
                    + " · " + blueprint.config().mhz() + " MHz · 模块 " + blueprint.config().modules().size()
                    + " 项").withStyle(ChatFormatting.GREEN), false);
        }
        return net.minecraft.world.InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        final ChipBlueprint blueprint = blueprint(stack);
        if (!blueprint.complete()) {
            tooltip.add(Component.literal("空白芯片蓝图").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("放进芯片蓝图设计机里设计").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        final ChipConfig c = blueprint.config();
        tooltip.add(Component.literal(c.label() + " · " + c.mhz() + " MHz · " + c.xlen() + " 位")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("模块（" + c.modules().size() + "）：" + String.join(", ", c.modules()))
                .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.literal("RAM " + c.ramKb() + "KB · Flash " + c.flashKb() + "KB")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("右键直接产出芯片（暂时）").withStyle(ChatFormatting.DARK_GRAY));
    }
}
