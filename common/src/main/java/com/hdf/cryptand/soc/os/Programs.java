package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.FsMode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ===== 程序库（common，纯 Java 零 MC）=====
 *
 * <p>用户 2026-09-18 定案："OS 等代码全部作为库下放到 common 以及编译为 lib 库放 res 进行调用，
 * 优先 common 跑测试成功" —— 所以固件编译产物（Boot / Cryptand OS / UI OS）作为
 * <b>classpath 资源</b>放在 common：{@code /assets/cryptand/soc/}。</p>
 *
 * <p>为什么放 common 而不是 neoforge：classpath 路径两者都能读到（真机不变），
 * 但**只有 common 能被离线沙盒读到** —— 这样"测试看到的字节"与"游戏里跑起来的字节"
 * 是同一份，而不是两份各自编译、各自同步。</p>
 *
 * <h3>盘上的约定</h3>
 * <p>引导程序放在每一块盘的 {@code /boot/system.bin}：Boot（BIOS）逐盘按这个路径找，
 * 找到就刷进 guest ROM。这样"盘里装的是什么"由盘自己决定，而不是由盘的档位查表决定 ——
 * 用户要的"软盘加载程序放入机箱"就是这个意思，也是"不关机换盘调试"的前提。</p>
 */
public final class Programs {

    /** 固件资源目录（与 MC 侧 {@code SocResources.BASE} 一致：同一条 classpath 路径） */
    public static final String DIR = "/assets/cryptand/soc/";

    /** 盘上的引导程序路径（第二级引导：由盘上的引导记录通过 ABI 交回宿主读它） */
    public static final String BOOT_PATH = "/boot/system.bin";

    /** 引导记录（BIOS 判据只认它）在固件资源里的 id：{@code cryptand-boot.bin} */
    public static final String BOOTLOADER_ID = "boot";

    /**
     * 盘上 bootloader 的位置（分层：BIOS → bootloader → 系统）。
     *
     * <p>体积约定（用户 2026-09-24）：装 mini OS 的盘，bootloader **< 4KB**；
     * 装完整 OS 的盘随意。
     */
    public static final String LOADER_PATH = "/boot/loader.bin";

    /** 引导程序所在目录 */
    public static final String BOOT_DIR = "/boot";

    /** 目录项：id 给人/AI 用，fileName 是资源文件名 */
    public record Program(String id, String displayName, String fileName) {
    }

    /** 全部已知程序（含未编译的；{@link #available()} 才是"现在能装的"） */
    private static final List<Program> CATALOG = List.of(
            new Program("boot", "Cryptand Boot（BIOS：从盘里引导系统）", "cryptand-boot.bin"),
            new Program("cryptand-os", "Cryptand OS（FreeRTOS + 命令行）", "cryptand-os.bin"),
            new Program("cryptand-ui-os", "Cryptand UI OS（FreeRTOS + LVGL）", "cryptand-ui-os.bin"),
    // PE = 装机环境（用户 2026-09-26："系统软盘默认带一个完整镜像 + PE，进入则进入 Cryptand OS PE"）。
    // 它自己不是给人用的系统，而是**装系统用的那个系统**：分盘 / 格式化 / 安装 / 一键安装。
    new Program("pe", "Cryptand OS PE（命令行安装环境）", "cryptand-pe.bin"),
            // ⚠ 不再有 "linux" 程序项（用户 2026-09-25："不要写 linux 挡位之类的"）：
            //   档位与程序目录里都不绑定具体系统，第 4 个系统只是"以后要跑什么就跑什么"的扩展位。
            new Program("mini-os", "Cryptand mini OS（极简：无调度器 / 无 shell）", "cryptand-mini-os.bin"));

    private Programs() {
    }

    public static List<Program> catalog() {
        return CATALOG;
    }

    /** 实际已编译（资源存在且非空）的程序 —— 程序加载器只应列出这些 */
    public static List<Program> available() {
        final List<Program> out = new ArrayList<>();
        for (final Program p : CATALOG) {
            if (read(p.fileName()).length > 0) {
                out.add(p);
            }
        }
        return out;
    }

    /** 按 id 找程序；没有 ⇒ null */
    public static Program byId(String id) {
        for (final Program p : CATALOG) {
            if (p.id().equalsIgnoreCase(id) || p.fileName().equalsIgnoreCase(id)) {
                return p;
            }
        }
        return null;
    }

    /** 读程序字节；缺失 ⇒ 空数组（调用方按"缺失"处理，不要抛异常打断引导流程） */
    public static byte[] read(String fileName) {
        try (InputStream in = Programs.class.getResourceAsStream(DIR + fileName)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static byte[] readById(String id) {
        final Program p = byId(id);
        return p == null ? new byte[0] : read(p.fileName());
    }

    /**
     * 把程序装进一块盘（写到 {@link #BOOT_PATH}），并**回读校验**。
     *
     * <p>为什么不省掉校验：程序加载器是给人用的写盘工具，写完不回读就等于把"写没写对"
     * 推给用户 —— 而错误要到下次开机才暴露，代价是整轮调试。</p>
     *
     * @throws IllegalArgumentException 程序为空
     * @throws IllegalStateException    回读内容与写入不一致（写盘链路有问题，必须立刻报）
     */
    public static void install(CryptandFileSystem fs, byte[] program) {
        installAt(fs, program, BOOT_PATH);
        // 引导记录与系统**一起装**（用户 2026-09-25："只认 bootloader 保持现实一致"）。
        // ⚠ 为什么必须在这里：BIOS 的判据只有"盘上有 /boot/loader.bin"一条，
        //   所以"只写了 system"的盘是**起不来**的 —— 而它从外面看一切正常
        //   （安装成功、文件在、回读校验也过）。这种半个状态最坑人，所以不留。
        installLoader(fs, readById(BOOTLOADER_ID));
    }

    /**
     * 把 **bootloader** 装进盘的 {@link #LOADER_PATH}。
     *
     * <p>分层（用户 2026-09-24 定案）：EEPROM 里的 Cryptand BIOS 找盘 → 载入盘上的 bootloader
     * → bootloader 载入系统。所以一块可引导的盘需要<b>两个</b>文件：
     * {@code /boot/loader.bin}（第二级引导）与 {@code /boot/system.bin}（系统本身）。</p>
     */
    public static void installLoader(CryptandFileSystem fs, byte[] program) {
        installAt(fs, program, LOADER_PATH);
    }

    /** 系统软盘上"待安装的完整镜像"放在这里（PE 从它拷贝到目标盘） */
    public static final String IMAGES_DIR = "/images";

    /** PE（装机环境）的程序 id */
    public static final String PE_ID = "pe";

    /**
     * 把一块**系统软盘**做成安装介质（用户 2026-09-26 定案）。
     *
     * <p>与 {@link #install} 的区别只有一处，但意义完全不同：</p>
     * <ul>
     *   <li>{@link #install}：盘上的 {@code /boot/system.bin} 就是**要跑的系统**；</li>
     *   <li>本方法：盘上的 {@code /boot/system.bin} 是 **PE（装机环境）**，而**完整镜像**
     *       作为文件放在 {@code /images/<id>.bin} —— 开机先进 PE，由 PE 把镜像**拷贝安装**
     *       到目标硬盘（用户原话："里面可以放入另一个完整系统镜像，完整镜像只需要 PE 运行时
     *       拷贝安装到具体硬盘即可"）。</li>
     * </ul>
     *
     * <p>所以"系统软盘"与"系统盘"从此是两种介质：前者是安装盘（可反复装），后者是装好的盘。</p>
     */
    public static void installInstallerMedia(CryptandFileSystem fs, String imageId) {
        installAt(fs, readById(PE_ID), BOOT_PATH);          // 引导进 PE，不是进系统
        // ★ 同一块盘还要能引导**Lua 架构**（用户 2026-09-26："eeprom 既支持原版 oc 的 lua 兼容启动，
        //   也支持 fatfs 盘找 bootloader 启动"）。Lua 架构下 OC 的 machine.lua 会去读 EEPROM 的
        //   eeprom 组件（我们的 bios.lua），而 bios.lua 找的就是盘根的 /init.lua ——
        //   所以系统软盘必须同时带一份 Lua 入口，否则换 OC 原版 CPU 后就只剩"没有 init.lua"。
        final byte[] luaInit = read("init.lua");
        if (luaInit.length > 0) {
            installAt(fs, luaInit, "/init.lua");
        }
        installLoader(fs, readById(BOOTLOADER_ID));         // 三层引导的第二层（BIOS 认它）
        final byte[] image = readById(imageId);
        if (image.length > 0) {
            installAt(fs, image, IMAGES_DIR + "/" + imageId + ".bin");
        }
    }

    /** 写程序到盘的任意约定位置（install / installLoader / installInstallerMedia 共用，避免几条路各写一遍校验） */
    private static void installAt(CryptandFileSystem fs, byte[] program, String path) {
        if (program == null || program.length == 0) {
            throw new IllegalArgumentException("empty program");
        }
        // 建父目录（/boot 与 /images 都是这样来的；写 /images/x.bin 前必须先把 /images 建出来）
        final int slash = path.lastIndexOf('/');
        if (slash > 0) {
            final String dir = path.substring(0, slash);
            if (!fs.exists(dir)) {
                fs.makeDirectory(dir);
            }
        }
        if (!fs.exists(BOOT_DIR)) {
            fs.makeDirectory(BOOT_DIR);
        }
        final int h = fs.open(path, FsMode.WRITE);
        fs.getHandle(h).write(program);
        fs.getHandle(h).close();
        final byte[] back = installedAt(fs, path);
        if (!Arrays.equals(back, program)) {
            throw new IllegalStateException("program verify failed at " + path + ": wrote "
                    + program.length + " bytes, read back " + back.length);
        }
    }

    /**
     * 读盘上任意约定位置的程序（没装 ⇒ 空数组）。
     *
     * <p>为什么提成 public（2026-09-27）：引导决策里除 {@link #BOOT_PATH}/{@link #LOADER_PATH}
     * 之外还有**Lua 目标的入口**（{@code /init.lua} 等，见
     * {@link BootPlan#LUA_INIT_PATHS}）—— 三条路径的读法必须一模一样（越界/读到半个文件的口径
     * 是同一个），所以只留这一份实现，{@link #installed}/{@link #installedLoader} 与 Lua 入口都调它。</p>
     */
    public static byte[] installedAt(CryptandFileSystem fs, String path) {
        if (!fs.exists(path)) {
            return new byte[0];
        }
        final int h = fs.open(path, FsMode.READ);
        final long len = fs.getHandle(h).length();
        final byte[] out = new byte[(int) len];
        int off = 0;
        while (off < out.length) {
            final byte[] chunk = new byte[Math.min(4096, out.length - off)];
            final int n = fs.getHandle(h).read(chunk);
            if (n <= 0) {
                break;
            }
            System.arraycopy(chunk, 0, out, off, n);
            off += n;
        }
        fs.getHandle(h).close();
        return off == out.length ? out : Arrays.copyOf(out, off);
    }

    /** 盘上 bootloader 的字节（没装 ⇒ 空数组） */
    public static byte[] installedLoader(CryptandFileSystem fs) {
        return installedAt(fs, LOADER_PATH);
    }

    /**
     * **强制清空后**装入程序（用户定案："让指定 id 的软盘强制清空加载"）。
     *
     * <p>顺序刻意是"先查空间 → 再清空 → 再写 → 再回读"：
     * 万一空间不够，盘上**原有内容不会被毁掉** —— 用户输错一个容量不该丢掉一张盘。</p>
     *
     * @return 被清掉的条目数（用于回显"清空了 N 项"）
     * @throws com.hdf.cryptand.soc.fs.FsException {@code ERR_NO_SPACE}：程序比整块盘还大
     */
    public static int installCleared(CryptandFileSystem fs, byte[] program) {
        if (program == null || program.length == 0) {
            throw new IllegalArgumentException("empty program");
        }
        // 空间判定按"系统 + 引导记录"两个文件来算：只算 program 的话，
        // 会在写完 system、正要写引导记录时才发现放不下 —— 而那时盘已经被清空了。
        final int loaderBytes = readById(BOOTLOADER_ID).length;
        if (program.length + loaderBytes > fs.spaceTotal()) {
            throw new com.hdf.cryptand.soc.fs.FsException(
                    com.hdf.cryptand.soc.oc.OcAbi.ERR_NO_SPACE,
                    "program " + program.length + " bytes + bootloader " + loaderBytes
                            + " bytes > disk capacity " + fs.spaceTotal() + " bytes");
        }
        final int removed = com.hdf.cryptand.soc.fs.DiskWipe.wipe(fs);
        install(fs, program);
        return removed;
    }

    /** 读盘上的引导程序（没装 ⇒ 空数组） */
    public static byte[] installed(CryptandFileSystem fs) {
        return installedAt(fs, BOOT_PATH);
    }
}
