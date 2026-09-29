package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * ===== 盘文件系统自测（离线，不启动 MC，2026-09-18）=====
 *
 * <p>覆盖 {@code cryptand-fs-design.md §7 阶段 1} 的五条验证要求：
 * 路径解析（含 jail / 越界 / 非法字符）、CRUD、句柄（EOF/独占/超限/关闭后）、
 * 配额（三处检查点 + used 回落）、只读盘；外加 Mode 映射。</p>
 *
 * <p>跑法：{@code gradlew :common:runFsTest}</p>
 */
public final class FsSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cryptand FileSystem Self Test (common/soc/fs, 纯 Java 无 MC) ===");
        final Path tmp = Files.createTempDirectory("cryptand-fs-test");
        try {
            pathTests(tmp);
            crudTests(tmp);
            handleTests(tmp);
            quotaTests(tmp);
            readonlyTests(tmp);
            modeTests();
            lifecycleTests(tmp);
        } finally {
            deleteRecursively(tmp);
        }
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 路径解析 ====================

    private static void pathTests(Path tmp) {
        check("路径：消解 . 与 ..", DiskMount.normalizeGuestPath("/a/./b/../c").equals("a/c"),
                DiskMount.normalizeGuestPath("/a/./b/../c"));
        check("路径：重复斜杠与尾斜杠", DiskMount.normalizeGuestPath("/a//b/").equals("a/b"),
                DiskMount.normalizeGuestPath("/a//b/"));
        check("路径：根归一成空串", DiskMount.normalizeGuestPath("/").isEmpty()
                        && DiskMount.normalizeGuestPath(".").isEmpty()
                        && DiskMount.normalizeGuestPath("").isEmpty(),
                "\"/\" -> \"" + DiskMount.normalizeGuestPath("/") + "\"");
        check("路径：越出根被拒（/../x）",
                errCode(() -> DiskMount.normalizeGuestPath("/../x"), OcAbi.ERR_INVALID_PATH), "ERR_INVALID_PATH");
        check("路径：越出根被拒（/a/../../b）",
                errCode(() -> DiskMount.normalizeGuestPath("/a/../../b"), OcAbi.ERR_INVALID_PATH), "ERR_INVALID_PATH");
        check("路径：非法字符被拒（冒号）",
                errCode(() -> DiskMount.normalizeGuestPath("/a:b"), OcAbi.ERR_INVALID_PATH), "ERR_INVALID_PATH");
        check("路径：非法字符被拒（竖线）",
                errCode(() -> DiskMount.normalizeGuestPath("/a|b"), OcAbi.ERR_INVALID_PATH), "ERR_INVALID_PATH");

        final DiskMount m = new DiskMount(tmp.resolve("disks/w1/addr-1"), "addr-1");
        check("路径：jail 后仍在盘根之下",
                m.resolve("/a/b").startsWith(m.root()) && m.resolve("/").equals(m.root()),
                m.resolve("/a/b").toString());
        check("路径：相对路径也当绝对路径处理（照 OC 的绝对路径约定）",
                m.resolve("a/b").equals(m.root().resolve("a/b")), m.resolve("a/b").toString());
        check("挂载布局：cryptand/disks/<worldId>/<address>",
                DiskMount.rootDir(tmp, "w1", "addr-2").toString().replace('\\', '/')
                        .endsWith("cryptand/disks/w1/addr-2"),
                DiskMount.rootDir(tmp, "w1", "addr-2").toString());
    }

    // ==================== 目录与文件 CRUD ====================

    private static void crudTests(Path tmp) throws Exception {
        final DiskMount m = new DiskMount(tmp.resolve("disks/w1/crud"), "crud");
        final DiskFileSystem fs = new DiskFileSystem(m, 256 * 1024, false);

        check("CRUD：makeDirectory 一层", fs.makeDirectory("/home"), "true");
        check("CRUD：父目录存在时可再建一层", fs.makeDirectory("/home/sub"), "true");
        check("CRUD：父目录不存在时只建一层 → false（照 OC）",
                !fs.makeDirectory("/nope/x"), "false");
        check("CRUD：makeDirectories 递归创建", fs.makeDirectories("/a/b/c") && fs.isDirectory("/a/b/c"), "true");
        check("CRUD：exists / isDirectory", fs.exists("/home") && fs.isDirectory("/home")
                && !fs.isDirectory("/home/none"), "true");
        check("CRUD：isDirectory 对根为 true、对不存在为 false",
                fs.isDirectory("/") && !fs.isDirectory("/nope"), "true/false");

        final int h = fs.open("/home/hello.txt", FsMode.WRITE);
        fs.getHandle(h).write("hello".getBytes(StandardCharsets.UTF_8));
        fs.getHandle(h).close();
        check("CRUD：写入后 size 为 5 字节", fs.size("/home/hello.txt") == 5, "size=" + fs.size("/home/hello.txt"));

        final String[] list = fs.list("/home");
        check("CRUD：list 返回子项名（子目录带尾斜杠）",
                list != null && list.length == 2 && list[0].equals("hello.txt") && list[1].equals("sub/"),
                list == null ? "null" : String.join(",", list));
        check("CRUD：list 对不存在的路径返回 null（不是空数组）", fs.list("/nope") == null, "null");
        check("CRUD：空目录 list 是空数组", fs.list("/a/b/c") != null && fs.list("/a/b/c").length == 0, "[]");
        check("CRUD：目录 size 为 0", fs.size("/home") == 0, "size=" + fs.size("/home"));
        check("CRUD：lastModified 非 0 且查询不抛", fs.lastModified("/home/hello.txt") > 0, ">0");
        check("CRUD：非法路径的查询返回 0/false（绝不抛）",
                fs.size("/..") == 0 && !fs.exists("/..") && fs.lastModified("/..") == 0, "0/false");

        check("CRUD：rename 成功", fs.rename("/home/hello.txt", "/home/hi.txt")
                && fs.exists("/home/hi.txt") && !fs.exists("/home/hello.txt"), "true");
        check("CRUD：rename 源不存在 ⇒ ERR_NOT_FOUND（照 OC 抛异常）",
                errCode(() -> fs.rename("/nope.txt", "/x.txt"), OcAbi.ERR_NOT_FOUND), "ERR_NOT_FOUND");
        check("CRUD：非空目录不可删", !fs.delete("/home"), "false");
        check("CRUD：删空目录成功", fs.delete("/home/sub"), "true");
        check("CRUD：删文件成功", fs.delete("/home/hi.txt"), "true");
    }

    // ==================== 句柄 ====================

    private static void handleTests(Path tmp) throws Exception {
        final DiskMount m = new DiskMount(tmp.resolve("disks/w1/handles"), "handles");
        final DiskFileSystem fs = new DiskFileSystem(m, 256 * 1024, false);
        final byte[] ten = "0123456789".getBytes(StandardCharsets.UTF_8);

        final int h = fs.open("/f.bin", FsMode.READ_WRITE_TRUNCATE);
        final CryptandFileSystem.Handle fh = fs.getHandle(h);
        check("句柄：open 返回正数随机 id", h > 0, "id=" + h);
        fh.write(ten);
        check("句柄：write 后 position=10 且 length=10", fh.position() == 10 && fh.length() == 10,
                "pos=" + fh.position() + " len=" + fh.length());
        check("句柄：seek(0) 返回新位置", fh.seek(0) == 0, "0");
        final byte[] four = new byte[4];
        check("句柄：read 填满数组并返回字节数", fh.read(four) == 4
                && new String(four, StandardCharsets.UTF_8).equals("0123"), new String(four, StandardCharsets.UTF_8));
        fh.seek(0);
        final byte[] all = new byte[64];
        check("句柄：读到文件末尾返回实际长度", fh.read(all) == 10, "10");
        check("句柄：EOF 再读返回 -1", fh.read(all) == -1, "-1");
        check("句柄：seek 负数 ⇒ ERR_BAD_MODE",
                errCode(() -> fh.seek(-1), OcAbi.ERR_BAD_MODE), "ERR_BAD_MODE");
        fh.close();
        check("句柄：关闭后 read ⇒ ERR_BAD_HANDLE",
                errCode(() -> fh.read(all), OcAbi.ERR_BAD_HANDLE), "ERR_BAD_HANDLE");
        check("句柄：关闭后 getHandle 返回 null（绝不抛）", fs.getHandle(h) == null, "null");

        final int w1 = fs.open("/g.bin", FsMode.WRITE);
        check("句柄：写句柄独占（再开一个写句柄 ⇒ ERR_NOT_FOUND）",
                errCode(() -> fs.open("/g.bin", FsMode.WRITE), OcAbi.ERR_NOT_FOUND), "ERR_NOT_FOUND");
        check("句柄：打开中的文件不可删", !fs.delete("/g.bin"), "false");
        fs.getHandle(w1).close();
        check("句柄：关闭后可以删", fs.delete("/g.bin"), "true");

        final int r = fs.open("/f.bin", FsMode.READ);
        check("句柄：只读句柄 write ⇒ ERR_BAD_HANDLE",
                errCode(() -> fs.getHandle(r).write(ten), OcAbi.ERR_BAD_HANDLE), "ERR_BAD_HANDLE");
        fs.getHandle(r).close();
        fs.makeDirectory("/dir");
        check("句柄：目录不能打开（任何模式都不行，照 OC）",
                errCode(() -> fs.open("/dir", FsMode.READ), OcAbi.ERR_NOT_FOUND), "ERR_NOT_FOUND");
        check("句柄：READ 要求文件已存在", errCode(() -> fs.open("/nope.bin", FsMode.READ),
                OcAbi.ERR_NOT_FOUND), "ERR_NOT_FOUND");

        // 句柄上限：16 个
        final int[] ids = new int[DiskFileSystem.MAX_HANDLES];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = fs.open("/limit" + i + ".bin", FsMode.WRITE);
        }
        check("句柄：上限 " + DiskFileSystem.MAX_HANDLES + " 个，第 " + (DiskFileSystem.MAX_HANDLES + 1)
                        + " 个 ⇒ ERR_TOO_MANY_HANDLES",
                errCode(() -> fs.open("/overflow.bin", FsMode.WRITE), OcAbi.ERR_TOO_MANY_HANDLES),
                "ERR_TOO_MANY_HANDLES");
        fs.close();
        check("句柄：close() 关掉全部（之后句柄查不到）", fs.getHandle(ids[0]) == null, "null");
    }

    // ==================== 配额 ====================

    private static void quotaTests(Path tmp) throws Exception {
        final DiskMount m = new DiskMount(tmp.resolve("disks/w1/quota"), "quota");
        // 容量 = 两个条目的固定开销 + 10 字节
        final long capacity = 2 * DiskFileSystem.FILE_COST + 10;
        final DiskFileSystem fs = new DiskFileSystem(m, capacity, false);

        check("配额：spaceTotal = 配置容量", fs.spaceTotal() == capacity, "total=" + fs.spaceTotal());
        final int a = fs.open("/a", FsMode.WRITE);
        fs.getHandle(a).close();
        final int b = fs.open("/b", FsMode.WRITE);
        check("配额：两个空文件刚好占满（2×FILE_COST）",
                fs.spaceUsed() == 2 * DiskFileSystem.FILE_COST, "used=" + fs.spaceUsed());
        fs.getHandle(b).write(new byte[10]);
        check("配额：写满（used = capacity）", fs.spaceUsed() == capacity, "used=" + fs.spaceUsed());
        check("配额：再写 1 字节 ⇒ ERR_NO_SPACE",
                errCode(() -> fs.getHandle(b).write(new byte[1]), OcAbi.ERR_NO_SPACE), "ERR_NO_SPACE");
        fs.getHandle(b).close();
        check("配额：超限时文件没被写坏（仍是 10 字节）", fs.size("/b") == 10, "size=" + fs.size("/b"));

        check("配额：删掉一个文件后 used 回落",
                fs.delete("/a") && fs.spaceUsed() == DiskFileSystem.FILE_COST + 10,
                "used=" + fs.spaceUsed());
        final int c = fs.open("/c", FsMode.WRITE);
        check("配额：回落之后又能新建", c > 0, "id=" + c);
        fs.getHandle(c).close();
        check("配额：建目录也占 FILE_COST",
                fs.delete("/c") && fs.makeDirectory("/d") && fs.spaceUsed() == capacity,
                "used=" + fs.spaceUsed());

        // 腾出空间（删掉那个 10 字节文件），再验"truncate 打开 = 释放空间"这条路
        check("配额：删文件后腾出空间", fs.delete("/b"), "true");
        final int t = fs.open("/t.bin", FsMode.READ_WRITE_TRUNCATE);
        fs.getHandle(t).write(new byte[3]);
        check("配额：truncate 打开并写入 3 字节",
                fs.getHandle(t).length() == 3, "len=" + fs.getHandle(t).length());
        fs.getHandle(t).close();
        check("配额：used 自洽（目录 512 + 文件 512 + 3）",
                fs.spaceUsed() == 2 * DiskFileSystem.FILE_COST + 3, "used=" + fs.spaceUsed());
    }

    // ==================== 只读盘 ====================

    private static void readonlyTests(Path tmp) throws Exception {
        final DiskMount m = new DiskMount(tmp.resolve("disks/w1/ro"), "ro");
        final DiskFileSystem rw = new DiskFileSystem(m, 64 * 1024, false);
        final int h = rw.open("/readme.txt", FsMode.WRITE);
        rw.getHandle(h).write("sealed".getBytes(StandardCharsets.UTF_8));
        rw.getHandle(h).close();

        final DiskFileSystem ro = new DiskFileSystem(m, 64 * 1024, true);
        check("只读：isReadOnly = true", ro.isReadOnly(), "true");
        check("只读：spaceTotal 返回 0（照 OC）", ro.spaceTotal() == 0, "0");
        check("只读：spaceUsed 仍能算出真实占用", ro.spaceUsed() > 0, "used=" + ro.spaceUsed());
        check("只读：读文件正常", ro.size("/readme.txt") == 6, "size=" + ro.size("/readme.txt"));
        check("只读：makeDirectory ⇒ false", !ro.makeDirectory("/x"), "false");
        check("只读：delete ⇒ false", !ro.delete("/readme.txt"), "false");
        check("只读：rename ⇒ ERR_READ_ONLY",
                errCode(() -> ro.rename("/readme.txt", "/x.txt"), OcAbi.ERR_READ_ONLY), "ERR_READ_ONLY");
        check("只读：写模式 open ⇒ ERR_READ_ONLY",
                errCode(() -> ro.open("/y.txt", FsMode.WRITE), OcAbi.ERR_READ_ONLY), "ERR_READ_ONLY");
        final int r = ro.open("/readme.txt", FsMode.READ);
        check("只读：读模式 open 正常", r > 0 && ro.getHandle(r).length() == 6, "len=" + ro.getHandle(r).length());
        ro.close();
    }

    // ==================== Mode 映射 ====================

    private static void modeTests() {
        check("Mode：字符串映射照 OC（r+/w+/a+ 一家）",
                FsMode.fromString("r+") == FsMode.READ_WRITE
                        && FsMode.fromString("r+b") == FsMode.READ_WRITE
                        && FsMode.fromString("w+") == FsMode.READ_WRITE_TRUNCATE
                        && FsMode.fromString("a+") == FsMode.READ_APPEND
                        && FsMode.fromString("rb") == FsMode.READ, "ok");
        check("Mode：ABI id 映射（0..5）", FsMode.fromId(0) == FsMode.READ
                && FsMode.fromId(1) == FsMode.WRITE && FsMode.fromId(2) == FsMode.APPEND
                && FsMode.fromId(3) == FsMode.READ_WRITE && FsMode.fromId(4) == FsMode.READ_WRITE_TRUNCATE
                && FsMode.fromId(5) == FsMode.READ_APPEND, "ok");
        check("Mode：非法 id ⇒ ERR_BAD_MODE",
                errCode(() -> FsMode.fromId(9), OcAbi.ERR_BAD_MODE), "ERR_BAD_MODE");
        check("Mode：非法字符串 ⇒ ERR_BAD_MODE",
                errCode(() -> FsMode.fromString("q"), OcAbi.ERR_BAD_MODE), "ERR_BAD_MODE");
        check("Mode：谓词与 OC 表一致（Write 截断、Append 追尾、Read 要求已存在）",
                FsMode.WRITE.truncate() && FsMode.APPEND.append() && !FsMode.APPEND.truncate()
                        && FsMode.READ.requiresExisting() && !FsMode.WRITE.requiresExisting()
                        && FsMode.READ_WRITE.requiresExisting() && !FsMode.READ_WRITE.truncate(), "ok");
    }

    // ==================== 生命周期 ====================

    /**
     * 实例的生命周期契约：**关闭 = 永久不可用，而且必须能被"缓存方"看出来**。
     *
     * <p>为什么单列一组：盘实例是**共享**的（一块盘 = 一个实例，平台侧按盘地址缓存），
     * "谁关掉了它"与"谁还拿着它"是两个独立事实 —— 缓存必须能问出 isOpen()，
     * 否则它会把一个死实例继续发给新调用方（2026-09-26 真机：
     * {@code disk … cannot be mounted: FsException[bad handle] filesystem already closed}）。</p>
     */
    private static void lifecycleTests(Path tmp) {
        final DiskMount m = new DiskMount(tmp.resolve("disks/w1/life"), "life");
        final DiskFileSystem fs = new DiskFileSystem(m, 64 * 1024, false);
        check("生命周期：关闭前 isOpen = true", fs.isOpen(), "true");
        fs.makeDirectory("/d");
        final int h = fs.open("/d/f.txt", FsMode.WRITE);
        fs.getHandle(h).write("x".getBytes(StandardCharsets.UTF_8));
        fs.getHandle(h).close();
        fs.close();
        check("生命周期：关闭后 isOpen = false", !fs.isOpen(), "false");
        check("生命周期：关闭后再 open ⇒ ERR_BAD_HANDLE",
                errCode(() -> fs.open("/new.txt", FsMode.WRITE), OcAbi.ERR_BAD_HANDLE), "ERR_BAD_HANDLE");
    }

    // ==================== 工具 ====================

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }

    /** 断言某个操作抛出的 FsException 错误码 */
    private static boolean errCode(Runnable action, int expected) {
        try {
            action.run();
            return false;
        } catch (FsException e) {
            return e.errCode() == expected;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 测试目录清理失败不影响结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
