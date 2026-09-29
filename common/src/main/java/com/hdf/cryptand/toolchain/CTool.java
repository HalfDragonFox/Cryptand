package com.hdf.cryptand.toolchain;

/**
 * ===== 已定位的工具（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * @param specId    对应 {@link CToolSpec#id()}
 * @param path      可执行文件绝对路径（缺失时为 null）
 * @param version   探测到的版本首行（缺失/探测失败为空）
 * @param source    来源（决定优先级与 UI 文案）
 * @param probeNanos 探测耗时（ns；诊断用）
 */
public record CTool(String specId, String path, String version, Source source, long probeNanos) {

    /** 工具来源（查找顺序即优先级） */
    public enum Source {
        /** ① 游戏目录自带：{@code <gameDir>/cryptand/tool/}（最高优先，整合包/玩家可替换） */
        GAME_TOOL_DIR("游戏目录 cryptand/tool"),
        /** ② 系统查找：{@code where}/{@code which} 等价的 PATH 解析 */
        SYSTEM_PATH("系统 PATH"),
        /** ③ 常见安装路径（xPack / 发行版包管理器位置等） */
        COMMON_PATH("常见安装路径"),
        /** ④ 由服务端代编译（客户端无工具时；需服务端开启） */
        SERVER_FALLBACK("服务器代编译"),
        /** 缺失 */
        MISSING("未找到");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public boolean found() {
        return path != null && source != Source.MISSING && source != Source.SERVER_FALLBACK;
    }

    /** UI 一行：{@code RISC-V GCC → E:\...\riscv-none-elf-gcc.exe (v15.2.0) [系统 PATH]} */
    public String displayLine(String displayName) {
        if (source == Source.SERVER_FALLBACK) {
            return displayName + " → 本地未找到，改用【服务器代编译】";
        }
        if (!found()) {
            return displayName + " → 未找到（" + source.label() + "）";
        }
        final String v = version == null || version.isBlank() ? "版本未知" : version.trim();
        return displayName + " → " + path + "  [" + v + "]  (" + source.label() + ")";
    }

    public static CTool missing(String specId) {
        return new CTool(specId, null, "", Source.MISSING, 0);
    }

    public static CTool serverFallback(String specId) {
        return new CTool(specId, null, "", Source.SERVER_FALLBACK, 0);
    }
}
