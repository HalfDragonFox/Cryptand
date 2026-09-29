package com.hdf.cryptand.neoforge.soc.compile;

import com.hdf.cryptand.toolchain.CCompileRequest;
import com.hdf.cryptand.toolchain.CCompileResult;
import com.hdf.cryptand.toolchain.CCompilerBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 编译服务路由（2026-09-15）=====
 *
 * <p><b>顺序固定（用户定稿）</b>：</p>
 * <ol>
 *   <li><b>本地工具链</b>（{@code cryptand/tool/…} 或系统 PATH）；</li>
 *   <li><b>服务端代编译</b>（本地缺工具且客户端允许、服务端开启时）；</li>
 *   <li><b>缺工具提示</b>（都不可用 → 返回带安装指引的失败结果）。</li>
 * </ol>
 *
 * <p>注意：本地<b>编译失败</b>（语法错误等）<b>不回退</b>服务端——服务端会同样失败；
 * 只有"缺少工具"才回退。</p>
 *
 * <p>⚠ {@link #compile} 可能阻塞（远程路径）⇒ 必须在异步线程调用。</p>
 */
public final class CompileServiceRouter {

    private final CCompilerBackend local;
    private final CCompilerBackend remote;

    public CompileServiceRouter(CCompilerBackend local, CCompilerBackend remote) {
        this.local = local;
        this.remote = remote;
    }

    /** 编译（本地 → 服务端 → 缺工具提示） */
    public CCompileResult compile(CCompileRequest request) {
        boolean localMissingTool = false;
        if (local != null && local.available()) {
            final CCompileResult result = local.compile(request);
            if (result.ok() || !isMissingTool(result)) {
                return result;                    // 成功，或"真编译错误"→ 不回退
            }
            localMissingTool = true;
        } else {
            localMissingTool = true;
        }

        if (remote != null && remote.available()) {
            final CCompileResult result = remote.compile(request);
            if (result.ok() || !isServerUnavailable(result)) {
                return result;                    // 服务端给出明确结果（成功/编译错误）
            }
            return CCompileResult.failure(localMissingTool
                    ? "缺少工具：RISC-V 交叉编译器；且服务端代编译不可用（" + result.error() + "）"
                    : result.error(), "none", result.elapsedMs());
        }

        return CCompileResult.failure(
                "缺少工具：RISC-V 交叉编译器（未安装/未放入 cryptand/tool/<平台>/），"
                        + "且服务端未开启代编译（soc.toml#enableServerCompile=false）",
                "none", 0);
    }

    /** UI：编译前打印"将使用 …"（不触发编译） */
    public List<String> planLines() {
        final List<String> out = new ArrayList<>();
        out.add("=== 编译方案 ===");
        if (local != null && local.available()) {
            out.add("  ① 本地工具链：" + local.name() + "（优先）");
        } else {
            out.add("  ① 本地工具链：不可用（未找到 RISC-V 工具）");
        }
        if (remote != null && remote.available()) {
            out.add("  ② 服务端代编译：可用（本地不可用时回退）");
        } else {
            out.add("  ② 服务端代编译：不可用（allowRemoteCompile=false）");
        }
        out.add("  ③ 都不行 → 提示缺少工具");
        return out;
    }

    private static boolean isMissingTool(CCompileResult result) {
        final String error = result.error() == null ? "" : result.error();
        return error.contains("缺少工具");
    }

    private static boolean isServerUnavailable(CCompileResult result) {
        final String error = result.error() == null ? "" : result.error();
        return error.contains("服务端未开启") || error.contains("服务端未响应")
                || error.contains("请求服务端编译失败") || error.contains("编译队列已满");
    }
}
