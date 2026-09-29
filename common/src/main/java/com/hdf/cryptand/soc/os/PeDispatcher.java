package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.fs.DiskPartitionTable;
import com.hdf.cryptand.soc.fs.DiskFormatTool;
import com.hdf.cryptand.soc.oc.ComponentBus;
import com.hdf.cryptand.soc.oc.OcAbi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ===== Cryptand OS PE 的宿主侧兑现（纯 Java，零 MC，2026-09-26）=====
 *
 * <p>用户定案："系统软盘默认带一个完整镜像 + PE，进入则进入 Cryptand OS PE，提供分区、安装等命令，
 * 包括一键安装；UI OS 和非 UI 都走命令行的 Cryptand OS PE"。</p>
 *
 * <p>PE 是跑在 guest 里的命令行程序，而"分区 / 格式化 / 装系统"这些动作的<b>实现</b>在宿主
 * （文件系统当前在宿主侧，见 decree §二的过渡形态说明）。所以 PE 与宿主之间只需要一层很薄的协议：
 * <b>入方向</b>用缓冲区传字符串（{@code address\0fsName\0label} 这种 NUL 分隔形式，与 FS_RENAME 同构），
 * <b>出方向</b>把人类可读的结果文本以 byte[] 返回 —— 核心会把它写进固件自己的缓冲区，PE 直接打印。</p>
 *
 * <p>⚠ 为什么这一层放 common：与 {@code FsCallDispatcher} 同样的理由 —— 装机语义（分区大小、
 * 一键安装的顺序、回读校验）是最需要反复验证的部分，而这里用"临时目录 + 真 DiskFormatTool/Installer"
 * 就能离线端到端跑，不需要 MC、不需要固件。</p>
 */
public final class PeDispatcher {

    /**
     * 平台侧只需提供这两件事：盘地址 → 盘目录、盘地址 → 逻辑容量。
     *
     * <p>MC 侧按存档分目录（{@code cryptand/disks/<world>/<address>}）；离线测试用临时目录。</p>
     */
    public interface DiskLocator {

        /** 盘目录（可不存在 —— 装机正是要把它建出来） */
        Path dirOf(String address);

        /** 目标盘的逻辑容量（字节）。**必须来自盘物品的规格**：写错容量会让分区表尺寸失真 */
        long capacityBytesOf(String address);

        /** 当前可见的目标盘地址（PE 的 targets 命令用；顺序稳定即可） */
        default List<String> addresses() {
            return List.of();
        }
    }

    private final DiskLocator disks;

    public PeDispatcher(DiskLocator disks) {
        this.disks = disks;
    }

    /** 分发一次 PE 调用（标量参数 + 缓冲区） */
    public ComponentBus.Result invoke(int methodId, List<Object> args, byte[] buffer) {
        final String[] parts = split(buffer);
        try {
            return switch (methodId) {
                case OcAbi.PE_METHOD_TARGETS -> ComponentBus.Result.ok(text(targets()));
                case OcAbi.PE_METHOD_FORMAT -> ComponentBus.Result.ok(
                        text(List.of(format(resolve(part(parts, 0)), kbArg(args, 0),
                                part(parts, 1), part(parts, 2)))));
                case OcAbi.PE_METHOD_INSTALL -> ComponentBus.Result.ok(
                        text(List.of(install(resolve(part(parts, 0)), part(parts, 1)))));
                case OcAbi.PE_METHOD_INSTALL_ALL -> ComponentBus.Result.ok(
                        text(installAll(resolve(part(parts, 0)), part(parts, 1))));
                case OcAbi.PE_METHOD_INFO -> ComponentBus.Result.ok(
                        text(List.of(info(resolve(part(parts, 0))))));
                default -> ComponentBus.Result.error(OcAbi.ERR_UNKNOWN_METHOD,
                        "unknown PE method #" + methodId);
            };
        } catch (IllegalArgumentException e) {
            return ComponentBus.Result.error(OcAbi.ERR_BAD_ARGS, e.getMessage());
        } catch (Throwable t) {
            return ComponentBus.Result.error(OcAbi.ERR_COMPONENT_FAILED,
                    t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
    }

    // ==================== 命令实现 ====================

    /** 列出可安装的目标盘：每行 {@code <address> <capKB>KB <sys|blank>} */
    private List<String> targets() {
        final List<String> addrs = disks.addresses();
        final List<String> out = new ArrayList<>();
        if (addrs.isEmpty()) {
            out.add("no target disk (put a blank floppy or an hdd in the case)");
            return out;
        }
        out.add("targets:");
        for (int i = 0; i < addrs.size(); i++) {
            final String address = addrs.get(i);
            out.add("  [" + i + "] " + address + "  " + (disks.capacityBytesOf(address) / 1024) + "KB  "
                    + (hasSystem(address) ? "sys" : "blank"));
        }
        return out;
    }

    /** 格式化：整盘一个分区（sizeKb &lt;= 0 ⇒ 用整盘容量） */
    private String format(String address, long sizeKb, String fsName, String label) {
        requireAddress(address);
        final Path dir = disks.dirOf(address);
        final long cap = disks.capacityBytesOf(address);
        if (cap <= 0) {
            throw new IllegalArgumentException("unknown capacity for disk " + address);
        }
        final long size = sizeKb > 0 ? sizeKb * 1024L : cap;
        if (size > cap) {
            throw new IllegalArgumentException("partition " + sizeKb + "KB exceeds disk " + (cap / 1024) + "KB");
        }
        final String desc = DiskFormatTool.format(dir, cap, size, fsName, label);
        return "formatted " + address + " -> " + desc;
    }

    /** 安装：把内置程序镜像写进盘上的 {@code /boot/system.bin}（分区表缺失时先建） */
    private String install(String address, String programId) {
        requireAddress(address);
        final String id = programId == null || programId.isBlank() ? "cryptand-os" : programId.trim();
        final byte[] image = Programs.readById(id);
        if (image.length == 0) {
            throw new IllegalArgumentException("unknown program: " + id
                    + " (built-in catalog misses it; rebuild with excode/firmware/build-all.ps1)");
        }
        final Path dir = disks.dirOf(address);
        final long cap = disks.capacityBytesOf(address);
        if (!Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE))) {
            // 空盘 ⇒ 先建分区表再装（"一键安装"的那一半；显式 install 也允许）
            DiskFormatTool.format(dir, cap, cap, "", "SYS");
        }
        final Installer.Report rep = Installer.install(dir, cap, 0, "", image, id);
        return "installed " + id + " (" + image.length + " B) -> " + address + " ; " + rep;
    }

    /** 一键安装：格式化（整盘一个系统分区）→ 安装镜像 → **回读校验** */
    private List<String> installAll(String address, String programId) {
        requireAddress(address);
        final String id = programId == null || programId.isBlank() ? "cryptand-os" : programId.trim();
        final List<String> out = new ArrayList<>();
        out.add(format(address, 0, "", "SYS"));
        out.add(install(address, id));
        final boolean ok = hasSystem(address);
        out.add(ok ? "verify: boot/system.bin present" : "verify: FAILED (boot/system.bin missing)");
        if (!ok) {
            throw new IllegalArgumentException("one-shot install failed verification on " + address);
        }
        return out;
    }

    /** 查现状：容量 / 分区数 / 是否有系统 */
    private String info(String address) {
        requireAddress(address);
        final Path dir = disks.dirOf(address);
        final long cap = disks.capacityBytesOf(address);
        final boolean table = Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE));
        final DiskPartitionTable t = DiskPartitionTable.load(dir, cap);
        final long used = t.partitions().stream().mapToLong(DiskPartitionTable.Partition::sizeBytes).sum();
        return address + " cap=" + (cap / 1024) + "KB partitions=" + t.partitions().size()
                + " allocated=" + (used / 1024) + "KB system=" + (hasSystem(address) ? "yes" : "no")
                + (table ? "" : " (blank disk)");
    }

    private boolean hasSystem(String address) {
        final Path dir = disks.dirOf(address);
        return Files.isRegularFile(dir.resolve(DiskPartitionTable.META_FILE))
                && Installer.hasSystem(dir, disks.capacityBytesOf(address));
    }

    // ==================== 协议小工具 ====================

    /** 缓冲区按 NUL 拆段（与 FS_RENAME 的 {@code from\0to} 同一约定） */
    static String[] split(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return new String[0];
        }
        final String text = new String(buffer, StandardCharsets.UTF_8);
        return text.split("\u0000", -1);
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index] : "";
    }

    private static long kbArg(List<Object> args, int index) {
        if (args == null || index >= args.size()) {
            return 0;
        }
        final Object v = args.get(index);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * 把玩家/脚本给的**短码**解析成完整盘地址（用户 2026-09-26："系统可以输入前几个 id 码来匹配，
     * 类似 oc 的 lua os，如果有多个则需要输入数字选择或者退出"）。
     *
     * <p>两种写法都接受：</p>
     * <ol>
     *   <li><b>编号</b>（纯数字）：就是 {@link #invoke} 的 targets 列出的 <code>[0] [1] …</code>
     *       —— 这是"多个候选时输入数字选择"那条路；</li>
     *   <li><b>地址前缀</b>（去掉 '-' 的若干位十六进制）：唯一命中就用它；
     *       命中多个直接报错并**把候选列出来**（让用户改输更长的前缀，或改用编号）——
     *       绝不猜一个。</li>
     * </ol>
     */
    private String resolve(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("missing disk (usage: <command> <index|id-prefix> [args])");
        }
        final String raw = code.trim();
        final List<String> all = disks.addresses();
        // ① 纯数字 = targets 的编号
        if (raw.chars().allMatch(Character::isDigit)) {
            final int index;
            try {
                index = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad disk index: " + raw);
            }
            if (index < 0 || index >= all.size()) {
                throw new IllegalArgumentException("disk index " + index + " out of range (0.."
                        + Math.max(0, all.size() - 1) + "); run 'disks' first");
            }
            return all.get(index);
        }
        // ② **任意长度的子串匹配**（忽略 '-'，大小写不敏感）—— 用户 2026-09-26 订正：
        //    "id 搜索不是固定短 id，是直接搜索所有盘 id 的字符串，然后挑出符合的盘" ——
        //    所以 needle 可以是盘地址里的**任意一段**（长度不限）：既不是"固定 8 位短码"，
        //    也不是"只匹配开头"。唯一命中就直接用；多个命中列候选（编号或更长片段）。
        final String needle = raw.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        final List<String> hits = new ArrayList<>();
        for (final String address : all) {
            if (address.replace("-", "").toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                hits.add(address);
            }
        }
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("no disk matches '" + raw + "' (run 'disks' to list targets)");
        }
        if (hits.size() > 1) {
            // 多个候选：把选择权交回用户（编号或更长前缀），不替他挑一个
            final StringBuilder sb = new StringBuilder("ambiguous disk id '").append(raw)
                    .append("' matches ").append(hits.size()).append(" disks:");
            for (int i = 0; i < hits.size(); i++) {
                sb.append('\n').append("  [").append(all.indexOf(hits.get(i))).append("] ").append(hits.get(i));
            }
            sb.append('\n').append("enter the index, a longer prefix, or press Enter to give up");
            throw new IllegalArgumentException(sb.toString());
        }
        return hits.get(0);
    }

    private static void requireAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("missing disk address (usage: <command> <address> [args])");
        }
    }

    private static byte[] text(List<String> lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }
}
