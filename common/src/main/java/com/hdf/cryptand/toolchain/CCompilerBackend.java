package com.hdf.cryptand.toolchain;

/**
 * ===== 编译后端（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>两种实现对应两条路径：</p>
 * <ol>
 *   <li><b>本地工具链</b>（{@code cryptand/tool/} 或系统 PATH）→ {@link LocalCToolchainCompiler}；</li>
 *   <li><b>服务端代编译</b>（客户端无工具且服务端开启编译功能时，平台侧实现，
 *       经 payload 往返；默认关闭）。</li>
 * </ol>
 *
 * <p>路由由上层按"本地 → 服务端 → 缺工具提示"顺序选择。</p>
 */
public interface CCompilerBackend {

    /** 后端名（UI/诊断） */
    String name();

    /** 当前是否可用（本地=工具齐备；服务端=已开启且在线） */
    boolean available();

    /** 执行编译（不得抛异常；失败经 {@link CCompileResult#error()} 返回） */
    CCompileResult compile(CCompileRequest request);
}
