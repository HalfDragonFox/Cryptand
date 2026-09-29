package com.hdf.cryptand.toolchain;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== C 编译结果（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * @param ok          是否成功（false 时 {@code error} 必有说明）
 * @param binary      产物（纯二进制固件；失败为 null）
 * @param stdout      编译器标准输出
 * @param stderr      编译器标准错误
 * @param diagnostics 解析出的诊断（file:line:col: error/warning: message）
 * @param toolchain   实际使用的工具链描述（路径 + 版本；UI 复述用）
 * @param elapsedMs   耗时
 * @param error       失败原因（含"缺少 xxx 工具"的场景）
 */
public record CCompileResult(boolean ok, byte[] binary, String stdout, String stderr,
                             List<Diagnostic> diagnostics, String toolchain,
                             long elapsedMs, String error) {

    /** 单条编译诊断（映射回编辑器行号用） */
    public record Diagnostic(String file, int line, int column, String severity, String message) {

        @Override
        public String toString() {
            return file + ":" + line + (column > 0 ? ":" + column : "") + " " + severity + ": " + message;
        }
    }

    public static CCompileResult failure(String error, String toolchain, long elapsedMs) {
        return new CCompileResult(false, null, "", "", List.of(), toolchain, elapsedMs, error);
    }

    public static CCompileResult of(byte[] binary, String stdout, String stderr,
                                    List<Diagnostic> diagnostics, String toolchain, long elapsedMs) {
        return new CCompileResult(true, binary, stdout, stderr, diagnostics, toolchain, elapsedMs, "");
    }

    /** 产物大小（诊断/UI） */
    public int binarySize() {
        return binary == null ? 0 : binary.length;
    }

    public List<String> diagnosticLines() {
        final List<String> out = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            out.add(d.toString());
        }
        return out;
    }
}
