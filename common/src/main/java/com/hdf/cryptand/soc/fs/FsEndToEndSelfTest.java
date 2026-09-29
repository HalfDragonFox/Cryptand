package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.os.Programs;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.oc.ComponentBus;
import com.hdf.cryptand.soc.oc.OcAbi;
import com.hdf.cryptand.soc.oc.OcArchitectureCore;
import com.hdf.cryptand.soc.riscv.Rv32Core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * ===== 文件系统端到端自测（假宿主回归，2026-09-18，纯 Java 无 MC）=====
 *
 * <p>这是 {@code cryptand-fs-design.md §7 阶段 3.3} 点名的"整套设计最关键的一条验证路径"：
 * <b>固件发文件操作 → 核心组包 → 宿主 FileSystem 真落盘</b>，全程不需要 MC、不需要真固件。</p>
 *
 * <p>装置：真 {@code SocBoard} + 真 {@code OcArchitectureCore} + 真 {@link DiskFileSystem}
 * （临时目录），只有"固件那一侧"用 Java 往邮箱里写请求来替代（与固件 {@code component_post}
 * 写的字段完全一致）。链路：</p>
 * <pre>
 *   post(邮箱通道) → core.pollMailbox() → core.drainCalls() → ComponentBus
 *      → {@link FsCallDispatcher}（#16..31 → FileSystem 调用）
 *      → 结果写回邮箱（出方向数据落进 guest 缓冲区）
 * </pre>
 *
 * <p>跑法：{@code gradlew :common:runFsE2ETest}</p>
 */
public final class FsEndToEndSelfTest {

    private static final long RAM_BASE = 0x2000_0000L;
    private static final long REG_BASE = 0x1000_0000L;
    private static final int RAM_BYTES = (int) (OcAbi.MAILBOX_BASE - RAM_BASE) + OcAbi.MAILBOX_SPAN;
    /** 固件自带的缓冲区（出方向数据落这里） */
    private static final int BUF = (int) RAM_BASE + 0x8000;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== FS end-to-end self test (mailbox -> core -> DiskFileSystem, no MC) ===");
        final Path tmp = Files.createTempDirectory("cryptand-fs-e2e");
        final Path rootDir = tmp.resolve("cryptand/disks/w1/disk-1");
        final DiskFileSystem fs = new DiskFileSystem(new DiskMount(rootDir, "disk-1"), 64 * 1024, false);
        final Rig rig = build(new FsBus(new FsCallDispatcher(fs)));
        final Path hostFile = rootDir.resolve("home/a.txt");

        // ① mkdir（递归）
        post(rig, 0, OcAbi.FS_MKDIR, "/home");
        check("FS_MKDIR 返回 1", result0(rig, 0) == 1, "result0=" + result0(rig, 0));
        check("宿主目录真的建出来了", Files.isDirectory(rootDir.resolve("home")), rootDir.resolve("home").toString());

        // ② 打开写模式（返回句柄）
        post(rig, 1, OcAbi.FS_OPEN, "/home/a.txt", OcAbi.FS_MODE_W);
        final int handle = result0(rig, 1);
        check("FS_OPEN(w) 返回正句柄", handle > 0, "handle=" + handle);

        // ③ 写入（入方向缓冲区 = 待写字节）
        writeGuest(rig, "hello fs");
        postWithBuf(rig, 2, OcAbi.FS_WRITE, "hello fs".getBytes(StandardCharsets.UTF_8).length, handle);
        check("FS_WRITE 返回 1", result0(rig, 2) == 1, "result0=" + result0(rig, 2));
        post(rig, 3, OcAbi.FS_CLOSE, handle);
        check("FS_CLOSE 成功（STATE=DONE）", state(rig, 3) == OcAbi.MB_STATE_DONE, "DONE");

        // ④ **真落盘**（这一条是整条链的铁证）
        check("宿主机上真的有这个文件且内容一致",
                Files.isRegularFile(hostFile)
                        && "hello fs".equals(new String(Files.readAllBytes(hostFile), StandardCharsets.UTF_8)),
                hostFile.toString());

        // ⑤ 大小 / 存在性
        post(rig, 4, OcAbi.FS_SIZE, "/home/a.txt");
        check("FS_SIZE = 8", result0(rig, 4) == 8, "size=" + result0(rig, 4));

        // ⑥ 读回来（出方向：数据写进 guest 缓冲区，result0 = 实读字节数）
        post(rig, 5, OcAbi.FS_OPEN, "/home/a.txt", OcAbi.FS_MODE_R);
        final int rh = result0(rig, 5);
        postBuf(rig, 6, OcAbi.FS_READ, 64, rh, 64);
        check("FS_READ result0 = 实读字节数 8", result0(rig, 6) == 8, "result0=" + result0(rig, 6));
        check("guest 缓冲区里拿到了文件内容",
                "hello fs".equals(text(rig.board.readMemory(BUF, 8))),
                text(rig.board.readMemory(BUF, 8)));

        // ⑦ 再读 → EOF 必须是 -1（不是 0，也不是空数组 —— 照 OC 的 Handle.read）
        postBuf(rig, 7, OcAbi.FS_READ, 64, rh, 64);
        check("FS_READ 到 EOF 返回 -1", result0(rig, 7) == -1, "result0=" + result0(rig, 7));
        post(rig, 8, OcAbi.FS_CLOSE, rh);

        // ⑧ list（NUL 分隔，目录带尾斜杠）
        // FS_LIST 约定：缓冲区起始是 "path\0"，BUF_LEN = **容量**（结果原地覆盖路径）
        postWithBuf(rig, 9, OcAbi.FS_LIST, 64, "/home\0");
        final int listed = result0(rig, 9);
        check("FS_LIST 返回写入字节数 6", listed == 6, "result0=" + listed);
        check("缓冲区里是 NUL 分隔的条目名（'a.txt' + NUL）",
                Arrays.equals(rig.board.readMemory(BUF, 6), "a.txt\0".getBytes(StandardCharsets.UTF_8)),
                text(rig.board.readMemory(BUF, 6)) + "<NUL>");

        // ⑨ 配额信息
        post(rig, 10, OcAbi.FS_SPACE);
        // used = 目录 /home 的 FILE_COST + 文件 a.txt 的 (FILE_COST + 8)
        check("FS_SPACE：total=65536 / used=2*FILE_COST+8",
                result0(rig, 10) == 64 * 1024 && result1(rig, 10) == 2 * DiskFileSystem.FILE_COST + 8,
                "total=" + result0(rig, 10) + " used=" + result1(rig, 10));

        // ⑩ 错误路径必须带**ABI 错误码**（这是本轮补的最后一块 ABI）
        post(rig, 11, OcAbi.FS_OPEN, "/nope.txt", OcAbi.FS_MODE_R);
        check("不存在 + 读模式 ⇒ STATE=ERROR", state(rig, 11) == OcAbi.MB_STATE_ERROR, "ERROR");
        check("错误码 = ERR_NOT_FOUND(7)（固件据此区分该报错与该重试）",
                result0(rig, 11) == OcAbi.ERR_NOT_FOUND, "errCode=" + result0(rig, 11));

        // ⑪ rename（单缓冲 NUL 分隔两个名字）
        rig.board.writeMemory(BUF, "/home/a.txt\0/home/b.txt".getBytes(StandardCharsets.UTF_8));
        postWithBuf(rig, 12, OcAbi.FS_RENAME, "/home/a.txt\0/home/b.txt".length());
        check("FS_RENAME 返回 1 且宿主文件已改名",
                result0(rig, 12) == 1 && Files.isRegularFile(rootDir.resolve("home/b.txt")),
                "result0=" + result0(rig, 12));

        // ⑫ 多分区盘：命名空间把 /home 路由到 HOME 分区
        scenarioMultipartDisk(tmp);

        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        deleteRecursively(tmp);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 多分区盘场景（用户 2026-09-24："完整 os 可以支持通过分区来实现功能，对应现代系统"）。
     *
     * <p>盘上有 SYS + HOME 两个分区；固件写 {@code /home/...} ⇒ 经命名空间按前缀路由 ⇒
     * <b>落到 HOME 分区</b>，系统分区里既没有这个文件、也不会被写坏。</p>
     *
     * <p>走的是与固件**完全相同**的路径：{@code FS_*} 邮箱投递 → 核心组包 →
     * {@link FsCallDispatcher} → {@link VolumeNamespace} → 分区文件系统。
     * 固件侧那一半（{@code fs_open("/home/…")}）已在真机验证过
     * （{@code [FS] disk handle = 3} + 写读自检）。</p>
     */
    private static void scenarioMultipartDisk(Path tmp) throws Exception {
        System.out.println("  --- 多分区盘：/home 落到 HOME 分区 ---");
        final Path dir = tmp.resolve("cryptand/disks/w1/multipart");
        final long cap = 8L * 1024 * 1024;
        DiskFormatTool.format(dir, cap, 2L * 1024 * 1024, "FAT12", "SYS");
        DiskFormatTool.format(dir, 0, 2L * 1024 * 1024, "FAT12", "HOME");
        final byte[] os = Programs.readById("cryptand-os");
        try (CryptandFileSystem sysOnly = DiskFileSystems.open(dir, cap, "multipart", false)) {
            Programs.install(sysOnly, os);
        }

        final java.util.List<DiskVolumes.Volume> vols = DiskVolumes.volumes(dir, cap, "multipart", false);
        final VolumeNamespace ns = VolumeNamespace.of(vols);
        final Rig rig = build(new FsBus(new FsCallDispatcher(ns)));

        post(rig, 0, OcAbi.FS_MKDIR, "/home/user");
        check("多分区：FS_MKDIR /home/user 返回 1", result0(rig, 0) == 1, "result0=" + result0(rig, 0));

        post(rig, 1, OcAbi.FS_OPEN, "/home/user/hello.txt", OcAbi.FS_MODE_W);
        final int h = result0(rig, 1);
        check("多分区：FS_OPEN(w) /home/user/hello.txt 返回正句柄", h > 0, "handle=" + h);
        writeGuest(rig, "hello home");
        postWithBuf(rig, 2, OcAbi.FS_WRITE, "hello home".length(), h);
        check("多分区：FS_WRITE 返回 1", result0(rig, 2) == 1, "result0=" + result0(rig, 2));
        post(rig, 3, OcAbi.FS_CLOSE, h);

        // ★ 铁证：宿主侧从 **HOME 分区**读到它（不是系统分区、也不是盘根）
        final DiskVolumes.Volume home = vols.stream()
                .filter(v -> v.role() == DiskVolumes.Role.HOME).findFirst().orElse(null);
        check("多分区：/home 的写入落在 HOME 分区上",
                home != null && "hello home".equals(readText(home.fs(), "/user/hello.txt")),
                home == null ? "没有 HOME 卷" : (home.note() != null ? home.note() : "HOME 卷里没有这个文件"));
        final DiskVolumes.Volume sys = vols.stream()
                .filter(v -> v.role() == DiskVolumes.Role.SYSTEM).findFirst().orElse(null);
        check("多分区：系统分区里没有 /home 的痕迹（真隔离）",
                sys != null && !sys.fs().exists("/home/user/hello.txt"), "系统分区不该有这个文件");

        // 根仍然 = 系统分区
        post(rig, 4, OcAbi.FS_SIZE, Programs.BOOT_PATH);
        check("多分区：根 = 系统分区（/boot/system.bin 大小 = 固件长度）",
                result0(rig, 4) == os.length, "size=" + result0(rig, 4));
        ns.close();
    }

    private static String readText(CryptandFileSystem fs, String path) {
        if (!fs.exists(path)) {
            return "";
        }
        final int h = fs.open(path, FsMode.READ);
        final byte[] buf = new byte[(int) fs.getHandle(h).length()];
        final int n = fs.getHandle(h).read(buf);
        fs.getHandle(h).close();
        return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
    }

    // ==================== 装置 ====================

    private record Rig(OcArchitectureCore core, SocBoard board) {
    }

    /** 假组件总线：把 filesystem 组件（下标 1）的调用交给真分发器 */
    private record FsBus(FsCallDispatcher dispatcher) implements ComponentBus {

        @Override
        public Result invoke(Call call) {
            return dispatcher.invoke(call);
        }

        @Override
        public List<Entry> components() {
            return List.of(new Entry("addr-gpu", "gpu"), new Entry("addr-fs", "filesystem"));
        }
    }

    private static Rig build(ComponentBus bus) {
        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(0).enableM(true));
        final RegBankDevice regBank = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        final SocBoard board = SocBoard.builder(cpu)
                .ram(RAM_BASE, RAM_BYTES)
                .device(REG_BASE, regBank, OcAbi.DEVICE_NAME)
                .build();
        return new Rig(new OcArchitectureCore(board, regBank, bus), board);
    }

    /** 固件侧投递：填字段 → 置 STATE=REQUEST → 宿主认领并兑现 */
    private static void post(Rig rig, int channel, int methodId, Object... args) {
        postBuf(rig, channel, methodId, 0, args);
    }

    /**
     * 投递带缓冲区的调用。
     *
     * <p>约定：String 参数当"路径/文本"写进 BUF；数值参数走 ARG*；{@code bufLen > 0}
     * 时**总是**登记 BUF_ADDR/BUF_LEN —— FS_READ / FS_LIST 这类"出方向"调用
     * 只有长度、没有 String 参数（数据是宿主写回来的），漏登记就会用到上一次的残值。</p>
     */
    private static void postBuf(Rig rig, int channel, int methodId, int bufLen, Object... args) {
        final long base = OcAbi.mailboxChannel(channel);
        int argc = 0;
        int len = bufLen;
        for (final Object a : args) {
            if (a instanceof String s) {
                final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                rig.board.writeMemory(BUF, bytes);
                if (len <= 0) {
                    len = bytes.length;
                }
            } else if (a instanceof Number n) {
                rig.board.writeInt(base + OcAbi.MB_ARG0 + argc * 4, n.intValue());
                argc++;
            }
        }
        if (len > 0) {
            rig.board.writeInt(base + OcAbi.MB_BUF_ADDR, BUF);
            rig.board.writeInt(base + OcAbi.MB_BUF_LEN, len);
        }
        rig.board.writeInt(base + OcAbi.MB_COMPONENT, 1);      // filesystem
        rig.board.writeInt(base + OcAbi.MB_METHOD, methodId);
        rig.board.writeInt(base + OcAbi.MB_ARGC, argc);
        rig.board.writeInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_REQUEST);
        rig.core.pollMailbox();
        rig.core.drainCalls(200);
    }

    /** FS_WRITE 专用：把要写的字节放进 BUF（不作为路径） */
    private static void postWithBuf(Rig rig, int channel, int methodId, int bufLen, Object... args) {
        postBuf(rig, channel, methodId, bufLen, args);
    }

    private static void writeGuest(Rig rig, String data) {
        rig.board.writeMemory(BUF, data.getBytes(StandardCharsets.UTF_8));
    }

    private static int result0(Rig rig, int channel) {
        return rig.board.readInt(OcAbi.mailboxChannel(channel) + OcAbi.MB_RESULT0);
    }

    private static int result1(Rig rig, int channel) {
        return rig.board.readInt(OcAbi.mailboxChannel(channel) + OcAbi.MB_RESULT0 + 4);
    }

    private static int state(Rig rig, int channel) {
        return rig.board.readInt(OcAbi.mailboxChannel(channel) + OcAbi.MB_STATE);
    }

    private static String text(byte[] bytes) {
        int end = bytes.length;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                end = i;
                break;
            }
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
