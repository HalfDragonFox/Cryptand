package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.board.SocIsa;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.UUID;

/**
 * ===== SoC 部件物品（2026-09-15 建立；2026-09-17 改为**纯物品**）=====
 *
 * <p>用一个物品类 + 类型/等级/规格承载整套部件体系（处理器 / 底板 / 存储 / 扩展卡），
 * 避免为每种档位写一个类；规格即"这颗部件能给沙箱什么"。</p>
 *
 * <p><b>用户 2026-09-17 定稿：部件是纯物品</b> —— 只插进机箱，不放置到世界里，
 * 对齐 OC 原版 cpu1/hdd1 的形态。因此部件没有方块、没有方块状态、也没有方块模型，
 * 外观直接由 {@code models/item/*.json} 提供；{@code instanceof SocPartItem} 的
 * 槽位判断（组装台 / 机箱）完全不受影响。</p>
 *
 * <p><b>用户 2026-09-18 追加：处理器必须携带 ISA / 位宽</b>（{@link SocIsa}）——
 * "位宽由 CPU 部件决定"，且"绝不静默按 RV32 跑"。所以处理器构造时 {@code isa} 必填
 * （漏声明直接在注册期抛异常，而不是等到游戏里跑出诡异现象）；非处理器部件没有位宽概念，
 * {@code isa} 为 {@code null}。</p>
 */
public class SocPartItem extends Item {

    /**
     * 盘地址的 NBT 键（存在原版通用组件 {@link DataComponents#CUSTOM_DATA} 里）。
     *
     * <p>为什么用 {@code CustomData} 而不注册自己的 {@code DataComponentType}：
     * 我们只需要一个字符串，且它**只被宿主侧读取**（固件永远看不到地址）——
     * 注册一个组件类型要动注册表、要处理序列化与同步，换来的表达力是零。
     * 等将来地址需要参与同步/比较/UI 时再升级成正式组件也不迟（那时迁移只是一次读-写）。</p>
     */
    public static final String ADDRESS_KEY = "cryptand:disk_address";

    /** 盘锁键：与地址存在同一个 CustomData 里 */
    public static final String LOCK_KEY = "cryptand:disk_lock";

    /**
     * 盘是否只读（= 锁定）。
     *
     * <p>语义来自 OC 源码（不是猜的）：盘的"只读"就是"锁定" ——
     * {@code DriveData.isLocked = lockInfo 非空}（{@code common/item/data/DriveData.scala}），
     * 软盘与硬盘<b>都能锁</b>，锁信息随物品走。</p>
     *
     * <p>默认值来自用户决定："软盘不能写只读罢了" ⇒ <b>软盘默认带锁、硬盘默认可写</b>
     * （硬盘模仿 SD 卡 / Flash，是可写数据盘）。显式设过 {@link #LOCK_KEY} 的盘以显式值为准 ——
     * 所以"解锁一张软盘"只需要显式写一次 false。</p>
     */
    public static boolean isLocked(ItemStack stack) {
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            final CompoundTag tag = data.copyTag();
            if (tag.contains(LOCK_KEY)) {
                return tag.getBoolean(LOCK_KEY);
            }
        }
        return stack.getItem() instanceof SocPartItem part && part.kind() == SocPartKind.FLOPPY;
    }

    /**
     * **强制分配地址**（用户 2026-09-18："分配地址可以增加一条指令来实现强制分配地址"）。
     *
     * <p>为什么需要：地址是自动生成的 UUID，测试时"我拿到的到底是哪块盘"就成了运气问题。
     * 强制指定一个地址后，{@code /cryptand soc disk …} 的盘目录、日志、断言都能写死，
     * 整条链路变成可复现的。</p>
     */
    public static void assignAddress(ItemStack stack, String address) {
        if (!(stack.getItem() instanceof SocPartItem) || address == null || address.isBlank()) {
            return;
        }
        // ⚠ 校验在 common（DiskAddress）：非法地址在这里就抛，绝不落进物品 ——
        //   地址同时是宿主目录名，落进去之后"两块盘共享一个目录"是查不出来的。
        final String clean = com.hdf.cryptand.soc.fs.DiskAddress.require(address);
        final CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putString(ADDRESS_KEY, clean);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** 显式设置锁（锁随物品走 ⇒ 换机器、换存档仍然只读） */
    public static void setLocked(ItemStack stack, boolean locked) {
        final CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(LOCK_KEY, locked);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** 读盘地址；没有则返回空串（**绝不在这里生成**，见 {@link #ensureAddress}） */
    public static String addressOf(ItemStack stack) {
        final CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getString(ADDRESS_KEY);
    }

    /**
     * 确保这块盘有地址（首次使用时生成 UUID 并写回物品栈）。
     *
     * <p>⚠ <b>只有服务端能生成</b>：地址决定"宿主挂载哪个文件夹"，如果客户端也生成一遍，
     * 同一个槽位在两边的地址就会不同（客户端看到的盘指向另一个目录）—— 这类 bug 在单人游戏里
     * 几乎看不出来，联机时才炸。所以 {@code serverSide=false} 时**直接返回空串**，
     * 由服务端在第一次挂载时落定。</p>
     *
     * @param serverSide 是否在服务端（权威侧）
     * @return 已存在的或新生成的地址；客户端且尚无地址时返回空串
     */
    public static String ensureAddress(ItemStack stack, boolean serverSide) {
        final String existing = addressOf(stack);
        if (!existing.isEmpty() || !serverSide) {
            return existing;
        }
        // 生成也只有一份（common 的 DiskAddress，格式与历史 UUID 一致 ⇒ 老存档/老测试语义不变）
        final String address = com.hdf.cryptand.soc.fs.DiskAddress.generate();
        final CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putString(ADDRESS_KEY, address);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return address;
    }

    private final SocPartKind kind;
    private final int tier;
    private final int spec;
    private final String description;

    /** ISA / 位宽声明：**处理器必填**，其余部件为 null */
    private final SocIsa isa;

    /** 非处理器部件（无位宽概念） */
    public SocPartItem(SocPartKind kind, int tier, int spec, String description) {
        this(kind, tier, spec, description, null);
    }

    public SocPartItem(SocPartKind kind, int tier, int spec, String description, SocIsa isa) {
        super(new Item.Properties().stacksTo(16));
        // ⚠ 处理器**必须**声明位宽：漏声明就意味着架构侧只能猜，而"猜"正是
        //   "64 位芯片跑 32 位程序"这类极难排查现象的来源 ⇒ 宁可在注册期崩掉。
        if (kind == SocPartKind.CHIP && isa == null) {
            throw new IllegalArgumentException(
                    "Cryptand 处理器（SocPartKind.CHIP）必须声明 ISA / 位宽（SocIsa），id=" + kind);
        }
        this.kind = kind;
        // ⚠ tier = 0 是合法值（"空盘"语义，用户 2026-09-26）：原先夹到 1 会让空软盘
        //   拿到系统盘同样的 tier ⇒ 被 CryptandOcDrivers.mountDisk 的 tier 1..3 预装条件命中，
        //   一放进机箱就被写入系统（"空软盘保证空"落不了地）。
        this.tier = Math.max(0, tier);
        this.spec = spec;
        this.description = description == null ? "" : description;
        this.isa = isa;
    }

    public SocPartKind kind() {
        return kind;
    }

    public int tier() {
        return tier;
    }

    /** 规格值（语义随 {@link SocPartKind}） */
    public int spec() {
        return spec;
    }

    /** ISA / 位宽声明（处理器有，其余部件为 null） */
    public SocIsa isa() {
        return isa;
    }

    /**
     * 盘地址的**短码**：去掉分隔符后取前 8 位（如 {@code 2022f657}）。
     *
     * <p>用途：固件 shell 的目标盘选择（用户 2026-09-26："系统可以输入前几个 id 码来匹配，
     * 类似 oc 的 lua os，如果有多个则需要输入数字选择或者退出"）。短码只用于**人机输入**，
     * 权威地址仍是完整 UUID（`addressOf`）。</p>
     */
    public static String shortCode(String address) {
        if (address == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < address.length() && sb.length() < 8; i++) {
            final char c = address.charAt(i);
            if (c != '-' && !Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(kind.label() + " · Tier " + tier).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(kind.specLabel() + "：" + spec).withStyle(ChatFormatting.AQUA));
        // OC 组件 id（用户 2026-09-26："组件 id 按照原版 oc 显示"）：
        //   能对上的照 OC 叫（filesystem / eeprom / gpu / redstone），对不上的用我们自己的名字；
        //   芯片与内存条在 OC 里根本不是组件（component.list 里没有 cpu/ram）⇒ 明写"机器内部"，
        //   不凭空造两个组件出来（名字的单一来源是 SocPartKind.ocComponent）。
        tooltip.add(Component.literal(kind.isComponent()
                        ? "OC 组件：" + kind.ocComponent()
                        : "OC 组件：无（机器内部，不进 component.list）")
                .withStyle(ChatFormatting.DARK_AQUA));
        if (!description.isEmpty()) {
            tooltip.add(Component.literal(description).withStyle(ChatFormatting.DARK_GRAY));
        }
        // ---- 盘/介质的**地址码**（用户 2026-09-26："有 id 的组件下方没有地址码"）----
        //   为什么必须显示：固件 shell 里要按地址选盘（OC 的 Lua OS 就是这套），
        //   玩家得先**看得见**地址才能输入；而且顺手给一个"短码"，shell 支持只输前几位匹配。
        final String address = addressOf(stack);
        if (!address.isEmpty()) {
            tooltip.add(Component.literal("地址：" + address).withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.literal("短码：" + shortCode(address) + "（shell 里只输这几位即可匹配）")
                    .withStyle(ChatFormatting.GOLD));
        } else if (kind == SocPartKind.FLOPPY || kind == SocPartKind.FLASH
                || kind == SocPartKind.EEPROM) {
            tooltip.add(Component.literal("地址：（未分配 —— 放进机箱或烧录时自动分配）")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (kind == SocPartKind.CHIP && isa != null) {
            appendIsaTooltip(tooltip);
            tooltip.add(Component.literal("等效主频 ≈ " + (spec / 1000) + " kHz（20 tick/s × 预算）")
                    .withStyle(ChatFormatting.DARK_GREEN));
            // ---- CPU 信息（2026-09-28 用户定案）：已装模块集 + 手动链路表 ----
            //   这两行是"这颗芯片现在装了什么、连了什么"的**唯一可见处**；
            //   手动链路表由硬件调节器 UI 写回物品（见 ChipInfo 的说明：手动表权威）。
            final ChipInfo info = ChipInfo.of(stack);
            if (info != null) {
                tooltip.add(Component.literal("已装模块（" + info.modules().size() + "）："
                                + (info.modules().isEmpty() ? "无" : String.join(", ", info.modules())))
                        .withStyle(ChatFormatting.BLUE));
                tooltip.add(Component.literal("手动链路：" + (info.links().isEmpty()
                                ? "空连接（两侧都没连）" : String.join("；", info.links())))
                        .withStyle(info.links().isEmpty() ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN));
            }
            tooltip.add(Component.literal("成品处理器：装进机箱（自由组装）或用于组装台")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * ISA / 位宽那一行 + **未实现时必须说清楚**。
     *
     * <p>用户 2026-09-18："RV64 → 明确报错/在日志与工具提示里说'该位宽内核未实现'，
     * 绝不静默按 RV32 跑"。工具提示是玩家在插卡之前唯一能看到的提示，所以这里必须
     * 把"会拒绝开机"写死，而不是只说一句抽象的"未支持"。</p>
     */
    private void appendIsaTooltip(List<Component> tooltip) {
        final StringBuilder line = new StringBuilder("ISA：").append(isa.label()).append('（').append(isa.id())
                .append("）· ").append(isa.xlenBits()).append(" 位 · ").append(isa.registerModel());
        if (isa.addressWindowKb() > 0) {
            // 规格值：本档内核未实现 ⇒ 该窗口尚未启用，注明"规格"避免误读成已生效
            line.append("；规格地址窗口 ").append(isa.addressWindowKb()).append(" KB（占位档，未启用）");
        }
        tooltip.add(Component.literal(line.toString()).withStyle(ChatFormatting.AQUA));

        switch (isa.support()) {
            case UNIMPLEMENTED -> {
                // 标题按缺失层面区分：「该位宽内核未实现」（RV64/RV32E）≠「该架构内核未实现」（8051）
                tooltip.add(Component.literal("⚠ " + isa.unimplementedHeadline() + "（" + isa.supportNote() + "）")
                        .withStyle(ChatFormatting.RED));
                tooltip.add(Component.literal("插进机箱会**拒绝开机**（日志报错 + 蜂鸣），绝不静默按 RV32 运行")
                        .withStyle(ChatFormatting.RED));
            }
            case EXTENSIONS_MISSING -> tooltip.add(Component.literal(
                            "⚠ " + isa.supportNote() + " 内核未实现：可开机，但用到这些指令会触发非法指令陷阱（明确报错，非静默错跑）")
                    .withStyle(ChatFormatting.GOLD));
            case FULL -> {
                // 完整支持：不加警告行
            }
        }
    }

    /**
     * 介质右键**方块**：点到程序加载器时，把交互转交给加载器。
     *
     * <p>⚠ 这一路是必需的（2026-09-26 真机定位）：手持物品右键时，服务端的
     * ServerPlayerGameMode.useItemOn 在**物品层**短路 —— 方块的 useItemOn /
     * useWithoutItem 都不会被调用。加载器只实现那两个入口的话，"手持软盘右键加载器"
     * 永远返回 PASS，而且日志里连一行 [ProgramLoader] act 都不会出现（空手右键却有两行）。
     * 物品自己接一路再转交，才是 NeoForge 下的正确接法。</p>
     */
    @Override
    public net.minecraft.world.InteractionResult useOn(
            net.minecraft.world.item.context.UseOnContext context) {
        final net.minecraft.world.level.Level level = context.getLevel();
        if (context.getPlayer() == null) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (level.getBlockState(context.getClickedPos()).getBlock()
                instanceof com.hdf.cryptand.neoforge.soc.block.ProgramLoaderBlock) {
            // ⚠ 返回类型必须是 InteractionResult（Item.useOn 的契约），不是 ItemInteractionResult
            return com.hdf.cryptand.neoforge.soc.block.ProgramLoaderBlock
                    .act(level, context.getClickedPos(), context.getPlayer());
        }
        return net.minecraft.world.InteractionResult.PASS;
    }
}
