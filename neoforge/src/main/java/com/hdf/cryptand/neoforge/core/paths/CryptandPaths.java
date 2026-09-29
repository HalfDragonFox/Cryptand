/**
 * ===== Cryptand 运行目录布局（2026-09-13） =====
 *
 * 统一管理游戏目录下的 `cryptand/` 工作区（用户要求："游戏运行时在 .minecraft 中创建
 * cryptand 文件夹，里面创建 libs；库加载优先用这个文件夹，然后才用资源包自带的"，
 * 并"包含一些其他通用内容，比如临时缓存（temp 文件夹）"）：
 *
 * <pre>
 *   &lt;gameDir&gt;/cryptand/
 *   ├── libs/                     玩家/整合包可替换的本地库（SDL3 等）——【最高优先级】
 *   │   └── natives/&lt;platform&gt;/   mod 内置库解压目录（次优先；不覆盖玩家文件）
 *   ├── temp/                     通用临时缓存（子包可放解压/导出中间文件）
 *   └── ...（后续通用子目录在此登记）
 * </pre>
 *
 * 根目录可用系统属性 `-Dcryptand.home=&lt;dir&gt;` 覆盖（测试/多实例）。
 * 全部方法幂等、线程安全；目录创建失败不抛异常（返回路径，调用方自行容错）。
 */

package com.hdf.cryptand.neoforge.core.paths;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CryptandPaths {

    /** 覆盖根目录的系统属性名 */
    public static final String HOME_PROPERTY = "cryptand.home";
    /** 工作区目录名 */
    public static final String DIR_NAME = "cryptand";

    private static volatile Path cachedRoot;

    private CryptandPaths() {
    }

    /** `<gameDir>/cryptand`（或 `-Dcryptand.home`） */
    public static Path root() {
        Path r = cachedRoot;
        if (r != null) {
            return r;
        }
        String override = System.getProperty(HOME_PROPERTY);
        r = (override != null && !override.isBlank())
                ? Path.of(override)
                : Path.of(System.getProperty("user.dir", "."), DIR_NAME);
        cachedRoot = r;
        return r;
    }

    /** 本地库目录（玩家可替换；优先级高于 mod 内置） */
    public static Path libs() {
        return root().resolve("libs");
    }

    /** mod 内置库解压目录（按平台分子目录） */
    public static Path natives(final String platform) {
        return libs().resolve("natives").resolve(platform);
    }

    /** 通用临时缓存目录 */
    public static Path temp() {
        return root().resolve("temp");
    }

    /** 确保基础布局存在（root / libs / temp）；返回 root */
    public static Path ensureLayout() {
        ensure(root());
        ensure(libs());
        ensure(temp());
        return root();
    }

    /** 创建目录（幂等；失败静默） */
    public static Path ensure(final Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Throwable ignored) {
        }
        return dir;
    }
}
