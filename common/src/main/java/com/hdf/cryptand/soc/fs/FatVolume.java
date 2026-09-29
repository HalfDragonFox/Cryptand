package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ===== FAT12/16/32 读写驱动（2026-09-18，B4）=====
 *
 * <p>为什么自己写而不是塞个第三方库：MCU 侧（FreeRTOS + FatFS）读写的就是**同一批字节**，
 * 所以宿主侧必须能对同一镜像做"逐字节等价"的操作 —— 一个纯 Java、零依赖、只吃
 * {@link DiskImage} 的实现，是唯一能在离线闸门里跑、又能被 MC 侧复用的形态。</p>
 *
 * <h3>能力与边界（明确写下来，免得将来误以为"FAT 全支持了"）</h3>
 * <ul>
 *   <li>✅ 读：目录遍历、8.3 与 LFN 名称、文件内容（跨簇链）</li>
 *   <li>✅ 写：创建/覆盖文件、mkdir、删除（空目录或文件）、自动分配/回收簇、目录按需扩簇</li>
 *   <li>✅ 三类型：FAT12/16/32 的表项打包（12 位跨字节、FAT32 高 4 位保留）</li>
 *   <li>❌ 不做：碎片整理、坏簇标记（0xFFF7）、FSInfo 精确维护（只在分配时保持单调）</li>
 * </ul>
 *
 * <p>⚠ <b>写穿策略</b>：所有扇区写立即落盘（并更新本地扇区缓存）。理由：这个卷会被
 * 沙箱里的 guest 与宿主同时访问，任何"延迟回写"都可能让 guest 读到旧数据 —— 正确性优先于吞吐。</p>
 */
public final class FatVolume implements AutoCloseable {

    /** 卷类型（由数据区簇数决定，跟 BPB 里的字符串无关 —— 那个字段历史上不可靠） */
    public enum FatType { FAT12, FAT16, FAT32 }

    /** 对外暴露的目录项视图 */
    public record Entry(String name, boolean directory, long size, int firstCluster) {
    }

    private static final int ATTR_READ_ONLY = 0x01;
    private static final int ATTR_HIDDEN = 0x02;
    private static final int ATTR_SYSTEM = 0x04;
    private static final int ATTR_VOLUME = 0x08;
    private static final int ATTR_DIR = 0x10;
    private static final int ATTR_ARCHIVE = 0x20;
    private static final int ATTR_LFN = 0x0F;

    private static final int FREE_CLUSTER = 0x00000000;
    private static final int CACHE_LIMIT = 512;

    private final DiskImage img;
    private final BlockIo io;
    private final FatType type;
    private final int sectorsPerCluster;
    private final int reservedSectors;
    private final int fatCount;
    private final int rootEntries;
    private final long totalSectors;
    private final int fatSectors;
    private final long rootDirSectors;
    private final long rootDirStart;
    private final long dataStart;
    private final long clusterCount;
    private final int rootCluster;

    private final Map<Long, byte[]> cache = new HashMap<>();

    private FatVolume(DiskImage img, BlockIo io, FatType type, int spc, int reserved, int fatCount,
                      int rootEntries, long totalSectors, int fatSectors, long rootDirSectors,
                      long rootDirStart, long dataStart, long clusterCount, int rootCluster) {
        this.img = img;
        this.io = io;
        this.type = type;
        this.sectorsPerCluster = spc;
        this.reservedSectors = reserved;
        this.fatCount = fatCount;
        this.rootEntries = rootEntries;
        this.totalSectors = totalSectors;
        this.fatSectors = fatSectors;
        this.rootDirSectors = rootDirSectors;
        this.rootDirStart = rootDirStart;
        this.dataStart = dataStart;
        this.clusterCount = clusterCount;
        this.rootCluster = rootCluster;
    }

    /**
     * 挂载（解析 BPB）。不做"猜类型"：BPB 里各字段必须自洽，否则直接报错 ——
     * 猜错类型会让写操作按错误的表项位宽去改 FAT，那是最难查的一类数据损坏。
     */
    public static FatVolume mount(DiskImage img) {
        final BlockIo io = new BlockIo(img);
        final byte[] boot = io.readSector(0);
        if (BlockIo.u8(boot, 510) != 0x55 || BlockIo.u8(boot, 511) != 0xAA) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "not a FAT volume (missing 0x55AA signature)");
        }
        final int bps = BlockIo.u16(boot, 11);
        if (bps != BlockIo.SECTOR) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "unsupported sector size: " + bps);
        }
        final int spc = BlockIo.u8(boot, 13);
        final int reserved = BlockIo.u16(boot, 14);
        final int fatCount = BlockIo.u8(boot, 16);
        final int rootEntries = BlockIo.u16(boot, 17);
        final long total = BlockIo.u16(boot, 19) != 0 ? BlockIo.u16(boot, 19) : BlockIo.u32(boot, 32);
        final int fatSectors = BlockIo.u16(boot, 22) != 0 ? BlockIo.u16(boot, 22) : (int) BlockIo.u32(boot, 36);
        final int rootCluster = (int) BlockIo.u32(boot, 44);
        if (spc == 0 || fatCount == 0 || fatSectors == 0 || total == 0) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "malformed BPB (spc/fatCount/fatSectors/total = 0)");
        }
        final long rootDirSectors = (rootEntries * 32L + BlockIo.SECTOR - 1) / BlockIo.SECTOR;
        final long rootDirStart = reserved + (long) fatCount * fatSectors;
        final long dataStart = rootDirStart + rootDirSectors;
        if (dataStart >= total) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "BPB data area starts past end of volume");
        }
        final long clusters = (total - dataStart) / spc;
        final FatType type = clusters < 4085 ? FatType.FAT12 : (clusters < 65525 ? FatType.FAT16 : FatType.FAT32);
        return new FatVolume(img, io, type, spc, reserved, fatCount, rootEntries, total, fatSectors,
                rootDirSectors, rootDirStart, dataStart, clusters, rootCluster);
    }

    public FatType type() {
        return type;
    }

    public int sectorsPerCluster() {
        return sectorsPerCluster;
    }

    public long capacityBytes() {
        return totalSectors * BlockIo.SECTOR;
    }

    public long clusterCount() {
        return clusterCount;
    }

    /** 空闲簇数（线性扫 FAT；用于 SpaceTotal/SpaceUsed 与测试断言） */
    public long freeClusters() {
        long n = 0;
        for (int c = 2; c < clusterCount + 2; c++) {
            if (fatGet(c) == FREE_CLUSTER) {
                n++;
            }
        }
        return n;
    }

    public long freeBytes() {
        return freeClusters() * (long) sectorsPerCluster * BlockIo.SECTOR;
    }

    public long usedBytes() {
        return (clusterCount - freeClusters()) * (long) sectorsPerCluster * BlockIo.SECTOR;
    }

    // ==================== 公开文件 API ====================

    public boolean exists(String path) {
        try {
            return walk(path) != null || isRootPath(path);
        } catch (FsException e) {
            return false;
        }
    }

    public boolean isDirectory(String path) {
        final Raw r = walk(path);
        return isRootPath(path) || (r != null && r.directory);
    }

    public long size(String path) {
        final Raw r = walk(path);
        return r == null || r.directory ? 0 : r.size;
    }

    /** 最后写入时间（epoch 毫秒；目录项的 DOS 时间戳，0 = 没记录） */
    public long lastModified(String path) {
        final Raw r = walk(path);
        return r == null ? 0 : dosToEpochMillis(r.writeDate, r.writeTime);
    }

    private static long dosToEpochMillis(int date, int time) {
        if (date == 0) {
            return 0;
        }
        final int year = 1980 + ((date >>> 9) & 0x7F);
        final int month = (date >>> 5) & 0x0F;
        final int day = date & 0x1F;
        final int hour = (time >>> 11) & 0x1F;
        final int minute = (time >>> 5) & 0x3F;
        final int second = (time & 0x1F) * 2;
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59) {
            return 0;
        }
        return java.time.LocalDateTime.of(year, month, day, hour, minute, Math.min(second, 59))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 列出目录（不递归）。路径不存在 ⇒ ERR_NOT_FOUND */
    public List<Entry> list(String path) {
        final Raw dir = walk(path);
        if (dir != null && !dir.directory) {
            throw FsException.invalidPath(path + " is not a directory");
        }
        if (dir == null && !isRootPath(path)) {
            throw FsException.notFound(path);
        }
        final List<Entry> out = new ArrayList<>();
        for (Raw r : scan(dir).entries) {
            out.add(new Entry(r.name, r.directory, r.size, r.firstCluster));
        }
        return out;
    }

    public byte[] read(String path) {
        final Raw r = walk(path);
        if (r == null) {
            throw FsException.notFound(path);
        }
        if (r.directory) {
            throw FsException.invalidPath(path + " is a directory");
        }
        return readChain(r.firstCluster, r.size);
    }

    /** 创建或覆盖文件（父目录必须已存在） */
    public void write(String path, byte[] data) {
        final String[] segs = segments(path);
        if (segs.length == 0) {
            throw FsException.invalidPath("cannot write to root");
        }
        final Raw parent = walk(join(segs, segs.length - 1));
        if (parent != null && !parent.directory) {
            throw FsException.invalidPath("parent is not a directory: " + path);
        }
        if (parent == null && segs.length > 1) {
            throw FsException.notFound("parent directory not found: " + path);
        }
        putFile(parent, segs[segs.length - 1], data);
    }

    /** 建目录（父目录必须已存在；已存在 ⇒ ERR_BAD_ARGS 而不是静默成功，调用方好判断） */
    public void mkdir(String path) {
        final String[] segs = segments(path);
        if (segs.length == 0) {
            throw FsException.invalidPath("cannot mkdir root");
        }
        final Raw parent = walk(join(segs, segs.length - 1));
        if (parent != null && !parent.directory) {
            throw FsException.invalidPath("parent is not a directory: " + path);
        }
        if (parent == null && segs.length > 1) {
            throw FsException.notFound("parent directory not found: " + path);
        }
        final String leaf = segs[segs.length - 1];
        final DirScan scan = scan(parent);
        if (findIn(scan.entries, leaf) != null) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "already exists: " + path);
        }
        final int cluster = allocCluster();
        zeroCluster(cluster);
        final Raw created = new Raw("", "", true, cluster, 0, 0, 0);
        writeEntry(scan, parent, created, leaf, true);
    }

    /** 删除文件或空目录（非空目录 ⇒ ERR_BAD_ARGS，不递归删除 —— 误删整棵树代价太大） */
    public void remove(String path) {
        final Raw r = walk(path);
        if (r == null) {
            throw FsException.notFound(path);
        }
        if (r.directory && !scan(r).entries.isEmpty()) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "directory not empty: " + path);
        }
        // 先断链再标记槽：中途断电最多丢一个已标删的项，绝不会出现"项还在但簇已释放"
        if (r.firstCluster >= 2) {
            freeChain(r.firstCluster);
        }
        markDeleted(r);
        r.directory = true;                       // 让下面的 findIn 用不到；此处仅为语义清晰
    }

    @Override
    public void close() {
        cache.clear();                            // 写穿 ⇒ 无需回写；这里只丢缓存
    }

    // ==================== 路径 ====================

    private static String[] segments(String path) {
        if (path == null) {
            return new String[0];
        }
        final String p = path.replace('\\', '/');
        final List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (!seg.isEmpty() && !seg.equals(".")) {
                out.add(seg);
            }
        }
        return out.toArray(new String[0]);
    }

    private static String join(String[] segs, int count) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append('/').append(segs[i]);
        }
        return sb.toString();
    }

    private static boolean isRootPath(String path) {
        return segments(path).length == 0;
    }

    /** 逐段下降找到目标项；根目录返回 null（"存在"由 {@link #isRootPath} 判断） */
    private Raw walk(String path) {
        Raw cur = null;
        for (String seg : segments(path)) {
            final List<Raw> entries = scan(cur).entries;
            final Raw next = findIn(entries, seg);
            if (next == null) {
                return null;
            }
            cur = next;
        }
        return cur;
    }

    private static Raw findIn(List<Raw> entries, String name) {
        for (Raw r : entries) {
            if (r.name.equalsIgnoreCase(name) || r.shortName.equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }

    // ==================== 目录扫描 ====================

    /** 一次目录扫描的结果：有效项 + 槽位图（用于分配新项） */
    private static final class DirScan {
        final List<Raw> entries = new ArrayList<>();
        final List<Long> slots = new ArrayList<>();     // 槽的字节偏移
        final List<Boolean> used = new ArrayList<>();
        final List<Long> sectors = new ArrayList<>();
        boolean growable;                                // 能否扩簇（FAT12/16 的根目录不能）

        /** 找一段连续空槽（LFN 需要 N 个名字槽 + 1 个短名槽） */
        int findFreeRun(int needed) {
            int run = 0;
            for (int i = 0; i < used.size(); i++) {
                if (Boolean.TRUE.equals(used.get(i))) {
                    run = 0;
                } else {
                    run++;
                    if (run >= needed) {
                        return i - needed + 1;
                    }
                }
            }
            return -1;
        }
    }

    private DirScan scan(Raw dir) {
        final DirScan out = new DirScan();
        if (dir == null && type != FatType.FAT32) {
            for (long i = 0; i < rootDirSectors; i++) {
                out.sectors.add(rootDirStart + i);
            }
            out.growable = false;                        // FAT12/16 根目录是定长区
        } else {
            final int first = dir == null ? rootCluster : dir.firstCluster;
            for (int c : chainOf(first)) {
                for (int i = 0; i < sectorsPerCluster; i++) {
                    out.sectors.add((long) (c - 2) * sectorsPerCluster + dataStart + i);
                }
            }
            out.growable = true;
        }
        final List<String> lfn = new ArrayList<>();
        boolean ended = false;
        for (long s : out.sectors) {
            final byte[] sec = sector(s);
            for (int off = 0; off < BlockIo.SECTOR; off += 32) {
                out.slots.add(s * BlockIo.SECTOR + off);
                final int firstByte = BlockIo.u8(sec, off);
                if (ended || firstByte == 0x00 || firstByte == 0xE5) {
                    out.used.add(Boolean.FALSE);
                    if (firstByte == 0x00) {
                        ended = true;                    // 0x00 = 本项及之后都空（规范）
                    }
                    lfn.clear();
                    continue;
                }
                final int attr = BlockIo.u8(sec, off + 11);
                if (attr == ATTR_LFN) {
                    lfn.add(0, lfnText(sec, off));       // 盘上是倒序，前置插入还原
                    out.used.add(Boolean.TRUE);
                    continue;
                }
                out.used.add(Boolean.TRUE);
                if ((attr & ATTR_VOLUME) != 0) {
                    lfn.clear();
                    continue;                            // 卷标项不是文件
                }
                final String shortName = shortNameOf(sec, off);
                final String name = lfn.isEmpty() ? shortName : String.join("", lfn);
                final int lfnSlots = lfn.size();
                lfn.clear();
                final int cluster = firstClusterOf(sec, off);
                final Raw raw = new Raw(name, shortName, (attr & ATTR_DIR) != 0, cluster,
                        BlockIo.u32(sec, off + 28), s, off, lfnSlots);
                raw.writeTime = BlockIo.u16(sec, off + 22);
                raw.writeDate = BlockIo.u16(sec, off + 24);
                out.entries.add(raw);
            }
        }
        return out;
    }

    /** 无有效项的目录（例如"父目录必须存在"的检查里不存在时） */
    private List<Raw> entriesOf(Raw dir) {
        return scan(dir).entries;
    }

    // ==================== 读写文件内容 ====================

    private byte[] readChain(int firstCluster, long size) {
        final byte[] out = new byte[(int) size];
        int done = 0;
        for (int c : chainOf(firstCluster)) {
            if (done >= size) {
                break;
            }
            final int n = (int) Math.min(sectorsPerCluster * (long) BlockIo.SECTOR, size - done);
            final byte[] chunk = io.read(clusterToByte(c), n);
            System.arraycopy(chunk, 0, out, done, n);
            done += n;
        }
        if (done < size) {
            throw new FsException(OcAbi.ERR_BAD_ARGS,
                    "cluster chain shorter than file size (" + done + " < " + size + ")");
        }
        return out;
    }

    /** 写入内容：先释放旧链（避免留下孤儿簇），再按需分配新链 */
    private void writeChain(Raw target, byte[] data) {
        if (target.firstCluster >= 2) {
            freeChain(target.firstCluster);
        }
        target.size = data.length;
        target.firstCluster = 0;
        if (data.length == 0) {
            return;
        }
        final int needClusters = (int) ((data.length + sectorsPerCluster * (long) BlockIo.SECTOR - 1)
                / (sectorsPerCluster * (long) BlockIo.SECTOR));
        int first = 0;
        int prev = 0;
        int written = 0;
        for (int i = 0; i < needClusters; i++) {
            final int c = allocCluster();
            if (i == 0) {
                first = c;
            } else {
                fatSet(prev, c);
            }
            prev = c;
            final int n = (int) Math.min(sectorsPerCluster * (long) BlockIo.SECTOR, data.length - written);
            final byte[] chunk = new byte[sectorsPerCluster * BlockIo.SECTOR];
            System.arraycopy(data, written, chunk, 0, n);
            io.write(clusterToByte(c), chunk, chunk.length);   // 整簇写：尾部补 0，不给旧数据留后门
            written += n;
        }
        fatSet(prev, eoc());
        target.firstCluster = first;
    }

    private long clusterToByte(int cluster) {
        return ((long) (cluster - 2) * sectorsPerCluster + dataStart) * BlockIo.SECTOR;
    }

    private List<Integer> chainOf(int first) {
        final List<Integer> out = new ArrayList<>();
        int c = first;
        int guard = 0;
        while (c >= 2 && c < clusterCount + 2 && !isEoc(c)) {
            out.add(c);
            final int next = fatGet(c);
            if (next == c) {
                throw new FsException(OcAbi.ERR_BAD_ARGS, "FAT self-loop at cluster " + c);
            }
            c = next;
            if (++guard > clusterCount + 2) {
                throw new FsException(OcAbi.ERR_BAD_ARGS, "FAT chain longer than volume");
            }
        }
        return out;
    }

    /** 追加一个簇到链尾（目录扩容用） */
    private void appendCluster(Raw dir) {
        if (dir == null && type != FatType.FAT32) {
            throw FsException.noSpace(rootEntries, 0);       // FAT12/16 根目录无法扩容
        }
        final int first = dir == null ? rootCluster : dir.firstCluster;
        final int c = allocCluster();
        zeroCluster(c);
        if (first < 2) {
            // 目录还没有任何簇（空的新目录）：直接把新簇作为它自己
            if (dir != null) {
                dir.firstCluster = c;
                final byte[] sec = sector(dir.sector);
                putClusterIntoSlot(sec, dir.offset, c);
                putSector(dir.sector, sec);
            }
            return;
        }
        int last = first;
        for (int x : chainOf(first)) {
            last = x;
        }
        fatSet(last, c);
    }

    private void zeroCluster(int cluster) {
        io.write(clusterToByte(cluster), new byte[sectorsPerCluster * BlockIo.SECTOR],
                sectorsPerCluster * BlockIo.SECTOR);
    }

    private void freeChain(int first) {
        for (int c : chainOf(first)) {
            fatSet(c, FREE_CLUSTER);
        }
    }

    // ==================== 目录项写入 ====================

    private void putFile(Raw parent, String leaf, byte[] data) {
        DirScan scan = scan(parent);
        Raw existing = findIn(scan.entries, leaf);
        if (existing != null) {
            if (existing.directory) {
                throw FsException.invalidPath("is a directory: " + leaf);
            }
            writeChain(existing, data);
            final byte[] sec = sector(existing.sector);
            BlockIo.put32(sec, existing.offset + 28, data.length);
            BlockIo.put16(sec, existing.offset + 26, existing.firstCluster & 0xFFFF);
            BlockIo.put16(sec, existing.offset + 20, (existing.firstCluster >>> 16) & 0xFFFF);
            stamp(sec, existing.offset);
            putSector(existing.sector, sec);
            return;
        }
        final Raw fresh = new Raw("", "", false, 0, 0, 0, 0);
        writeEntry(scan, parent, fresh, leaf, false);
        writeChain(fresh, data);
        // 内容写完后回填首簇与大小（此时才有真实值）
        final byte[] sec = sector(fresh.sector);
        BlockIo.put32(sec, fresh.offset + 28, fresh.size);
        BlockIo.put16(sec, fresh.offset + 26, fresh.firstCluster & 0xFFFF);
        BlockIo.put16(sec, fresh.offset + 20, (fresh.firstCluster >>> 16) & 0xFFFF);
        stamp(sec, fresh.offset);
        putSector(fresh.sector, sec);
    }

    /**
     * 写一个目录项（必要时带 LFN）：先找连续空槽，找不到就扩目录簇后重试一次。
     * LFN 项在盘上必须<b>倒序</b>（序号大的在前），否则驱动拼出来的名字是反的。
     */
    private void writeEntry(DirScan scan, Raw parent, Raw target, String leaf, boolean isDir) {
        final boolean needLfn = needsLfn(leaf);
        final int needSlots = (needLfn ? lfnSlotCount(leaf) : 0) + 1;
        int start = scan.findFreeRun(needSlots);
        if (start < 0) {
            if (!scan.growable) {
                throw FsException.noSpace(needSlots, 0);
            }
            appendCluster(parent);
            scan = scan(parent);
            start = scan.findFreeRun(needSlots);
            if (start < 0) {
                throw new FsException(OcAbi.ERR_BAD_ARGS, "directory still full after growing");
            }
        }
        final byte[] shortName = makeShortName(leaf, scan.entries);
        final long shortSlot = scan.slots.get(start + needSlots - 1);
        if (needLfn) {
            final List<String> parts = lfnParts(leaf);
            final int checksum = shortNameChecksum(shortName);
            for (int i = 0; i < parts.size(); i++) {
                // 盘上顺序：序号 = parts.size()..1（第一个写的序号最大，且带 0x40 结束标记）
                final int seq = parts.size() - i;
                final long slot = scan.slots.get(start + i);
                final byte[] sec = sector(slot / BlockIo.SECTOR);
                final int off = (int) (slot % BlockIo.SECTOR);
                java.util.Arrays.fill(sec, off, off + 32, (byte) 0);
                sec[off] = (byte) (seq | (i == 0 ? 0x40 : 0x00));
                // ⚠ 序号与内容必须同向：盘上第一项序号最大（带 0x40 结束标记）且装名字的**最后**一块，
                // 最后一项序号 1 装第一块。写反了驱动仍能读，只是名字会前后颠倒 —— 最难发现的一类错。
                putLfnText(sec, off, parts.get(parts.size() - 1 - i));
                sec[off + 11] = (byte) ATTR_LFN;
                sec[off + 12] = 0;
                sec[off + 13] = (byte) checksum;
                BlockIo.put16(sec, off + 26, 0);
                putSector(slot / BlockIo.SECTOR, sec);
            }
        }
        final byte[] sec = sector(shortSlot / BlockIo.SECTOR);
        final int off = (int) (shortSlot % BlockIo.SECTOR);
        java.util.Arrays.fill(sec, off, off + 32, (byte) 0);
        System.arraycopy(shortName, 0, sec, off, 11);
        sec[off + 11] = (byte) (isDir ? ATTR_DIR : ATTR_ARCHIVE);
        stamp(sec, off);
        BlockIo.put16(sec, off + 26, isDir ? (target.firstCluster & 0xFFFF) : 0);
        BlockIo.put16(sec, off + 20, isDir ? ((target.firstCluster >>> 16) & 0xFFFF) : 0);
        if (isDir) {
            BlockIo.put32(sec, off + 28, 0);
        }
        putSector(shortSlot / BlockIo.SECTOR, sec);

        target.sector = shortSlot / BlockIo.SECTOR;
        target.offset = off;
        target.shortName = shortNameToText(shortName);
        target.name = leaf;
        target.lfnSlots = needLfn ? needSlots - 1 : 0;
    }

    private void markDeleted(Raw r) {
        for (int i = 0; i <= r.lfnSlots; i++) {
            final long slot = (r.sector * BlockIo.SECTOR + r.offset) - (long) i * 32;
            final byte[] sec = sector(slot / BlockIo.SECTOR);
            final int off = (int) (slot % BlockIo.SECTOR);
            if (off >= 0) {
                sec[off] = (byte) 0xE5;
                putSector(slot / BlockIo.SECTOR, sec);
            }
        }
    }

    // ==================== FAT 表访问（三种位宽） ====================

    private int fatGet(int cluster) {
        final long base = (long) reservedSectors * BlockIo.SECTOR;
        switch (type) {
            case FAT12 -> {
                final long off = base + cluster + (cluster / 2);
                final int v = byteAt(off) | (byteAt(off + 1) << 8);
                return (cluster & 1) == 0 ? (v & 0x0FFF) : (v >>> 4);
            }
            case FAT16 -> {
                final long off = base + (long) cluster * 2;
                return byteAt(off) | (byteAt(off + 1) << 8);
            }
            default -> {
                final long off = base + (long) cluster * 4;
                return (int) ((byteAt(off) | (byteAt(off + 1) << 8) | (byteAt(off + 2) << 16)
                        | ((long) byteAt(off + 3) << 24)) & 0x0FFFFFFFL);
            }
        }
    }

    private void fatSet(int cluster, int value) {
        for (int copy = 0; copy < fatCount; copy++) {
            final long base = (reservedSectors + (long) copy * fatSectors) * BlockIo.SECTOR;
            switch (type) {
                case FAT12 -> {
                    final long off = base + cluster + (cluster / 2);
                    final int old = byteAt(off) | (byteAt(off + 1) << 8);
                    final int merged = (cluster & 1) == 0
                            ? ((old & 0xF000) | (value & 0x0FFF))
                            : ((old & 0x000F) | ((value & 0x0FFF) << 4));
                    putByte(off, merged & 0xFF);
                    putByte(off + 1, (merged >>> 8) & 0xFF);
                }
                case FAT16 -> {
                    final long off = base + (long) cluster * 2;
                    putByte(off, value & 0xFF);
                    putByte(off + 1, (value >>> 8) & 0xFF);
                }
                default -> {
                    // 高 4 位是保留位：必须保留原值（真实驱动按 & 0x0FFFFFFF 读）
                    final long off = base + (long) cluster * 4;
                    final int keep = byteAt(off + 3) & 0xF0;
                    final int v = (value & 0x0FFFFFFF) | (keep << 24);
                    putByte(off, v & 0xFF);
                    putByte(off + 1, (v >>> 8) & 0xFF);
                    putByte(off + 2, (v >>> 16) & 0xFF);
                    putByte(off + 3, (v >>> 24) & 0xFF);
                }
            }
        }
    }

    private int eoc() {
        return switch (type) {
            case FAT12 -> 0x0FFF;
            case FAT16 -> 0xFFFF;
            case FAT32 -> 0x0FFFFFFF;
        };
    }

    private int eocMin() {
        return switch (type) {
            case FAT12 -> 0x0FF8;
            case FAT16 -> 0xFFF8;
            case FAT32 -> 0x0FFFFFF8;
        };
    }

    private boolean isEoc(int value) {
        return value >= eocMin();
    }

    private int allocCluster() {
        for (int c = 2; c < clusterCount + 2; c++) {
            if (fatGet(c) == FREE_CLUSTER) {
                fatSet(c, eoc());
                return c;
            }
        }
        throw FsException.noSpace(sectorsPerCluster * (long) BlockIo.SECTOR, 0);
    }

    // ==================== 扇区缓存 / 字节访问 ====================

    private byte[] sector(long sectorNo) {
        byte[] s = cache.get(sectorNo);
        if (s == null) {
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
            s = io.readSector(sectorNo);
            cache.put(sectorNo, s);
        }
        return s;
    }

    private void putSector(long sectorNo, byte[] data) {
        io.writeSector(sectorNo, data);
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(sectorNo, data);
    }

    private int byteAt(long off) {
        return BlockIo.u8(sector(off / BlockIo.SECTOR), (int) (off % BlockIo.SECTOR));
    }

    private void putByte(long off, int value) {
        final long no = off / BlockIo.SECTOR;
        final byte[] s = sector(no);
        s[(int) (off % BlockIo.SECTOR)] = (byte) value;
        putSector(no, s);
    }

    // ==================== 目录项字段 ====================

    private int firstClusterOf(byte[] sec, int off) {
        final int low = BlockIo.u16(sec, off + 26);
        if (type == FatType.FAT32) {
            return (BlockIo.u16(sec, off + 20) << 16) | low;
        }
        return low;
    }

    private void putClusterIntoSlot(byte[] sec, int off, int cluster) {
        BlockIo.put16(sec, off + 26, cluster & 0xFFFF);
        if (type == FatType.FAT32) {
            BlockIo.put16(sec, off + 20, (cluster >>> 16) & 0xFFFF);
        }
    }

    private static String shortNameOf(byte[] sec, int off) {
        final String base = BlockIo.ascii(sec, off, 8).trim();
        final String ext = BlockIo.ascii(sec, off + 8, 3).trim();
        return ext.isEmpty() ? base : base + "." + ext;
    }

    private static String shortNameToText(byte[] name11) {
        final String base = new String(name11, 0, 8, StandardCharsets.US_ASCII).trim();
        final String ext = new String(name11, 8, 3, StandardCharsets.US_ASCII).trim();
        return ext.isEmpty() ? base : base + "." + ext;
    }

    /** 目录项时间戳（DOS 格式；写当前时间，方便用户/日志判断"什么时候写进去的"） */
    private static void stamp(byte[] sec, int off) {
        final LocalDateTime now = LocalDateTime.now();
        BlockIo.put16(sec, off + 22, (now.getHour() << 11) | (now.getMinute() << 5) | (now.getSecond() / 2));
        BlockIo.put16(sec, off + 24, ((now.getYear() - 1980) << 9) | (now.getMonthValue() << 5) | now.getDayOfMonth());
        BlockIo.put16(sec, off + 14, BlockIo.u16(sec, off + 22));
        BlockIo.put16(sec, off + 16, BlockIo.u16(sec, off + 24));
    }

    // ==================== 短名 / LFN ====================

    private static boolean needsLfn(String name) {
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot + 1) : "";
        if (base.isEmpty() || base.length() > 8 || ext.length() > 3) {
            return true;
        }
        for (int i = 0; i < base.length(); i++) {
            if (!isLegal83Char(base.charAt(i))) {
                return true;
            }
        }
        for (int i = 0; i < ext.length(); i++) {
            if (!isLegal83Char(ext.charAt(i))) {
                return true;
            }
        }
        return name.indexOf('.') != dot;                  // 多个点
    }

    private static boolean isLegal83Char(char c) {
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
        if (c >= '0' && c <= '9') {
            return true;
        }
        return "!#$%&'()-@^_{}~".indexOf(c) >= 0 || (int) c == 96;
    }

    private static int lfnSlotCount(String longName) {
        return (longName.length() + 12) / 13;
    }

    /** 拆成 13 字符一块（顺序 = 名字从左到右） */
    private static List<String> lfnParts(String longName) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < longName.length(); i += 13) {
            out.add(longName.substring(i, Math.min(longName.length(), i + 13)));
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    private static void putLfnText(byte[] sec, int off, String part) {
        final int[] positions = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
        for (int i = 0; i < 13; i++) {
            final int code = i < part.length() ? part.charAt(i) : (i == part.length() ? 0x0000 : 0xFFFF);
            BlockIo.put16(sec, off + positions[i], code);
        }
    }

    private static String lfnText(byte[] sec, int off) {
        final int[] positions = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
        final StringBuilder sb = new StringBuilder();
        for (int p : positions) {
            final int code = BlockIo.u16(sec, off + p);
            if (code == 0x0000 || code == 0xFFFF) {
                break;
            }
            sb.append((char) code);
        }
        return sb.toString();
    }

    /** 短名校验和（规范算法：循环右移 + 累加）—— 驱动靠它把 LFN 与短名配对 */
    private static int shortNameChecksum(byte[] name11) {
        int sum = 0;
        for (int i = 0; i < 11; i++) {
            sum = (((sum & 1) << 7) + (sum >>> 1) + (name11[i] & 0xFF)) & 0xFF;
        }
        return sum;
    }

    /** 生成不与现有项冲突的 11 字节短名（长名走 ~N 数字尾，这是 FAT 的标准做法） */
    private byte[] makeShortName(String leaf, List<Raw> existing) {
        final int dot = leaf.lastIndexOf('.');
        String base = dot > 0 ? leaf.substring(0, dot) : leaf;
        String ext = dot > 0 ? leaf.substring(dot + 1) : "";
        base = sanitize(base);
        ext = sanitize(ext);
        if (base.isEmpty()) {
            base = "FILE";
        }
        if (base.length() > 8) {
            base = base.substring(0, 8);
        }
        if (ext.length() > 3) {
            ext = ext.substring(0, 3);
        }
        if (!needsLfn(leaf) && !conflicts(base, ext, existing)) {
            return build11(base, ext);
        }
        for (int n = 1; n < 1_000_000; n++) {
            final String tail = "~" + n;
            final String head = base.length() > 8 - tail.length()
                    ? base.substring(0, 8 - tail.length()) : base;
            final String candidate = head + tail;
            if (!conflicts(candidate, ext, existing)) {
                return build11(candidate, ext);
            }
        }
        throw FsException.noSpace(1, 0);
    }

    private static String sanitize(String s) {
        final StringBuilder sb = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
            if (isLegal83Char(c) || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static boolean conflicts(String base, String ext, List<Raw> existing) {
        final String want = ext.isEmpty() ? base : base + "." + ext;
        for (Raw r : existing) {
            if (r.shortName.equalsIgnoreCase(want)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] build11(String base, String ext) {
        final byte[] out = new byte[11];
        java.util.Arrays.fill(out, (byte) ' ');
        final byte[] b = base.getBytes(StandardCharsets.US_ASCII);
        final byte[] e = ext.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, out, 0, Math.min(8, b.length));
        System.arraycopy(e, 0, out, 8, Math.min(3, e.length));
        return out;
    }

    // ==================== 内部目录项 ====================

    private static final class Raw {
        String name;
        String shortName;
        boolean directory;
        int firstCluster;
        long size;
        long sector;
        int offset;
        int lfnSlots;
        int writeTime;
        int writeDate;

        Raw(String name, String shortName, boolean directory, int firstCluster, long size,
            long sector, int offset) {
            this(name, shortName, directory, firstCluster, size, sector, offset, 0);
        }

        Raw(String name, String shortName, boolean directory, int firstCluster, long size,
            long sector, int offset, int lfnSlots) {
            this.name = name;
            this.shortName = shortName;
            this.directory = directory;
            this.firstCluster = firstCluster;
            this.size = size;
            this.sector = sector;
            this.offset = offset;
            this.lfnSlots = lfnSlots;
        }
    }
}
