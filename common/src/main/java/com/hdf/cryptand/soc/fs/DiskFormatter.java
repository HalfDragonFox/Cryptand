package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * ===== 格式化工具（用户 2026-09-18 定案：格式化是一等动作）=====
 *
 * <p>用户原话：「定义 fatfs 等的格式化，格式化比如 8MB 硬盘创建 8MB 一体化文件，
 * 然后对此文件进行格式化为 fatfs 作为空间」+「剩余空间是未格式化的」。</p>
 *
 * <p>本类只负责**把空间变成一个可用的文件系统**（写元数据），不含文件读写 —— 那属于
 * {@link FatImage} 的活。这样切分的好处：格式化是"一次性、幂等、可离线验证"的动作，
 * 而文件读写是"长期演进"的部分，两者互不绑死。</p>
 *
 * <h3>支持的文件系统</h3>
 * <ul>
 *   <li>{@link Fs#NONE} —— 裸块设备（未格式化；MCU 直接按扇区用）</li>
 *   <li>{@link Fs#FAT12} / {@link Fs#FAT16} / {@link Fs#FAT32} —— 写标准 BPB + 空 FAT + 空根目录，
 *       能被 FatFS / OC / 任何 FAT 驱动识别（<b>容量决定类型，不能乱选</b>：见 {@link #autoFat}）</li>
 *   <li>{@link Fs#CRYPTAND} —— 文件夹分区（宿主文件树，OC 侧直接读写，玩家也能用文件管理器改）</li>
 * </ul>
 *
 * <p>⚠ <b>容量规则是真的</b>：FAT32 的根目录是簇链、最小簇数有下限（约 65525 簇）⇒ 小于约 32MB
 * 的分区**不能**用 FAT32；而 FAT12 的簇号只有 12 位 ⇒ 超过约 4084 簇就不该再用 FAT12。
 * 所以"格式化"菜单应给出**推荐值**而不是让玩家随便选（{@link #autoFat}）。</p>
 */
public final class DiskFormatter {

    /** 支持的文件系统 */
    public enum Fs {
        /** 裸块设备：不写任何文件系统元数据 */
        NONE,
        FAT12,
        FAT16,
        FAT32,
        /** Cryptand 文件夹分区：宿主文件树，无元数据（"格式化" = 清空目录 + 写卷标） */
        CRYPTAND
    }

    /** 扇区大小（FAT 的行业标准；BPB 里也是这个值） */
    public static final int SECTOR = 512;

    /** 卷标文件（CRYPTAND 分区用它记卷标：一个小文本，人可读） */
    public static final String LABEL_FILE = ".cryptand-volume";

    private DiskFormatter() {
    }

    /**
     * 按分区大小推荐 FAT 类型（真实容量规则）。
     *
     * <p>簇大小取 4KB（8 个扇区）这一常见值，于是：可用簇数 ≈ 容量 / 4KB。
     * FAT12 上限 4084 簇 ⇒ 约 16MB；FAT16 上限 65524 簇 ⇒ 约 256MB。
     * 为稳妥起见（并留出真实工具链的余量），阈值取 FAT12 ≤ 8MB、FAT16 ≤ 128MB，其余 FAT32。</p>
     */
    public static Fs autoFat(long sizeBytes) {
        if (sizeBytes <= 8L * 1024 * 1024) {
            return Fs.FAT12;
        }
        if (sizeBytes <= 128L * 1024 * 1024) {
            return Fs.FAT16;
        }
        return Fs.FAT32;
    }

    /** 人类可读的类型名（日志/工具提示） */
    public static String name(Fs fs) {
        return switch (fs) {
            case NONE -> "raw";
            case FAT12 -> "FAT12";
            case FAT16 -> "FAT16";
            case FAT32 -> "FAT32";
            case CRYPTAND -> "CryptandFS";
        };
    }

    /**
     * 格式化一个**镜像分区**（块设备）。
     *
     * <p>FAT12/16 会写出：引导扇区（含完整 BPB）、两份空 FAT、空根目录区。
     * 这就是"新盘"的样子 —— FatFS 挂载后看到的是空盘而不是"未格式化"。</p>
     *
     * @param img   分区对应的块设备（{@link DiskImage}）
     * @param fs    目标文件系统（{@link Fs#NONE} = 只清空）
     * @param label 卷标（写入 BPB；FAT 卷标固定 11 字节，超长会被截断）
     * @throws FsException {@code ERR_BAD_ARGS}（容量与 FAT 类型不匹配）、{@code ERR_NO_SPACE}（写失败）
     */
    public static void formatImage(DiskImage img, Fs fs, String label) {
        if (fs == Fs.CRYPTAND) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "CRYPTAND is a folder filesystem, not an image one");
        }
        // 容量与类型必须先校验：否则会写出"自己觉得对、真驱动读不懂"的盘（见 validateCapacity）
        final long[] lay = fs == Fs.NONE ? null : layout(fs, img.capacityBytes());
        if (lay != null) {
            validateCapacity(fs, lay[1]);
        }
        // 再清空：格式化必须是"幂等且干净"的（旧块残留会让 FAT 驱动读到上一份文件系统）
        wipe(img);
        if (lay == null) {
            return;
        }
        writeFatBootSector(img, fs, label, (int) lay[0]);
        writeEmptyFats(img, fs);
        writeEmptyRootDir(img, fs);
    }

    /**
     * 格式化一个**文件夹分区**（宿主文件树）。
     *
     * <p>"格式化"在这里的含义是<b>清空</b> + 写卷标文件；没有元数据要写 —— 这正是
     * 用户要的"玩家/AI 能用文件管理器直接改盘内容"的那条路。</p>
     */
    public static void formatFolder(Path partDir, String label) {
        if (!Files.isDirectory(partDir)) {
            try {
                Files.createDirectories(partDir);
            } catch (IOException e) {
                throw new FsException(OcAbi.ERR_INVALID_PATH, "cannot create " + partDir + ": " + e.getMessage());
            }
        }
        deleteChildren(partDir);
        try {
            Files.write(partDir.resolve(LABEL_FILE),
                    ("CryptandFS\nlabel=" + (label == null ? "" : label) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FsException(OcAbi.ERR_NO_SPACE, "write label failed: " + e.getMessage());
        }
    }

    // ==================== FAT 写入 ====================

    /** 全部清空（把每个已分配的块释放掉 —— 比逐字节写 0 快得多，语义一样） */
    private static void wipe(DiskImage img) {
        for (final int b : img.allocatedBlocks()) {
            img.freeBlock(b);
        }
    }

    /**
     * 写引导扇区（含 BPB）—— FAT12/16 的位置与含义是**标准**，不是我们自己定的：
     * 字段偏移来自 FAT 规范（BPB 布局），FatFS / OC / 任何驱动都按这个读。
     */
    private static void writeFatBootSector(DiskImage img, Fs fs, String label, int fatSectors) {
        final BlockIo io = new BlockIo(img);
        final long totalSectors = io.totalSectors();
        final boolean fat32 = fs == Fs.FAT32;
        final int sectorsPerCluster = sectorsPerClusterFor(fs, totalSectors);
        // FAT32 的保留区必须容下 FSInfo(扇区1) 与引导备份(扇区6)，惯例 32
        final int reservedSectors = fat32 ? 32 : 1;
        final int fatCount = 2;
        final int rootEntries = fat32 ? 0 : 512;                     // FAT32 的根目录是簇链，项数记为 0

        final byte[] sec = io.readSector(0);
        sec[0] = (byte) 0xEB;
        sec[1] = (byte) 0x3C;
        sec[2] = (byte) 0x90;
        BlockIo.putAscii(sec, 3, "CRYPTAND", 8);
        BlockIo.put16(sec, 11, SECTOR);
        sec[13] = (byte) sectorsPerCluster;
        BlockIo.put16(sec, 14, reservedSectors);
        sec[16] = (byte) fatCount;
        BlockIo.put16(sec, 17, rootEntries);
        BlockIo.put16(sec, 19, totalSectors < 0x10000 ? (int) totalSectors : 0);
        sec[21] = (byte) 0xF8;                                       // 介质描述符：固定盘
        BlockIo.put16(sec, 22, fat32 ? 0 : fatSectors);              // FAT32 这里必须为 0
        BlockIo.put16(sec, 24, 63);                                  // 每磁道扇区（惯例值）
        BlockIo.put16(sec, 26, 255);                                 // 磁头数（惯例值）
        BlockIo.put32(sec, 28, 0);                                   // 隐藏扇区
        BlockIo.put32(sec, 32, totalSectors >= 0x10000 ? totalSectors : 0);
        final String vol = label == null ? "" : label.toUpperCase(java.util.Locale.ROOT);
        final long serial = System.currentTimeMillis() / 1000;
        if (fat32) {
            // FAT32 扩展：每 FAT 扇区数(32 位) / 根目录簇 / FSInfo / 引导备份
            BlockIo.put32(sec, 36, fatSectors);
            BlockIo.put16(sec, 40, 0);                               // 扩展标志
            BlockIo.put16(sec, 42, 0);                               // 文件系统版本 0.0
            BlockIo.put32(sec, 44, 2);                               // 根目录起始簇：固定为 2
            BlockIo.put16(sec, 48, 1);                               // FSInfo 扇区
            BlockIo.put16(sec, 50, 6);                               // 引导扇区备份
            BlockIo.put32(sec, 52, 0);                               // 保留
            sec[64] = (byte) 0x80;
            sec[66] = 0x29;
            BlockIo.put32(sec, 67, serial);
            BlockIo.putAscii(sec, 71, vol, 11);
            BlockIo.putAscii(sec, 82, "FAT32", 8);
        } else {
            sec[36] = (byte) 0x80;
            sec[38] = 0x29;
            BlockIo.put32(sec, 39, serial);
            BlockIo.putAscii(sec, 43, vol, 11);
            BlockIo.putAscii(sec, 54, fs == Fs.FAT12 ? "FAT12" : "FAT16", 8);
        }
        sec[510] = 0x55;
        sec[511] = (byte) 0xAA;
        io.writeSector(0, sec);

        if (fat32) {
            // FSInfo：免扫描的空闲簇计数（驱动拿它省一次全表扫描；数值必须自洽）
            final byte[] fsi = new byte[SECTOR];
            BlockIo.put32(fsi, 0, 0x41615252L);
            BlockIo.put32(fsi, 484, 0x61417272L);
            BlockIo.put32(fsi, 488, fatClusterCount(fs, totalSectors, sectorsPerCluster, reservedSectors, rootEntries, fatSectors) - 1);
            BlockIo.put32(fsi, 492, 3);                              // 下一个可用簇：2 被根目录占了
            BlockIo.put32(fsi, 508, 0xAA550000L);
            io.writeSector(1, fsi);
            // 引导扇区备份（真实 FAT32 总有；坏了还能救）
            io.writeSector(6, sec);
        }
    }

    /**
     * 布局：{每 FAT 扇区数, 数据区簇数}。写入与校验共用同一份计算 —— 否则
     * "校验通过但写出别的布局"这种错会永远查不出来。
     */
    private static long[] layout(Fs fs, long capacityBytes) {
        final long totalSectors = capacityBytes / SECTOR;
        if (totalSectors < 64) {
            throw new FsException(OcAbi.ERR_NO_SPACE, "partition too small: " + capacityBytes + " bytes");
        }
        final int spc = sectorsPerClusterFor(fs, totalSectors);
        final int reserved = fs == Fs.FAT32 ? 32 : 1;
        final int rootEntries = fs == Fs.FAT32 ? 0 : 512;
        final int fatSectors = fatSectorsFor(fs, totalSectors, spc, reserved, rootEntries);
        final long clusters = fatClusterCount(fs, totalSectors, spc, reserved, rootEntries, fatSectors);
        return new long[]{fatSectors, clusters};
    }

    /**
     * 容量与 FAT 类型的<b>规范硬约束</b>。簇号位数决定簇数窗口，越界写出的盘
     * 连真实驱动都挂不上（FAT12 12 位、FAT16 16 位、FAT32 28 位）。
     *
     * <p>用户定案"不静默降级"⇒ 这里报错，由调用方（格式化界面）换推荐类型重来。</p>
     */
    private static void validateCapacity(Fs fs, long clusters) {
        switch (fs) {
            case FAT12 -> {
                if (clusters > 4084) {
                    throw new FsException(OcAbi.ERR_BAD_ARGS,
                            "too many clusters for FAT12: " + clusters + " (max 4084)");
                }
            }
            case FAT16 -> {
                if (clusters < 4085 || clusters > 65524) {
                    throw new FsException(OcAbi.ERR_BAD_ARGS,
                            "cluster count out of FAT16 range: " + clusters + " (4085..65524)");
                }
            }
            case FAT32 -> {
                if (clusters < 65525) {
                    throw new FsException(OcAbi.ERR_BAD_ARGS,
                            "too few clusters for FAT32: " + clusters + " (min 65525)");
                }
            }
            default -> {
            }
        }
    }

    /**
     * 簇大小：FAT12/16/32 统一 4KB（8 扇区）。4KB 是 SD 卡/软盘镜像的通用值，
     * 既让簇数远离各类型的上下限，也不至于为大文件浪费太多尾部空间。
     */
    private static int sectorsPerClusterFor(Fs fs, long totalSectors) {
        return 8;
    }

    /**
     * 每 FAT 扇区数：<b>精确计算</b>而不是估算 —— FAT 大小决定数据区起点，
     * 估算多了会浪费空间，少了会直接写坏数据区（两者都不行，所以这里必须算准）。
     */
    private static int fatSectorsFor(Fs fs, long totalSectors, int spc, int reserved, int rootEntries) {
        final int rootDirSectors = (rootEntries * 32 + SECTOR - 1) / SECTOR;
        final int bitsPerEntry = fs == Fs.FAT12 ? 12 : (fs == Fs.FAT16 ? 16 : 32);
        for (int fatSectors = 1; fatSectors < 0xFFFF; fatSectors++) {
            final long dataSectors = totalSectors - reserved - 2L * fatSectors - rootDirSectors;
            final long clusters = dataSectors / spc;
            final long needed = (clusters + 2) * bitsPerEntry / 8;
            if (needed <= (long) fatSectors * SECTOR) {
                return fatSectors;
            }
        }
        throw new FsException(OcAbi.ERR_NO_SPACE, "partition too small/large for " + fs);
    }

    /** 数据区簇数（FAT32 的 FSInfo 需要它） */
    private static long fatClusterCount(Fs fs, long totalSectors, int spc, int reserved, int rootEntries, int fatSectors) {
        final int rootDirSectors = (rootEntries * 32 + SECTOR - 1) / SECTOR;
        return (totalSectors - reserved - 2L * fatSectors - rootDirSectors) / spc;
    }

    /**
     * 两份 FAT 都写上前两个表项（介质描述符 + EOC），其余为 0 = 空闲簇。
     *
     * <p>⚠ 这里<b>读回刚写的 BPB</b>再算偏移，而不是复用写引导扇区时的局部变量：
     * FAT 大小/保留扇区决定数据区起点，是"格式化结果是否自洽"的关键；
     * 读回能立刻发现"写进去的和算出来的不一致"（例如 FAT32 的 32 位字段写到了 16 位位置）。</p>
     */
    private static void writeEmptyFats(DiskImage img, Fs fs) {
        final BlockIo io = new BlockIo(img);
        final byte[] boot = io.readSector(0);
        final int fatSectors = fs == Fs.FAT32 ? (int) BlockIo.u32(boot, 36) : BlockIo.u16(boot, 22);
        final int reserved = BlockIo.u16(boot, 14);
        final int rootCluster = fs == Fs.FAT32 ? (int) BlockIo.u32(boot, 44) : 0;
        if (fatSectors <= 0) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "BPB has no FAT sectors");
        }
        for (int copy = 0; copy < 2; copy++) {
            final byte[] head = new byte[SECTOR];
            switch (fs) {
                case FAT12 -> {
                    // 12 位打包：3 字节装两个表项 ⇒ 簇0=0xFF8(介质) 簇1=0xFFF(EOC)
                    head[0] = (byte) 0xF8;
                    head[1] = (byte) 0xFF;
                    head[2] = (byte) 0xFF;
                }
                case FAT16 -> {
                    BlockIo.put16(head, 0, 0xFFF8);
                    BlockIo.put16(head, 2, 0xFFFF);
                }
                default -> {
                    // FAT32：高 4 位保留（读时掩码 0x0FFFFFFF），根目录簇必须标为链尾
                    BlockIo.put32(head, 0, 0x0FFFFFF8L);
                    BlockIo.put32(head, 4, 0x0FFFFFFFL);
                    BlockIo.put32(head, 8, 0x0FFFFFFFL);
                }
            }
            io.writeSector((long) (reserved + copy * fatSectors), head);
        }
        if (fs == Fs.FAT32 && rootCluster != 2) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "FAT32 root cluster must be 2, got " + rootCluster);
        }
    }

    /**
     * 空根目录。FAT12/16 是固定长度的连续区域；FAT32 是普通簇链（起始簇写在 BPB 偏移 44）。
     * 目录项首字节 0 = "本项及之后都为空"，这是驱动判断目录结束的唯一依据。
     */
    private static void writeEmptyRootDir(DiskImage img, Fs fs) {
        final BlockIo io = new BlockIo(img);
        final byte[] boot = io.readSector(0);
        final int reserved = BlockIo.u16(boot, 14);
        final int fatCount = BlockIo.u8(boot, 16);
        if (fs == Fs.FAT32) {
            final int spc = BlockIo.u8(boot, 13);
            final int fatSectors = (int) BlockIo.u32(boot, 36);
            final int rootCluster = (int) BlockIo.u32(boot, 44);
            final long dataStart = reserved + (long) fatCount * fatSectors;
            final long first = dataStart + (long) (rootCluster - 2) * spc;
            for (int i = 0; i < spc; i++) {
                io.writeSector(first + i, new byte[SECTOR]);
            }
            return;
        }
        final int fatSectors = BlockIo.u16(boot, 22);
        final int rootEntries = BlockIo.u16(boot, 17);
        final int rootSectors = Math.max(1, (rootEntries * 32 + SECTOR - 1) / SECTOR);
        final long rootStart = reserved + (long) fatCount * fatSectors;
        for (int i = 0; i < rootSectors; i++) {
            io.writeSector(rootStart + i, new byte[SECTOR]);
        }
    }

    private static void deleteChildren(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                        try {
                            Files.deleteIfExists(x);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
