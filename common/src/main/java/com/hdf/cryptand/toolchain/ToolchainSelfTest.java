package com.hdf.cryptand.toolchain;

import com.hdf.cryptand.toolchain.CCompileRequest;
import com.hdf.cryptand.toolchain.CCompileResult;
import com.hdf.cryptand.toolchain.CCompilerBackend;
import com.hdf.cryptand.toolchain.CTool;
import com.hdf.cryptand.toolchain.CToolSpec;
import com.hdf.cryptand.toolchain.CToolchainLocator;
import com.hdf.cryptand.toolchain.CToolchainReport;
import com.hdf.cryptand.toolchain.CToolchains;
import com.hdf.cryptand.toolchain.LocalCToolchainCompiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * ===== 工具链查找 / 编译后端自测（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>验证用户定稿的查找顺序：<b>cryptand/tool 自带目录 → 系统 PATH → 常见路径 →
 * 服务端代编译 → 缺工具提示</b>，以及编译失败时不抛异常、诊断行可解析。</p>
 *
 * <p>运行：{@code gradlew :common:runToolchainTest}。不依赖真实 RISC-V 工具链
 * （用平台自带工具模拟"可执行文件 + --version 探测"）。</p>
 */
public final class ToolchainSelfTest {

    private static int passed;
    private static int failed;
    private static final StringBuilder log = new StringBuilder();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Cryptand Toolchain Self Test (common/soc/toolchain) ===");

        final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        final String probeToolName = windows ? "where" : "ls";
        final Path probeToolSource = windows
                ? Path.of(System.getenv("SystemRoot") == null ? "C:\\Windows" : System.getenv("SystemRoot"),
                          "System32", "where.exe")
                : Path.of("/bin/ls");

        // ---------- T1：真实查找报告（本机大概率无 RISC-V 工具链，但报告必须成型） ----------
        final Path gameDir = Files.createTempDirectory("cryptand-tool-test-");
        final CToolchainLocator locator = new CToolchainLocator(gameDir);
        final CToolchainReport real = locator.locate(CToolchains.firmwareToolchain());
        check("T1 报告含 2 个工具（gcc + objcopy）", real.tools().size() == 2);
        check("T1 展示行含标题", real.toDisplayLines().get(0).contains("编译工具链"));
        check("T1 展示行数量合理", real.toDisplayLines().size() >= 4);
        check("T1 轨迹非空（记录了每个来源的尝试）", real.traceLines().size() >= 6);
        check("T1 工具目录指向 cryptand/tool",
                locator.toolDir().toString().replace('\\', '/').endsWith("cryptand/tool"));
        System.out.println("  [info] 本机查找结果：");
        for (String line : real.toDisplayLines()) {
            System.out.println("         " + line);
        }

        // ---------- T2：系统 PATH 查找（平台自带工具） ----------
        final CToolSpec systemSpec = new CToolSpec("sys", "平台工具").names(probeToolName);
        final CToolchainReport sysReport = new CToolchainLocator(gameDir).locate(systemSpec);
        final CTool sysTool = sysReport.get("sys");
        check("T2 系统 PATH 命中（" + probeToolName + "）", sysTool.found());
        check("T2 来源标记为 SYSTEM_PATH", sysTool.source() == CTool.Source.SYSTEM_PATH);
        check("T2 探测到版本首行", sysTool.version() != null && !sysTool.version().isBlank());

        // ---------- T3：平台目录约定（tool/<platform>-<arch> > tool/<platform> > tool/general > 根） ----------
        final List<String> platformDirs = CToolchainLocator.platformDirNames();
        final String platformDir = platformDirs.get(0);          // 如 windows-x64
        final String exeName = probeToolName + (windows ? ".exe" : "");
        final Path toolDir = gameDir.resolve("cryptand").resolve("tool");

        check("T3 目录约定含平台目录", platformDir.length() > 0 && !platformDir.equals("general"));
        check("T3 目录约定含 general（通用）", platformDirs.contains("general"));
        check("T3 general 排在平台目录之后", platformDirs.indexOf("general") == platformDirs.size() - 1);
        check("T3 平台目录名与 natives 命名一致（platform-arch）",
                platformDir.contains("-") || platformDir.equals("general"));

        // general 目录命中
        final Path generalDir = toolDir.resolve("general");
        Files.createDirectories(generalDir);
        Files.copy(probeToolSource, generalDir.resolve(exeName));
        final CToolchainReport rGeneral = new CToolchainLocator(gameDir).locate(systemSpec);
        check("T3 tool/general 命中（通用工具）",
                rGeneral.get("sys").found() && rGeneral.get("sys").source() == CTool.Source.GAME_TOOL_DIR);
        check("T3 轨迹标注命中的目录名", rGeneral.traceLines().stream()
                .anyMatch(s -> s.contains("自带目录命中[general]")));

        // 平台目录优先于 general（同一工具两处都有 → 取平台目录）
        final Path platformDirPath = toolDir.resolve(platformDir);
        Files.createDirectories(platformDirPath);
        Files.copy(probeToolSource, platformDirPath.resolve(exeName));
        final CToolchainReport rPlatform = new CToolchainLocator(gameDir).locate(systemSpec);
        check("T3 平台目录优先于 general",
                rPlatform.get("sys").found() && rPlatform.get("sys").path().contains(platformDir));

        // ---------- T4：平台目录 + 子目录（tool/<platform>/riscv/bin/） ----------
        // ⚠ 用【独立临时目录】：Windows 下被执行过的 exe 可能拒绝删除（AccessDenied），
        //   故不复用上面的 gameDir（避免清理操作）。
        final Path gameDir2 = Files.createTempDirectory("cryptand-tool-test2-");
        final Path subDir = gameDir2.resolve("cryptand").resolve("tool")
                .resolve(platformDir).resolve("riscv").resolve("bin");
        Files.createDirectories(subDir);
        Files.copy(probeToolSource, subDir.resolve(exeName));
        final CToolchainReport rSub = new CToolchainLocator(gameDir2).locate(systemSpec);
        check("T4 平台目录子目录（<platform>/riscv/bin）命中",
                rSub.get("sys").source() == CTool.Source.GAME_TOOL_DIR);
        check("T4 提示列出平台目录与 general",
                new CToolchainLocator(gameDir2).toolDirHint().contains("general")
                        && new CToolchainLocator(gameDir2).toolDirHint().contains(platformDir));

        // ---------- T5：缺工具提示 + 服务端回退标记 ----------
        final CTool missing = CTool.missing("riscv-gcc");
        check("T5 缺失工具 found()=false", !missing.found());
        check("T5 缺失提示非空", !real.missingHints().isEmpty());
        check("T5 缺失提示含安装指引",
                real.missingHints().stream().anyMatch(s -> s.contains("cryptand/tool") || s.contains("xPack")));
        final CTool fallback = CTool.serverFallback("riscv-gcc");
        check("T5 服务端回退标记", fallback.source() == CTool.Source.SERVER_FALLBACK);
        check("T5 服务端回退展示文案", fallback.displayLine("RISC-V GCC").contains("服务器代编译"));

        // ---------- T6：诊断行解析（file:line:col: severity: message） ----------
        final String fakeOutput = String.join("\n",
                "/tmp/soc-x/main.c:12:5: error: 'x' undeclared (first use in this function)",
                "/tmp/soc-x/main.c:20: warning: unused variable 'y'",
                "note: unrelated trailing note without location",
                "ld: cannot find cryptand.ld");
        final List<CCompileResult.Diagnostic> diags = LocalCToolchainCompiler.parseDiagnostics(fakeOutput);
        check("T6 解析出 2 条带定位的诊断", diags.size() == 2);
        check("T6 首条 file:line:col 正确",
                diags.get(0).line() == 12 && diags.get(0).column() == 5
                        && diags.get(0).severity().equals("error"));
        check("T6 次条无列号时列=0",
                diags.get(1).line() == 20 && diags.get(1).column() == 0
                        && diags.get(1).severity().equals("warning"));

        // ---------- T7：工具缺失时编译返回失败结果（不抛异常） ----------
        final Path workRoot = Files.createTempDirectory("cryptand-cc-");
        final CCompilerBackend broken = new LocalCToolchainCompiler(workRoot,
                CTool.missing("riscv-gcc"), CTool.missing("riscv-objcopy"));
        check("T7 缺工具时 available()=false", !broken.available());
        final CCompileResult r = broken.compile(new CCompileRequest("blinky", "int main(void){return 0;}"));
        check("T7 返回失败而非抛异常", !r.ok());
        check("T7 失败原因含'缺少工具'", r.error().contains("缺少工具"));
        check("T7 失败原因含服务端提示", r.error().contains("服务端"));

        // ---------- T8：编译请求参数（march/mabi/附加文件/链接脚本/超时） ----------
        final CCompileRequest req = new CCompileRequest("blinky", "int main(void){for(;;);}")
                .march("rv32im").mabi("ilp32")
                .file("cryptand.ld", "ENTRY(_start)")
                .linkerScript("cryptand.ld")
                .timeoutMs(5_000)
                .arg("-Os");
        check("T8 march 默认 rv32im", req.march().equals("rv32im"));
        check("T8 mabi 默认 ilp32（无浮点 ABI）", req.mabi().equals("ilp32"));
        check("T8 附加文件已登记", req.extraFiles().containsKey("cryptand.ld"));
        check("T8 链接脚本已设置", req.linkerScript().equals("cryptand.ld"));
        check("T8 额外参数已登记", req.extraArgs().contains("-Os"));
        check("T8 超时下限保护", new CCompileRequest("x", "").timeoutMs(10).timeoutMs() >= 1_000);

        // ---------- T9：预定义工具链描述 ----------
        check("T9 gcc spec 候选名含 xPack 命名",
                CToolchains.riscvGcc().executableNames().contains("riscv-none-elf-gcc"));
        check("T9 objcopy spec 与 gcc 同名前缀",
                CToolchains.riscvObjcopy().executableNames().contains("riscv-none-elf-objcopy"));
        check("T9 固件工具链含 2 项", CToolchains.firmwareToolchain().size() == 2);
        check("T9 编译器工具链描述可读",
                CToolchains.compiler(real, workRoot).describeToolchain().contains("缺少 riscv-gcc"));

        System.out.println();
        System.out.print(log);
        System.out.println();
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            log.append("  [PASS] ").append(name).append('\n');
        } else {
            failed++;
            log.append("  [FAIL] ").append(name).append('\n');
        }
    }
}
