package com.hdf.cryptand.soc.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ===== 分块动态盘（qcow2 精神，2026-09-18 用户定案）=====
 *
 * <p>用户要的是「设置 60G 则开始分配为 0G，根据安装实际情况动态扩展」—— 与虚拟机的
 * thin provisioning 一致。<b>不用</b>"预分配 + 稀疏文件"：Windows 上 `setLength(60G)` 会真分配
 * 60G，而 Java 标准 API 给不出 NTFS 的稀疏标记（FSCTL_SET_SPARSE）。改成<b>分块文件</b>：
 * 只有写过的块才存在。</p>
 *
 * <pre>
 *   &lt;address&gt;/
 *     ├─ disk.json     容量 / 块大小（纯文本，便于人工排查；不存"已分配块表"）
 *     └─ blocks/
 *          000000.blk   ← 只存写过的块
 *          000017.blk
 * </pre>
 *
 * <p>三个好处：① 初始占用真的是 0 字节；② <b>实际占用 = 块文件之和</b>，配额判定精确可靠
 * （不必猜稀疏文件的真实占用）；③ 跨平台稳。</p>
 *
 * <p>⚠ <b>已分配块表就是目录本身</b>：没有第二份状态要维护，因此不存在"表与文件不一致"的
 * 经典 bug（这正是选择"一块一个文件"而不是"单文件 + 位图头"的原因）。</p>
 */
public final class DiskImage implements AutoCloseable {

    /** 块文件后缀 */
    private static final String BLOCK_EXT = ".blk";
    /** 元数据文件名 */
    public static final String META_FILE = "disk.json";

    private final Path dir;
    private final Path blocksDir;
    private final long capacityBytes;
    private final int blockSize;

    private DiskImage(Path dir, long capacityBytes, int blockSize) {
        this.dir = dir;
        this.blocksDir = dir.resolve("blocks");
        this.capacityBytes = capacityBytes;
        this.blockSize = blockSize;
    }

    /**
     * **打开**一个动态盘（<b>读路径</b>）。
     *
     * <p>已有 {@code disk.json} ⇒ 容量与块大小以文件为准（盘是物品，换台机器插回来必须还是
     * 同一个尺寸，不能由调用方临时决定）；<b>没有也不写</b> —— 只是按入参容量与自适应块大小
     * 打开一块"尚未落盘任何东西"的盘。</p>
     *
     * <p>🔴 为什么读路径绝不写（用户 2026-09-26："一块空软盘…在被创造/首次使用/放进机器时
     * 只会分配地址，只有<b>显式</b>执行写入才行"）：挂载一块盘是"看它、用它"，
     * 在这里写 meta 就等于"插上机器就被写了内容"。</p>
     */
    public static DiskImage open(Path dir, long capacityBytes) {
        try {
            Files.createDirectories(dir.resolve("blocks"));
        } catch (IOException e) {
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_INVALID_PATH,
                    "cannot create disk dir " + dir + ": " + e.getMessage());
        }
        final Path meta = dir.resolve(META_FILE);
        long capacity = capacityBytes;
        int block = blockSizeFor(capacityBytes);
        if (Files.isRegularFile(meta)) {
            final String text = readText(meta);
            capacity = (long) readNumber(text, "capacityBytes", capacityBytes);
            block = (int) readNumber(text, "blockSize", block);
        }
        return new DiskImage(dir, capacity, block);
    }

    /**
     * **创建**一块动态盘：与 {@link #open} 相同的语义，但把容量与块大小**落盘**到
     * {@code disk.json}（此后这块盘"定型"，换机器插回来尺寸不变）。
     *
     * <p>⚠ 只有显式动作走这里：格式化分区（{@code DiskFormatTool}）、测试里造盘、
     * 程序加载器首次写盘。挂载/挂载扫描一律用 {@link #open}。</p>
     */
    public static DiskImage create(Path dir, long capacityBytes) {
        final DiskImage img = open(dir, capacityBytes);
        final Path meta = dir.resolve(META_FILE);
        if (!Files.isRegularFile(meta)) {
            writeText(meta, "{\n  \"capacityBytes\": " + img.capacityBytes()
                    + ",\n  \"blockSize\": " + img.blockSize() + "\n}\n");
        }
        return img;
    }

    /** 块大小按容量自适应：小盘 1MB / 中盘 4MB / 大盘 16MB（60G ⇒ 约 3840 块） */
    public static int blockSizeFor(long capacityBytes) {
        if (capacityBytes <= 8L * 1024 * 1024) {
            return 1024 * 1024;
        }
        if (capacityBytes <= 512L * 1024 * 1024) {
            return 4 * 1024 * 1024;
        }
        return 16 * 1024 * 1024;
    }

    public Path directory() {
        return dir;
    }

    public long capacityBytes() {
        return capacityBytes;
    }

    public int blockSize() {
        return blockSize;
    }

    /** 逻辑块数（容量 / 块大小，向上取整） */
    public int blockCount() {
        return (int) ((capacityBytes + blockSize - 1) / blockSize);
    }

    /** 已分配的块索引（升序）—— 直接来自目录，无第二份状态 */
    public List<Integer> allocatedBlocks() {
        final List<Integer> out = new ArrayList<>();
        if (!Files.isDirectory(blocksDir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(blocksDir)) {
            s.forEach(p -> {
                final String n = p.getFileName().toString();
                if (n.endsWith(BLOCK_EXT)) {
                    try {
                        out.add(Integer.parseInt(n.substring(0, n.length() - BLOCK_EXT.length())));
                    } catch (NumberFormatException ignored) {
                        // 不是我们的块文件：忽略（玩家丢进来的东西不该让盘读不出来）
                    }
                }
            });
        } catch (IOException ignored) {
            // 读不到就当没有已分配块
        }
        out.sort(Integer::compareTo);
        return out;
    }

    /**
     * 实际占用（字节）= 已分配块文件大小之和。
     *
     * <p>这是**配额判定用的权威数字**：因为每个块文件都是按需长大（不是预分配），
     * 它同时反映了"逻辑分配"与"真实磁盘占用"。</p>
     */
    public long usedBytes() {
        long sum = 0;
        if (!Files.isDirectory(blocksDir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(blocksDir)) {
            for (final Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    sum += Files.size(p);
                }
            }
        } catch (IOException ignored) {
            // 同上：读不到按 0 计
        }
        return sum;
    }

    /**
     * 块缓存（2026-09-18 补）：一个块 1~16MB，而文件系统按 <b>512B 扇区</b>访问 ——
     * 没有缓存的话，每读写一个扇区都要读/写一整个块文件，FAT 驱动会慢到不可用。
     *
     * <p>上限按<b>字节</b>而非块数：块大小随容量自适应，按块数设限会让大容量盘吃掉几个 GB 内存。</p>
     */
    private static final long CACHE_BYTES_LIMIT = 64L * 1024 * 1024;

    private final java.util.LinkedHashMap<Integer, byte[]> cache =
            new java.util.LinkedHashMap<>(16, 0.75f, true);

    private long cachedBytes;

    /**
     * 读一个块；**未分配的块返回全 0**（稀疏语义 —— 这正是"新盘上到处是 0"的来由）。
     *
     * <p>⚠ <b>所有权</b>：返回的数组是内部缓冲，调用方只应在"读-改-写"的模式下使用它
     * （改完立刻 {@link #writeBlock}）；要长期持有请 {@code clone()}。这避免了每次读都
     * 复制一个 16MB 数组 —— 那比读盘本身还贵。</p>
     */
    public byte[] readBlock(int index) {
        checkIndex(index);
        final byte[] cached = cache.get(index);
        if (cached != null) {
            return cached;
        }
        final Path p = blockPath(index);
        final byte[] out = new byte[blockSize];
        if (Files.isRegularFile(p)) {
            try {
                final byte[] raw = Files.readAllBytes(p);
                System.arraycopy(raw, 0, out, 0, Math.min(raw.length, out.length));
            } catch (IOException e) {
                throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_NOT_FOUND,
                        "read block " + index + " failed: " + e.getMessage());
            }
        }
        // 未分配的块也缓存：连续读稀疏区域时不必反复 stat 文件系统
        cachePut(index, out);
        return out;
    }

    /** 放入缓存并按字节预算淘汰最久未用的块（至少留 1 块，避免刚放的被自己挤掉） */
    private void cachePut(int index, byte[] data) {
        final byte[] old = cache.put(index, data);
        if (old != null) {
            cachedBytes -= old.length;
        }
        cachedBytes += data.length;
        final java.util.Iterator<java.util.Map.Entry<Integer, byte[]>> it = cache.entrySet().iterator();
        while (cachedBytes > CACHE_BYTES_LIMIT && cache.size() > 1 && it.hasNext()) {
            final java.util.Map.Entry<Integer, byte[]> e = it.next();
            if (e.getKey() == index) {
                continue;
            }
            cachedBytes -= e.getValue().length;
            it.remove();
        }
    }

    /** 写一个块；**首次写才创建文件**（这是"动态扩展"的落点） */
    public void writeBlock(int index, byte[] data) {
        checkIndex(index);
        final byte[] buf = new byte[blockSize];
        if (data != null) {
            System.arraycopy(data, 0, buf, 0, Math.min(data.length, buf.length));
        }
        try {
            Files.write(blockPath(index), buf);
        } catch (IOException e) {
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_NO_SPACE,
                    "write block " + index + " failed: " + e.getMessage());
        }
        cachePut(index, buf);
    }

    /** 让一个块回到"未分配"（释放空间；TRIM 的语义） */
    public boolean freeBlock(int index) {
        checkIndex(index);
        // 必须同时丢缓存：否则"释放后读回"会拿到已删块的旧内容（正确性问题，不是性能问题）
        final byte[] dropped = cache.remove(index);
        if (dropped != null) {
            cachedBytes -= dropped.length;
        }
        try {
            return Files.deleteIfExists(blockPath(index));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        // 无长驻句柄：每次读写直接落文件，所以这里没有要关的东西。
        // 保留 AutoCloseable 是为了让调用方用 try-with-resources 表达"用完了"。
    }

    // ==================== 内部 ====================

    private Path blockPath(int index) {
        return blocksDir.resolve(String.format("%06d", index) + BLOCK_EXT);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= blockCount()) {
            throw FsException.badMode("block index out of range: " + index + " (0.." + (blockCount() - 1) + ")");
        }
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(Path p, String text) {
        try {
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_NO_SPACE,
                    "write " + p + " failed: " + e.getMessage());
        }
    }

    /**
     * 从极简 JSON 里取一个数字字段（只认 `"key": 123` 这种形态）。
     *
     * <p>为什么不用 Gson/Jackson：common 是**纯 Java 零 MC** 模块，不该为一个"两个字段的元数据文件"
     * 引入依赖；而且这里的格式由我们自己写、自己读，最简解析反而最不可能出错。</p>
     */
    private static double readNumber(String json, String key, double def) {
        final int at = json.indexOf('"' + key + '"');
        if (at < 0) {
            return def;
        }
        final int colon = json.indexOf(':', at);
        if (colon < 0) {
            return def;
        }
        int end = colon + 1;
        while (end < json.length() && (json.charAt(end) == ' ' || json.charAt(end) == '\t')) {
            end++;
        }
        int start = end;
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
