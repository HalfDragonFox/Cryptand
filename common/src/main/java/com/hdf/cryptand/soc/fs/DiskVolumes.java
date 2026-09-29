package com.hdf.cryptand.soc.fs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ===== 盘的多卷视图（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-24）："完整 os 可以支持通过分区来实现功能，对应现代系统" ——
 * 一块盘不再只有一个文件系统，而是<b>一张分区表 = 一组卷</b>，每个卷有自己的用途与挂载点。</p>
 *
 * <p>与真机对应：现代 PC 的"分区表 + fstab（分区 → 挂载点）"。这里把它压成纯数据：
 * 分区 <b>label</b> 决定<b>角色</b>，角色决定挂载点。</p>
 *
 * <p>⚠ 为什么不按"分区顺序"定角色：顺序会随格式化/删分区变化（{@link DiskPartitionTable#remove}
 * 之后编号是连续的），而 label 是人写进去的意图。所以角色认 label，顺序只做无 label 时的兜底。</p>
 */
public final class DiskVolumes {

    /** 分区用途 —— 现代系统 fstab 的精神：分区是人分好的，用途决定它挂到哪。 */
    public enum Role {
        /** 系统卷（{@code /boot/system.bin} 所在）；label：SYS / SYSTEM / BOOT */
        SYSTEM("/", "sys"),
        /** 程序与库；label：USR / APP / LIB */
        USR("/usr", "usr"),
        /** 用户数据；label：HOME / USER */
        HOME("/home", "home"),
        /** 其他数据；label：DATA / VAR / TMP */
        DATA("/data", "data"),
        /** 认不出的 label 归这里（有明确落点，**不**静默当系统盘）；label：其它 */
        RAW("/mnt", "raw");

        private final String mountPoint;
        private final String key;

        Role(String mountPoint, String key) {
            this.mountPoint = mountPoint;
            this.key = key;
        }

        /** 该卷在 guest 里的挂载点（现代系统的路径形态） */
        public String mountPoint() {
            return mountPoint;
        }

        /** 短名（节点地址/日志/AI 工具用；不含 '/' 以免破坏地址语法） */
        public String key() {
            return key;
        }
    }

    /**
     * 一个卷 = 分区 + 它挂上的文件系统。
     *
     * @param note 该卷为什么不可用（{@code fs == null} 时非空）—— 不静默：调用方能看到真实原因
     */
    public record Volume(int index, DiskPartitionTable.Kind kind, long sizeBytes, String format,
                         String label, Role role, CryptandFileSystem fs, String note) {

        /** 这个卷能不能用（未格式化的分区只留一条占位记录，不进文件系统） */
        public boolean available() {
            return fs != null;
        }

        /** 卷名（日志/节点地址用，如 {@code part1:home}） */
        public String name() {
            return "part" + index + ":" + role.key();
        }
    }

    private DiskVolumes() {
    }

    /**
     * 分区 label → 角色。认不出的一律 {@link Role#RAW} ——
     * <b>不</b>默认成系统盘：那会让"随手分的一个区"悄悄变成引导分区。
     */
    public static Role roleOf(String label) {
        if (label == null || label.isBlank()) {
            return null;   // 没有标注意图 ⇒ 交给调用方按位置兜底
        }
        final String s = label.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("SYS") || s.startsWith("BOOT")) {
            return Role.SYSTEM;
        }
        if (s.startsWith("USR") || s.startsWith("APP") || s.startsWith("LIB")) {
            return Role.USR;
        }
        if (s.startsWith("HOME") || s.startsWith("USER")) {
            return Role.HOME;
        }
        if (s.startsWith("DATA") || s.startsWith("VAR") || s.startsWith("TMP")) {
            return Role.DATA;
        }
        return Role.RAW;
    }

    /** 无 label 时的位置兜底：第一个分区是系统卷，之后 usr / home / data */
    private static Role defaultRoleOf(int ordinal) {
        return switch (ordinal) {
            case 0 -> Role.SYSTEM;
            case 1 -> Role.USR;
            case 2 -> Role.HOME;
            default -> Role.DATA;
        };
    }

    /**
     * 挂载盘上的<b>所有</b>分区（用户 2026-09-24："完整 os 可以支持通过分区来实现功能"）。
     *
     * <p>没有分区的老盘返回空列表 —— 那是"盘根 = 文件夹树"的那条路，由
     * {@link DiskFileSystems#open} 处理。</p>
     */
    public static List<Volume> volumes(Path diskDir, long capacityBytes, String address, boolean readOnly) {
        final DiskPartitionTable table = DiskPartitionTable.load(diskDir, capacityBytes);
        final List<DiskPartitionTable.Partition> parts = table.partitions();
        final List<Volume> out = new ArrayList<>(parts.size());
        int ordinal = 0;
        for (final DiskPartitionTable.Partition p : parts) {
            Role role = roleOf(p.label());
            if (role == null) {
                role = defaultRoleOf(ordinal);
            }
            ordinal++;
            out.add(mountOne(table, p, role, address, readOnly));
        }
        return out;
    }

    /**
     * 系统卷（{@code /boot/system.bin} 所在的那一卷）。
     *
     * <p>⚠ 这里<b>不</b>用"第一个分区"：多分区盘上第一个分区可能是 /home ——
     * 那样引导就会去用户数据区找系统。角色认 label（无 label 时按位置兜底），
     * 所以分区顺序换不动系统卷是谁。</p>
     *
     * @return {@code null} = 这块盘没有分区（调用方走"文件夹树"老路）
     * @throws IllegalStateException 有系统分区但挂不上（未格式化等）—— 必须说清为什么
     */
    public static Volume system(Path diskDir, long capacityBytes, String address, boolean readOnly) {
        final DiskPartitionTable table = DiskPartitionTable.load(diskDir, capacityBytes);
        final List<DiskPartitionTable.Partition> parts = table.partitions();
        if (parts.isEmpty()) {
            return null;   // 没有分区 ⇒ 老盘（调用方走"盘根 = 文件夹树"）
        }
        // ① 明确的系统角色（label SYS/BOOT）—— 唯一权威
        for (final DiskPartitionTable.Partition p : parts) {
            if (roleOf(p.label()) == Role.SYSTEM) {
                return requireAvailable(mountOne(table, p, Role.SYSTEM, address, readOnly), address);
            }
        }
        // ② 没有标明用途的分区 ⇒ 位置兜底（老盘、手工分的盘、"FOLDERVOL"/"SMALL" 这类自定标签）。
        //    ⚠ RAW（认不出的 label）也算"没标明用途"：它是"人没说要干什么"，
        //      而不是"明确说了不要当系统盘"——后者才是 USR/HOME/DATA。
        for (final DiskPartitionTable.Partition p : parts) {
            final Role role = roleOf(p.label());
            if (role == null || role == Role.RAW) {
                return requireAvailable(mountOne(table, p, defaultRoleOf(0), address, readOnly), address);
            }
        }
        // ③ 有 IMAGE 分区但**没格式化** ⇒ 报这个（比"没有系统卷"更接近根因：
        //    未格式化的分区承载不了任何角色，而"刚 format 完就挂载"最常见的就是这种情况）
        for (final DiskPartitionTable.Partition p : parts) {
            if (p.kind() == DiskPartitionTable.Kind.IMAGE
                    && !p.format().toUpperCase(java.util.Locale.ROOT).startsWith("FAT")) {
                throw new IllegalStateException("disk " + address + " partition #" + p.index()
                        + " is not formatted (format=" + p.format() + "); format it before mounting");
            }
        }
        // ④ 每个分区都带了明确的**非系统**角色（例如只有 /home）⇒ 这块盘没有系统卷。
        //    ⚠ 绝不能"拿第一个分区顶替"：那会把系统装到 /home 上、并把用户数据格掉。
        final StringBuilder roles = new StringBuilder();
        for (final DiskPartitionTable.Partition p : parts) {
            roles.append(roles.length() == 0 ? "" : ", ")
                    .append('#').append(p.index()).append('=').append(roleOf(p.label()));
        }
        throw new IllegalStateException("disk " + address
                + " has no system volume (partition roles: " + roles + ")");
    }

    /** 系统卷必须**可用**：挂不上就把原因原样说清（"盘不可用"不能只是个空文件系统） */
    private static Volume requireAvailable(Volume v, String address) {
        if (!v.available()) {
            throw new IllegalStateException("disk " + address + " system volume unusable: " + v.note());
        }
        return v;
    }

    /**
     * 卷表（每个分区一行）—— 命令 / MCP 工具 / 日志**共用这一份文本**。
     *
     * <p>为什么不做成三个地方各拼一遍：卷表是"盘上到底有什么"的对外表达，
     * 各处自己拼就会出现"命令里写着 /home、日志里写着 raw"这种对不上账的现象。</p>
     */
    public static List<String> describe(List<Volume> volumes) {
        final List<String> out = new ArrayList<>(volumes.size());
        for (final Volume v : volumes) {
            final StringBuilder sb = new StringBuilder();
            sb.append(v.name()).append(' ').append(v.format())
                    .append(' ').append(v.sizeBytes() / 1024).append("KB label=")
                    .append(v.label() == null ? "" : v.label())
                    .append(" -> ").append(v.role().mountPoint());
            if (v.role() == Role.SYSTEM) {
                sb.append(" [系统卷]");
            }
            if (!v.available()) {
                sb.append(" 不可用: ").append(v.note());
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 挂一个分区（{@link #volumes} 与 {@link #system} 共用，保证两条路的挂载规则一致） */
    private static Volume mountOne(DiskPartitionTable table, DiskPartitionTable.Partition p, Role role,
                                   String address, boolean readOnly) {
        if (p.kind() == DiskPartitionTable.Kind.IMAGE
                && !p.format().toUpperCase(Locale.ROOT).startsWith("FAT")) {
            // 明确记录"这一卷为什么没挂上"，而不是整盘报错或静默跳过
            return new Volume(p.index(), p.kind(), p.sizeBytes(), p.format(), p.label(), role, null,
                    "partition #" + p.index() + " is not formatted (format=" + p.format() + ")");
        }
        final CryptandFileSystem fs;
        try {
            fs = p.kind() == DiskPartitionTable.Kind.IMAGE
                    ? new FatFileSystem(FatVolume.mount(
                            DiskImage.open(table.partitionDir(p.index()), p.sizeBytes())), readOnly)
                    : new DiskFileSystem(new DiskMount(table.partitionDir(p.index()), address),
                            p.sizeBytes(), readOnly);
        } catch (RuntimeException e) {
            return new Volume(p.index(), p.kind(), p.sizeBytes(), p.format(), p.label(), role, null,
                    "mount failed: " + e);
        }
        return new Volume(p.index(), p.kind(), p.sizeBytes(), p.format(), p.label(), role, fs, null);
    }
}
