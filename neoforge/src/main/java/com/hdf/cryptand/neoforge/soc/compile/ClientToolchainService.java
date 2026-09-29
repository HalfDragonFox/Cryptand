package com.hdf.cryptand.neoforge.soc.compile;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.toolchain.CCompileRequest;
import com.hdf.cryptand.toolchain.CCompileResult;
import com.hdf.cryptand.toolchain.CToolchainLocator;
import com.hdf.cryptand.toolchain.CToolchainReport;
import com.hdf.cryptand.toolchain.CToolchains;
import com.hdf.cryptand.toolchain.LocalCToolchainCompiler;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ===== 客户端工具链服务（2026-09-15）=====
 *
 * <p>职责：<b>异步探测</b>（实测冷探测约 13s，绝不能放主线程）→ <b>缓存报告</b> →
 * 供 UI 打印"将使用哪些工具"→ 提供编译器与路由。</p>
 *
 * <p>探测顺序由 {@link CToolchainLocator} 决定：{@code cryptand/tool/<平台>/} → general →
 * 系统 PATH → 常见路径 →（服务端代编译由路由负责）。</p>
 */
public final class ClientToolchainService {

    private static volatile CToolchainReport report;
    private static volatile LocalCToolchainCompiler localCompiler;
    private static volatile CompileServiceRouter router;
    private static volatile boolean probing;

    private ClientToolchainService() {
    }

    /** 是否正在探测 */
    public static boolean probing() {
        return probing;
    }

    /** 已缓存的报告（未探测完成为 null） */
    public static CToolchainReport cachedReport() {
        return report;
    }

    /**
     * 异步探测工具链（结果缓存；{@code force=true} 时强制重查）。
     *
     * @param onDone 探测完成回调（<b>后台线程</b>执行；UI 侧需自行切主线程）
     */
    public static void probeAsync(boolean force, Runnable onDone) {
        if (probing) {
            return;
        }
        if (report != null && !force) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        probing = true;
        ThreadDispatchers.get().submitGeneric(() -> {
            try {
                final Path gameDir = FMLPaths.GAMEDIR.get();
                final CToolchainLocator locator = new CToolchainLocator(gameDir);
                addCommonRoots(locator);
                final CToolchainReport fresh = locator.locate(CToolchains.firmwareToolchain());
                report = fresh;
                localCompiler = CToolchains.compiler(fresh,
                        gameDir.resolve("cryptand").resolve("tmp"));
            } catch (Throwable ignored) {
            } finally {
                probing = false;
                if (onDone != null) {
                    // ⚠ 必须回到主线程再回调：调用方常在这里发聊天消息（MC 要求主线程），
                    //   在后台线程直接调 sendSuccess 会抛异常，并被本方法的 catch 吞掉 ——
                    //   表现为"命令提示了正在检查，但结果永远不来"（2026-09-15 实测）。
                    // 总是走主线程（不要"先试后台、失败再补" —— 那样可能重复执行回调）
                    try {
                        final net.minecraft.client.Minecraft mc =
                                net.minecraft.client.Minecraft.getInstance();
                        if (mc != null) {
                            mc.execute(onDone);
                        } else {
                            onDone.run();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, TaskMode.EXCLUSIVE);
    }

    public static void probeAsync(boolean force) {
        probeAsync(force, null);
    }

    /** 常见安装根（xPack / 发行版包管理器位置的常见盘符） */
    private static void addCommonRoots(CToolchainLocator locator) {
        final String home = System.getProperty("user.home", "");
        if (!home.isEmpty()) {
            locator.commonRoot(Path.of(home, ".xpack"));
            locator.commonRoot(Path.of(home, ".local", "bin"));
            locator.commonRoot(Path.of(home, "AppData", "Roaming", "xPacks"));
        }
        locator.commonRoot(Path.of("/opt"));
        locator.commonRoot(Path.of("/usr", "local"));
        locator.commonRoot(Path.of("C:\\Program Files"));
        locator.commonRoot(Path.of("C:\\msys64", "mingw64"));
    }

    /** 本地编译器（未探测时返回 null；{@code available()==false} 亦可安全调用 compile） */
    public static LocalCToolchainCompiler localCompiler() {
        return localCompiler;
    }

    /** 路由器：本地 → 服务端 → 缺工具提示（懒建，本地报告未就绪时也安全） */
    public static CompileServiceRouter router() {
        CompileServiceRouter r = router;
        if (r == null) {
            synchronized (ClientToolchainService.class) {
                r = router;
                if (r == null) {
                    r = new CompileServiceRouter(localCompiler(), new RemoteServerCompiler());
                    router = r;
                }
            }
        }
        return r;
    }

    /** 由于本地编译器在探测后才存在，路由需在探测完成后重建 */
    public static void invalidateRouter() {
        router = null;
    }

    /**
     * 异步编译（本地 → 服务端 → 缺工具提示）。
     *
     * @param callback 回调（<b>后台线程</b>执行；UI 侧需自行切主线程）
     */
    public static void compileAsync(CCompileRequest request, Consumer<CCompileResult> callback) {
        if (report == null) {
            probeAsync(false, () -> {
                invalidateRouter();
                doCompile(request, callback);
            });
        } else {
            doCompile(request, callback);
        }
    }

    private static void doCompile(CCompileRequest request, Consumer<CCompileResult> callback) {
        ThreadDispatchers.get().submitGeneric(() -> {
            final CCompileResult result;
            try {
                result = router().compile(request);
            } catch (Throwable t) {
                if (callback != null) {
                    callback.accept(CCompileResult.failure("编译异常：" + t, "none", 0));
                }
                return;
            }
            if (callback != null) {
                try {
                    callback.accept(result);
                } catch (Throwable ignored) {
                }
            }
        }, TaskMode.EXCLUSIVE);
    }

    /** UI：编译前打印的工具信息（未探测完成时给出提示） */
    public static List<String> displayLines() {
        final List<String> out = new ArrayList<>();
        final CToolchainReport r = report;
        if (r == null) {
            out.add(probing ? "正在查找编译工具链…" : "尚未探测编译工具链（可用 /cryptand soc tools 触发）");
            return out;
        }
        out.addAll(r.toDisplayLines());
        out.addAll(router().planLines());
        return out;
    }
}
