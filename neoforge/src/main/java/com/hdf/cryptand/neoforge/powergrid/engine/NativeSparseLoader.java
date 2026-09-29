package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.circuitsimulation.solver.NativeDense;
import com.hdf.cryptand.circuitsimulation.solver.NativeSparse;
import com.hdf.cryptand.math.NativeMath;
import com.hdf.cryptand.neoforge.CryptandNeoForge;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * SuperLU 稀疏求解 DLL 加载器（2026-08-11；2026-08-30 统一底层接口）。
 *
 * 从 mod 资源 assets/cryptand/native/libpowergridNative7.dll 提取到本地临时
 * 目录并 System.load。成功 → 同时启用：
 *   - {@link NativeMath}（统一门面：稠密 zgesv/稀疏 zgssv/BLAS gemm/axpy/dot/nrm2）
 *   - {@link NativeSparse} / {@link NativeDense}（旧薄代理保持一致状态）
 * 失败/缺失 → 全部回退 Java 实现（NativeMath 自动转纯 Java，结果一致）。
 *
 * 幂等：只尝试一次。在 PhasorEngine.init（服务端首个求解前）调用。
 */
public final class NativeSparseLoader {

    private static volatile boolean attempted;

    private NativeSparseLoader() {}

    /** 提取并加载 DLL；成功则启用稀疏求解。幂等（只尝试一次）。 */
    public static synchronized void load() {
        if (attempted) return;
        attempted = true;
        try {
            String libName = "libpowergridNative7.dll";
            Path nativeDir = Paths.get(".pg-native");
            Files.createDirectories(nativeDir);
            Path libPath = nativeDir.resolve(libName);
            // ⚠ 2026-08-14 修复：DLL 已存在 → 不覆盖。PowerGrid 原版启动时已把
            // 同名 DLL 提取到 .pg-native 并 System.load（句柄被持有）；此处
            // Files.copy(REPLACE_EXISTING) 会先 deleteIfExists → 被占用时
            // AccessDenied（实证日志）。已存在直接加载；缺失才从资源复制。
            if (!Files.exists(libPath)) {
                ClassLoader cl = NativeSparseLoader.class.getClassLoader();
                try (InputStream stream = cl.getResourceAsStream(
                        "assets/cryptand/native/" + libName)) {
                    if (stream != null) {
                        Files.copy(stream, libPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (Files.exists(libPath)) {
                System.load(libPath.toAbsolutePath().toString());
                NativeMath.setLoaded(true);
                // 新 DLL 编译含全部精度（enable_single/double/complex/complex16 ON）
                NativeMath.setSingleLoaded(true);
                // 旧代理同步状态
                NativeSparse.setLoaded(true);
                NativeDense.setLoaded(true);
                NativeSparse.setSingleLoaded(true);
                NativeDense.setSingleLoaded(true);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[NativeMath] SuperLU sparse + LAPACK dense + BLAS unified interface enabled ({} bytes)",
                        Files.size(libPath));
            } else {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[NativeMath] DLL not found in resources, falling back to pure Java math");
            }
        } catch (Throwable t) {
            NativeMath.setLoaded(false);
            NativeSparse.setLoaded(false);
            NativeDense.setLoaded(false);
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[NativeMath] failed to load DLL, falling back to pure Java math", t);
        }
    }
}
