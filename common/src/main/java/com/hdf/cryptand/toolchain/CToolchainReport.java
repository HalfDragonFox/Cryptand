package com.hdf.cryptand.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== 工具链查找报告（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>用户要求："编译前在界面中先打印使用的相关工具信息"。本报告就是那份信息：
 * 每个工具的来源、路径、版本、探测耗时，以及缺失时的安装提示。</p>
 *
 * <p>同时保留<b>查找轨迹</b>（试过哪些候选、结果如何），便于诊断"为什么找不到"。</p>
 */
public final class CToolchainReport {

    private final Map<String, CTool> tools = new LinkedHashMap<>();
    private final Map<String, CToolSpec> specs = new LinkedHashMap<>();
    private final List<String> trace = new ArrayList<>();
    private long totalNanos;

    void addSpec(CToolSpec spec) {
        specs.put(spec.id(), spec);
    }

    void put(CTool tool) {
        tools.put(tool.specId(), tool);
    }

    void trace(String line) {
        trace.add(line);
    }

    void total(long nanos) {
        this.totalNanos = nanos;
    }

    public CTool get(String specId) {
        return tools.get(specId);
    }

    public CToolSpec spec(String specId) {
        return specs.get(specId);
    }

    /** 全部工具（按 spec 注册顺序） */
    public List<CTool> tools() {
        return new ArrayList<>(tools.values());
    }

    /** 查找轨迹（诊断） */
    public List<String> traceLines() {
        return new ArrayList<>(trace);
    }

    public boolean allFound() {
        for (CTool t : tools.values()) {
            if (!t.found()) {
                return false;
            }
        }
        return !tools.isEmpty();
    }

    /** 缺失的工具 id（含安装提示），交给 UI 提示 */
    public List<String> missingHints() {
        final List<String> out = new ArrayList<>();
        for (CTool t : tools.values()) {
            if (!t.found()) {
                final CToolSpec s = specs.get(t.specId());
                out.add((s == null ? t.specId() : s.displayName()) + "："
                        + (s == null || s.absentHint().isEmpty() ? "未找到，请安装或放入 cryptand/tool/" : s.absentHint()));
            }
        }
        return out;
    }

    public long totalNanos() {
        return totalNanos;
    }

    /** 界面打印用（用户要求：编译前先展示将使用哪些工具） */
    public List<String> toDisplayLines() {
        final List<String> out = new ArrayList<>();
        out.add("=== 编译工具链 ===");
        for (CTool t : tools.values()) {
            final CToolSpec s = specs.get(t.specId());
            out.add("  " + t.displayLine(s == null ? t.specId() : s.displayName()));
        }
        for (String hint : missingHints()) {
            out.add("  ⚠ 缺少工具 → " + hint);
        }
        out.add("  查找耗时: " + (totalNanos / 1_000_000) + " ms");
        return out;
    }

    /** 诊断打印（含查找轨迹） */
    public List<String> toDiagnosticLines() {
        final List<String> out = new ArrayList<>(toDisplayLines());
        out.add("--- 查找轨迹 ---");
        out.addAll(trace);
        return out;
    }
}
