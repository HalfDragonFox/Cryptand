package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

import com.hdf.cryptand.neoforge.CryptandNeoForge;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * native DLL 自主加载器（SableNativeLoader）。
 *
 * <p>【测试关键前提】正式替换 sable 时将从 build.gradle 移除官方
 * {@code dev.ryanhcode.sable:sable-neoforge} runtimeOnly 依赖，届时：
 * <ul>
 *   <li>f64/f32 原生 DLL 由本核心自主加载（不再依赖官方 jar 的 lz4 解压加载，也不再靠
 *       Rapier3DNativeRedirectMixin 重定向官方加载流程）；</li>
 *   <li>本 loader 从类路径资源复制未压缩 DLL 到运行目录并 System.load，并以配置
 *       enableSableRapier64 选择 f64 或 f32。</li>
 * </ul>
 *
 * <p>约定资源路径（与现有 f64 资源一致）：
 * <ul>
 *   <li>f64: {@code /assets/cryptand/sable_natives/f64/sable_rapier_{arch}_{os}.dll}</li>
 *   <li>f32: {@code /assets/cryptand/sable_natives/f32/sable_rapier_{arch}_{os}.dll}（预留，
 *       当前未打包；先落到 f64 或官方流程）</li>
 * </ul>
 */
public final class SableNativeLoader {
    private static final String RESOURCE_BASE = "assets/cryptand/sable_natives/";
    private static volatile boolean loaded = false;

    private SableNativeLoader() {
    }

    /**
     * 是否已加载 native。
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 加载所选精度（f64/f32）的 native DLL。幂等。
     *
     * @param useF64 true=f64 DLL；false=f32 DLL（未打包时回退 f64）
     * @return true=已成功加载（或已加载过）；false=失败（调用方回退纯 Java 仿真）
     */
    public static synchronized boolean load(boolean useF64) {
        if (loaded) return true;
        try {
            String arch = System.getProperty("os.arch", "x86_64").toLowerCase(Locale.ROOT);
            if (arch.equals("amd64")) arch = "x86_64";
            String os = System.getProperty("os.name", "windows").toLowerCase(Locale.ROOT);
            String osKey;
            if (os.contains("win")) osKey = "windows";
            else if (os.contains("mac")) osKey = "macos";
            else osKey = "linux";

            String dir = useF64 ? "f64" : "f32";
            String resource = RESOURCE_BASE + dir + "/sable_rapier_" + arch + "_" + osKey + ".dll";

            try (InputStream in = SableNativeLoader.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (in == null) {
                    // f32 资源未打包 → 回退 f64
                    if (!useF64) return load(true);
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[CryptandSable] native resource missing: {}", resource);
                    return false;
                }
                Path dirPath = Paths.get(".", "cryptand", "natives").toAbsolutePath();
                Files.createDirectories(dirPath);
                Path out = dirPath.resolve("sable_rapier_" + arch + "_" + osKey + ".dll");
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                System.load(out.toAbsolutePath().toString());
                loaded = true;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] loaded native {} from {}", dir, out);
                return true;
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] native load failed: {}", t);
            return false;
        }
    }
}