package com.hdf.cryptand.toolchain;

import java.util.List;

/**
 * ===== 预定义工具链（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>SoC 固件（裸机 RV32IM）需要两个工具：交叉编译器 + objcopy（同属一个工具链包）。</p>
 */
public final class CToolchains {

    private CToolchains() {
    }

    /** RISC-V 交叉编译器（按优先级列出常见发行名） */
    public static CToolSpec riscvGcc() {
        return new CToolSpec("riscv-gcc", "RISC-V GCC 交叉编译器")
                .names("riscv-none-elf-gcc", "riscv64-unknown-elf-gcc", "riscv32-unknown-elf-gcc",
                        "riscv64-linux-gnu-gcc", "riscv-none-embed-gcc")
                .relativePaths("bin", "riscv/bin", "riscv-none-elf-gcc/bin", "bin/riscv")
                .absentHint("未找到 RISC-V 交叉编译器。安装 xPack riscv-none-elf-gcc（约 400MB），"
                        + "或解压到 <游戏目录>/cryptand/tool/<平台>/（如 tool/windows-x64/bin/、"
                        + "tool/linux-x64/、tool/macos-arm64/；通用工具放 tool/general/）；"
                        + "服务端开启编译功能时可请求服务端代编译");
    }

    /** RISC-V objcopy（把 ELF 转纯二进制固件） */
    public static CToolSpec riscvObjcopy() {
        return new CToolSpec("riscv-objcopy", "RISC-V objcopy")
                .names("riscv-none-elf-objcopy", "riscv64-unknown-elf-objcopy",
                        "riscv32-unknown-elf-objcopy", "riscv64-linux-gnu-objcopy",
                        "riscv-none-embed-objcopy")
                .relativePaths("bin", "riscv/bin", "riscv-none-elf-gcc/bin", "bin/riscv")
                .absentHint("未找到 RISC-V objcopy（通常随交叉工具链一起安装）");
    }

    /** SoC 固件完整工具链（查找顺序即此列表顺序） */
    public static List<CToolSpec> firmwareToolchain() {
        return List.of(riscvGcc(), riscvObjcopy());
    }

    /**
     * 从查找报告构造本地编译器（工具缺失时编译器 {@code available()==false}，
     * 编译返回"缺少 xxx 工具"的失败结果）。
     */
    public static LocalCToolchainCompiler compiler(CToolchainReport report,
                                                   java.nio.file.Path workRoot) {
        final CTool gcc = report.get("riscv-gcc");
        final CTool objcopy = report.get("riscv-objcopy");
        return new LocalCToolchainCompiler(workRoot, gcc, objcopy);
    }
}
