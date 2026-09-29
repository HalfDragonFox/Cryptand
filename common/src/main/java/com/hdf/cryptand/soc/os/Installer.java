package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.DiskFileSystems;
import com.hdf.cryptand.soc.fs.DiskFormatTool;
import com.hdf.cryptand.soc.fs.DiskFormatter;
import com.hdf.cryptand.soc.fs.DiskPartitionTable;
import com.hdf.cryptand.soc.fs.FsException;
import com.hdf.cryptand.soc.oc.OcAbi;

import java.nio.file.Path;

/**
 * ===== 安装器（common，纯 Java 零 MC）：把系统装到盘上 =====
 *
 * <p>用户定案的引导链路（PE 安装）：软盘带 PE + 安装器 —— 安装器把一块"目标盘"
 * 变成"能引导的系统盘"：<b>建分区 → 格式化 → 装程序 → 校验</b>。四步全在 common，
 * 所以整条安装链路可以离线跑完整遍，不用起客户端。</p>
 *
 * <p>⚠ <b>顺序是这个类的全部要点</b>：先算"装不装得下"，再动盘。
 * 反过来就会"格式化完才发现装不下" —— 而那时旧系统已经没了。
 * 安装器最不能干的就是这种事（闸门里专门有一条断言盯着它）。</p>
 */
public final class Installer {

    /**
     * 安装结果（回显给命令/工具用）。
     *
     * @param filesystem     实际用的文件系统名
     * @param partitionBytes 分区大小
     * @param clearedEntries 被清掉的旧条目数（0 = 本来是空盘）
     * @param programBytes   装入的程序大小
     */
    public record Report(String filesystem, long partitionBytes, int clearedEntries, int programBytes,
                         int loaderBytes) {
    }

    /**
     * FAT 目录条目/元数据的余量：安装写的是两个文件（{@code /boot} 目录 + 两个条目），
     * 容量判定要留出这点开销，否则"刚好装满"的盘会在写 loader 时翻车。
     */
    private static final long DIRECTORY_SLACK = 4096L;



    private Installer() {
    }

    /**
     * 把程序装成一块可引导的盘。
     *
     * @param diskDir        盘目录
     * @param capacityBytes  盘容量（&lt;= 0 ⇒ 沿用已有 disk.json）
     * @param partitionBytes 目标分区大小
     * @param fsName         文件系统名（空 ⇒ 按分区大小推荐）
     * @param program        程序字节（{@link Programs#readById} 取）
     * @param label          卷标
     * @throws FsException {@code ERR_NO_SPACE} 装不下（此时<b>盘没有被改动</b>）
     */
    public static Report install(Path diskDir, long capacityBytes, long partitionBytes, String fsName,
                                 byte[] program, String label) {
        if (program == null || program.length == 0) {
            throw new IllegalArgumentException("empty program");
        }
        // 引导分层（用户 2026-09-24 定案）：可引导的系统盘装的是**两个**文件 ——
        // /boot/loader.bin（BIOS 交控制权的那一层）+ /boot/system.bin（系统本体）。
        // 所以这里一次装齐：少写 loader 的盘在 BIOS 眼里就是"旧盘"，只能由 BIOS 一步到位引导，
        // 分层引导那条路根本不会被执行到。
        final byte[] loader = Programs.readById(Programs.BOOTLOADER_ID);
        // 盘上已有的 FAT 分区就是安装目标：**重装/换系统 = 复用同一个分区**，
        // 否则一块盘会被越装分区越多（实测踩过：第二次装直接 ERR_NO_SPACE，因为第一次已占满整盘）。
        final DiskPartitionTable table = DiskPartitionTable.load(diskDir, capacityBytes);
        // 复用哪一块分区：**认角色**（用户 2026-09-24："完整 os 可以支持通过分区来实现功能"）。
        // ⚠ 不能"取第一个 FAT 分区"：多分区盘上第一个分区很可能是 /home，
        //   那样装系统会把用户数据分区格掉（而且系统装到了 /home 上）。
        //   明确的系统角色（label SYS/BOOT）优先；无 label 的老盘分区按位置兜底（与 DiskVolumes 一致）。
        DiskPartitionTable.Partition existing = null;
        for (final DiskPartitionTable.Partition p : table.partitions()) {
            if (p.kind() != DiskPartitionTable.Kind.IMAGE
                    || !p.format().toUpperCase(java.util.Locale.ROOT).startsWith("FAT")) {
                continue;
            }
            final com.hdf.cryptand.soc.fs.DiskVolumes.Role role =
                    com.hdf.cryptand.soc.fs.DiskVolumes.roleOf(p.label());
            if (role == com.hdf.cryptand.soc.fs.DiskVolumes.Role.SYSTEM) {
                existing = p;
                break;
            }
            if (role == null && existing == null) {
                existing = p;
            }
        }
        // partitionBytes <= 0 ⇒ 用整盘容量（"把系统装到这块盘上"最常见的意思）
        final long requested = partitionBytes > 0 ? partitionBytes : table.capacityBytes();
        final long targetBytes = existing != null ? existing.sizeBytes() : requested;
        // 复用分区时沿用**它自己的文件系统**（不在这里换类型：换类型是 format 的活，不是 install 的）
        final String targetFsName = existing != null ? existing.format() : fsName;
        final DiskFormatter.Fs fs = targetFsName == null || targetFsName.isBlank()
                ? DiskFormatter.autoFat(targetBytes)
                : DiskFormatTool.parseFs(targetFsName);

        // ① 先判断装不装得下 —— 不够就直接抛，**一个字节都不碰盘**
        final long usable = usableBytes(targetBytes, fs);
        // 容量判定必须按"两个文件"来算：只算 system 的话，会在第二步写 loader 时才发现放不下，
        // 而那时盘已经格过了 —— 这正是本类开头列的头号忌讳。
        final long needed = (long) program.length + loader.length + DIRECTORY_SLACK;
        if (needed > usable) {
            throw new FsException(OcAbi.ERR_NO_SPACE, "program " + program.length
                    + " bytes + bootloader " + loader.length + " bytes does not fit a "
                    + targetBytes + "-byte " + DiskFormatter.name(fs)
                    + " partition (usable " + usable + ")");
        }

        // ② 系统分区的卷标必须**表明系统角色**（角色是认 label 的，见 DiskVolumes.roleOf）：
        //    否则会出现"install 说装好了，DiskFileSystems.open 却找不到系统卷" ——
        //    调用方传 "SMALL"/"DATA" 这类标签时就会踩到（闸门当场抓到：装完盘打不开）。
        final String systemLabel = com.hdf.cryptand.soc.fs.DiskVolumes.roleOf(label)
                == com.hdf.cryptand.soc.fs.DiskVolumes.Role.SYSTEM
                ? label
                : (label == null || label.isBlank() ? "SYS" : "SYS " + label.trim());

        // ③ 首次安装 ⇒ 建分区 + 格式化；重装 ⇒ 只把已有分区重新格式化（安装语义就是"覆盖"）
        if (existing == null) {
            DiskFormatTool.format(diskDir, capacityBytes, targetBytes, fsName, systemLabel);
        } else {
            try (com.hdf.cryptand.soc.fs.DiskImage img =
                         com.hdf.cryptand.soc.fs.DiskImage.open(table.partitionDir(existing.index()),
                                 existing.sizeBytes())) {
                DiskFormatter.formatImage(img, fs, systemLabel);
            }
        }

        // ④ 挂载 → 清空 → 装程序 → 回读校验（installCleared 自带校验）
        try (CryptandFileSystem mounted = DiskFileSystems.open(diskDir, capacityBytes, "installer", false)) {
            final int cleared = Programs.installCleared(mounted, program);
            // 引导记录由 Programs.install 一并写入（"装系统"这个动作就含它）—— 这里不再重复写。
            return new Report(DiskFormatter.name(fs), partitionBytes, cleared, program.length, loader.length);
        }
    }

    /**
     * 目标盘是不是已经装好了（有**系统角色**的 IMAGE+FAT 分区）—— 命令用来显示"还要不要装"。
     *
     * <p>⚠ 判据是角色而不是"有没有 FAT 分区"：多分区盘上 /home、/usr 也都是 FAT 分区，
     * 按"有 FAT 就算装好"会得到"明明没装系统却显示已装"。</p>
     */
    public static boolean hasSystem(Path diskDir, long capacityBytes) {
        try {
            final DiskPartitionTable table = DiskPartitionTable.load(diskDir, capacityBytes);
            for (final DiskPartitionTable.Partition p : table.partitions()) {
                if (p.kind() != DiskPartitionTable.Kind.IMAGE
                        || !p.format().toUpperCase(java.util.Locale.ROOT).startsWith("FAT")) {
                    continue;
                }
                final com.hdf.cryptand.soc.fs.DiskVolumes.Role role =
                        com.hdf.cryptand.soc.fs.DiskVolumes.roleOf(p.label());
                if (role == com.hdf.cryptand.soc.fs.DiskVolumes.Role.SYSTEM || role == null) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    /**
     * 分区里能放多少字节的程序。
     *
     * <p>FAT 的元数据（保留扇区 + 两份 FAT + 固定根目录区）随容量变化，这里按
     * "至少扣 64KB，或容量的 2%"保守估算 —— 宁可判小（报错让用户换大分区），
     * 也不要判大（格式化完才发现装不下）。</p>
     */
    public static long usableBytes(long partitionBytes, DiskFormatter.Fs fs) {
        if (fs == DiskFormatter.Fs.CRYPTAND) {
            return partitionBytes;
        }
        return Math.max(0, partitionBytes - Math.max(64L * 1024, partitionBytes / 50));
    }
}
