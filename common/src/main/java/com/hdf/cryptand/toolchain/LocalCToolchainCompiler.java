package com.hdf.cryptand.toolchain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ===== 本地工具链编译器（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>用外部 RISC-V 交叉工具链编译裸机固件：</p>
 * <pre>
 *   &lt;gcc&gt; -march=rv32im -mabi=ilp32 -nostdlib -ffreestanding -T cryptand.ld
 *         -o firmware.elf firmware.c
 *   &lt;objcopy&gt; -O binary firmware.elf firmware.bin
 * </pre>
 *
 * <p>工具缺失时不抛异常，返回带"缺少 xxx 工具"的失败结果（UI 直接提示）。</p>
 *
 * <p>⚠ 进程调用是 IO，须在后台线程使用（勿在 MC 主线程）。</p>
 */
public final class LocalCToolchainCompiler implements CCompilerBackend {

    /** gcc 诊断行：file:line[:col]: severity: message */
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^(.+?):(\\d+)(?::(\\d+))?:\\s*(fatal error|error|warning|note)\\s*:\\s*(.*)$");

    private final Path workRoot;
    private final CTool gcc;
    private final CTool objcopy;
    private final String label;

    public LocalCToolchainCompiler(Path workRoot, CTool gcc, CTool objcopy) {
        this.workRoot = workRoot;
        this.gcc = gcc;
        this.objcopy = objcopy;
        this.label = describe(gcc, objcopy);
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public boolean available() {
        return gcc != null && gcc.found() && objcopy != null && objcopy.found();
    }

    /** 工具链描述（UI 复述："将使用 … "） */
    public String describeToolchain() {
        return label;
    }

    private static String describe(CTool gcc, CTool objcopy) {
        final StringBuilder sb = new StringBuilder();
        sb.append(gcc != null && gcc.found()
                ? gcc.path() + " (" + gcc.version() + ")"
                : "缺少 riscv-gcc");
        sb.append(" + ");
        sb.append(objcopy != null && objcopy.found()
                ? objcopy.path()
                : "缺少 riscv-objcopy");
        return sb.toString();
    }

    @Override
    public CCompileResult compile(CCompileRequest request) {
        final long start = System.nanoTime();
        if (!available()) {
            final List<String> missing = new ArrayList<>();
            if (gcc == null || !gcc.found()) {
                missing.add("RISC-V GCC 交叉编译器");
            }
            if (objcopy == null || !objcopy.found()) {
                missing.add("RISC-V objcopy");
            }
            return CCompileResult.failure("缺少工具：" + String.join("、", missing)
                    + "（可放入 <游戏目录>/cryptand/tool/ 或安装后加入 PATH；"
                    + "服务端开启编译功能时可请求代编译）", label, 0);
        }

        Path dir = null;
        try {
            // ⚠ Files.createTempDirectory 要求**父目录已存在**：workRoot（如 run/cryptand/tmp）
            //   若还没建过就会抛 NoSuchFileException（2026-09-15 实测：
            //   "编译异常：NoSuchFileException: .../cryptand/tmp/soc-blinky-9364199772710036789"）
            final Path tempRoot = workRoot == null ? Path.of(".") : workRoot;
            Files.createDirectories(tempRoot);
            dir = Files.createTempDirectory(tempRoot, "soc-" + request.programName() + "-");
            final Path sourceFile = dir.resolve("main.c");
            Files.writeString(sourceFile, request.source(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> e : request.extraFiles().entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }

            final Path elf = dir.resolve("firmware.elf");
            final Path bin = dir.resolve("firmware.bin");

            // ---------- 编译 ----------
            final List<String> cc = new ArrayList<>();
            cc.add(gcc.path());
            cc.add("-march=" + request.march());
            cc.add("-mabi=" + request.mabi());
            cc.add("-nostdlib");
            cc.add("-ffreestanding");
            cc.add("-fno-builtin");
            cc.add("-Wall");
            cc.add("-O2");
            if (!request.linkerScript().isBlank()) {
                cc.add("-T");
                cc.add(request.linkerScript());
            }
            cc.addAll(request.extraArgs());
            cc.add("-o");
            cc.add(elf.getFileName().toString());
            cc.add(sourceFile.getFileName().toString());

            final ExecOutcome ccOut = exec(dir, cc, request.timeoutMs());
            final List<CCompileResult.Diagnostic> diagnostics = parseDiagnostics(ccOut.output);
            if (ccOut.timedOut) {
                return CCompileResult.failure("编译超时（" + request.timeoutMs() + "ms）", label,
                        (System.nanoTime() - start) / 1_000_000);
            }
            if (ccOut.exitCode != 0 || !Files.exists(elf)) {
                return new CCompileResult(false, null, ccOut.output, "", diagnostics, label,
                        (System.nanoTime() - start) / 1_000_000,
                        "编译失败（退出码 " + ccOut.exitCode + "）");
            }

            // ---------- 转纯二进制 ----------
            final List<String> oc = List.of(objcopy.path(), "-O", "binary",
                    elf.getFileName().toString(), bin.getFileName().toString());
            final ExecOutcome ocOut = exec(dir, oc, request.timeoutMs());
            if (ocOut.exitCode != 0 || !Files.exists(bin)) {
                return new CCompileResult(false, null, ccOut.output + "\n" + ocOut.output, "", diagnostics,
                        label, (System.nanoTime() - start) / 1_000_000,
                        "objcopy 失败（退出码 " + ocOut.exitCode + "）");
            }

            final byte[] binary = Files.readAllBytes(bin);
            return CCompileResult.of(binary, ccOut.output, ocOut.output, diagnostics, label,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            return CCompileResult.failure("编译异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()), label,
                    (System.nanoTime() - start) / 1_000_000);
        } finally {
            cleanup(dir);
        }
    }

    /** 解析 gcc 诊断（映射回编辑器行号） */
    public static List<CCompileResult.Diagnostic> parseDiagnostics(String output) {
        final List<CCompileResult.Diagnostic> out = new ArrayList<>();
        if (output == null) {
            return out;
        }
        for (String raw : output.split("\\R")) {
            final Matcher m = DIAGNOSTIC.matcher(raw.trim());
            if (m.matches()) {
                out.add(new CCompileResult.Diagnostic(
                        m.group(1),
                        Integer.parseInt(m.group(2)),
                        m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                        m.group(4),
                        m.group(5)));
            }
        }
        return out;
    }

    // ==================== 进程调用 ====================

    private record ExecOutcome(int exitCode, String output, boolean timedOut) {
    }

    private static ExecOutcome exec(Path dir, List<String> command, int timeoutMs) {
        final StringBuilder sb = new StringBuilder();
        try {
            final ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ExecOutcome(-1, sb.toString(), true);
            }
            return new ExecOutcome(process.exitValue(), sb.toString(), false);
        } catch (Throwable t) {
            return new ExecOutcome(-1, sb + "\n[exec error] " + t.getClass().getSimpleName()
                    + ": " + t.getMessage(), false);
        }
    }

    private static void cleanup(Path dir) {
        if (dir == null) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 平台扩展名辅助（诊断） */
    public static String exeSuffix() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "";
    }
}
