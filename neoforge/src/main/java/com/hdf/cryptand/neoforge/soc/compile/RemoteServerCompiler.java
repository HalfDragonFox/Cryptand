package com.hdf.cryptand.neoforge.soc.compile;

import com.google.gson.Gson;
import com.hdf.cryptand.neoforge.soc.config.ConfigSoc;
import com.hdf.cryptand.neoforge.soc.net.CompileRequestPayload;
import com.hdf.cryptand.neoforge.soc.net.CompileResultPayload;
import com.hdf.cryptand.toolchain.CCompileRequest;
import com.hdf.cryptand.toolchain.CCompileResult;
import com.hdf.cryptand.toolchain.CCompilerBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ===== 客户端 · 服务端代编译后端（2026-09-15）=====
 *
 * <p>实现 {@link CCompilerBackend}：把编译请求经 payload 发给服务端，等待回执。
 * <b>本方法会阻塞</b>（等待网络往返）——只能由异步线程调用（路由层保证）。</p>
 *
 * <p>可用性：客户端配置 {@code allowRemoteCompile}（默认 true）。服务端是否真的提供
 * 由回执说明（未开启会明确回执"服务端未开启代编译功能"；模组未装则表现为超时）。</p>
 */
public final class RemoteServerCompiler implements CCompilerBackend {

    private static final Gson GSON = new Gson();
    private static final Map<Long, CompletableFuture<CCompileResult>> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    /** 客户端等待回执的超时（ms；比服务端超时略长，留出网络与排队余量） */
    private static final long CLIENT_TIMEOUT_MARGIN_MS = 15_000;

    @Override
    public String name() {
        return "remote(server)";
    }

    @Override
    public boolean available() {
        return ConfigSoc.allowRemoteCompile();
    }

    @Override
    public CCompileResult compile(CCompileRequest request) {
        final long id = SEQ.incrementAndGet();
        final CompletableFuture<CCompileResult> future = new CompletableFuture<>();
        PENDING.put(id, future);
        final long start = System.nanoTime();
        try {
            final Map<String, String> files = new java.util.LinkedHashMap<>(request.extraFiles());
            CompileRequestPayload.sendToServer(new CompileRequestPayload(
                    id, request.programName(), request.source(), GSON.toJson(files),
                    request.march(), request.mabi(), request.linkerScript(), request.timeoutMs()));
            return future.get(request.timeoutMs() + CLIENT_TIMEOUT_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return CCompileResult.failure("服务端未响应（模组未安装 / 未开启 enableServerCompile / 超时）",
                    name(), (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            return CCompileResult.failure("请求服务端编译失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()),
                    name(), (System.nanoTime() - start) / 1_000_000);
        } finally {
            PENDING.remove(id);
        }
    }

    /** 客户端收到回执（由 {@link CompileResultPayload#handle} 在客户端主线程调用） */
    public static void complete(CompileResultPayload payload) {
        final CompletableFuture<CCompileResult> future = PENDING.remove(payload.requestId());
        if (future == null) {
            return;
        }
        final List<CCompileResult.Diagnostic> diagnostics = new ArrayList<>();
        for (String line : payload.diagnostics().split("\\R")) {
            if (!line.isBlank()) {
                diagnostics.add(new CCompileResult.Diagnostic("(server)", 0, 0, "note", line.trim()));
            }
        }
        future.complete(new CCompileResult(payload.ok(),
                payload.binary() == null || payload.binary().length == 0 ? null : payload.binary(),
                "", payload.error(), diagnostics,
                "[服务端] " + payload.toolchain(), payload.elapsedMs(),
                payload.ok() ? "" : payload.error()));
    }

    /** 当前等待中的请求数（诊断） */
    public static int pending() {
        return PENDING.size();
    }
}
