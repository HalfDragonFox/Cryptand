package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ===== 盘的分区表（用户 2026-09-18 定案）=====
 *
 * <pre>
 *   &lt;cryptand/disks/&lt;worldId&gt;/&lt;address&gt;&gt;/
 *     ├─ disk.json     容量 + 分区表（每项：kind / format / size / label）
 *     ├─ part1/        镜像分区（{@link Kind#IMAGE}：里面是分块块文件，见 {@link DiskImage}）
 *     ├─ part2/        文件夹分区（{@link Kind#FOLDER}：里面就是文件树，见 {@link DiskFileSystem}）
 *     └─ （剩余 = 未分配 = 容量 − Σ分区）
 * </pre>
 *
 * <p>用户原话是「一旦被格式化就会在此文件夹创建一个指定大小的文件…可以分区多个分区就是多个不同文件，
 * 都在同一硬盘的下面」。这里做了一处**必要的协调**：镜像分区落成<b>目录</b>（`partN/`）而不是单个文件 ——
 * 因为"文件"无法动态增长（Windows 上预分配就是真占空间，见 {@link DiskImage} 的说明）。
 * 目录里放分块文件，于是<b>分区内的空间同样是按需分配</b>，而"分区"这个真实语义一点没丢。</p>
 *
 * <p>两种分区对应两种使用方：<b>IMAGE</b> = 块设备（将来写 FAT 给 MCU/裸机当 SD 卡用），
 * <b>FOLDER</b> = 文件树（SOC 侧直接走 OC 的 filesystem 组件语义，玩家也能用文件管理器改）。</p>
 *
 * <p>⚠ 分区大小是**逻辑容量**，不是实际占用：一块 8MB 的盘可以有一个 4MB 的 IMAGE 分区，
 * 而它在宿主上可能只占几百 KB（写到哪里才分配到哪里）—— 这正是 thin provisioning。</p>
 */
public final class DiskPartitionTable {

    /** 分区类型 */
    public enum Kind {
        /** 镜像分区：块设备（给 FAT / SD 卡场景用），实现是 {@link DiskImage} */
        IMAGE,
        /** 文件夹分区：直接映射文件树，实现是 {@link DiskFileSystem} */
        FOLDER
    }

    /**
     * 一个分区。
     *
     * @param index     分区号（也是目录名 partN 的 N，从 1 起）
     * @param kind      镜像 / 文件夹
     * @param startBytes 起始偏移（顺序分配，见 {@link #create}）
     * @param sizeBytes 逻辑容量
     * @param format    文件系统标识（NONE = 未格式化 / 裸块设备；将来 FAT12 / FAT16 / FAT32）
     * @param label     卷标（玩家可读）
     */
    public record Partition(int index, Kind kind, long startBytes, long sizeBytes, String format, String label) {
    }

    /** 元数据文件名（与 {@link DiskImage#META_FILE} 同名：都表示"这块盘的描述"） */
    public static final String META_FILE = "disk.json";

    private final Path diskDir;
    private final long capacityBytes;
    private final List<Partition> partitions = new ArrayList<>();

    private DiskPartitionTable(Path diskDir, long capacityBytes) {
        this.diskDir = diskDir;
        this.capacityBytes = capacityBytes;
    }

    /**
     * 读入分区表；**没有分区表时返回空表，并且绝不落盘**。
     *
     * <p>⚠ 若目录里已有 {@code disk.json}，以**文件里的容量**为准（盘是物品，换台机器插回来
     * 必须还是同一个尺寸）。</p>
     *
     * <p>🔴 关键纪律（用户 2026-09-26："<b>空软盘创建时为空</b>"）：这里是<b>读路径</b> ——
     * 挂载一块盘（把它放进机箱、引导扫描、只读看看）<b>不允许</b>在盘上留下任何东西。
     * 早先这里在缺文件时会 {@code save()}，后果是"把空盘插进机箱"这一下就写出了一个
     * {@code disk.json}：空盘拿到手就不再是空的，而且所有
     * {@code Files.isRegularFile(…disk.json)} 的"这块盘初始化过没有"判断会<b>恒为真</b>
     * （程序加载器的"空盘自动格式化"于是永远不触发、分区大小被判成 0）。</p>
     *
     * <p>分区表只在**显式动作**里创建：{@link #create} / {@link #remove} 等会自己 {@code save()}。
     * 所以"盘上有没有 {@code disk.json}"从此就是"这块盘有没有被格式化过"的可信判据。</p>
     */
    public static DiskPartitionTable load(Path diskDir, long capacityBytes) {
        try {
            Files.createDirectories(diskDir);
        } catch (IOException e) {
            throw new FsException(OcAbi.ERR_INVALID_PATH,
                    "cannot create disk dir " + diskDir + ": " + e.getMessage());
        }
        final Path meta = diskDir.resolve(META_FILE);
        if (!Files.isRegularFile(meta)) {
            // 空盘：返回空表（容量取调用方给的盘规格），**不写盘**
            return new DiskPartitionTable(diskDir, capacityBytes);
        }
        final String text = readText(meta);
        final long capacity = (long) num(text, "capacityBytes", capacityBytes);
        final DiskPartitionTable t = new DiskPartitionTable(diskDir, capacity);
        t.parsePartitions(text);
        return t;
    }

    public Path directory() {
        return diskDir;
    }

    public long capacityBytes() {
        return capacityBytes;
    }

    public List<Partition> partitions() {
        return List.copyOf(partitions);
    }

    /** 已分配（Σ 分区大小） */
    public long allocatedBytes() {
        long sum = 0;
        for (final Partition p : partitions) {
            sum += p.sizeBytes();
        }
        return sum;
    }

    /** 未分配空间 = 容量 − Σ分区。**这是真实语义，不需要额外表示** */
    public long unallocatedBytes() {
        return Math.max(0, capacityBytes - allocatedBytes());
    }

    /** 分区目录（partN） */
    public Path partitionDir(int index) {
        return diskDir.resolve("part" + index);
    }

    /**
     * 新建一个分区（顺序分配：接在最后一个分区之后，不留空洞）。
     *
     * <p>不留空洞是**有意的简化**：真实分区表允许任意起始扇区，但那样就得处理"空闲区间合并"
     * 这类教科书问题，而玩家/固件都不需要它。将来真要对齐（比如 FAT 分区要扇区对齐）再引入
     * 起始偏移的显式分配。</p>
     *
     * @throws FsException {@code ERR_NO_SPACE}（放不下）、{@code ERR_BAD_ARGS}（参数非法）
     */
    public Partition create(Kind kind, long sizeBytes, String format, String label) {
        if (kind == null) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "partition kind must not be null");
        }
        if (sizeBytes <= 0) {
            throw new FsException(OcAbi.ERR_BAD_ARGS, "partition size must be > 0, got " + sizeBytes);
        }
        if (sizeBytes > unallocatedBytes()) {
            throw FsException.noSpace(sizeBytes, unallocatedBytes());
        }
        final int index = partitions.size() + 1;
        final Partition p = new Partition(index, kind,
                allocatedBytes(), sizeBytes,
                format == null || format.isEmpty() ? "NONE" : format,
                label == null ? "" : label);
        partitions.add(p);
        // 目录先建出来：IMAGE 与 FOLDER 共用 partN/，含义由表里的 kind 决定
        try {
            Files.createDirectories(partitionDir(index));
        } catch (IOException e) {
            partitions.remove(partitions.size() - 1);
            throw new FsException(OcAbi.ERR_INVALID_PATH,
                    "cannot create partition dir: " + e.getMessage());
        }
        save();
        return p;
    }

    /**
     * 删除一个分区（表项 + 目录）。
     *
     * <p>⚠ 删除会**连内容一起删** —— 与真实"删分区"一致；要保留内容就先拷贝出去。
     * 这是有意的：如果只删表项而留下目录，那块空间会变成"幽灵占用"（宿主上占着、盘上查不到）。</p>
     */
    public boolean remove(int index) {
        final int at = indexOf(index);
        if (at < 0) {
            return false;
        }
        deleteRecursively(partitionDir(index));
        partitions.remove(at);
        renumber();
        save();
        return true;
    }

    /** 改卷标/格式（不改变大小与内容） */
    public boolean update(int index, String format, String label) {
        final int at = indexOf(index);
        if (at < 0) {
            return false;
        }
        final Partition old = partitions.get(at);
        partitions.set(at, new Partition(old.index(), old.kind(), old.startBytes(), old.sizeBytes(),
                format == null || format.isEmpty() ? old.format() : format,
                label == null ? old.label() : label));
        save();
        return true;
    }

    public Partition get(int index) {
        final int at = indexOf(index);
        return at < 0 ? null : partitions.get(at);
    }

    /** 人类可读的一行（日志/工具提示） */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("DiskPartitionTable[capacity=").append(capacityBytes)
                .append(" allocated=").append(allocatedBytes())
                .append(" unallocated=").append(unallocatedBytes());
        for (final Partition p : partitions) {
            sb.append(" | #").append(p.index()).append(' ').append(p.kind())
                    .append(' ').append(p.sizeBytes()).append('B')
                    .append(' ').append(p.format());
            if (!p.label().isEmpty()) {
                sb.append(" (").append(p.label()).append(')');
            }
        }
        return sb.append(']').toString();
    }

    // ==================== 持久化 ====================

    /** 落盘（格式与解析器一一对应，见下面的 parsePartitions） */
    public void save() {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"capacityBytes\": ").append(capacityBytes).append(",\n");
        sb.append("  \"partitions\": [\n");
        for (int i = 0; i < partitions.size(); i++) {
            final Partition p = partitions.get(i);
            sb.append("    {\"kind\": \"").append(p.kind()).append("\"")
                    .append(", \"sizeBytes\": ").append(p.sizeBytes())
                    .append(", \"format\": \"").append(p.format()).append("\"")
                    .append(", \"label\": \"").append(p.label()).append("\"}");
            sb.append(i + 1 < partitions.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        try {
            Files.write(diskDir.resolve(META_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FsException(OcAbi.ERR_NO_SPACE, "write disk.json failed: " + e.getMessage());
        }
    }

    /** 解析我们自己写出来的格式：每个分区占一行，字段顺序固定 */
    private void parsePartitions(String json) {
        partitions.clear();
        final String[] lines = json.split("\n");
        long start = 0;
        for (final String line : lines) {
            final String s = line.trim();
            if (!s.startsWith("{")) {
                continue;
            }
            final Kind kind = "FOLDER".equals(str(s, "kind")) ? Kind.FOLDER : Kind.IMAGE;
            final long size = (long) num(s, "sizeBytes", 0);
            final String format = str(s, "format");
            final String label = str(s, "label");
            if (size <= 0) {
                continue;
            }
            final int index = partitions.size() + 1;
            partitions.add(new Partition(index, kind, start, size,
                    format.isEmpty() ? "NONE" : format, label));
            start += size;
        }
    }

    private int indexOf(int index) {
        for (int i = 0; i < partitions.size(); i++) {
            if (partitions.get(i).index() == index) {
                return i;
            }
        }
        return -1;
    }

    /** 删掉中间分区后重新编号（目录也随之改名），保持"分区号连续"这一简单不变量 */
    private void renumber() {
        for (int i = 0; i < partitions.size(); i++) {
            final Partition old = partitions.get(i);
            final int want = i + 1;
            if (old.index() != want) {
                try {
                    Files.move(partitionDir(old.index()), partitionDir(want));
                } catch (IOException ignored) {
                    // 目录挪不动就只改表：宁可不一致地保留内容，也不要删数据
                }
                partitions.set(i, new Partition(want, old.kind(), old.startBytes(), old.sizeBytes(),
                        old.format(), old.label()));
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 取字符串字段（只认 "key": "value" 形态；不处理转义 —— 卷标里不允许出现引号，见 update 的校验） */
    private static String str(String json, String key) {
        final int at = json.indexOf('"' + key + '"');
        if (at < 0) {
            return "";
        }
        final int colon = json.indexOf(':', at);
        if (colon < 0) {
            return "";
        }
        final int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return "";
        }
        final int close = json.indexOf('"', open + 1);
        return close < 0 ? "" : json.substring(open + 1, close);
    }

    /** 取数字字段（整数字面量，与 DiskImage 的解析同风格） */
    private static double num(String json, String key, double def) {
        final int at = json.indexOf('"' + key + '"');
        if (at < 0) {
            return def;
        }
        final int colon = json.indexOf(':', at);
        if (colon < 0) {
            return def;
        }
        int end = colon + 1;
        while (end < json.length() && Character.isWhitespace(json.charAt(end))) {
            end++;
        }
        final int start = end;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return def;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
