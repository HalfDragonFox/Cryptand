package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.ComponentBus;
import com.hdf.cryptand.soc.oc.OcAbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ===== DISK_* 调用 → 盘的块设备（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>用户定案："把格式化相关代码做成通用 c 的库，然后系统库加入即可"。要让格式化的
 * <b>决定权</b>归 guest 侧的通用 C 库（各系统链接同一份），guest 就必须能按偏移读写分区的
 * 任意字节 —— 而此前它只有文件级（FS_* → OC filesystem 组件）与 PE 业务（宿主干活）两条路，
 * 都不足以"自己格式化"。本类补上第三样：<b>块级读写</b>。</p>
 *
 * <h3>host 只当存储（这条线的纪律）</h3>
 * <ul>
 *   <li>本类不做任何"文件系统"决策：不写 BPB、不建 FAT、不判簇大小 —— 那些是 C 库的事；</li>
 *   <li>它只做三件事：按分区定位、把偏移换算成块设备读写、把结果包成 {@link ComponentBus.Result}。</li>
 * </ul>
 *
 * <h3>为什么按"盘索引"寻址</h3>
 * <p>字符串参数在寄存器/邮箱 ABI 里走缓冲区，而 {@code BLOCK_WRITE} 的缓冲区已经被"待写字节"
 * 占满 —— 于是盘用一个整数索引（{@link DiskLocator#addresses()} 的稳定顺序，与 PE 的
 * targets 编号同一套），分区用 0 基下标。字符�串一个都不必传。</p>
 *
 * <h3>写是"读-改-写"</h3>
 * <p>底层块是 1~16MB（见 {@link DiskImage#blockSizeFor}），而 guest 按 512B 扇区写 ——
 * 所以必须先读整块、改其中一段、再写回；直接写会把同块内其它扇区抹成 0。
 * 块级缓存由 {@link DiskImage} 负责，这里不做第二次缓存。</p>
 */
public final class DiskBlockDispatcher {

    /**
     * 平台侧只需两件事：盘索引 → 盘目录 / 逻辑容量。
     *
     * <p>与 {@code PeDispatcher.DiskLocator} 同形 —— 因为两者都是"把盘地址翻译成宿主位置"，
     * 未来这两处应该合成同一个接口（内测期先各自独立，避免为一个接口改动两处实现）。</p>
     */
    public interface DiskLocator {

        /** 盘目录（可不存在 —— 格式化正是要把它建出来） */
        Path dirOf(String address);

        /** 目标盘的逻辑容量（字节）。**必须来自盘物品的规格** */
        long capacityBytesOf(String address);

        /** 当前可见的目标盘地址（顺序稳定即可；BLOCK_* 的 targetIndex 就是这个顺序） */
        default List<String> addresses() {
            return List.of();
        }
    }

    private final DiskLocator disks;

    public DiskBlockDispatcher(DiskLocator disks) {
        this.disks = disks;
    }

    /** 分发一次 DISK_* 调用（标量参数 + 缓冲区；缓冲区方向由 {@link OcAbi#isOutbound} 决定） */
    public ComponentBus.Result invoke(int methodId, List<Object> args, byte[] buffer) {
        try {
            return switch (methodId) {
                case OcAbi.DISK_METHOD_INFO -> info(target(args, 0));
                case OcAbi.DISK_METHOD_BLOCK_READ -> blockRead(target(args, 0),
                        argi(args, 1, -1), argl(args, 2, 0L), argi(args, 3, 0));
                case OcAbi.DISK_METHOD_BLOCK_WRITE -> blockWrite(target(args, 0),
                        argi(args, 1, -1), argl(args, 2, 0L), buffer);
                default -> ComponentBus.Result.error(OcAbi.ERR_UNKNOWN_METHOD,
                        "unknown disk method #" + methodId);
            };
        } catch (FsException e) {
            return ComponentBus.Result.error(e.errCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return ComponentBus.Result.error(OcAbi.ERR_BAD_ARGS, e.getMessage());
        } catch (Throwable t) {
            return ComponentBus.Result.error(OcAbi.ERR_COMPONENT_FAILED,
                    t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
    }

    // ==================== 命令实现 ====================

    /** 容量 + 分区数 + 各分区大小（C 库据此知道"该格式化多大"） */
    private ComponentBus.Result info(String address) {
        final DiskPartitionTable table = tableOf(address);
        final List<Object> values = new ArrayList<>();
        values.add(disks.capacityBytesOf(address));
        values.add(table.partitions().size());
        for (final DiskPartitionTable.Partition p : table.partitions()) {
            if (values.size() >= OcAbi.RESULT_SLOTS) {
                break;      // 结果槽有限：分区多于 6 个时只回前几个（分页不在本阶段范围）
            }
            values.add(p.sizeBytes());
        }
        return new ComponentBus.Result(true, values, "", OcAbi.ERR_NONE);
    }

    /** 出方向：把分区的一段字节交给核心写进 guest 缓冲区 */
    private ComponentBus.Result blockRead(String address, int partIndex, long offset, int len) {
        if (len <= 0 || len > OcAbi.BUF_MAX) {
            throw new IllegalArgumentException("bad read length: " + len + " (1.." + OcAbi.BUF_MAX + ")");
        }
        return ComponentBus.Result.ok(readAt(address, partIndex, offset, len));
    }

    /** 入方向：数据在 guest 内存里（buffer），这里读出去写进盘 */
    private ComponentBus.Result blockWrite(String address, int partIndex, long offset, byte[] data) {
        if (data == null || data.length == 0) {
            return ComponentBus.Result.ok(0);
        }
        if (data.length > OcAbi.BUF_MAX) {
            throw new IllegalArgumentException("write too large: " + data.length + " (max " + OcAbi.BUF_MAX + ")");
        }
        writeAt(address, partIndex, offset, data);
        return ComponentBus.Result.ok(data.length);
    }

    // ==================== 块设备换算 ====================

    private byte[] readAt(String address, int partIndex, long offset, int len) {
        final DiskPartitionTable table = tableOf(address);
        final DiskPartitionTable.Partition part = partition(table, partIndex);
        try (DiskImage img = DiskImage.open(table.partitionDir(part.index()), part.sizeBytes())) {
            final int bs = img.blockSize();
            final byte[] out = new byte[len];
            int done = 0;
            while (done < len) {
                final long at = offset + done;
                if (at < 0 || at >= part.sizeBytes()) {
                    throw new IllegalArgumentException("read past end of partition: offset " + at
                            + " >= " + part.sizeBytes());
                }
                final int blockIndex = (int) (at / bs);
                final int inBlock = (int) (at % bs);
                final byte[] block = img.readBlock(blockIndex);     // 只读：不要改它（内部缓冲）
                final int n = (int) Math.min(len - done,
                        Math.min(bs - inBlock, part.sizeBytes() - at));
                System.arraycopy(block, inBlock, out, done, n);
                done += n;
            }
            return out;
        }
    }

    private void writeAt(String address, int partIndex, long offset, byte[] data) {
        final DiskPartitionTable table = tableOf(address);
        final DiskPartitionTable.Partition part = partition(table, partIndex);
        try (DiskImage img = DiskImage.open(table.partitionDir(part.index()), part.sizeBytes())) {
            final int bs = img.blockSize();
            int done = 0;
            while (done < data.length) {
                final long at = offset + done;
                if (at < 0 || at >= part.sizeBytes()) {
                    throw new IllegalArgumentException("write past end of partition: offset " + at
                            + " >= " + part.sizeBytes());
                }
                final int blockIndex = (int) (at / bs);
                final int inBlock = (int) (at % bs);
                final byte[] block = img.readBlock(blockIndex);     // ★ 读-改-写：不能直接覆盖整块
                final int n = (int) Math.min(data.length - done,
                        Math.min(bs - inBlock, part.sizeBytes() - at));
                System.arraycopy(data, done, block, inBlock, n);
                img.writeBlock(blockIndex, block);
                done += n;
            }
        }
    }

    private DiskPartitionTable tableOf(String address) {
        final Path dir = disks.dirOf(address);
        return DiskPartitionTable.load(dir, disks.capacityBytesOf(address));
    }

    /** 0 基分区下标 → 分区（越界把"有几个分区"讲清楚，不静默） */
    private static DiskPartitionTable.Partition partition(DiskPartitionTable table, int index) {
        final List<DiskPartitionTable.Partition> parts = table.partitions();
        if (index < 0 || index >= parts.size()) {
            throw new IllegalArgumentException("partition index " + index + " out of range (0.."
                    + Math.max(0, parts.size() - 1) + "); format the disk first");
        }
        return parts.get(index);
    }

    /** 盘索引（{@link DiskLocator#addresses()} 的顺序）→ 盘地址 */
    private String target(List<Object> args, int index) {
        final int i = argi(args, index, -1);
        final List<String> all = disks.addresses();
        if (i < 0 || i >= all.size()) {
            throw new IllegalArgumentException("disk index " + i + " out of range (0.."
                    + Math.max(0, all.size() - 1) + "); no target disk?");
        }
        return all.get(i);
    }

    // ==================== 参数小工具 ====================

    private static int argi(List<Object> args, int index, int fallback) {
        final Object v = arg(args, index);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static long argl(List<Object> args, int index, long fallback) {
        final Object v = arg(args, index);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    private static Object arg(List<Object> args, int index) {
        return args == null || index < 0 || index >= args.size() ? null : args.get(index);
    }
}
