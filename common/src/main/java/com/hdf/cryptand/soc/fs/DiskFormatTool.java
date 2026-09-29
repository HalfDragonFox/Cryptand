package com.hdf.cryptand.soc.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * ===== 格式化工具（纯 Java，零 MC）=====
 *
 * <p>用户要求："格式化可以写成单独库，然后沙盒跑一遍" —— 所以格式化**不依赖 Minecraft**：
 * 它只认"一个盘目录 + 容量 + 分区大小 + 类型名"，MC 侧（{@code OcDiskFormat}）只负责把
 * 存档键与盘地址翻成目录路径。这样整条链路（建分区 → 格式化 → 挂载 → 读写）都能离线自测，
 * 不用为了验证等两分钟客户端起来。</p>
 *
 * <p>"格式化"在这里的完整含义是两件事，顺序固定：</p>
 * <ol>
 *   <li><b>建分区</b>：在 {@code disk.json} 里登记一个 IMAGE（块设备）或 FOLDER（文件树）分区，
 *       占用 {@code sizeBytes}（未分配 = 容量 − Σ分区）</li>
 *   <li><b>写文件系统元数据</b>：IMAGE ⇒ {@link DiskFormatter#formatImage}（BPB + FAT + 根目录）；
 *       FOLDER ⇒ {@link DiskFormatter#formatFolder}（清空 + 卷标）</li>
 * </ol>
 */
public final class DiskFormatTool {

    private DiskFormatTool() {
    }

    /**
     * 格式化盘上的一个新分区。
     *
     * @param diskDir       盘目录（不存在则创建）
     * @param capacityBytes 盘容量；<b>&lt;= 0 表示沿用已有 {@code disk.json}</b> 的容量
     *                      （盘的真实容量是挂载时写进去的，那才是权威）
     * @param sizeBytes     本次分区大小
     * @param fsName        文件系统名（空 ⇒ 按容量自动推荐）；未知名字 ⇒ 抛异常
     * @param label         卷标
     */
    public static String format(Path diskDir, long capacityBytes, long sizeBytes, String fsName, String label) {
        if (sizeBytes < 64L * 512) {
            throw new IllegalArgumentException("partition too small: " + sizeBytes + " bytes");
        }
        if (capacityBytes <= 0 && !Files.isRegularFile(diskDir.resolve(DiskPartitionTable.META_FILE))) {
            throw new IllegalArgumentException(
                    "unknown disk capacity: pass capacityKb (get it from oc_disk_list)");
        }
        final DiskPartitionTable table = DiskPartitionTable.load(diskDir, capacityBytes);
        final DiskFormatter.Fs fs = fsName == null || fsName.isBlank()
                ? DiskFormatter.autoFat(sizeBytes)
                : parseFs(fsName);

        if (fs == DiskFormatter.Fs.CRYPTAND) {
            final DiskPartitionTable.Partition part =
                    table.create(DiskPartitionTable.Kind.FOLDER, sizeBytes, DiskFormatter.name(fs), label);
            DiskFormatter.formatFolder(table.partitionDir(part.index()), label);
            return describe(part, fs, sizeBytes, label);
        }
        final DiskPartitionTable.Partition part =
                table.create(DiskPartitionTable.Kind.IMAGE, sizeBytes, DiskFormatter.name(fs), label);
        // ⚠ 这里是**显式格式化** ⇒ 用 create（写 disk.json 定型）；挂载路径一律用 open（读不写）
        try (DiskImage img = DiskImage.create(table.partitionDir(part.index()), sizeBytes)) {
            // 容量与类型不匹配时抛 ERR_BAD_ARGS 并带上簇数 —— 让调用方看到真实原因，而不是静默换类型
            DiskFormatter.formatImage(img, fs, label);
        }
        return describe(part, fs, sizeBytes, label);
    }

    private static String describe(DiskPartitionTable.Partition part, DiskFormatter.Fs fs,
                                   long sizeBytes, String label) {
        return "partition #" + part.index() + " " + DiskFormatter.name(fs)
                + " size=" + (sizeBytes / 1024) + "KB label=" + (label == null ? "" : label);
    }

    /** 类型名 → 枚举（对外也开放：命令与 AI 工具共用同一套名字，避免两处各写一遍 switch） */
    public static DiskFormatter.Fs parseFs(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "RAW", "NONE" -> DiskFormatter.Fs.NONE;
            case "FAT12" -> DiskFormatter.Fs.FAT12;
            case "FAT16" -> DiskFormatter.Fs.FAT16;
            case "FAT32" -> DiskFormatter.Fs.FAT32;
            case "CRYPTAND", "FOLDER" -> DiskFormatter.Fs.CRYPTAND;
            default -> throw new IllegalArgumentException("unknown filesystem: " + name);
        };
    }
}
