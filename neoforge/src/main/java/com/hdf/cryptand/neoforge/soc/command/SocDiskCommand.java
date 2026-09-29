package com.hdf.cryptand.neoforge.soc.command;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.DiskFormatTool;
import com.hdf.cryptand.soc.fs.DiskPartitionTable;
import com.hdf.cryptand.soc.os.Programs;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ===== `/cryptand soc disk …`（2026-09-18）=====
 *
 * <p>用户定案："你可以定义指令，这样的话可以让指定 id 的软盘**强制清空加载**（空间不够的话就报错），
 * 软盘可以定义多个大小的，方便使用。" 命令归属 soc 子包（与 `/cryptand soc tools` 一致）。</p>
 *
 * <p>"把程序装进盘"的主入口是命令而不是 GUI：命令可脚本化、可被 AI 调用，
 * 调试循环要的正是"改一行 → 装盘 → 重启机器"这种不给手添活的操作。</p>
 *
 * <pre>
 *   /cryptand soc disk list                          列出已挂载的盘（容量/已用/是否装了程序）
 *   /cryptand soc disk info   &lt;盘地址&gt;                单块盘详情
 *   /cryptand soc disk load   &lt;盘地址&gt; &lt;程序id&gt;       **强制清空**后装入程序（空间不够 ⇒ 报错且不毁盘）
 *   /cryptand soc disk format &lt;盘地址&gt; &lt;sizeKb&gt;      建分区 + 写文件系统（类型按容量自动推荐）
 * </pre>
 *
 * <p>判断逻辑全在 common（{@code Programs} / {@code DiskFormatTool} / {@code DiskWipe}）：
 * 本类只做"命令参数 ⇄ 纯数据"的翻译与回显。</p>
 */
public final class SocDiskCommand {

    private SocDiskCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("disk")
                .then(Commands.literal("bios")
                        .then(Commands.literal("show")
                                .then(Commands.argument("address", StringArgumentType.string())
                                        .executes(ctx -> biosShow(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "address")))))
                        .then(Commands.literal("order")
                                .then(Commands.argument("address", StringArgumentType.string())
                                        .then(Commands.argument("value", StringArgumentType.string())
                                                .executes(ctx -> biosSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "address"),
                                                        "order", StringArgumentType.getString(ctx, "value"))))))
                        .then(Commands.literal("forcedslot")
                                .then(Commands.argument("address", StringArgumentType.string())
                                        .then(Commands.argument("value", StringArgumentType.string())
                                                .executes(ctx -> biosSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "address"),
                                                        "forcedslot", StringArgumentType.getString(ctx, "value"))))))
                        .then(Commands.literal("label")
                                .then(Commands.argument("address", StringArgumentType.string())
                                        .then(Commands.argument("value", StringArgumentType.string())
                                                .executes(ctx -> biosSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "address"),
                                                        "label", StringArgumentType.getString(ctx, "value")))))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("info")
                        .then(Commands.argument("address", StringArgumentType.string())
                                .executes(ctx -> info(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "address")))))
                .then(Commands.literal("load")
                        .then(Commands.argument("address", StringArgumentType.string())
                                .then(Commands.argument("program", StringArgumentType.string())
                                        .executes(ctx -> load(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "address"),
                                                StringArgumentType.getString(ctx, "program"))))))
                .then(Commands.literal("address")
                        .executes(ctx -> assignAddress(ctx.getSource(), ""))
                        .then(Commands.argument("address", StringArgumentType.string())
                                .executes(ctx -> assignAddress(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "address")))))
                .then(Commands.literal("install")
                        .then(Commands.argument("address", StringArgumentType.string())
                                .then(Commands.argument("program", StringArgumentType.string())
                                        .executes(ctx -> install(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "address"),
                                                StringArgumentType.getString(ctx, "program"))))))
                .then(Commands.literal("format")
                        .then(Commands.argument("address", StringArgumentType.string())
                                .then(Commands.argument("sizeKb", IntegerArgumentType.integer(64))
                                        .executes(ctx -> format(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "address"),
                                                IntegerArgumentType.getInteger(ctx, "sizeKb"))))));
    }

    private static int list(CommandSourceStack src) {
        final var addrs = com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.mountedAddresses();
        if (addrs.isEmpty()) {
            src.sendFailure(Component.literal("没有已挂载的盘（机器开机时盘才会挂载；先放机器再开机）"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("已挂载 " + addrs.size() + " 块盘："), false);
        for (final String a : addrs) {
            src.sendSuccess(() -> Component.literal("  " + describe(a)), false);
        }
        return addrs.size();
    }

    private static int info(CommandSourceStack src, String address) {
        // 卷表先打（用户 2026-09-24："完整 os 可以支持通过分区来实现功能，对应现代系统"）：
        // 分区表是宿主侧的东西，不必等盘被机器挂载就能查 —— 盘还在箱外时也能看布局。
        // 只有系统卷参与引导，其余是 /usr、/home 这类角色卷。
        try {
            for (final String line : com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.describeVolumes(
                    src.getLevel(), address, 0, false)) {
                src.sendSuccess(() -> Component.literal("  " + line), false);
            }
        } catch (RuntimeException e) {
            // 读不动分区表 ⇒ 说出来（不静默）；下面的挂载状态会给出更直接的处置建议
            src.sendFailure(Component.literal("读分区表失败：" + e.getMessage()));
        }
        if (com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.mounted(address) == null) {
            src.sendFailure(Component.literal("这块盘没挂载：" + address + "（用 /cryptand soc disk list 看）"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(describe(address)), false);
        return 1;
    }

    private static int load(CommandSourceStack src, String address, String program) {
        final byte[] bytes = Programs.readById(program);
        if (bytes.length == 0) {
            final StringBuilder ids = new StringBuilder();
            for (final Programs.Program p : Programs.available()) {
                ids.append(ids.length() == 0 ? "" : " / ").append(p.id());
            }
            src.sendFailure(Component.literal("没有这个程序：" + program + "（可用：" + ids + "）"));
            return 0;
        }
        final CryptandFileSystem fs = mount(src.getLevel(), address, src);
        if (fs == null) {
            return 0;
        }
        try {
            final int removed = Programs.installCleared(fs, bytes);
            src.sendSuccess(() -> Component.literal("已清空 " + removed + " 项并装入 " + program
                    + "（" + bytes.length + " 字节）→ " + Programs.BOOT_PATH
                    + "；重启机器即生效（控制台断电重开）"), true);
            return removed;
        } catch (RuntimeException e) {
            // 空间不够等：common 已经把原因说清楚了，原样转给玩家（不吞、不改写）
            src.sendFailure(Component.literal("装盘失败：" + e.getMessage()));
            return 0;
        }
    }

    /**
     * 一键装系统：建分区（或复用）→ 格式化 → 装程序 → 校验。
     *
     * <p>与 `load` 的区别：`load` 只换程序（保留分区与文件系统），`install` 会**重新格式化**
     * 目标分区 —— 前者是调试循环，后者是"把这块盘做成系统盘"。</p>
     */
    private static int install(CommandSourceStack src, String address, String program) {
        final byte[] bytes = Programs.readById(program);
        if (bytes.length == 0) {
            final StringBuilder ids = new StringBuilder();
            for (final Programs.Program p : Programs.available()) {
                ids.append(ids.length() == 0 ? "" : " / ").append(p.id());
            }
            src.sendFailure(Component.literal("没有这个程序：" + program + "（可用：" + ids + "）"));
            return 0;
        }
        final Path dir = com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.directoryOf(src.getLevel(), address);
        if (!Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE))) {
            src.sendFailure(Component.literal("这块盘还没被挂载过（没有分区表）：先把它放进机器开机一次，"
                    + "机器挂载时会按盘的真实容量写下 disk.json"));
            return 0;
        }
        try {
            final com.hdf.cryptand.soc.os.Installer.Report rep = com.hdf.cryptand.soc.os.Installer.install(
                    dir, 0, 0, "", bytes, program);
            com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.unmount(address);
            src.sendSuccess(() -> Component.literal("已安装 " + program + "：分区 "
                    + (rep.partitionBytes() / 1024) + "KB " + rep.filesystem()
                    + "，程序 " + rep.programBytes() + " 字节；重启机器即生效"), true);
            return 1;
        } catch (RuntimeException e) {
            // 装不下等：common 已经把原因说清楚了（且保证盘没被改动），原样转给玩家
            src.sendFailure(Component.literal("安装失败（盘未被改动）：" + e.getMessage()));
            return 0;
        }
    }

    private static int format(CommandSourceStack src, String address, int sizeKb) {
        try {
            // 容量传 0 ⇒ 沿用盘目录里 disk.json 的容量（盘的真实容量是机器挂载时写进去的）
            final String out = DiskFormatTool.format(com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.directoryOf(src.getLevel(), address),
                    0, sizeKb * 1024L, "", "");
            com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.unmount(address);
            src.sendSuccess(() -> Component.literal(out + "；盘已卸载，重启机器即按新分区挂载"), true);
            return 1;
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal("格式化失败：" + e.getMessage()));
            return 0;
        }
    }

    /**
     * 给主手的盘**强制分配地址**（用户 2026-09-18："分配地址可以增加一条指令来实现强制分配地址
     * （仅针对可以分配地址的比如硬盘这种）"）。
     *
     * <p>为什么需要：地址默认是自动生成的 UUID ⇒ 测试时"我拿到的是哪块盘"全靠运气，
     * 盘目录、命令参数、断言都没法写死。强制指定之后整条链路就变成可复现的了
     * （配合 {@code /cryptand soc disk load <id> <程序>} 做"组装器式"的程序更换）。</p>
     */
    private static int assignAddress(CommandSourceStack src, String address) {
        final net.minecraft.world.entity.player.Player player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("该命令需要玩家执行（把盘拿在主手）"));
            return 0;
        }
        final net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof com.hdf.cryptand.neoforge.soc.content.SocPartItem)) {
            src.sendFailure(Component.literal("主手不是 Cryptand 部件 —— 请把要分配地址的盘拿在主手"));
            return 0;
        }
        // 地址的生成与校验只有一份（common 的 DiskAddress）—— 命令这里只做"取参数 + 回显"。
        // ⚠ 非法地址必须**当场拒绝**：a/b 与 a_b 安全化后是同一个目录，两块盘会共享内容。
        final String target;
        try {
            target = address == null || address.isBlank()
                    ? com.hdf.cryptand.soc.fs.DiskAddress.generate()
                    : com.hdf.cryptand.soc.fs.DiskAddress.require(address);
        } catch (com.hdf.cryptand.soc.fs.FsException e) {
            src.sendFailure(Component.literal("地址不合法：" + e.getMessage()));
            return 0;
        }
        com.hdf.cryptand.neoforge.soc.content.SocPartItem.assignAddress(stack, target);
        src.sendSuccess(() -> Component.literal("已给「" + stack.getHoverName().getString()
                + "」分配地址 " + target + "（后续 /cryptand soc disk load|install 就用它）"), true);
        return 1;
    }

    /**
     * 取盘的**宿主侧可写实例**。
     *
     * <p>⚠ 刻意不走 {@code OcDiskMounts.mounted(...)}：那里面是**机器看到的**实例 ——
     * 软盘在那里是只读的（锁）。但用户定案"根据此 id 对软盘使用程序进行程序强制更换"，
     * 也就是宿主侧的程序加载器必须能写。所以这里独立开一个可写实例，
     * 这正是"组装器式"换程序能成立的原因。</p>
     */
    private static CryptandFileSystem mount(ServerLevel level, String address, CommandSourceStack src) {
        final Path dir = com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.directoryOf(level, address);
        if (!Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE))) {
            src.sendFailure(Component.literal("这块盘还没被挂载过，也没有分区表：" + address
                    + "（先把它放进机器开机一次，或先用 disk format 建分区）"));
            return null;
        }
        return com.hdf.cryptand.soc.fs.DiskFileSystems.open(dir, 0, address, false);
    }

    private static String describe(String address) {
        final CryptandFileSystem fs = com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts.mounted(address);
        if (fs == null) {
            return address + "  （未挂载）";
        }
        final byte[] prog = Programs.installed(fs);
        return address + "  " + (fs.spaceUsed() / 1024) + "/" + (fs.spaceTotal() / 1024) + " KB"
                + (prog.length > 0 ? "  已装程序 " + prog.length + " 字节" : "  （盘上没有程序）");
    }


    /** /cryptand soc disk bios show <盘地址> —— 读盘上的 CMOS（{@code /boot/cmos.cfg}） */
    private static int biosShow(CommandSourceStack src, String address) {
        try {
            final com.hdf.cryptand.soc.bios.BiosConfig cfg = readCmos(src, address);
            if (cfg == null) {
                src.sendSuccess(() -> Component.literal("这块盘没有 CMOS（"
                        + com.hdf.cryptand.soc.bios.BiosCmos.CMOS_PATH + "）⇒ 机器用默认配置开机"), false);
                return 1;
            }
            src.sendSuccess(() -> Component.literal("CMOS：" + cfg.describe()), false);
            return 1;
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal("读 CMOS 失败：" + e.getMessage()));
            return 0;
        }
    }

    /**
     * /cryptand soc disk bios &lt;order|forcedslot|label&gt; &lt;盘地址&gt; &lt;值&gt; —— 写盘上的 CMOS。
     *
     * <p>解析全部委托 common 的 {@code BiosConfig.withSetting}（与 MCP 工具、将来的 Setup 界面
     * **同一份**）：非法值当场拒绝并把原话转给玩家，不吞不改写。</p>
     */
    private static int biosSet(CommandSourceStack src, String address, String key, String value) {
        try {
            final com.hdf.cryptand.soc.fs.CryptandFileSystem fs = openForWrite(src, address);
            if (fs == null) {
                return 0;
            }
            final com.hdf.cryptand.soc.bios.BiosConfig before = com.hdf.cryptand.soc.bios.BiosCmos.read(fs);
            final com.hdf.cryptand.soc.bios.BiosConfig base = before == null
                    ? com.hdf.cryptand.soc.bios.BiosConfig.defaults() : before;
            final com.hdf.cryptand.soc.bios.BiosConfig after = base.withSetting(key, value);
            com.hdf.cryptand.soc.bios.BiosCmos.write(fs, after);
            src.sendSuccess(() -> Component.literal("CMOS 已写入并回读校验通过：" + after.describe()
                    + "；机器重新上电即生效"), true);
            return 1;
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("设置被拒绝：" + e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal("写 CMOS 失败：" + e.getMessage()));
            return 0;
        }
    }

    /** 读盘上的 CMOS（没有 ⇒ null） */
    private static com.hdf.cryptand.soc.bios.BiosConfig readCmos(CommandSourceStack src, String address) {
        final com.hdf.cryptand.soc.fs.CryptandFileSystem fs = openForWrite(src, address);
        return fs == null ? null : com.hdf.cryptand.soc.bios.BiosCmos.read(fs);
    }

    /**
     * 打开盘的可写实例（与 info/format/install **同一条路径**：容量 0 = 沿用盘目录里的 disk.json）。
     *
     * <p>⚠ 刻意不要求"盘已被机器挂载"：盘还在箱外时也要能改 CMOS（配置属于介质，不属于某台机器）。</p>
     */
    private static com.hdf.cryptand.soc.fs.CryptandFileSystem openForWrite(CommandSourceStack src, String address) {
        final java.nio.file.Path dir = com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts
                .directoryOf(src.getLevel(), address);
        if (!java.nio.file.Files.isRegularFile(dir.resolve(com.hdf.cryptand.soc.fs.DiskPartitionTable.META_FILE))) {
            src.sendFailure(Component.literal("这块盘还没被挂载过，也没有分区表：" + address
                    + "（先把它放进机器开机一次，或先用 disk format 建分区）"));
            return null;
        }
        return com.hdf.cryptand.soc.fs.DiskFileSystems.open(dir, 0, address, false);
    }
}
