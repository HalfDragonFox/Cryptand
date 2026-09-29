package com.hdf.cryptand.toolchain;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 工具需求描述（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>描述"要找什么工具"：候选可执行名、相对常见根的子路径、缺失提示。
 * 与查找逻辑解耦——新增工具（如 objcopy / 未来 yosys）只需加一个 spec。</p>
 */
public final class CToolSpec {

    private final String id;
    private final String displayName;
    private final List<String> executableNames = new ArrayList<>();
    private final List<String> relativePaths = new ArrayList<>();
    private String absentHint = "";

    public CToolSpec(String id, String displayName) {
        this.id = id;
        this.displayName = displayName == null ? id : displayName;
    }

    /** 候选可执行文件名（按优先级；含扩展名由查找器补齐） */
    public CToolSpec names(String... names) {
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                executableNames.add(n.trim());
            }
        }
        return this;
    }

    /** 工具目录下的常见相对路径（如 {@code "bin"}、{@code "riscv/bin"}） */
    public CToolSpec relativePaths(String... paths) {
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                relativePaths.add(p.trim());
            }
        }
        return this;
    }

    /** 缺失时给玩家的安装提示（UI 直接显示） */
    public CToolSpec absentHint(String hint) {
        this.absentHint = hint == null ? "" : hint;
        return this;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public List<String> executableNames() {
        return new ArrayList<>(executableNames);
    }

    public List<String> relativePaths() {
        return new ArrayList<>(relativePaths);
    }

    public String absentHint() {
        return absentHint;
    }

    @Override
    public String toString() {
        return id + "(" + String.join("/", executableNames) + ")";
    }
}
