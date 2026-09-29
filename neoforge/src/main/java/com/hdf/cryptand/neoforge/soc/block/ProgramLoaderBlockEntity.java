package com.hdf.cryptand.neoforge.soc.block;

import com.hdf.cryptand.neoforge.opencomputers.OcDiskFormat;
import com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts;
import com.hdf.cryptand.neoforge.soc.content.SocPartItem;
import com.hdf.cryptand.soc.fs.DiskFormatTool;
import com.hdf.cryptand.soc.fs.DiskPartitionTable;
import com.hdf.cryptand.soc.os.Installer;
import com.hdf.cryptand.soc.os.Programs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ===== 程序加载器的方块实体（2026-09-25）=====
 *
 * <p>现实对标 J-Flash / J-Link：**槽里放一块介质，把程序烧进去**。它同时是 UI 与服务端能力的唯一落点
 * （面板按钮、MCP 工具、聊天栏反馈都走这里，三处不会分叉）。</p>
 *
 * <p>⚠ 五条踩过的坑，改这个类之前先读：</p>
 * <ol>
 *   <li>写盘前先 {@code unmount} —— 机器引导时挂载的那份是 <b>readOnly</b> 实例，复用它写入会直接报
 *       {@code read only: /boot/system.bin}；</li>
 *   <li>失败必须**写明原因**，并且**同时进日志**（无人化只读日志，玩家聊天栏看不见）；</li>
 *   <li>⚠ 容量口径：{@code OcDiskFormat.format} 的 capacityBytes 传 0 会抛 {@code unknown disk capacity}，
 *       必须给介质容量（{@code SocPartItem.spec()} 对盘类就是容量 KB）；</li>
 *   <li>⚠ 无 id 先分配 id，且别用 {@code assignAddress(held, null)}：它在 address == null 时**直接 return**；</li>
 *   <li>⚠ 模型是**状态驱动**的（{@code has_floppy}），BlockEntity 字段变化不会自动影响模型 ⇒
 *       只有 {@code setBlock} 能改，见 {@link #syncFloppyState()}。</li>
 * </ol>
 */
public class ProgramLoaderBlockEntity extends BlockEntity {

    /** 没指定来源时烧哪个程序（面板/工具都不填的落点） */
    public static final String DEFAULT_PROGRAM = "cryptand-os";

    private static final String NBT_MEDIA = "Media";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/soc");

    /**
     * 槽位（1 格）。用 {@link ItemStackHandler} 而不是裸 {@code ItemStack}：
     * LDLib2 的 {@code ItemSlot} 要绑一个真容器（玩家能直接拖放介质）。
     */
    private final ItemStackHandler slot = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int index) {
            setChanged();
            syncFloppyState();
        }
    };

    /** 最近一次操作的结果（面板显示；与日志、聊天栏是**同一份文本**） */
    private String lastLog = "（还没操作过）";

    public ProgramLoaderBlockEntity(BlockPos pos, BlockState state) {
        super(com.hdf.cryptand.neoforge.soc.content.SocContent.PROGRAM_LOADER_BE.get(), pos, state);
    }

    // ==================== 槽位 ====================

    public ItemStackHandler slotHandler() {
        return slot;
    }

    public ItemStack media() {
        return slot.getStackInSlot(0);
    }

    /** 放入/取出介质（同步方块状态 ⇒ 模型切到"有软盘"形态） */
    public void setMedia(ItemStack stack, boolean updateBlock) {
        slot.setStackInSlot(0, stack == null ? ItemStack.EMPTY : stack);
    }

    /** 把 {@link ProgramLoaderBlock#HAS_FLOPPY} 与槽位对齐（幂等；客户端与未加载时不做） */
    private void syncFloppyState() {
        if (level == null || level.isClientSide()) {
            return;
        }
        final BlockState st = getBlockState();
        if (!st.hasProperty(ProgramLoaderBlock.HAS_FLOPPY)) {
            return;
        }
        final boolean has = !media().isEmpty();
        if (st.getValue(ProgramLoaderBlock.HAS_FLOPPY) != has) {
            level.setBlock(worldPosition, st.setValue(ProgramLoaderBlock.HAS_FLOPPY, has), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final ItemStack stack = media();
        if (!stack.isEmpty()) {
            tag.put(NBT_MEDIA, stack.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        slot.setStackInSlot(0, tag.contains(NBT_MEDIA)
                ? ItemStack.parse(registries, tag.getCompound(NBT_MEDIA)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY);
    }

    // ==================== 服务端能力（面板 / MCP / 聊天栏共用） ====================

    /** 一次操作的结果：{@code ok=false} 时 {@code detail} 就是原因 */
    public record Report(boolean ok, String detail) {

        public String text() {
            return (ok ? "OK · " : "ERR · ") + detail;
        }
    }

    public String lastLog() {
        return lastLog;
    }

    /** 介质摘要（面板与 MCP 都读它，避免两处各写一套判断） */
    public List<String> mediaLines() {
        final List<String> out = new ArrayList<>();
        final ItemStack stack = media();
        if (stack.isEmpty()) {
            out.add("槽位空 —— 潜行右键放一块空软盘进来");
            return out;
        }
        out.add(stack.getHoverName().getString() + " ×" + stack.getCount());
        if (!(stack.getItem() instanceof SocPartItem part)) {
            out.add("不是 Cryptand 介质（不能烧录）");
            return out;
        }
        final String address = SocPartItem.addressOf(stack);
        out.add("盘地址：" + (isBlank(address) ? "（未分配 —— 烧录时自动分配）" : address));
        out.add("容量：" + part.spec() + " KB");
        if (!isBlank(address) && level instanceof ServerLevel server) {
            final Path dir = OcDiskMounts.directoryOf(server, address);
            final boolean table = Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE));
            out.add("分区表：" + (table ? "已初始化" : "未初始化（烧录时自动建）"));
            if (table) {
                out.add("系统文件：boot/system.bin "
                        + (Installer.hasSystem(dir, capacityBytes(part)) ? "已安装" : "无"));
            }
        }
        return out;
    }

    /**
     * 烧录：把程序写进**槽里的介质**。
     *
     * @param source 程序来源 —— ① 内置程序 id（{@link Programs#readById}）；② 游戏目录内的文件路径。
     *               空白 = {@link #DEFAULT_PROGRAM}
     */
    public Report flash(String source, Player feedback) {
        return flash(source, "", 0L, feedback);
    }

    /**
     * **面板入口**：分区大小是玩家输入的**文本** ⇒ 先严格解析再烧录。
     *
     * <p>用户定的纪律（"非法值明确报错，不静默取近似"）：容量写错一点点，格式化出来的就不是
     * 玩家要的那块盘，而症状要到挂载/引导时才暴露（"格式化明明成功，系统却装不下"）。
     * 所以这里既不做"最近合法值"，也不做截断 —— 直接报错并停在原地。</p>
     */
    public Report flashFromUi(String source, String fsName, String sizeText, Player feedback) {
        final long sizeKb;
        try {
            sizeKb = parseSizeKb(sizeText);
        } catch (IllegalArgumentException bad) {
            return report(false, bad.getMessage(), feedback);
        }
        return flash(source, fsName, sizeKb, feedback);
    }

    /**
     * "分区大小(KB)" 文本 → KB 数。
     *
     * @return {@code 0} = 整盘一个分区（留空 / {@code 0}）
     * @throws IllegalArgumentException 非数字 / 负数 / 超出 long —— 消息里带上原文本，玩家知道改哪
     */
    public static long parseSizeKb(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        final String t = text.trim();
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                throw new IllegalArgumentException("分区大小只能是数字（KB）或留空：收到 \"" + t + "\"");
            }
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException tooBig) {
            throw new IllegalArgumentException("分区大小超出可表示范围：" + t);
        }
    }

    /**
     * 烧录（可指定**文件系统**与**分区大小** —— 用户 2026-09-26："程序加载器可以设置空间格式化和分区"）。
     *
     * @param fsName 文件系统名（空 = 按容量自动推荐 FAT12/16/32；见 DiskFormatTool.parseFs）
     * @param sizeKb 分区大小（KB；<= 0 = 整盘一个分区）
     */
    public Report flash(String source, String fsName, long sizeKb, Player feedback) {
        final String id = isBlank(source) ? DEFAULT_PROGRAM : source.trim();
        final ItemStack target = media();
        if (target.isEmpty()) {
            return report(false, "槽里没有介质（先潜行右键放一块空软盘进来）", feedback);
        }
        if (!(target.getItem() instanceof SocPartItem part)) {
            return report(false, "槽里的东西不是 Cryptand 介质", feedback);
        }
        if (!(level instanceof ServerLevel server)) {
            return new Report(false, "不在服务端（不应发生）");
        }
        // ① 盘地址：没有就分配（空介质拿到手就能烧）
        String address = SocPartItem.addressOf(target);
        if (isBlank(address)) {
            address = SocPartItem.ensureAddress(target, true);
            if (isBlank(address)) {
                return report(false, "无法为这块介质分配盘地址", feedback);
            }
        }
        // ② 参数**先校验**（无论盘上有没有分区表都校验：非法值必须当场报错，不能因为"这次用不上"就放过去）。
        //    ⚠ 两条纪律都落在这里：①不静默取近似（超容量不改成容量上限）；②未知文件系统名明确报错。
        final long capBytes = capacityBytes(part);
        if (sizeKb > 0 && sizeKb > capBytes / 1024L) {
            return report(false, "分区大小 " + sizeKb + " KB 超过介质容量 " + (capBytes / 1024L)
                    + " KB —— 明确报错，不改成容量上限；要么改小，要么留空（整盘一个分区）", feedback);
        }
        if (!isBlank(fsName)) {
            try {
                // 合法文件系统名的**唯一判据**（common 的 DiskFormatTool，命令与 AI 工具共用同一张表）
                DiskFormatTool.parseFs(fsName);
            } catch (RuntimeException bad) {
                return report(false, "未知的文件系统名「" + fsName + "」：" + bad.getMessage(), feedback);
            }
        }
        Path dir = OcDiskMounts.directoryOf(server, address);
        final boolean partitioned = Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE));
        if (!partitioned) {
            // 建分区 + 写文件系统；容量必须给（capacityBytes 传 0 会抛 unknown disk capacity）
            final long partBytes = sizeKb > 0 ? sizeKb * 1024L : capBytes;
            final String fmt;
            try {
                fmt = OcDiskFormat.format(server, address, capBytes, partBytes,
                        isBlank(fsName) ? null : fsName, null);
            } catch (RuntimeException bad) {
                // 分区太小（< 32KB）/ 容量与类型不匹配等 ⇒ 把原因原样交出去
                // （DiskFormatTool / DiskFormatter 是唯一判据，这里绝不改参数再试一次）
                return report(false, "无法按这两个参数初始化：" + bad.getMessage(), feedback);
            }
            dir = OcDiskMounts.directoryOf(server, address);
            if (!Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE))) {
                return report(false, "这块盘无法初始化：" + fmt, feedback);
            }
        } else if (sizeKb > 0 || !isBlank(fsName)) {
            // 已经分过区的盘：**不重建**（那会毁掉盘上内容），但必须**说明参数没生效** ——
            // 否则玩家会以为"我设了 512KB/FAT12"，而实际拿到的是原样保留的旧分区（静默不一致）。
            LOG.info("[ProgramLoader] 盘 {} 已有分区表 ⇒ 本次的文件系统/分区大小参数未生效（要改先 Reformat）",
                    address);
        }
        // ③ 程序字节
        final byte[] bytes = readProgram(server, id);
        if (bytes == null) {
            return report(false, "找不到程序「" + id + "」（既不是内置程序，也不是游戏目录内的文件）", feedback);
        }
        if (bytes.length == 0) {
            return report(false, "程序「" + id + "」是空的（固件没编译？先跑 excode/firmware/build-all.ps1）", feedback);
        }
        // ④ 写入（先 unmount：挂载中的实例是只读的）
        final String paramNote = partitioned && (sizeKb > 0 || !isBlank(fsName))
                ? "（盘上已有分区表：文件系统/分区大小按原样保留 —— 要改请先 Reformat）" : "";
        try {
            OcDiskMounts.unmount(address);
            final Installer.Report rep = Installer.install(dir, 0, 0, "", bytes, id);
            LOG.info("[ProgramLoader] {} ({} B) -> {} ; {}{}", id, bytes.length, address, rep, paramNote);
            return report(true, id + "（" + bytes.length + " B）-> " + address + " ; " + rep + paramNote,
                    feedback);
        } catch (Throwable t) {
            return report(false, "写入失败：" + t, feedback);
        }
    }

    /** 重新格式化槽里的介质（清掉全部分区与文件，回到空盘） */
    public Report reformat(Player feedback) {
        final ItemStack target = media();
        if (target.isEmpty()) {
            return report(false, "槽里没有介质", feedback);
        }
        if (!(target.getItem() instanceof SocPartItem part)) {
            return report(false, "槽里的东西不是 Cryptand 介质", feedback);
        }
        if (!(level instanceof ServerLevel server)) {
            return new Report(false, "不在服务端（不应发生）");
        }
        String address = SocPartItem.addressOf(target);
        if (isBlank(address)) {
            address = SocPartItem.ensureAddress(target, true);
        }
        if (isBlank(address)) {
            return report(false, "无法为这块介质分配盘地址", feedback);
        }
        try {
            OcDiskMounts.unmount(address);
            final long capBytes = capacityBytes(part);
            final String fmt = OcDiskFormat.format(server, address, capBytes, capBytes, null, null);
            LOG.info("[ProgramLoader] reformat {} -> {}", address, fmt);
            return report(true, "重新格式化 " + address + " ; " + fmt, feedback);
        } catch (Throwable t) {
            return report(false, "格式化失败：" + t, feedback);
        }
    }

    /** 校验：槽里这块盘上有没有系统文件（读的是 {@code Installer} 的口径，与引导同源） */
    public Report verify(String source, Player feedback) {
        final ItemStack target = media();
        if (target.isEmpty()) {
            return report(false, "槽里没有介质", feedback);
        }
        if (!(target.getItem() instanceof SocPartItem part)) {
            return report(false, "槽里的东西不是 Cryptand 介质", feedback);
        }
        if (!(level instanceof ServerLevel server)) {
            return new Report(false, "不在服务端（不应发生）");
        }
        final String address = SocPartItem.addressOf(target);
        if (isBlank(address)) {
            return report(false, "这块盘还没有盘地址（先烧录一次）", feedback);
        }
        final Path dir = OcDiskMounts.directoryOf(server, address);
        if (!Installer.hasSystem(dir, capacityBytes(part))) {
            return report(false, "盘上没有系统文件（boot/system.bin 缺失）", feedback);
        }
        final String id = isBlank(source) ? DEFAULT_PROGRAM : source.trim();
        final byte[] bytes = readProgram(server, id);
        return report(true, "盘上已有系统文件；对照程序「" + id + "」= "
                + (bytes == null ? "（找不到，无法比大小）" : bytes.length + " B"), feedback);
    }

    /**
     * 程序来源解析：
     * ① 内置程序 id（{@link Programs}）优先；② 否则当**游戏目录内的文件路径**读
     * （相对路径按 run/ 解析；越出游戏目录一律拒绝 —— 不给任意路径读盘）。
     */
    private byte[] readProgram(ServerLevel server, String source) {
        final byte[] builtin = Programs.readById(source);
        if (builtin.length > 0) {
            return builtin;
        }
        try {
            final Path root = server.getServer().getServerDirectory().toAbsolutePath().normalize();
            Path p = Path.of(source);
            if (!p.isAbsolute()) {
                p = root.resolve(source);
            }
            p = p.normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) {
                return null;
            }
            return Files.readAllBytes(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 统一出口：写最近一次结果 + 进日志 + 聊天栏（三者同一份文本） */
    private Report report(boolean ok, String detail, Player feedback) {
        lastLog = (ok ? "OK · " : "ERR · ") + detail;
        if (ok) {
            LOG.info("[ProgramLoader] {}", detail);
        } else {
            LOG.warn("[ProgramLoader] {}", detail);
        }
        if (feedback instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal("[ProgramLoader] " + lastLog), false);
        }
        return new Report(ok, detail);
    }

    /** 烧录默认程序 —— 供"旧入口"（无需 UI 的快路径）与无人化使用 */
    public InteractionResult writeToMedia(Player player) {
        return flash(DEFAULT_PROGRAM, player).ok() ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private static long capacityBytes(SocPartItem part) {
        return (long) part.spec() * 1024L;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /* flash(source, fsName, sizeKb, feedback) 面板可指定文件系统与分区大小（2026-09-26） */
}
