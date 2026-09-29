/**
 * ===== OC 机器诊断与开关机的 MCP 工具（2026-09-17）=====
 *
 * <p><b>为什么要它</b>：无人化测试时，"部件插进机箱"能靠 {{@code container_*}} 验证，但
 * "机器到底有没有开机、为什么开不了机"在外部**看不到** —— OC 的 {{@code Machine.start()}}
 * 有多个静默失败分支（节点没接入网络时直接 {{@code case _ => false}}，不写任何日志），
 * 只看日志会一头雾水。所以这里按用户要求「没有的 mcp 自己实现」，补两个**原子工具**：</p>
 *
 * <table border="1">
 *   <tr><th>工具</th><th>作用</th></tr>
 *   <tr><td>{{@code oc_machine_state}}</td><td>读机器内部状态：运行/暂停、错误、架构类、组件数/上限、
 *       节点地址与**网络是否为 null**（这是 {{@code start()}} 静默失败的头号原因）、能量缓冲</td></tr>
 *   <tr><td>{{@code oc_machine_power}}</td><td>直接开关机（调用 {{@code machine.start()/stop()}}），
 *       等价于 GUI 电源按钮或"潜行右键"，并把失败原因（{{@code lastError}}）带回</td></tr>
 * </table>
 *
 * <p><b>抗版本策略</b>：OC 的 API 接口（{{@code api.machine.Machine}} / {{@code api.network.Environment}}）
 * 稳定，直接用；而 {{@code isRunning / start / stop}} 属于内部实现类（Scala 类，跨版本可能改名），
 * 一律走**反射**读取，取不到就如实报告，不会因为 OC 升级而编译失败或抛异常。</p>
 *
 * <p><b>线程纪律</b>：世界访问一律投递到服务端线程执行（{{@code server.execute}），MCP 调用线程等待结果。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers.ai;

import com.google.gson.JsonObject;
import com.hdf.cryptand.neoforge.aiauto.mc.WorldOps;
import com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer;
import com.hdf.cryptand.neoforge.opencomputers.CryptandOcArchitecture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import com.hdf.cryptand.neoforge.opencomputers.OcDiskFormat;
import com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class OcAiTools {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    private OcAiTools() {
    }

    /** 注册两个工具（由 {@code OpenComputersEntry.init} 调用；此时才确定 OC 在场） */
    public static void registerAll() {
        AiToolServer.category("world", () -> {

            // 真彩屏调试通道（无人化验证设备链路：写像素/填充/上锁/读状态）。
            // ⚠ 像素操作要求先切 GRAPHICS 模式 —— 模式不对就明确报错（设备模型里就是这么定的，
            //   不在这里偷偷替调用方切模式）。
            AiToolServer.register("truescreen",
                    "真彩屏：写像素/填充/切模式/上锁/读状态（1..32bpp 设备链路验证）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "action", "status|configure|mode|fill|pixel|lock|unlock",
                            "w", "int（configure：宽）", "h", "int（configure：高）",
                            "bpp", "int（已废弃：色深由屏的 tier 声明 1/8/16/24，传不一致的值会报错）",
                            "mode", "GRAPHICS|TEXT", "color", "int RGB（fill/pixel）", "index", "int 调色板索引（fill/pixel 用索引色深时）",
                            "px", "int（pixel：像素 X —— 只用 px/py，x/y/z 永远是方块坐标）",
                            "py", "int（pixel：像素 Y）"),
                    args -> AiToolServer.ok(onServer(level -> trueScreen(level, posOf(args), args), 10_000)));

            AiToolServer.register("oc_machine_state",
                    "读 OC 机器（机箱/服务器）内部状态：运行/暂停、错误、架构类、组件数与上限、"
                            + "节点地址/网络（网络=null 是开不了机的头号原因）、能量缓冲 —— 诊断“为什么开不了机”",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）"),
                    args -> AiToolServer.ok(onServer(level -> describe(level, posOf(args)), 5_000)));

            AiToolServer.register("oc_machine_power",
                    "给 OC 机器开关机：直接调用 machine.start()/stop()（等价 GUI 电源按钮 / 潜行右键），"
                            + "并回报是否成功与失败原因（lastError）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "on", "bool（默认 true=开机）"),
                    args -> {
                        final boolean on = AiToolServer.bool(args, "on", true);
                        return AiToolServer.ok(onServer(level -> power(level, posOf(args), on), 5_000));
                    });

            AiToolServer.register("oc_key",
                    "给 OC 机器注入键盘输入 —— 走**真实后续路径**：与真人按键一样进**统一消息缓存区**"
                            + "（guest RAM 的 MSG_QUEUE @0x20023800，宿主 postToGuest(KIND_KEY)，"
                            + "固件 hal_msg_take 按 tail 消费），**不再是 UART RX**"
                            + "（16 字节硬件 FIFO 会把回车挤掉，2026-09-25 真机实测）。"
                            + "key = 单个键名（a/enter/space/backspace/tab/escape/up/down/left/right/"
                            + "home/end/delete/f1..f12…，固件行编辑已支持 ANSI 序列），text = 整段文本；"
                            + "两者可同时给（先 text 后 key）。返回值带消息缓存区状态（待发/容量、丢弃、seq、已消费）"
                            + "—— 注入成功 ≠ 固件收到，这几项才分得清「队列满丢了」还是「固件没在消费」。"
                            + "**Lua 架构（OC 原版 CPU）的机器现在也支持**：走 key_down / text_input 信号"
                            + "（与真彩屏窗口输入服务端同一段实现），返回值改成报「注入到哪个屏 + 几个字符」。",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "key", "string（键名，可选）",
                            "text", "string（整段文本，可选）"),
                    args -> AiToolServer.ok(onServer(level -> injectKey(level, posOf(args),
                            AiToolServer.str(args, "key", ""), AiToolServer.str(args, "text", "")), 5_000)));

            AiToolServer.register("soc_inspect",
                    "**高级分析器（无人化）**：返回与高级分析器面板**同一份**沙箱内部快照文本 —— 七个分区"
                            + "（处理器 / 执行 / 内存 / 模块清单 / 设备表 / 设备缓存 / 消息缓存区），"
                            + "模块清单 = 虚拟机为芯片建立/装载的模块（接口/速率/窗口/组件槽位、FIFO 模块深度、"
                            + "内存容量与映射、显卡通道与屏分辨率）+ 资源总账（占用 vs 上限）；文本由 common 的 "
                            + "SandboxInspect 排版（面板与它逐字同源，绝不两处各算一套）。"
                            + "坐标取 OC 机器（机箱 / 服务器）；给高级分析器方块的坐标也行（跟随它锁定的目标机）。"
                            + "缺项显示 -（查不到 ≠ 0）。",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）"),
                    args -> AiToolServer.ok(onServer(level -> inspectSandbox(level, posOf(args)), 10_000)));

            AiToolServer.register("oc_screen",
                    "读 OC 机器屏幕的**字符内容**（每行去尾空格）—— 无人化断言专用："
                            + "截图会像素化到认不出字，而这里拿的是固件交给 gpu.blit 的那份字符阵列",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）"),
                    args -> AiToolServer.ok(onServer(level -> screenText(level, posOf(args)), 5_000)));

            AiToolServer.register("oc_use_item",
                    "模拟**玩家右键**（对指定方块使用一次）—— 无人化验证 \"手持 X 右键\"（如程序加载器写盘）"
                            + "与 \"空手右键\"（如 OC 屏开终端窗口）两类交互用；"
                            + "玩家取服务器上的第一个在线玩家（单人测试即 Dev）；"
                            + "empty=true 时空手右键（临时把主手换空、用完还原）",
                    AiToolServer.schema("x", "int/string（支持 ~）", "y", "int/string（支持 ~）",
                            "z", "int/string（支持 ~）", "empty", "bool（可选，true = 空手右键）"),
                    args -> AiToolServer.ok(onServer(level ->
                            useHeldOn(level, posOf(args), AiToolServer.bool(args, "empty", false)), 15_000)));

            AiToolServer.register("oc_disk_list",
                    "列出当前已挂载的 Cryptand 盘（address = 盘物品 UUID）及其宿主目录 —— "
                            + "要格式化或排查盘问题，先用它拿到 address",
                    AiToolServer.schema("address", "string（可选，只看这一块）"),
                    args -> AiToolServer.ok(onServer(level -> {
                        final java.util.Set<String> addrs = OcDiskMounts.mountedAddresses();
                        final String only = AiToolServer.str(args, "address", "");
                        if (addrs.isEmpty()) {
                            return "（没有已挂载的盘：机器开机时盘才会挂载）";
                        }
                        final StringBuilder sb = new StringBuilder("已挂载 " + addrs.size() + " 块盘：");
                        for (String a : addrs) {
                            if (!only.isEmpty() && !a.equals(only)) {
                                continue;
                            }
                            final com.hdf.cryptand.soc.fs.CryptandFileSystem fs = OcDiskMounts.mounted(a);
                            final String usage = fs == null ? "?"
                                    : (fs.spaceUsed() / 1024) + "/" + (fs.spaceTotal() / 1024) + " KB";
                            sb.append("\n  ").append(a).append("  ").append(usage)
                                    .append("  ->  ").append(OcDiskMounts.directoryOf(level, a));
                            // 卷表（用户 2026-09-24："完整 os 可以支持通过分区来实现功能"）：
                            // 一块盘 = 一张分区表 = 一组卷（/、/usr、/home…）。AI 侧看不到盘上有哪些卷，
                            // 就没法判断"系统装在哪、数据该放哪" —— 所以跟挂载状态一起给出。
                            try {
                                for (final String vol : OcDiskMounts.describeVolumes(level, a, 0, false)) {
                                    sb.append("\n      ").append(vol);
                                }
                            } catch (RuntimeException e) {
                                sb.append("\n      （读分区表失败：").append(e.getMessage()).append("）");
                            }
                        }
                        return sb.toString();
                    }, 5_000)));

            AiToolServer.register("oc_bios_show",
                    "读一块盘上的 CMOS（BIOS 配置，/boot/cmos.cfg）：boot order / 强制槽位 / 卷标。"
                            + "没有该文件说明这块盘没带配置（机器会用默认值开机）。",
                    AiToolServer.schema("address", "string（盘 UUID，先用 oc_disk_list 查）"),
                    args -> AiToolServer.ok(onServer(level -> {
                        final String a = AiToolServer.str(args, "address", "");
                        if (a.isEmpty()) {
                            return "ERR 需要 address（盘 UUID；先用 oc_disk_list 查）";
                        }
                        try {
                            final com.hdf.cryptand.soc.fs.CryptandFileSystem fs =
                                    com.hdf.cryptand.soc.fs.DiskFileSystems.open(
                                            com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts
                                                    .directoryOf(level, a), 0, a, false);
                            final com.hdf.cryptand.soc.bios.BiosConfig cfg =
                                    com.hdf.cryptand.soc.bios.BiosCmos.read(fs);
                            if (cfg == null) {
                                return "这块盘没有 CMOS（" + com.hdf.cryptand.soc.bios.BiosCmos.CMOS_PATH
                                        + "）⇒ 机器用默认配置开机";
                            }
                            return "CMOS：" + cfg.describe();
                        } catch (RuntimeException e) {
                            return "ERR " + e.getMessage();
                        }
                    }, 5_000)));

            AiToolServer.register("oc_bios_set",
                    "改一块盘上的 CMOS（BIOS 配置），下次开机生效。key = order（floppy|hdd|slot）/ "
                            + "forcedslot（none 或 0..N）/ label（文本）。非法值**明确报错**，不静默忽略。",
                    AiToolServer.schema("address", "string（盘 UUID）",
                            "key", "string（order | forcedslot | label）", "value", "string（新值）"),
                    args -> AiToolServer.ok(onServer(level -> {
                        final String a = AiToolServer.str(args, "address", "");
                        final String key = AiToolServer.str(args, "key", "");
                        final String value = AiToolServer.str(args, "value", "");
                        if (a.isEmpty() || key.isEmpty()) {
                            return "ERR 需要 address 与 key（value 可空以表示清空）";
                        }
                        try {
                            final com.hdf.cryptand.soc.fs.CryptandFileSystem fs =
                                    com.hdf.cryptand.soc.fs.DiskFileSystems.open(
                                            com.hdf.cryptand.neoforge.opencomputers.OcDiskMounts
                                                    .directoryOf(level, a), 0, a, false);
                            final com.hdf.cryptand.soc.bios.BiosConfig before =
                                    com.hdf.cryptand.soc.bios.BiosCmos.read(fs);
                            final com.hdf.cryptand.soc.bios.BiosConfig base = before == null
                                    ? com.hdf.cryptand.soc.bios.BiosConfig.defaults() : before;
                            final com.hdf.cryptand.soc.bios.BiosConfig after = base.withSetting(key, value);
                            com.hdf.cryptand.soc.bios.BiosCmos.write(fs, after);
                            return "CMOS 已写入并回读校验通过：" + after.describe();
                        } catch (RuntimeException e) {
                            return "ERR " + e.getMessage();
                        }
                    }, 5_000)));

            AiToolServer.register("oc_disk_format",
                    "格式化一块盘：在盘目录里**建一个分区**并把文件系统写上去（用户定案“格式化是一等动作”）。"
                            + "fs 省略时按容量自动推荐（≤8MB→FAT12、≤128MB→FAT16、其余 FAT32）；"
                            + "容量与该类型不匹配时**明确报错**（不静默降级）。格式化后该盘会卸载，重新开机即挂载。",
                    AiToolServer.schema("address", "string（盘 UUID，先用 oc_disk_list 查）",
                            "sizeKb", "int（分区大小 KB —— 盘是嵌入式容量，KB 才够细）",
                            "capacityKb", "int（盘总容量 KB，默认沿用盘目录里的 disk.json）",
                            "fs", "string（FAT12/FAT16/FAT32/RAW/CRYPTAND，可选）",
                            "label", "string（卷标，可选）"),
                    args -> AiToolServer.ok(onServer(level -> formatDisk(level,
                            AiToolServer.str(args, "address", ""),
                            AiToolServer.str(args, "capacityKb", ""),
                            AiToolServer.str(args, "sizeKb", ""),
                            AiToolServer.str(args, "fs", ""),
                            AiToolServer.str(args, "label", "")), 15_000)));

            AiToolServer.register("oc_disk_program",
                    "把编译好的程序（固件）装进一块盘：写入盘的 "
                            + com.hdf.cryptand.soc.os.Programs.BOOT_PATH
                            + " 并回读校验。装完重启机器即生效（oc_machine_power off → on），"
                            + "**不需要重启客户端** —— 这正是空软盘 + 程序加载器要的快速调试循环。"
                            + "program 取 id：boot / cryptand-os / cryptand-ui-os。"
                            + "media=true 时改成做**系统安装介质**（系统软盘那种）：/boot/system.bin 放 PE 装机环境、"
                            + "完整镜像进 /images/<program>.bin、并同时写 **/init.lua**（Lua 架构的入口）"
                            + "—— 少了 /init.lua 的盘插进 OC 原版 CPU 的机器就只能报「没有 init.lua」。",
                    AiToolServer.schema("address", "string（盘 UUID；oc_disk_list 查）",
                            "program", "string（程序 id，默认 cryptand-os）",
                            "media", "bool（true = 做成安装介质：PE + /images + /init.lua）"),
                    args -> AiToolServer.ok(onServer(level -> installProgram(level,
                            AiToolServer.str(args, "address", ""),
                            AiToolServer.str(args, "program", "cryptand-os"),
                            AiToolServer.bool(args, "media", false)), 15_000)));
        });
    }

    /** 格式化（建分区 + 写文件系统）并卸载该盘，让下次开机按新分区挂载 */
    private static String formatDisk(ServerLevel level, String address, String capacityKb, String sizeKb,
                                     String fs, String label) {
        if (address == null || address.isBlank()) {
            return "ERR 需要 address（盘 UUID；先用 oc_disk_list 查）";
        }
        final long size = parseKb(sizeKb, 8);
        // 容量默认 **0 = 沿用盘目录里已有的 disk.json**：盘的真实容量由"机器挂载时"写入，
        // 让调用方随手传一个容量只会写出与盘不符的元数据（第一次试就是 8KB 盘收到 8MB 的坑）。
        final long capacity = parseKb(capacityKb, 0);
        if (size <= 0) {
            return "ERR sizeKb 必须 > 0（当前 " + sizeKb + "）";
        }
        final String out = OcDiskFormat.format(level, address, capacity * 1024L, size * 1024L, fs, label);
        OcDiskMounts.unmount(address);
        return out + "\n盘目录: " + OcDiskMounts.directoryOf(level, address)
                + "\n该盘已卸载：机器重新开机后即按新分区挂载";
    }

    /** 把程序装进盘（写 /boot/system.bin + 回读校验）—— 引导读的就是这个文件 */
    private static String installProgram(ServerLevel level, String address, String programId,
                                                 boolean media) {
        if (address == null || address.isBlank()) {
            return "ERR 需要 address（盘 UUID；先用 oc_disk_list 查）";
        }
        final byte[] bytes = com.hdf.cryptand.soc.os.Programs.readById(programId);
        if (bytes.length == 0) {
            final StringBuilder ids = new StringBuilder();
            for (final var pr : com.hdf.cryptand.soc.os.Programs.available()) {
                ids.append(ids.length() == 0 ? "" : "/").append(pr.id());
            }
            return "ERR 程序 " + programId + " 不存在或没编译（可用：" + ids + "）";
        }
        com.hdf.cryptand.soc.fs.CryptandFileSystem fs = OcDiskMounts.mounted(address);
        if (fs == null) {
            final java.nio.file.Path dir = OcDiskMounts.directoryOf(level, address);
            if (!java.nio.file.Files.isRegularFile(
                    dir.resolve(com.hdf.cryptand.soc.fs.DiskPartitionTable.META_FILE))) {
                return "ERR 这块盘还没挂载过 —— 先开机一次让机器挂载它（或先用 oc_disk_format 建分区）";
            }
            // 已有 disk.json ⇒ 容量以文件为准，传 0 是安全的（不会覆盖盘的真实容量）
            fs = OcDiskMounts.of(level, address, 0, false);
        }
        if (!media) {
            com.hdf.cryptand.soc.os.Programs.install(fs, bytes);
            return "已装 " + programId + "（" + bytes.length + " 字节）→ " + address
                    + ":" + com.hdf.cryptand.soc.os.Programs.BOOT_PATH
                    + "；重启机器（oc_machine_power off 再 on）即生效";
        }
        // 安装介质（系统软盘）：PE 进 /boot/system.bin、完整镜像进 /images/<id>.bin、再补 Lua 入口 /init.lua。
        //   ⚠ 为什么必须给这条路：只有 /boot/loader.bin 的盘在 Lua 架构下起不来，而 Programs.install
        //   不写 /init.lua —— 验证就被卡在这里（真机症状：错误= 里 no /init.lua on any filesystem
        //   (found 1 C/RV32 boot disk(s) with /boot/loader.bin)）。
        com.hdf.cryptand.soc.os.Programs.installInstallerMedia(fs, programId);
        final String luaInitPath = "/init.lua";
        final int initBytes = fs.exists(luaInitPath) ? (int) fs.size(luaInitPath) : 0;
        return "已把 " + address + " 做成安装介质（PE + /images/" + programId + ".bin + "
                + com.hdf.cryptand.soc.os.Programs.BOOT_PATH + " + " + luaInitPath
                + " " + initBytes + " 字节）；重启机器（oc_machine_power off 再 on）即生效";
    }

    private static long parseKb(String text, long fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是整数: " + text);
        }
    }

    // ==================== 服务端线程执行 ====================

    private static String onServer(java.util.function.Function<ServerLevel, String> body, long timeoutMs) {
        final Minecraft mc = Minecraft.getInstance();
        final MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return "ERR 服务端不可用（需要单人世界）";
        }
        final ServerLevel level = server.overworld();
        final CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(body.apply(level));
            } catch (Throwable t) {
                future.complete("ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return "ERR 服务端执行超时或失败：" + t.getClass().getSimpleName();
        }
    }

    private static BlockPos posOf(JsonObject args) {
        return WorldOps.resolve(AiToolServer.str(args, "x", "~"),
                AiToolServer.str(args, "y", "~"), AiToolServer.str(args, "z", "~"));
    }

    // ==================== 状态读取 ====================

    private static String describe(ServerLevel level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return "ERR 坐标 " + pos.toShortString() + " 没有方块实体";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("方块=").append(level.getBlockState(pos).getBlock()).append('\n');

        final Object machine = reflect(be, "machine");
        if (machine == null) {
            return sb.append("ERR 该方块不是 OC 机器（没有 machine 访问器）").toString();
        }
        sb.append("机器类=").append(machine.getClass().getName()).append('\n');

        // —— 走 OC 稳定 API 读得到的部分 ——
        if (machine instanceof li.cil.oc.api.machine.Machine api) {
            sb.append("架构=").append(api.architecture() == null ? "null"
                    : api.architecture().getClass().getName()).append('\n');
            sb.append("组件数=").append(api.componentCount()).append(" / 上限=").append(api.maxComponents()).append('\n');
            // 组件表（address → 类型）：排查"机器里到底插了什么 / GPU 在不在 / 盘挂上了没有"
            // 最直接的一项 —— 以前只能靠数组件数猜（2026-09-26 真机："PE 跑起来了但屏幕没内容"）。
            try {
                sb.append("组件表=");
                boolean first = true;
                for (final var e : api.components().entrySet()) {
                    sb.append(first ? " " : "\n        ").append(e.getKey()).append("  ").append(e.getValue());
                    first = false;
                }
                sb.append('\n');
            } catch (Throwable t) {
                sb.append("组件表=（不可读：").append(t.getClass().getSimpleName()).append("）\n");
            }
            sb.append("错误=").append(api.lastError() == null ? "（无）" : api.lastError()).append('\n');
            sb.append("开机时长=").append(String.format("%.2f", api.upTime())).append("s\n");
        }

        // —— 内部实现类走反射 ——
        sb.append("isRunning=").append(stringify(call(machine, "isRunning"))).append('\n');
        sb.append("isPaused=").append(stringify(call(machine, "isPaused"))).append('\n');

        // —— Cryptand 架构的"标称 vs 实测"主频（用户 2026-09-17：MHz 口径，方便比性能）——
        try {
            if (machine instanceof li.cil.oc.api.machine.Machine api
                    && api.architecture() instanceof CryptandOcArchitecture arch) {
                sb.append("CPU 标称=").append(arch.nominalMhz()).append(" MHz")
                        .append("  实测=").append(String.format("%.2f", arch.actualMhz())).append(" MHz")
                        .append("  负载=").append(String.format("%.0f%%", arch.load() * 100.0))
                        .append("  内核=").append(machine_nativeLabel(arch))
                        // 位宽来自 CPU 部件（用户 2026-09-18 定案）⇒ 排查"64 位芯片"先看这一项
                        .append("  ISA=").append(arch.isa() == null ? "（未判定/无处理器）" : arch.isa().id())
                        .append('\n');
            }
        } catch (Throwable t) {
            sb.append("（主频统计不可用：").append(t.getClass().getSimpleName()).append("）\n");
        }

        // —— 节点与网络（start() 静默失败的关键判据）——
        if (be instanceof li.cil.oc.api.network.Environment env) {
            final li.cil.oc.api.network.Node node = env.node();
            if (node == null) {
                sb.append("节点=null（机器还没建节点）\n");
            } else {
                sb.append("节点地址=").append(node.address()).append('\n');
                sb.append("网络=").append(node.network() == null
                        ? "**null ⇒ OC 的 Machine.start() 会静默返回 false（不写日志）**"
                        : node.network().getClass().getSimpleName()).append('\n');
                if (node instanceof li.cil.oc.api.network.Connector c) {
                    sb.append("能量缓冲=").append(c.globalBuffer())
                            .append(" / ").append(c.globalBufferSize()).append('\n');
                }
            }
        } else {
            sb.append("（该方块未实现 api.network.Environment，跳过节点信息）\n");
        }
        return sb.toString();
    }

    // ==================== 开关机 ====================

    /**
     * 注入键盘输入到 Cryptand 架构（"无法打字"那条链的验证入口）。
     *
     * <p>它覆盖：**消息缓存区**（{@code HostMessageRing}，KIND_KEY）→ 固件 {@code hal_msg_take}
     * 按 tail 消费 → shell 收到字符 → 回显/执行命令。唯一没覆盖的是"键盘组件 → 信号 → onSignal"
     * 那一跳（只有真人按键才会走）—— onSignal 抽出字节后走的是**同一个** {@code postToGuest}。</p>
     */
    /**
     * **手持物品右键**：走玩家右键的**真实服务端入口**。
     *
     * <p>⚠ 为什么不是 `BlockState.useWithoutItem`：它是 `protected`，外部调不到；
     * 而 {@code ServerPlayerGameMode.useItemOn} 正是服务端处理玩家右键的那条路 —— 与真人操作同构。</p>
     */
    private static String useHeldOn(ServerLevel level, BlockPos pos, boolean emptyHand) {
        final net.minecraft.server.level.ServerPlayer sp =
                level.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (sp == null) {
            return "ERR 没有在线玩家（需要玩家在线才能模拟右键）";
        }
        final net.minecraft.world.item.ItemStack held = sp.getMainHandItem();
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        final net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
        // 空手右键：临时把主手换空（真人空手右键等价），用完**无条件**还原并广播，避免改坏玩家背包。
        final net.minecraft.world.item.ItemStack restore = held.copy();
        if (emptyHand) {
            sp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    net.minecraft.world.item.ItemStack.EMPTY);
        }
        final net.minecraft.world.InteractionResult r;
        try {
            r = sp.gameMode.useItemOn(sp, level,
                    emptyHand ? net.minecraft.world.item.ItemStack.EMPTY : held,
                    net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        } finally {
            if (emptyHand) {
                sp.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, restore);
                sp.containerMenu.broadcastChanges();
            }
        }
        return (emptyHand ? "empty-hand" : "held=" + held.getItem() + " x" + held.getCount())
                + " ; block=" + state.getBlock() + " ; result=" + r;
    }

    /**
     * 读机器屏幕的**字符内容**（无人化断言用）。
     *
     * <p>取舍：截图会像素化到认不出字（实测），而这里拿的是固件交给 {@code gpu.blit}
     * 的那份 w×h 字符阵列 —— 它就是屏幕上真正显示的东西。</p>
     */
    /**
     * 真彩屏调试（无人化验证）：动作全部落到 common 的 {@code TrueColorScreen} 上，
     * 这里只做"方块实体 → 设备"的取用与结果编排。
     *
     * <p>任务 F-2（2026-09-27）：旧自研真彩屏（{@code soc/block/TrueScreenBlockEntity}）已整体
     * 删除 ⇒ 这里只剩移植件这一条路：设备从**像素通道**按方块坐标取（见 {@link #trueScreenPort}）。</p>
     */
    private static String trueScreen(ServerLevel level, BlockPos pos, com.google.gson.JsonObject args) {
        return trueScreenPort(pos, args);
    }

    /**
     * **新移植屏**（Scala 移植件）的真彩屏工具动作体。
     *
     * <p>与旧自研屏共用同一套 action（status|configure|mode|fill|pixel|lock|unlock），
     * 差别只有三处，全部来自生命周期不同而不是第二套语义：</p>
     *
     * <p>⚠ <b>这些 action 全是调试手段</b>（任务 G）：功能路径 = 程序画图像 → 由屏链路自动决定
     * "直接画像素 / 转字符"，判定规则只有 common 的 {@code ScreenOutputFace} 一份。
     * 其中 {@code action=mode} 尤其只是"把设备的派生状态位强制摆一下"（例如看像素帧渲染），
     * <b>不是</b> guest 可用的模式开关（{@code gpu.setMode} 已作废、不实现）。</p>
     * <ol>
     *   <li>设备从 {@code TrueScreenGraphicsSink} 取 —— 按**方块坐标**在同一张登记表里查
     *       （{@code TrueScreenGraphicsApi.sinkAtRequired}，缺了会响亮记 warn）；</li>
     *   <li>设备是**懒建**的：没 configure 之前 {@code device()} 为 null ⇒ 除 configure 外的动作明确报错，不猜；</li>
     *   <li>写完调 {@code markFrameChanged()}（新屏没有"立刻同步"：**主线程 tick** 把帧发给附近玩家）。</li>
     * </ol>
     *
     * <p>返回串末尾多一个 {@code via=port}（旧屏那支保持原样不动），真机断言可以据此区分哪条路生效。</p>
     */
    private static String trueScreenPort(BlockPos pos, com.google.gson.JsonObject args) {
        final com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsSink sink =
                com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsApi.sinkAtRequired(pos, "OcAiTools.trueScreen");
        if (sink == null) {
            return "ERR 坐标 " + pos.toShortString() + " 既不是旧自研真彩屏，也没有登记的像素通道"
                    + "（新移植屏要先接入 OC 网络；拼合屏只能对 origin 操作）";
        }
        final String action = AiToolServer.str(args, "action", "status");
        try {
            if ("configure".equals(action)) {
                // ⚠ 色深**只有屏一个来源**（用户定案 2026-09-29：四档按 1/8/16/24，
                //   见 TrueScreenSettings.screenBppByTier）：调试工具不再另开一套 bpp。
                //   要测别的色深就放对应档的屏（tier 决定 bpp）；显式传了不一致的 bpp 就明确报错。
                if (args.has("bpp") && AiToolServer.num(args, "bpp", sink.bpp()) != sink.bpp()) {
                    return "ERR bpp 已废弃：色深由屏的 tier 声明（当前 " + sink.bpp()
                            + "bpp）；要换色深请放对应档的屏（truescreen1..4 = 1/8/16/24bpp）";
                }
                sink.configure(AiToolServer.num(args, "w", 64), AiToolServer.num(args, "h", 64), sink.bpp());
            }
            final com.hdf.cryptand.soc.board.TrueColorScreen d = sink.device();
            if (d == null) {
                return "ERR 还没建像素设备：先 action=configure w=.. h=.. bpp=..（像素模式必须先显式 configure）";
            }
            switch (action) {
                case "configure", "status" -> {
                    // configure 已经在上面落地；status 只是读
                }
                // ⚠ 调试手段，**不是功能路径**（用户定案 repo/gpu-auto-convert-output-2026-09-27.md）：
                //   屏设备的 TEXT/GRAPHICS 是**内部派生状态**（谁在画 × 屏能显示什么，规则在
                //   common 的 ScreenOutputFace），功能路径上没有任何东西读/写它；这里只是
                //   "无人化验证时把设备强制摆到某个面"来看渲染/报文，下一次真实写入会按
                //   写入种类重新派生（GPU 字符 API ⇒ TEXT；图形面 ⇒ GRAPHICS/转字符）。
                case "mode" -> d.setMode(com.hdf.cryptand.soc.board.TrueColorScreen.Mode
                        .valueOf(AiToolServer.str(args, "mode", "GRAPHICS").toUpperCase(java.util.Locale.ROOT)));
                case "fill" -> {
                    final int rgb = AiToolServer.num(args, "color", 0x00FF00);
                    final int index = AiToolServer.num(args, "index", 1);
                    for (int y = 0; y < d.height(); y++) {
                        for (int x = 0; x < d.width(); x++) {
                            if (d.depth().palette()) {
                                d.setIndex(x, y, index);
                            } else {
                                d.setRgb(x, y, rgb);
                            }
                        }
                    }
                }
                case "pixel" -> {
                    final int px = AiToolServer.num(args, "px", 0);
                    final int py = AiToolServer.num(args, "py", 0);
                    if (d.depth().palette()) {
                        d.setIndex(px, py, AiToolServer.num(args, "index", 1));
                    } else {
                        d.setRgb(px, py, AiToolServer.num(args, "color", 0xFFFFFF));
                    }
                }
                case "lock" -> sink.lock();
                case "unlock" -> sink.unlock();
                default -> {
                    return "ERR 未知 action：" + action + "（status|configure|mode|fill|pixel|lock|unlock）";
                }
            }
            sink.markFrameChanged();
            final com.hdf.cryptand.soc.board.TrueColorScreen.Status st = d.status();
            return d.width() + "x" + d.height() + "@" + d.depth().bits() + "bpp mode=" + st.mode()
                    + " locked=" + st.locked() + " frames=" + st.frames() + " skipped=" + st.skipped()
                    + " stride=" + d.stride() + " frameBytes=" + d.frameBytes()
                    + (st.error().isEmpty() ? "" : " error=" + st.error()) + " via=port";
        } catch (RuntimeException e) {
            return "ERR " + e.getMessage();
        }
    }

    /**
     * 高级分析器（无人化）：把沙箱内部快照按 {@code SandboxInspect} 排成文本。
     *
     * <p>与面板共用 {@code AdvancedAnalyzerPanelData}（内部再经 OC 软判 + 探针）；
     * 面板显示的分区文本就是 {@code SandboxInspect.sectionText(...)}，这里给的是整篇
     * {@code SandboxInspect.text(...)} —— 两者逐字同源，断言不会出现"面板对、工具错"。</p>
     */
    private static String inspectSandbox(ServerLevel level, BlockPos pos) {
        final BlockPos target = com.hdf.cryptand.neoforge.soc.ui.AdvancedAnalyzerPanelData.inspectTarget(level, pos);
        final var snapshot = com.hdf.cryptand.neoforge.soc.ui.AdvancedAnalyzerPanelData.snapshot(level, target);
        if (target == null) {
            return "ERR 坐标 " + pos.toShortString() + " 没找到可分析的 OC 机器"
                    + "（把高级分析器贴在机箱旁 8 格内，或直接给机箱坐标）\n"
                    + com.hdf.cryptand.soc.board.SandboxInspect.text(snapshot);
        }
        final BlockEntity be = level.getBlockEntity(target);
        final boolean machine = be instanceof li.cil.oc.api.machine.MachineHost;
        return "分析目标：" + target.toShortString() + "  " + level.getBlockState(target).getBlock()
                + (machine ? "（OC 机器）" : "（**不是 OC 机器**：以下各项为占位符）") + "\n"
                + com.hdf.cryptand.soc.board.SandboxInspect.text(snapshot);
    }

    private static String screenText(ServerLevel level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return "ERR 该坐标没有方块实体";
        }
        final Object machine = reflect(be, "machine");
        if (!(machine instanceof li.cil.oc.api.machine.Machine m) || m.architecture() == null) {
            return "ERR 该方块不是运行中的 OC 机器（machine/architecture 为空）";
        }
        // ★ 屏幕内容一律**问 GPU 组件要**（它才是屏幕现在真正显示的东西）。
        //
        // 以前这里只对 OC 原生架构这么做，Cryptand 架构走 arch.screenText() —— 而那是
        // **最近一帧 blit 的文本**，不是整屏：PE 每次都只重画变化的那一行，于是"屏幕上有整屏
        // 输出"却只能读到一行提示符（2026-09-26 真机：disks 的输出已在屏上，oc_screen 只回
        // "> _"）。现在 Cryptand 显卡本身就是一个真的 gpu 组件（CryptandGpuEnvironment），
        // 两条架构读的都是它 ⇒ 断言口径统一，也不需要第二套实现。
        final String debug = m.architecture() instanceof CryptandOcArchitecture arch ? arch.screenDebug() : "";
        final String text = ocNativeScreenText(m);
        if (text.startsWith("ERR")) {
            return text + (debug.isEmpty() ? "" : "  " + debug);
        }
        return (debug.isEmpty() ? "" : debug + "\n") + text;
    }

    /**
     * **OC 原生（Lua）架构**的屏幕文本：走 {@code Machine.invoke} 问 GPU 要字符。
     *
     * <p>为什么是这条路：Lua 架构的屏幕不由我们的架构管（{@code CryptandOcArchitecture} 那条路
     * 只在 Cryptand 机上有效），但 OC 的 GPU 组件方法是**公开的组件调用**（Lua 的
     * {@code component.gpu.get(x,y)} 走的就是它）—— 逐字符取回来即可，等价于"把屏幕内容念出来"。</p>
     *
     * <p>成本：80×25 = 2000 次组件调用；只用于无人化断言，不参与渲染路径。</p>
     */
    private static String ocNativeScreenText(li.cil.oc.api.machine.Machine m) {
        String gpu = null;
        // ⚠ 这里找的是 **OC 原版显卡**（组件名 {@code "gpu"}），不是我们的 {@code rc_gpu}：
        //   本方法读的是「OC 原生屏」的字符内容；我们自己的真彩屏文本走
        //   {@code OcComponentBus.lastFrameText} 那条通道。所以改名 rc_gpu 不影响这里。
        for (final var e : m.components().entrySet()) {
            if ("gpu".equals(e.getValue())) {
                gpu = e.getKey();
                break;
            }
        }
        if (gpu == null) {
            return "ERR 机器里没有 GPU 组件（组件表：" + m.components() + "）";
        }
        try {
            final Object[] res = m.invoke(gpu, "getResolution", new Object[0]);
            // ⚠ 显卡**还没 bind 到屏**时 getResolution 回的是空值（真机踩到：直接 NPE
            //   "Cannot invoke Number.intValue() because res[0] is null"）⇒ 这不是异常，
            //   是"这台机器没有可用屏幕"，必须说人话（OC 的 gpu 语义就是"没 bind 就没分辨率"）。
            if (res == null || res.length < 2 || !(res[0] instanceof Number) || !(res[1] instanceof Number)) {
                return "ERR 这台机器的 GPU 还没绑定屏幕（getResolution 返回空）——"
                        + "先看 oc_machine_state 的组件表里有没有 screen、以及 gpu.bind 是否成功";
            }
            final int w = ((Number) res[0]).intValue();
            final int h = ((Number) res[1]).intValue();
            if (w <= 0 || h <= 0 || (long) w * h > 40_000L) {
                return "ERR 屏幕分辨率异常：" + w + "x" + h;
            }
            final StringBuilder sb = new StringBuilder();
            for (int y = 1; y <= h; y++) {
                final StringBuilder line = new StringBuilder(w);
                for (int x = 1; x <= w; x++) {
                    final Object[] c = m.invoke(gpu, "get", new Object[]{x, y});
                    line.append(c != null && c.length > 0 && c[0] != null ? String.valueOf(c[0]) : " ");
                }
                sb.append(line.toString().stripTrailing()).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "ERR 读 OC 屏幕失败（GPU " + gpu + "）：" + e;
        }
    }

    private static String injectKey(ServerLevel level, BlockPos pos, String key, String text) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return "ERR 该坐标没有方块实体";
        }
        final Object machine = reflect(be, "machine");
        if (!(machine instanceof li.cil.oc.api.machine.Machine m) || m.architecture() == null) {
            return "ERR 该方块不是运行中的 OC 机器（machine/architecture 为空）";
        }
        if (!(m.architecture() instanceof CryptandOcArchitecture arch)) {
            // Lua 架构（OC 原生）：键盘入口是 **key_down / text_input 信号**，不是消息缓存区。
            // 走屏组件自己的输入入口（移植件 component.TextBuffer.keyDown/textInput；与真彩屏窗口、
            // 方块键盘**同一个**实现）—— 于是"无人化工具能敲进 Lua 机器"与"玩家在真彩屏窗口里能敲进去"
            // 验的是同一段代码（旧 CryptandScreenEnvironment 已随任务 F-2 删除）。
            return injectKeyViaScreen(level, m, key, text);
        }
        final int n = arch.injectInput(key, text);
        // ⚠ 带上**消息缓存区**状态（2026-09-27 改口径）：注入成功 ≠ 固件收到。
        //   待发还在涨 ⇒ 固件没在消费；丢弃非 0 ⇒ 队列满，这次输入真的少了字节；
        //   seq/已消费在动 ⇒ 通道确实在工作。没有这几项时，真机上"按键没反应"
        //   根本分不清是"没进队列"还是"固件没读"（2026-09-25 的教训）。
        final String ring = "；消息缓存区 待发 " + arch.msgPendingBytes() + "/" + arch.msgCapacity() + " 字节"
                + "（" + arch.msgPendingMessages() + " 条）"
                + "，丢弃 " + arch.msgDropped() + " 条"
                + "，seq=" + arch.msgSeq()
                + "，已消费 " + arch.msgConsumedBytes() + " 字节"
                + "，发布 " + arch.msgFlushes() + " 次";
        return n > 0
                ? ("已注入 " + n + " 字节到**消息缓存区**（MSG_QUEUE，key=\"" + key + "\", text=\"" + text + "\"）" + ring)
                : "注入 0 字节：键名没有可注入的表示（纯修饰键等）或两个参数都为空" + ring;
    }

    /**
     * **Lua 架构**（OC 原生）的键盘注入：找机器组件表里的 keyboard/screen，交给我们的屏环境发信号。
     *
     * <p>为什么不在工具里自己 {@code signal}：key_down / text_input 的形态与编码**只有一份**
     * 实现 —— 真彩屏窗口与方块键盘都落在 {@code component.TextBuffer.keyDown/keyUp/textInput}
     * 上（报文路径见 truescreen/network/ServerPacketHandler.scala:65/72/80）⇒ 这里走**同一个**
     * 入口：{@code li.cil.oc.server.ComponentTracker} 按地址取组件 + {@code api.internal.TextBuffer}
     * 的 keyDown/textInput（OC 原版屏同样适用）。再写一遍就是第二份语义。</p>
     *
     * <p>任务 F-2（2026-09-27）：旧的 {@code CryptandScreenEnvironment.pressKey/typeText} 已随
     * 旧自研真彩屏删除，本方法改走上面那条移植件入口。</p>
     *
     * <p>键名分两类：控制键（回车/退格/Tab/ESC/方向键…）走 {@code keyDown}（GLFW 键码口径）；
     * 可打印字符与整段文本走 {@code textInput} —— 一条键只走一条路，绝不双写
     * （双写就会一个字进去两次）。</p>
     */
    private static String injectKeyViaScreen(ServerLevel level, li.cil.oc.api.machine.Machine m,
                                             String key, String text) {
        li.cil.oc.api.internal.TextBuffer target = null;
        String targetAddress = "";
        try {
            for (final var e : m.components().entrySet()) {
                final String type = String.valueOf(e.getValue());
                if (!"keyboard".equals(type) && !"screen".equals(type)) {
                    continue;
                }
                final String address = String.valueOf(e.getKey());
                final scala.Option<li.cil.oc.api.network.ManagedEnvironment> found =
                        li.cil.oc.server.ComponentTracker.get(level, address);
                if (!found.isEmpty() && found.get() instanceof li.cil.oc.api.internal.TextBuffer tb) {
                    target = tb;
                    targetAddress = address;
                    break;
                }
            }
        } catch (Throwable t) {
            return "ERR 读机器组件表失败：" + t;
        }
        if (target == null) {
            return "ERR 这台机器没有可注入的屏幕组件（组件表里没有 screen/keyboard）——"
                    + "Lua 架构的键盘入口是 key_down/text_input 信号，只有机箱上装着的屏能收";
        }
        int sent = 0;
        final StringBuilder what = new StringBuilder();
        // ⚠ 无人化注入没有玩家实体：屏组件的服务端输入实现只负责把信号发给邻近键盘
        //   （component/TextBuffer.scala:1196 sendToKeyboards 不读 player），所以这里传 null。
        final net.minecraft.world.entity.player.Player who = null;
        if (text != null && !text.isEmpty()) {
            for (int i = 0; i < text.length(); ) {
                final int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                target.textInput(cp, who);
                sent++;
            }
            what.append("text=\"").append(text).append("\" ");
        }
        if (key != null && !key.isEmpty()) {
            final int[] ctl = controlKeyOf(key);
            if (ctl != null) {
                target.keyDown((char) ctl[1], ctl[0], who);
                sent++;
                what.append("key=").append(key).append("(key_down code=").append(ctl[0]).append(") ");
            } else {
                // 非控制键：单字符按可打印字符走 text_input；其余（f1/insert/pageUp…）
                // 用 common 的**唯一**键名编码器给出的字符表示（UART 字节）当文本送。
                final byte[] bytes = com.hdf.cryptand.soc.oc.OcKeyboardInput.encodeKey(key);
                if (bytes.length == 0) {
                    return "ERR 键名 " + key + " 没有可注入的表示（纯修饰键等）——"
                            + "Lua 侧面只能按字符流注入";
                }
                for (final byte b : bytes) {
                    target.textInput(b & 0xFF, who);
                    sent++;
                }
                what.append("key=").append(key).append("(").append(bytes.length).append(" 字节) ");
            }
        }
        return "已按 OC 键盘语义注入到 " + targetAddress + "（key_down / text_input 信号）："
                + what + "—— 共 " + sent + " 个字符";
    }

    /**
     * 控制键 → {GLFW 键码, 字符码}（0 = 无字符）。
     *
     * <p>只列控制键：可打印字符一律走 text_input（见 {@link #injectKeyViaScreen} 的说明）。</p>
     */
    private static int[] controlKeyOf(String key) {
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "enter", "return" -> new int[]{257, 13};
            case "backspace", "back" -> new int[]{259, 8};
            case "tab" -> new int[]{258, 9};
            case "escape", "esc" -> new int[]{256, 27};
            case "space", "spacebar" -> new int[]{32, 32};
            case "delete", "del" -> new int[]{261, 0};
            case "insert", "ins" -> new int[]{260, 0};
            case "up", "arrowup" -> new int[]{265, 0};
            case "down", "arrowdown" -> new int[]{264, 0};
            case "left", "arrowleft" -> new int[]{263, 0};
            case "right", "arrowright" -> new int[]{262, 0};
            case "home" -> new int[]{268, 0};
            case "end" -> new int[]{269, 0};
            case "pageup", "pgup" -> new int[]{266, 0};
            case "pagedown", "pgdn" -> new int[]{267, 0};
            default -> null;
        };
    }

    private static String power(ServerLevel level, BlockPos pos, boolean on) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return "ERR 坐标 " + pos.toShortString() + " 没有方块实体";
        }
        final Object machine = reflect(be, "machine");
        if (machine == null) {
            return "ERR 该方块不是 OC 机器（没有 machine 访问器）";
        }
        if (on) {
            final Object ok = call(machine, "start");
            final String err = machine instanceof li.cil.oc.api.machine.Machine api
                    ? api.lastError() : null;
            return "machine.start() → " + stringify(ok)
                    + (err == null ? "；无错误" : "；lastError=" + err);
        }
        final Object r = call(machine, "stop");
        return "machine.stop() → " + stringify(r);
    }

    // ==================== 反射小工具 ====================

    private static Object reflect(Object target, String method) {
        return call(target, method);
    }

    /** 无参反射调用（找不到方法/调用失败都返回 null，由调用方如实报告） */
    private static Object call(Object target, String method, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
        } catch (Throwable t) {
            LOG.debug("[OpenComputers] 反射调用 {} 失败：{}", method, t.toString());
            return "ERR " + t.getClass().getSimpleName();
        }
        return null;
    }

    private static String stringify(Object value) {
        return value == null ? "（取不到）" : String.valueOf(value);
    }

    /** 内核标签（native / Java）——架构侧在日志里也这么报 */
    private static String machine_nativeLabel(CryptandOcArchitecture arch) {
        return arch.nativeKernel() ? "C++ native" : "Java";
    }
}
