package com.hdf.cryptand.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ===== 内置下载表（2026-09-15 toolchain 子库，纯 Java 零 MC）=====
 *
 * <p>各平台工具包的真实下载地址（默认官方源；可用配置的镜像前缀覆盖）。
 * 目标：<b>傻瓜式</b>——玩家一条指令即可下载本平台工具链，无需自己找地址。</p>
 */
public final class CToolDownloads {

    /** xPack riscv-none-elf-gcc 版本（含 RV32/RV64 裸机工具链 + objcopy） */
    public static final String XPACK_VERSION = "15.2.0-1";

    /** OSS CAD Suite 版本（Yosys 全家桶） */
    public static final String OSS_CAD_SUITE_VERSION = "2026-09-15";
    /** 该 release 的文件名日期（紧凑格式） */
    public static final String OSS_CAD_SUITE_COMPACT = "20260915";

    private static final String BASE = "https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack"
            + "/releases/download/v" + XPACK_VERSION + "/";

    private CToolDownloads() {
    }

    /** 全部平台（内置表） */
    public static List<CToolDownload> all() {
        final List<CToolDownload> out = new ArrayList<>();
        out.add(new CToolDownload("riscv-gcc", "windows-x64",
                BASE + "xpack-riscv-none-elf-gcc-" + XPACK_VERSION + "-win32-x64.zip",
                444L * 1024 * 1024, "zip"));
        out.add(new CToolDownload("riscv-gcc", "linux-x64",
                BASE + "xpack-riscv-none-elf-gcc-" + XPACK_VERSION + "-linux-x64.tar.gz",
                413L * 1024 * 1024, "tar.gz"));
        out.add(new CToolDownload("riscv-gcc", "linux-arm64",
                BASE + "xpack-riscv-none-elf-gcc-" + XPACK_VERSION + "-linux-arm64.tar.gz",
                406L * 1024 * 1024, "tar.gz"));
        out.add(new CToolDownload("riscv-gcc", "macos-x64",
                BASE + "xpack-riscv-none-elf-gcc-" + XPACK_VERSION + "-darwin-x64.tar.gz",
                387L * 1024 * 1024, "tar.gz"));
        out.add(new CToolDownload("riscv-gcc", "macos-arm64",
                BASE + "xpack-riscv-none-elf-gcc-" + XPACK_VERSION + "-darwin-arm64.tar.gz",
                383L * 1024 * 1024, "tar.gz"));

        // ---- Yosys / OSS CAD Suite（FPGA：Verilog 综合；与 riscv-gcc 并行下载）----
        final String oc = "https://github.com/YosysHQ/oss-cad-suite-build/releases/download/"
                + OSS_CAD_SUITE_VERSION + "/";
        out.add(new CToolDownload("yosys", "windows-x64",
                oc + "oss-cad-suite-windows-x64-" + OSS_CAD_SUITE_COMPACT + ".tgz",
                568L * 1024 * 1024, "tar.gz"));
        out.add(new CToolDownload("yosys", "linux-x64",
                oc + "oss-cad-suite-linux-x64-" + OSS_CAD_SUITE_COMPACT + ".tgz",
                708L * 1024 * 1024, "tar.gz"));
        out.add(new CToolDownload("yosys", "macos-x64",
                oc + "oss-cad-suite-darwin-x64-" + OSS_CAD_SUITE_COMPACT + ".tgz",
                480L * 1024 * 1024, "tar.gz"));
        return out;
    }

    /** 工具显示名（UI 直接显示） */
    public static String displayName(String toolId) {
        return switch (toolId) {
            case "riscv-gcc" -> "RISC-V GCC 交叉编译器（固件 C 编译）";
            case "yosys" -> "Yosys / OSS CAD Suite（FPGA Verilog 综合）";
            default -> toolId;
        };
    }

    /** 全部工具 id（UI 列表） */
    public static List<String> toolIds() {
        final List<String> out = new ArrayList<>();
        for (CToolDownload d : all()) {
            if (!out.contains(d.toolId())) {
                out.add(d.toolId());
            }
        }
        return out;
    }

    /** 当前平台标识（与 {@link CToolchainLocator#platformDirNames()} 命名一致） */
    public static String currentPlatform() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String platform = os.contains("win") ? "windows"
                : (os.contains("mac") || os.contains("darwin")) ? "macos"
                : os.contains("linux") ? "linux" : "unknown";
        final String archName = switch (arch) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> "x64";
        };
        return platform + "-" + archName;
    }

    /** 本平台对应的下载（找不到返回 null） */
    public static CToolDownload forCurrentPlatform(String toolId) {
        return forPlatform(toolId, currentPlatform());
    }

    /** 指定平台的下载（找不到返回 null） */
    public static CToolDownload forPlatform(String toolId, String platform) {
        for (CToolDownload d : all()) {
            if (d.toolId().equals(toolId) && d.platform().equals(platform)) {
                return d;
            }
        }
        return null;
    }

    /** 全部平台名（UI/诊断） */
    public static List<String> platforms() {
        final List<String> out = new ArrayList<>();
        for (CToolDownload d : all()) {
            if (!out.contains(d.platform())) {
                out.add(d.platform());
            }
        }
        return out;
    }
}
