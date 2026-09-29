package com.hdf.cryptand.neoforge.soc.block;

import com.hdf.cryptand.neoforge.soc.ui.SocUiKit;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ===== 程序加载器（烧录器）方块（2026-09-25）=====
 *
 * <p>用户定案：「可打开的方块，里面填写文件地址然后写入，还可以对软盘进行其他操作」+
 * 「类似 jlink 软件一样的效果」—— 现实对标 SEGP 的 J-Flash / 固件烧录器。</p>
 *
 * <p>交互（唯一一套）：</p>
 * <ul>
 *   <li><b>潜行右键</b>：把手持介质放进/取出槽位（槽里有盘就取出，没盘就放入）；</li>
 *   <li><b>普通右键</b>：打开面板 —— 填程序来源（内置程序 id 或游戏目录内的文件路径），
 *       Flash / Verify / Reformat，介质槽可直接拖放。</li>
 * </ul>
 *
 * <p>⚠ 状态位 {@link #HAS_FLOPPY} 是<b>模型变体</b>的驱动：有盘时用带软盘的模型
 * （"里面有软盘时显示软盘"）。它由 {@link ProgramLoaderBlockEntity#setMedia} 经
 * {@code setBlock} 同步 —— BlockEntity 字段变化不会自动影响模型。</p>
 *
 * <p>⚠ 面板按钮的回调在<b>服务端</b>执行（LDLib2 的 UI 树由服务端装配并同步到客户端），
 * 所以本面板不需要网络包；客户端发起的动作才需要（见 payload 注册表那一套）。</p>
 */
public class ProgramLoaderBlock extends Block implements EntityBlock, BlockUIMenuType.BlockUI {

    /** 交互诊断（真机"恒 PASS"排查用；一次右键一行，成本可忽略） */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/soc");

    public static final MapCodec<ProgramLoaderBlock> CODEC = simpleCodec(ProgramLoaderBlock::new);

    /** 槽位里有没有介质（驱动 blockstate 变体：有盘时模型显示软盘） */
    public static final BooleanProperty HAS_FLOPPY = BooleanProperty.create("has_floppy");

    /**
     * 面板 → 服务端的消息名（LDLib2 的 {@code sendMessage/onMessage} 通道）。
     *
     * <p>⚠ 两条消息的载荷里带的是**玩家在客户端填的**参数：两端各一棵 UI 树，服务端那棵里的
     * TextField 值永远是初始值 —— 直接读它等于"静默按默认值烧录"。名字用常量，
     * 免得发送侧与接收侧各写一遍字符串（写错一个字符就变成"点了没反应"）。</p>
     */
    private static final String MSG_FLASH = "cryptand:program_loader/flash";

    /** 见 {@link #MSG_FLASH} */
    private static final String MSG_VERIFY = "cryptand:program_loader/verify";

    /** 文件系统控件上"自动"的显示文本（值本身 = 空串 = 让服务端按容量推荐） */
    private static final String FILE_SYSTEM_AUTO = "文件系统：自动（按容量推荐）";

    public ProgramLoaderBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HAS_FLOPPY, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_FLOPPY);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProgramLoaderBlockEntity(pos, state);
    }

    /**
     * 右键：潜行 = 存取介质；普通 = 打开面板。
     *
     * <p>⚠ 1.21.1 的入口是 {@code useWithoutItem}（旧的六参数 {@code use()} 已废弃）——
     * 介质从玩家手上读，所以用 withoutItem 版本最直接。</p>
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return act(level, pos, player);
    }

    /**
     * ⚠ <b>手持物品</b>右键走的是这个入口，不是 {@code useWithoutItem} —— 只实现后者的话，
     * 玩家手里拿着软盘右键会被当成"没交互"（返回 PASS），潜行存取与打开面板全都不触发。
     * 实测踩过：手持 {@code cryptand:floppy_b1m} 右键加载器 ⇒ { result=PASS }，两次都如此。
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state,
                                                                  Level level, BlockPos pos, Player player,
                                                                  net.minecraft.world.InteractionHand hand,
                                                                  BlockHitResult hit) {
        return act(level, pos, player) == InteractionResult.SUCCESS
                ? net.minecraft.world.ItemInteractionResult.SUCCESS
                : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * 三个入口的共同逻辑：潜行 = 存取介质；普通 = 打开面板。
     *
     * <p>⚠ 为什么是"三个入口"（2026-09-26 真机定位）：<b>手持物品</b>右键时，
     * ServerPlayerGameMode.useItemOn 在**物品层**就短路了 —— 方块的
     * useItemOn / useWithoutItem **一个都不会被调用**。日志指纹很干净：
     * 空手右键有两行 [ProgramLoader] act，手持软盘右键一行都没有。
     * 所以介质物品自己也接一路（SocPartItem.useOn），点到加载器时转交到这里。</p>
     *
     * <p>⚠ 这里每一步都打一行诊断（真机曾经	extbf{恒返回 PASS} 却查不出停在哪）：
     * 日志会直接写出"方块实体是不是加载器 / 潜行状态 / 手上拿的是什么 / 槽里有什么"，
     * 一次右键就能定位。诊断成本极低（一次右键一行），排障价值极高。</p>
     */
    public static InteractionResult act(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;      // 客户端只回执，真活由服务端干
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ProgramLoaderBlockEntity loader)) {
            LOG.info("[ProgramLoader] act: 方块实体不是加载器（be={}）⇒ PASS", be);
            return InteractionResult.PASS;
        }
        LOG.info("[ProgramLoader] act: shift={} held={} media={}", player.isShiftKeyDown(),
                player.getMainHandItem().getItem(), loader.media().getItem());
        if (player.isShiftKeyDown()) {
            return swapMedia(loader, player);
        }
        if (player instanceof ServerPlayer sp) {
            BlockUIMenuType.openUI(sp, pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** 潜行右键：槽里有盘就取出，没有就把手持的盘放进去 */
    private static InteractionResult swapMedia(ProgramLoaderBlockEntity loader, Player player) {
        final ItemStack inSlot = loader.media();
        if (!inSlot.isEmpty()) {
            if (!player.getInventory().add(inSlot.copy())) {
                player.drop(inSlot.copy(), false);
            }
            loader.setMedia(ItemStack.EMPTY, true);
            return InteractionResult.SUCCESS;
        }
        final ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof com.hdf.cryptand.neoforge.soc.content.SocPartItem)) {
            return InteractionResult.PASS;      // 手上不是介质：什么也不做（不静默放进别的东西）
        }
        loader.setMedia(held.copyWithCount(1), true);
        held.shrink(1);
        return InteractionResult.SUCCESS;
    }

    // ==================== 面板（OC 材质 + 现代风格，LDLib2） ====================

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        final ProgramLoaderBlockEntity loader =
                holder.player.level().getBlockEntity(holder.pos) instanceof ProgramLoaderBlockEntity be ? be : null;

        final Label state = SocUiKit.dim(loader == null ? "找不到方块实体" : "就绪");
        final Label mediaInfo = SocUiKit.text("");
        final Label log = SocUiKit.dim(loader == null ? "—" : loader.lastLog());

        final Supplier<List<String>> mediaLines = () -> loader == null
                ? List.of("找不到方块实体（区块没加载？）")
                : loader.mediaLines();
        final Runnable refresh = () ->
                SocUiKit.set(mediaInfo, String.join("\n", mediaLines.get()), ChatFormatting.WHITE);
        refresh.run();

        // ---------- 介质 ----------
        final ItemStackHandler handler = loader == null ? new ItemStackHandler(1) : loader.slotHandler();
        final ItemSlot slot = new ItemSlot().bind(handler, 0);
        slot.layout(l -> l.width(18).height(18));
        final UIElement mediaGroup = SocUiKit.group("介质（可直接拖放；潜行右键也能存取）",
                SocUiKit.row(slot, SocUiKit.dim("← 放一块空软盘或已烧录的盘")),
                mediaInfo);

        // ---------- 程序来源 ----------
        final TextField source = new TextField();
        source.setText(ProgramLoaderBlockEntity.DEFAULT_PROGRAM);
        source.layout(l -> l.widthPercent(70));
        final UIElement sourceRow = new UIElement().layout(l -> l.widthPercent(100).gapAll(4)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        sourceRow.addChildren(SocUiKit.dim("来源："), source);
        final UIElement builtinRow = new UIElement().layout(l -> l.widthPercent(100).gapAll(4)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        builtinRow.addChild(SocUiKit.dim("内置程序："));
        for (final com.hdf.cryptand.soc.os.Programs.Program program
                : com.hdf.cryptand.soc.os.Programs.available()) {
            final Button pick = new Button().setText(Component.literal(program.id()));
            pick.setOnClick(e -> source.setValue(program.id(), true));
            builtinRow.addChild(pick);
        }
        final UIElement programGroup = SocUiKit.group("程序来源（内置 id，或游戏目录内的文件路径）",
                sourceRow, builtinRow);

        // ---------- 空间 / 分区（文件系统 + 分区大小；用户 2026-09-26："可设置空间格式化和分区"）----------
        //  ⚠ 这两个值是**玩家在客户端**填的：服务端那棵树里的控件值始终是初始值（两端各一棵 UI 树），
        //    所以"在服务端回调里直接读 TextField"会读到默认值 —— 那正是"静默按默认值烧录"。
        //    三个参数（来源 / 文件系统 / 分区大小）打包走 LDLib2 自己的消息通道
        //    （onMessage/sendMessage 就是一条 C2S RPC），不自己造网络包，也不让服务端去猜。
        final String[] fsChoice = {""};                 // 空 = 自动（按容量推荐 FAT12/16/32）
        final Label fsState = SocUiKit.dim(FILE_SYSTEM_AUTO);
        final TextField partKb = new TextField();
        partKb.setText("");
        partKb.layout(l -> l.width(80));
        final java.util.function.BiConsumer<String, String> pickFs = (label, value) -> {
            fsChoice[0] = value;
            SocUiKit.set(fsState, label, ChatFormatting.WHITE);     // 客户端可见的即时反馈
        };
        final Button fsAuto = new Button().setText(Component.literal("自动"));
        fsAuto.setOnClick(e -> pickFs.accept(FILE_SYSTEM_AUTO, ""));
        final Button fsFat12 = new Button().setText(Component.literal("FAT12"));
        fsFat12.setOnClick(e -> pickFs.accept("文件系统：FAT12", "FAT12"));
        final Button fsFat16 = new Button().setText(Component.literal("FAT16"));
        fsFat16.setOnClick(e -> pickFs.accept("文件系统：FAT16", "FAT16"));
        final Button fsFat32 = new Button().setText(Component.literal("FAT32"));
        fsFat32.setOnClick(e -> pickFs.accept("文件系统：FAT32", "FAT32"));
        final Button fsCryptand = new Button().setText(Component.literal("CRYPTAND"));
        fsCryptand.setOnClick(e -> pickFs.accept("文件系统：CRYPTAND（原生目录树）", "CRYPTAND"));
        // ⚠ 只有这 5 个值能进 LDLib2 的候选（"自动" = 空串）。名字**不在这里另立**：
        //   合法性与大小上限由服务端判定（DiskFormatTool.parseFs / 介质容量），面板只负责取值。
        final UIElement spaceGroup = SocUiKit.group("空间 / 分区（非法值明确报错，绝不取近似）",
                SocUiKit.row(SocUiKit.dim("文件系统："), fsAuto, fsFat12, fsFat16, fsFat32, fsCryptand),
                fsState,
                SocUiKit.row(SocUiKit.dim("分区大小(KB)："), partKb,
                        SocUiKit.dim("留空或 0 = 整盘一个分区；不能超过介质容量")),
                SocUiKit.dim("超过容量 / 未知文件系统 / 非数字 ⇒ 明确报错并停在原地（不改成最近的合法值）"));

        // ---------- 动作 ----------
        final Button flash = new Button().setText(Component.literal("Flash 烧录"));
        final Button verify = new Button().setText(Component.literal("Verify 校验"));
        final Button reformat = new Button().setText(Component.literal("Reformat 格式化"));
        final Button refreshBtn = new Button().setText(Component.literal("刷新"));
        final Consumer<ProgramLoaderBlockEntity.Report> show = rep -> {
            SocUiKit.set(log, rep.text(), rep.ok() ? ChatFormatting.GREEN : ChatFormatting.RED);
            refresh.run();
        };
        // ⚠ 烧录 / 校验都**必须落到服务端**执行（写盘只有服务端能写）。用 LDLib2 的消息通道：
        //   · 客户端点击 = 收集参数 + 发 RPC（参数取自客户端那棵树，也就是玩家真正填的内容）；
        //   · 服务端收到 = 执行 + 校验 + 回报（report 会同时写日志、聊天栏与 lastLog）。
        flash.setOnClick(e -> {
            if (loader == null) {
                return;
            }
            final CompoundTag payload = new CompoundTag();
            payload.putString("source", source.getValue());
            payload.putString("fs", fsChoice[0]);
            payload.putString("sizeKb", partKb.getValue());
            flash.sendMessage(MSG_FLASH, payload);
        });
        flash.onMessage(MSG_FLASH, payload -> {
            if (loader != null) {
                show.accept(loader.flashFromUi(payload.getString("source"), payload.getString("fs"),
                        payload.getString("sizeKb"), holder.player));
            }
        });
        verify.setOnClick(e -> {
            if (loader == null) {
                return;
            }
            final CompoundTag payload = new CompoundTag();
            payload.putString("source", source.getValue());
            verify.sendMessage(MSG_VERIFY, payload);
        });
        verify.onMessage(MSG_VERIFY, payload -> {
            if (loader != null) {
                show.accept(loader.verify(payload.getString("source"), holder.player));
            }
        });
        // 格式化不需要参数 ⇒ 直接用 LDLib2 的服务端点击（RPC 路由到服务端），不必自己打包
        reformat.setOnServerClick(e -> {
            if (loader != null) {
                show.accept(loader.reformat(holder.player));
            }
        });
        // 刷新只重读介质摘要（客户端就能做，且要让玩家**看见**更新后的文本）⇒ 留在客户端
        refreshBtn.setOnClick(e -> refresh.run());

        // ---------- 装配 ----------
        final UIElement root = SocUiKit.panel(400, 420);
        root.addChildren(
                SocUiKit.titleBar(SocUiKit.title("■ Program Loader"), state),
                SocUiKit.body(
                        SocUiKit.dim("把程序烧进槽里的介质（现实对标 J-Flash / J-Link）"),
                        mediaGroup,
                        programGroup,
                        spaceGroup,
                        SocUiKit.row(flash, verify, reformat, refreshBtn),
                        SocUiKit.group("最近一次操作", log)),
                // ⚠ 物品栏必须有：介质槽的来源在玩家背包里（没有它就没有"从哪拿盘"的地方）
                SocUiKit.group("玩家物品栏（把介质拖进上面的槽）", SocUiKit.playerInventory(holder.player)));
        // 登记根元素：无人化工具（ui_list / ui_dump / ui_screenshot）靠它找到这个面板
        // （与其它面板的 remember(name, root) 同一套机制）
        com.hdf.cryptand.neoforge.soc.ui.SocUiInspector.remember("program_loader", root);
        return new ModularUI(SocUiKit.createUI(root), holder.player);
    }
}
