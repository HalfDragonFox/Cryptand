package com.hdf.cryptand.neoforge.soc.compile;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hdf.cryptand.neoforge.soc.config.ConfigSoc;
import com.hdf.cryptand.neoforge.soc.net.CompileRequestPayload;
import com.hdf.cryptand.neoforge.soc.net.CompileResultPayload;
import com.hdf.cryptand.toolchain.CCompileRequest;
import com.hdf.cryptand.toolchain.CCompileResult;
import com.hdf.cryptand.toolchain.CToolchainLocator;
import com.hdf.cryptand.toolchain.CToolchainReport;
import com.hdf.cryptand.toolchain.CToolchains;
import com.hdf.cryptand.toolchain.LocalCToolchainCompiler;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ===== 服务端代编译服务（2026-09-15）=====
 *
 * <p>用户定稿：<b>服务端可配置是否开启编译功能（默认关闭）</b>，开启后可配置
 * "多少核心用于编译"；客户端无工具链时可请求服务端代编译。</p>
 *
 * <h3>限流三层</h3>
 * <ol>
 *   <li><b>并发核心数</b>：固定大小线程池 = {@code serverCompileCores}（同时编译上限）；</li>
 *   <li><b>队列上限</b>：有界队列 = {@code serverCompileQueueLimit}（超出立即回执"排队已满"）；</li>
 *   <li><b>源码大小</b>：{@code serverCompileMaxSourceBytes}（超出直接拒绝）；</li>
 * </ol>
 * 另加单次编译超时 {@code serverCompileTimeoutMs}（与客户端请求取较小值）。
 *
 * <p>编译全程在后台线程（不占用服务端主线程）；结果经 {@link CompileResultPayload} 回执。</p>
 */
public final class ServerCompileService {

    private static final Gson GSON = new Gson();

    private static volatile ThreadPoolExecutor executor;
    private static volatile int executorCores = -1;
    private static volatile LocalCToolchainCompiler compiler;
    private static final AtomicInteger REJECTED = new AtomicInteger();

    private ServerCompileService() {
    }

    /** 服务端收到编译请求（主线程回调→立即转后台，不阻塞主线程） */
    public static void handle(ServerPlayer player, CompileRequestPayload payload) {
        // ① 功能开关（默认关闭 → 明确回执，而不是静默超时）
        if (!ConfigSoc.enableServerCompile()) {
            reply(player, new CompileResultPayload(payload.requestId(), false, new byte[0],
                    "服务端未开启代编译功能（soc.toml#enableServerCompile=false）",
                    "", "", 0));
            return;
        }
        // ② 源码大小
        final int sourceBytes = payload.source().length() + payload.extraFilesJson().length();
        final int maxBytes = ConfigSoc.serverCompileMaxSourceBytes();
        if (sourceBytes > maxBytes) {
            reply(player, new CompileResultPayload(payload.requestId(), false, new byte[0],
                    "源码过大：" + sourceBytes + " > " + maxBytes + " 字节（serverCompileMaxSourceBytes）",
                    "", "", 0));
            return;
        }
        // ③ 入队（队列满则拒绝）
        try {
            executor().execute(() -> {
                final CCompileResult result = compileSafely(payload);
                reply(player, toPayload(payload.requestId(), result));
            });
        } catch (Throwable rejected) {
            REJECTED.incrementAndGet();
            reply(player, new CompileResultPayload(payload.requestId(), false, new byte[0],
                    "编译队列已满（serverCompileQueueLimit=" + ConfigSoc.serverCompileQueueLimit() + "），请稍后重试",
                    "", "", 0));
        }
    }

    // ==================== 编译 ====================

    private static CCompileResult compileSafely(CompileRequestPayload payload) {
        try {
            final CCompileRequest request = new CCompileRequest(payload.programName(), payload.source())
                    .march(payload.march())
                    .mabi(payload.mabi())
                    .linkerScript(payload.linkerScript())
                    .timeoutMs(Math.min(payload.timeoutMs(), ConfigSoc.serverCompileTimeoutMs()));
            final Map<String, String> files = parseFiles(payload.extraFilesJson());
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (!e.getKey().equals("main.c")) {
                    request.file(e.getKey(), e.getValue());
                }
            }
            return compiler().compile(request);
        } catch (Throwable t) {
            return CCompileResult.failure("服务端编译异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()), "server", 0);
        }
    }

    private static Map<String, String> parseFiles(String json) {
        try {
            final Map<String, String> out = GSON.fromJson(json,
                    new TypeToken<Map<String, String>>() {
                    }.getType());
            return out == null ? Map.of() : out;
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /** 服务端本地工具链（懒探测；服务端自己也要有工具链才能代编译） */
    private static LocalCToolchainCompiler compiler() {
        LocalCToolchainCompiler c = compiler;
        if (c == null) {
            synchronized (ServerCompileService.class) {
                c = compiler;
                if (c == null) {
                    final Path gameDir = FMLPaths.GAMEDIR.get();
                    final CToolchainLocator locator = new CToolchainLocator(gameDir);
                    final CToolchainReport report = locator.locate(CToolchains.firmwareToolchain());
                    c = CToolchains.compiler(report, gameDir.resolve("cryptand").resolve("tmp"));
                    compiler = c;
                }
            }
        }
        return c;
    }

    /** 服务端自带工具目录提示（UI/命令显示「把工具放这里」） */
    public static String toolDirHint() {
        return new CToolchainLocator(FMLPaths.GAMEDIR.get()).toolDirHint();
    }

    /** 直接编译（不经网络；命令/服务端自身流程用；调用方须在后台线程） */
    public static CCompileResult compileDirect(CCompileRequest request) {
        return compiler().compile(request);
    }

    /** 服务端工具链报告（诊断/管理命令用） */
    public static CToolchainReport locateToolchain() {
        final Path gameDir = FMLPaths.GAMEDIR.get();
        return new CToolchainLocator(gameDir).locate(CToolchains.firmwareToolchain());
    }

    /** 线程池（并发上限 = serverCompileCores；队列上限 = serverCompileQueueLimit） */
    private static ThreadPoolExecutor executor() {
        final int cores = ConfigSoc.serverCompileCores();
        ThreadPoolExecutor e = executor;
        if (e == null || cores != executorCores) {
            synchronized (ServerCompileService.class) {
                e = executor;
                if (e == null || cores != executorCores) {
                    if (e != null) {
                        e.shutdown();   // 配置变更：旧池收敛（在跑的任务自然结束）
                    }
                    e = new ThreadPoolExecutor(cores, cores, 30, TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(ConfigSoc.serverCompileQueueLimit()),
                            r -> {
                                final Thread t = new Thread(r, "cryptand-soc-compile");
                                t.setDaemon(true);
                                return t;
                            },
                            new ThreadPoolExecutor.AbortPolicy());
                    executor = e;
                    executorCores = cores;
                }
            }
        }
        return e;
    }

    // ==================== 回执 ====================

    private static CompileResultPayload toPayload(long requestId, CCompileResult result) {
        final StringBuilder diag = new StringBuilder();
        for (String line : result.diagnosticLines()) {
            diag.append(line).append('\n');
        }
        return new CompileResultPayload(requestId, result.ok(),
                result.binary() == null ? new byte[0] : result.binary(),
                result.error() == null ? "" : result.error(),
                diag.toString(), result.toolchain(), result.elapsedMs());
    }

    private static void reply(ServerPlayer player, CompileResultPayload payload) {
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (Throwable ignored) {
        }
    }

    /** 诊断：当前队列/拒绝计数 */
    public static String describe() {
        final ThreadPoolExecutor e = executor;
        return "ServerCompileService[enabled=" + ConfigSoc.enableServerCompile()
                + " cores=" + ConfigSoc.serverCompileCores()
                + " queueLimit=" + ConfigSoc.serverCompileQueueLimit()
                + " active=" + (e == null ? 0 : e.getActiveCount())
                + " queued=" + (e == null ? 0 : e.getQueue().size())
                + " rejected=" + REJECTED.get() + "]";
    }
}
