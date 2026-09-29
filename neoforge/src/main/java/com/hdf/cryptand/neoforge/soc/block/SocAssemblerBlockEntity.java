package com.hdf.cryptand.neoforge.soc.block;

import com.hdf.cryptand.neoforge.soc.content.SocAssembledItem;
import com.hdf.cryptand.neoforge.soc.content.SocContent;
import com.hdf.cryptand.neoforge.soc.content.SocPartItem;
import com.hdf.cryptand.neoforge.soc.content.SocPartKind;
import com.hdf.cryptand.neoforge.soc.content.SocSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ===== 组装台方块实体（2026-09-15，参考 OpenComputers 组装机）=====
 *
 * <p>把部件组装成一颗<b>完整芯片</b>：芯片（内核）＋ 底板（槽位数）＋ 内存条 ＋ 存储 ＋ 扩展卡。</p>
 *
 * <h3>槽位</h3>
 * <pre>
 *   0 芯片(CHIP)   1 底板(BOARD)   2 内存条(RAM)   3 存储(FLASH)
 *   4..7 扩展卡(CARD_*)，数量受底板槽位数限制
 * </pre>
 *
 * <h3>组装规则</h3>
 * <ul>
 *   <li>必需：芯片 + 底板；</li>
 *   <li>扩展卡数量 ≤ 底板槽位数；</li>
 *   <li>产出进入输出槽（UUID 于此刻生成；装入机箱后固件文件即 {@code <uuid>.bin}）；</li>
 *   <li>组装消耗全部输入。</li>
 * </ul>
 */
public class SocAssemblerBlockEntity extends BlockEntity {

    public static final int SLOT_CHIP = 0;
    public static final int SLOT_BOARD = 1;
    public static final int SLOT_RAM = 2;
    public static final int SLOT_FLASH = 3;
    public static final int SLOT_CARD_FIRST = 4;
    public static final int SLOT_CARD_COUNT = 4;
    public static final int SLOT_COUNT = SLOT_CARD_FIRST + SLOT_CARD_COUNT;

    public static final int SLOT_OUTPUT = 8;

    /** 真容器（LDLib2 ItemSlot 直接绑定；槽 8 = 输出，不可手动放置） */
    private final net.neoforged.neoforge.items.ItemStackHandler handler =
            new net.neoforged.neoforge.items.ItemStackHandler(SLOT_OUTPUT + 1) {
                @Override
                protected void onContentsChanged(int slot) {
                    setChanged();
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    if (slot == SLOT_OUTPUT || stack.isEmpty()) {
                        return false;
                    }
                    if (!(stack.getItem() instanceof SocPartItem part)) {
                        return false;
                    }
                    return switch (slot) {
                        case SLOT_CHIP -> part.kind() == SocPartKind.CHIP;
                        case SLOT_BOARD -> part.kind() == SocPartKind.BOARD;
                        case SLOT_RAM -> part.kind() == SocPartKind.RAM;
                        case SLOT_FLASH -> part.kind() == SocPartKind.FLASH;
                        default -> slot >= SLOT_CARD_FIRST && part.kind().name().startsWith("CARD_");
                    };
                }
            };

    /** 槽位容器（UI 的 ItemSlot 绑定它） */
    public net.neoforged.neoforge.items.ItemStackHandler handler() {
        return handler;
    }

    public SocAssemblerBlockEntity(BlockPos pos, BlockState state) {
        super(SocContent.SOC_ASSEMBLER_BE.get(), pos, state);
    }

    // ==================== 槽位访问 ====================

    public ItemStack output() {
        return handler.getStackInSlot(SLOT_OUTPUT);
    }

    public ItemStack slot(int index) {
        return index >= 0 && index < SLOT_OUTPUT ? handler.getStackInSlot(index) : ItemStack.EMPTY;
    }

    /** 按类型放入（返回是否成功） */
    public boolean insert(ItemStack held) {
        if (held.isEmpty() || !(held.getItem() instanceof SocPartItem part)) {
            return false;
        }
        final int target = targetSlot(part.kind());
        if (target >= 0 && handler.getStackInSlot(target).isEmpty() && handler.isItemValid(target, held)) {
            handler.setStackInSlot(target, held.copyWithCount(1));
            return true;
        }
        // 扩展卡：找第一个空卡槽
        if (part.kind().name().startsWith("CARD_")) {
            for (int i = SLOT_CARD_FIRST; i < SLOT_OUTPUT; i++) {
                if (handler.getStackInSlot(i).isEmpty()) {
                    handler.setStackInSlot(i, held.copyWithCount(1));
                    return true;
                }
            }
        }
        return false;
    }

    private static int targetSlot(SocPartKind kind) {
        return switch (kind) {
            case CHIP -> SLOT_CHIP;
            case BOARD -> SLOT_BOARD;
            case RAM -> SLOT_RAM;
            case FLASH -> SLOT_FLASH;
            default -> -1;
        };
    }

    /** 取出全部输入与输出（潜行右键） */
    public List<ItemStack> takeAll() {
        final List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            final ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                out.add(stack);
                handler.setStackInSlot(i, ItemStack.EMPTY);
            }
        }
        setChanged();
        return out;
    }

    public ItemStack takeOutput() {
        final ItemStack out = handler.getStackInSlot(SLOT_OUTPUT);
        handler.setStackInSlot(SLOT_OUTPUT, ItemStack.EMPTY);
        return out;
    }

    // ==================== 组装 ====================

    /** 校验并组装；成功时产出进入输出槽并消耗输入 */
    public boolean assemble() {
        if (!handler.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            return false;   // 输出槽占用：先取走
        }
        final SocSpec spec = previewSpec();
        if (spec == null) {
            return false;
        }
        handler.setStackInSlot(SLOT_OUTPUT, SocAssembledItem.create(spec));
        for (int i = 0; i < SLOT_OUTPUT; i++) {
            handler.setStackInSlot(i, ItemStack.EMPTY);
        }
        setChanged();
        return true;
    }

    /** 预览规格（不消耗；用于 UI 与校验）；不满足返回 null */
    public SocSpec previewSpec() {
        final ItemStack chip = handler.getStackInSlot(SLOT_CHIP);
        final ItemStack board = handler.getStackInSlot(SLOT_BOARD);
        if (!(chip.getItem() instanceof SocPartItem chipPart) || chipPart.kind() != SocPartKind.CHIP) {
            return null;
        }
        if (!(board.getItem() instanceof SocPartItem boardPart) || boardPart.kind() != SocPartKind.BOARD) {
            return null;
        }
        final int boardSlots = boardPart.spec();
        final List<String> cards = new ArrayList<>();
        int cardCount = 0;
        for (int i = SLOT_CARD_FIRST; i < SLOT_OUTPUT; i++) {
            final ItemStack card = handler.getStackInSlot(i);
            if (card.getItem() instanceof SocPartItem cardPart) {
                cardCount++;
                if (cardCount > boardSlots) {
                    return null;   // 扩展卡超过底板槽位
                }
                cards.add(cardPart.kind().name());
            }
        }
        final int ramKb = handler.getStackInSlot(SLOT_RAM).getItem() instanceof SocPartItem ramPart
                && ramPart.kind() == SocPartKind.RAM ? ramPart.spec() : 0;
        final int flashKb = handler.getStackInSlot(SLOT_FLASH).getItem() instanceof SocPartItem flashPart
                && flashPart.kind() == SocPartKind.FLASH ? flashPart.spec() : 0;

        // 族与 ISA 都取自**芯片部件**（用户 2026-09-29："所有模块/架构信息在 CPU 里"）：
        // 旧口径按 tier 编一个 Kind（MCU/SOC/FPGA），与逐档物品的族映射对不上（CPU 族会被标成 SOC），已废。
        final com.hdf.cryptand.soc.board.SocCpuTiers.Family family =
                com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.family(chip);
        if (family == null || chipPart.isa() == null) {
            return null;   // 取不到族/ISA 就不组装 —— 绝不猜架构
        }
        // 规格本体在 common（ChipConfig）：类型 / 族 / ISA / 主频 / 内存 / 模块集 / 配置连接
        return SocSpec.of(new com.hdf.cryptand.soc.board.ChipConfig(
                com.hdf.cryptand.soc.board.ChipType.CPU,
                family,
                chipPart.isa(),
                com.hdf.cryptand.soc.board.SocCpuTiers.mhzOfSpec(chipPart.spec()),
                ramKb,
                flashKb,
                com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.moduleNames(family),
                List.of(),   // 配置连接表：装入后由链接层写入（同一份 CPU 权威）
                cards));
    }

    /** UI 摘要（当前装配内容 + 预览） */
    public List<String> describe() {
        final List<String> out = new ArrayList<>();
        out.add("芯片：" + name(handler.getStackInSlot(SLOT_CHIP))
                + "  底板：" + name(handler.getStackInSlot(SLOT_BOARD)));
        out.add("内存：" + name(handler.getStackInSlot(SLOT_RAM))
                + "  存储：" + name(handler.getStackInSlot(SLOT_FLASH)));
        final StringBuilder cards = new StringBuilder("扩展卡：");
        boolean any = false;
        for (int i = SLOT_CARD_FIRST; i < SLOT_OUTPUT; i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                cards.append(name(handler.getStackInSlot(i))).append(' ');
                any = true;
            }
        }
        out.add(any ? cards.toString().trim() : "扩展卡：（空）");
        final SocSpec spec = previewSpec();
        if (spec != null) {
            out.add("可组装 → " + spec.label() + " · " + spec.mhz() + " MHz · "
                    + spec.effectiveCyclesPerTick() + " /tick · 模块 " + spec.modules().size()
                    + " · RAM " + spec.ramKb() + "KB");
        } else if (!handler.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            out.add("输出槽已有成品，请先取走");
        } else {
            out.add("不可组装：需要【芯片】+【底板】，且扩展卡不超过底板槽位数");
        }
        return out;
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "（空）" : stack.getHoverName().getString();
    }

    // ==================== 持久化 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Slots", handler.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Slots")) {
            handler.deserializeNBT(registries, tag.getCompound("Slots"));
        }
    }
}
