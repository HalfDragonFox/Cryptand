/**
 * ===== Cryptand RV32 native 内核 · JNI 声明与库加载（2026-09-17）=====
 *
 * <p>用户决定：**内核模拟直接用 C++**（不再走"先优化 Java"的中间路线）。
 * C++ 源码在 {@code excode/cryptand-rv32/}，编出 {@code cryptand_rv32.dll}；
 * 本类是它在 Java 侧的唯一入口，纯 Java 零 MC 依赖（与 soc 子包分层一致）。</p>
 *
 * <h3>为什么这样设计</h3>
 * <ul>
 *   <li><b>解释循环里零 JNI 往返</b>：内存由 native 自持，Java 只在上电灌镜像、
 *       诊断时读写；寄存器/CSR 只在需要时（自测、UI）查询。</li>
 *   <li><b>设备访问走事务</b>：native 碰到非 ROM/RAM 地址就记一条 MMIO 事务并立刻返回，
 *       Java 在设备上兑现后调 {@link #completeMmio} 继续 —— 所以 RegBank/Timer/UART
 *       仍然是 Java 对象，soc 子包一行都不用改。</li>
 *   <li><b>库缺失可容忍</b>：{@link #available()} 为 false 时上层自动回落到纯 Java 内核
 *       （{@code Rv32Core}），单机/无 native 环境照常能玩。</li>
 * </ul>
 *
 * <h3>库加载顺序（与项目既有 native 约定一致）</h3>
 * <ol>
 *   <li>{@code -Dcryptand.rv32.lib=<绝对路径>}（开发/自测最方便）</li>
 *   <li>{@code config/cryptand/engines/cryptand_rv32.dll}（部署目录，和物理引擎 DLL 并列）</li>
 *   <li>{@code System.loadLibrary("cryptand_rv32")}（java.library.path / 打包进 jar 的 natives）</li>
 * </ol>
 */
package com.hdf.cryptand.soc.nativebridge;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NativeRv32 {

    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String error = null;
        try {
            ok = tryLoad();
            if (!ok) {
                error = "cryptand_rv32 未找到（尝试了 -Dcryptand.rv32.lib、config/cryptand/engines/、java.library.path）";
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        AVAILABLE = ok;
        LOAD_ERROR = error;
    }

    private NativeRv32() {
    }

    private static boolean tryLoad() {
        // 候选目录：① -Dcryptand.rv32.lib 指定的主 DLL 所在目录（自测最方便）
        //           ② 部署目录（与其它 Cryptand native 并列）
        final List<Path> dirs = new ArrayList<>();
        File explicitFile = null;
        final String explicit = System.getProperty("cryptand.rv32.lib");
        if (explicit != null && !explicit.isBlank()) {
            explicitFile = new File(explicit);
            final File parent = explicitFile.getParentFile();
            if (parent != null) {
                dirs.add(parent.toPath());
            }
        }
        for (final String d : new String[]{"config/cryptand/engines", "cryptand/natives", "run/cryptand/natives"}) {
            dirs.add(Path.of(d));
        }

        // ⓪ 先加载依赖库（MinGW 运行时）
        //
        // 沙箱用到 C++ 标准库的同步原语 ⇒ 编出来的 DLL 会**动态依赖 libwinpthread-1.dll**。
        // 那个 DLL 虽与主 DLL 并排放在引擎目录，但 Windows 解析依赖时并不搜索
        // "被加载 DLL 自己的目录"（除非调用方用了 LOAD_WITH_ALTERED_SEARCH_PATH），
        // 于是 System.load(主 DLL) 会失败在 "找不到指定的程序"。
        // 解决：把依赖 DLL 也一起分发到引擎目录，并在主 DLL 之前**显式加载**它 ——
        // 一旦它进了进程，主 DLL 的依赖就自然解析得到。
        for (final Path dir : dirs) {
            preload(dir.resolve("libwinpthread-1.dll"));
        }

        // ① 显式路径
        if (explicitFile != null && explicitFile.isFile()) {
            System.load(explicitFile.getAbsolutePath());
            return true;
        }
        // ② 部署目录
        final String[] names = {"cryptand_rv32.dll", "libcryptand_rv32.so", "libcryptand_rv32.dylib"};
        for (final Path dir : dirs) {
            for (final String name : names) {
                final Path p = dir.resolve(name);
                if (Files.isRegularFile(p)) {
                    System.load(p.toAbsolutePath().toString());
                    return true;
                }
            }
        }
        // ③ 系统库搜索路径
        try {
            System.loadLibrary("cryptand_rv32");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 尽力预加载一个依赖 DLL（不存在/已加载都直接忽略）。 */
    private static void preload(Path p) {
        try {
            if (Files.isRegularFile(p)) {
                System.load(p.toAbsolutePath().toString());
            }
        } catch (Throwable ignored) {
            // 已经加载过（另一个 classloader）/ 平台不需要：都不是错误
        }
    }

    /** native 内核是否可用（不可用时上层回落 Java 内核） */
    public static boolean available() {
        return AVAILABLE;
    }

    public static String loadError() {
        return LOAD_ERROR;
    }

    // ==================== native 方法（与 jni_bridge.cpp 一一对应） ====================

    public static native long create(int resetVector, int ramBase, int ramSize, int romBase, int romSize);

    public static native void destroy(long handle);

    public static native void reset(long handle);

    public static native void loadImage(long handle, int addr, byte[] data);

    public static native int step(long handle, int cycles);

    public static native int pc(long handle);

    public static native int reg(long handle, int index);

    public static native int csr(long handle, int csr);

    public static native long instructionsRetired(long handle);

    public static native boolean faulted(long handle);

    public static native boolean halted(long handle);

    public static native int faultCause(long handle);

    public static native int faultTval(long handle);

    public static native int faultEpc(long handle);

    // ---- MMIO 事务往返 ----
    public static native boolean hasMmio(long handle);

    public static native int mmioAddr(long handle);

    public static native int mmioSize(long handle);

    public static native boolean mmioIsWrite(long handle);

    public static native int mmioValue(long handle);

    public static native void completeMmio(long handle, int value);

    // ---- 内存（诊断/自测）----
    public static native void readMemory(long handle, int addr, byte[] out);

    public static native void writeMemory(long handle, int addr, byte[] in);

    // ---- 中断：宿主每次 step 前把设备待处理位图推进来 ----
    public static native void setPendingInterrupts(long handle, int pendingBits);
}
