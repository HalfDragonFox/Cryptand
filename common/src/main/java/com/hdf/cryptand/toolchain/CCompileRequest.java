package com.hdf.cryptand.toolchain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== C 编译请求（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>面向 SoC 固件（裸机 RV32IM）：源码 + 附加文件（.h/.ld）+ 目标 ABI + 超时。</p>
 */
public final class CCompileRequest {

    private final String programName;
    private final String source;
    private final Map<String, String> extraFiles = new LinkedHashMap<>();
    private final java.util.List<String> extraArgs = new java.util.ArrayList<>();
    private String march = "rv32im";
    private String mabi = "ilp32";
    private String linkerScript = "";
    private int timeoutMs = 30_000;

    public CCompileRequest(String programName, String source) {
        this.programName = programName == null || programName.isBlank() ? "program" : programName.trim();
        this.source = source == null ? "" : source;
    }

    /** 附加文件（文件名 → 内容；如 {@code "cryptand.ld"}、{@code "util.h"}） */
    public CCompileRequest file(String name, String content) {
        extraFiles.put(name, content == null ? "" : content);
        return this;
    }

    public CCompileRequest arg(String... args) {
        extraArgs.addAll(List.of(args));
        return this;
    }

    /** 目标 ISA（须与 SoC 内核匹配；默认 rv32im —— 无 F/D ⇒ 不能用浮点 ABI） */
    public CCompileRequest march(String march) {
        this.march = march;
        return this;
    }

    /** 目标 ABI（默认 ilp32 = 软浮点/无浮点） */
    public CCompileRequest mabi(String mabi) {
        this.mabi = mabi;
        return this;
    }

    /** 链接脚本文件名（须同时在 {@link #file} 中提供内容） */
    public CCompileRequest linkerScript(String name) {
        this.linkerScript = name == null ? "" : name;
        return this;
    }

    public CCompileRequest timeoutMs(int ms) {
        this.timeoutMs = Math.max(1_000, ms);
        return this;
    }

    public String programName() {
        return programName;
    }

    public String source() {
        return source;
    }

    public Map<String, String> extraFiles() {
        return new LinkedHashMap<>(extraFiles);
    }

    public List<String> extraArgs() {
        return List.copyOf(extraArgs);
    }

    public String march() {
        return march;
    }

    public String mabi() {
        return mabi;
    }

    public String linkerScript() {
        return linkerScript;
    }

    public int timeoutMs() {
        return timeoutMs;
    }
}
