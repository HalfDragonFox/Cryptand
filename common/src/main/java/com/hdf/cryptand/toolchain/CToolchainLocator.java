package com.hdf.cryptand.toolchain;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ===== 工具链查找器（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p><b>查找顺序（用户定稿）</b>：</p>
 * <ol>
 *   <li><b>游戏目录自带</b>：{@code <gameDir>/cryptand/tool/}（含一层子目录，如
 *       {@code tool/riscv/bin/}）——整合包/玩家可替换，最高优先级；</li>
 *   <li><b>系统查找</b>：调用 {@code where}（Windows）/ {@code which}（POSIX）
 *       解析 PATH —— "类似 cmd 调用查找"；</li>
 *   <li><b>常见安装路径</b>：xPack / 发行版包管理器 / 常见盘符目录；</li>
 *   <li><b>服务端代编译</b>：由上层路由决定（本类只标记
 *       {@link CTool.Source#SERVER_FALLBACK}）；</li>
 *   <li>全都没有 → 返回缺失诊断（UI 提示"缺少 xxx 工具"）。</li>
 * </ol>
 *
 * <p>找到后可执行文件会执行一次 {@code --version}（超时 2s）取版本行——
 * 既是"确认可用"，也是给界面打印的信息。</p>
 *
 * <p>⚠ 涉及进程调用（IO），应在后台线程使用。</p>
 */
public final class CToolchainLocator {

    private static final long PROBE_TIMEOUT_MS = 2_000;

    private final Path gameDir;
    private final Path toolDir;
    private final List<Path> commonRoots = new ArrayList<>();

    /**
     * @param gameDir 游戏运行目录（平台侧传入 {@code FMLPaths.GAMEDIR} 等；common 不依赖 MC）
     */
    public CToolchainLocator(Path gameDir) {
        this.gameDir = gameDir;
        this.toolDir = gameDir == null ? null : gameDir.resolve("cryptand").resolve("tool");
    }

    /** 追加"常见安装路径"根（可选；不存在则跳过） */
    public CToolchainLocator commonRoot(Path root) {
        if (root != null) {
            commonRoots.add(root);
        }
        return this;
    }

    /** 自带工具目录（UI 显示"把工具放这里"用） */
    public Path toolDir() {
        return toolDir;
    }

    /**
     * 平台目录名（按优先级；与项目 natives 命名一致，如 {@code windows-x64}）。
     *
     * <p>目录约定（用户 2026-09-15 定稿）：</p>
     * <pre>
     *   cryptand/tool/&lt;platform&gt;-&lt;arch&gt;/   ① 最具体（如 windows-x64、linux-arm64）
     *   cryptand/tool/&lt;platform&gt;/         ② 平台级（如 windows）
     *   cryptand/tool/general/            ③ 通用（跨平台工具：脚本/纯 Java 工具等）
     *   cryptand/tool/                    ④ 根（兼容手放/旧布局）
     * </pre>
     * 每个目录下再依次尝试 {@link CToolSpec#relativePaths()}（如 {@code bin/}）与一层子目录。
     */
    public static List<String> platformDirNames() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String platform = os.contains("win") ? "windows"
                : (os.contains("mac") || os.contains("darwin")) ? "macos"
                : os.contains("linux") ? "linux" : "";
        final String archName = switch (arch) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i686" -> "x86";
            default -> arch;
        };
        final List<String> out = new ArrayList<>();
        if (!platform.isEmpty()) {
            out.add(platform + "-" + archName);   // 与 natives/windows-x64 命名一致
            out.add(platform);
        }
        out.add("general");                       // 通用（跨平台工具）
        return out;
    }

    /** UI 提示：把工具放到哪些目录（实际存在的会标注） */
    public String toolDirHint() {
        if (toolDir == null) {
            return "(无游戏目录)";
        }
        final StringBuilder sb = new StringBuilder();
        for (String name : platformDirNames()) {
            sb.append("\n    ").append(toolDir.resolve(name))
                    .append(Files.isDirectory(toolDir.resolve(name)) ? "  [已存在]" : "");
        }
        return sb.toString();
    }

    /** 按顺序查找全部 spec */
    public CToolchainReport locate(List<CToolSpec> specs) {
        final long start = System.nanoTime();
        final CToolchainReport report = new CToolchainReport();
        for (CToolSpec spec : specs) {
            report.addSpec(spec);
            report.put(locateOne(spec, report));
        }
        report.total(System.nanoTime() - start);
        return report;
    }

    public CToolchainReport locate(CToolSpec... specs) {
        return locate(List.of(specs));
    }

    // ==================== 单工具查找 ====================

    private CTool locateOne(CToolSpec spec, CToolchainReport report) {
        // ① 游戏目录自带
        CTool tool = searchLocal(spec, report);
        if (tool != null) {
            return tool;
        }
        // ② 系统 PATH（where / which）
        tool = searchSystemPath(spec, report);
        if (tool != null) {
            return tool;
        }
        // ③ 常见安装路径
        tool = searchCommonRoots(spec, report);
        if (tool != null) {
            return tool;
        }
        report.trace("[" + spec.id() + "] 未找到（自带目录/系统 PATH/常见路径均无）");
        return CTool.missing(spec.id());
    }

    /**
     * ① 游戏目录自带：{@code cryptand/tool/} 下按
     * {@link #platformDirNames()}（平台+架构 → 平台 → general → 根）依次查找。
     */
    private CTool searchLocal(CToolSpec spec, CToolchainReport report) {
        if (toolDir == null) {
            return null;
        }
        for (String dirName : platformDirNames()) {
            final CTool found = searchInBase(spec, toolDir.resolve(dirName), dirName, report);
            if (found != null) {
                return found;
            }
        }
        // ④ 根目录（兼容手放/旧布局）
        final CTool atRoot = searchInBase(spec, toolDir, "(tool 根)", report);
        if (atRoot != null) {
            return atRoot;
        }
        report.trace("[" + spec.id() + "] 自带目录无（已试 " + String.join(", ", platformDirNames())
                + ", 根目录）");
        return null;
    }

    /** 在某个自带目录基址下查找（含 relativePaths 与一层子目录，如 tool/riscv/bin） */
    private CTool searchInBase(CToolSpec spec, Path base, String label, CToolchainReport report) {
        if (!Files.isDirectory(base)) {
            return null;
        }
        final List<Path> dirs = new ArrayList<>();
        dirs.add(base);
        for (String rel : spec.relativePaths()) {
            dirs.add(base.resolve(rel));
        }
        try (var stream = Files.list(base)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child)) {
                    dirs.add(child);
                    dirs.add(child.resolve("bin"));
                    for (String rel : spec.relativePaths()) {
                        dirs.add(child.resolve(rel));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        for (Path dir : dirs) {
            for (String name : candidates(spec)) {
                final Path exe = dir.resolve(name);
                if (Files.isRegularFile(exe)) {
                    report.trace("[" + spec.id() + "] 自带目录命中[" + label + "]: " + exe);
                    return probe(spec, exe, CTool.Source.GAME_TOOL_DIR, report);
                }
            }
        }
        return null;
    }

    /** ② 系统查找：where（Windows）/ which（POSIX） */
    private CTool searchSystemPath(CToolSpec spec, CToolchainReport report) {
        final boolean windows = isWindows();
        for (String name : candidates(spec)) {
            final String resolved = resolveOnPath(name, windows);
            if (resolved != null) {
                report.trace("[" + spec.id() + "] 系统 PATH 命中: " + resolved);
                return probe(spec, Path.of(resolved), CTool.Source.SYSTEM_PATH, report);
            }
        }
        report.trace("[" + spec.id() + "] 系统 PATH 无（" + String.join(", ", candidates(spec)) + "）");
        return null;
    }

    /** ③ 常见安装路径 */
    private CTool searchCommonRoots(CToolSpec spec, CToolchainReport report) {
        for (Path root : commonRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            final List<Path> dirs = new ArrayList<>();
            dirs.add(root);
            dirs.add(root.resolve("bin"));
            for (String rel : spec.relativePaths()) {
                dirs.add(root.resolve(rel));
                dirs.add(root.resolve(rel).resolve("bin"));
            }
            for (Path dir : dirs) {
                for (String name : candidates(spec)) {
                    final Path exe = dir.resolve(name);
                    if (Files.isRegularFile(exe)) {
                        report.trace("[" + spec.id() + "] 常见路径命中: " + exe);
                        return probe(spec, exe, CTool.Source.COMMON_PATH, report);
                    }
                }
            }
        }
        report.trace("[" + spec.id() + "] 常见路径无（已试 " + commonRoots.size() + " 个根）");
        return null;
    }

    // ==================== 探测 ====================

    /** 执行 {@code --version} 取首行（超时 2s；失败不视为找到） */
    private CTool probe(CToolSpec spec, Path exe, CTool.Source source, CToolchainReport report) {
        final long start = System.nanoTime();
        String version = "";
        try {
            final ProcessBuilder pb = new ProcessBuilder(exe.toString(), "--version");
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                version = reader.readLine();
            }
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                report.trace("[" + spec.id() + "] 探测超时: " + exe);
                return CTool.missing(spec.id());
            }
        } catch (Throwable t) {
            report.trace("[" + spec.id() + "] 探测失败: " + exe + " (" + t.getClass().getSimpleName() + ")");
            return CTool.missing(spec.id());
        }
        final long elapsed = System.nanoTime() - start;
        final String v = version == null ? "" : version.trim();
        report.trace("[" + spec.id() + "] 探测成功: " + v + " (" + (elapsed / 1_000_000) + "ms)");
        return new CTool(spec.id(), exe.toString(), v, source, elapsed);
    }

    // ==================== 平台工具 ====================

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 候选文件名：原名 + 平台扩展名 */
    private static List<String> candidates(CToolSpec spec) {
        final List<String> out = new ArrayList<>();
        for (String name : spec.executableNames()) {
            out.add(name);
            if (isWindows() && !name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                out.add(name + ".exe");
            }
        }
        return out;
    }

    /** where / which 解析（"类似 cmd 调用查找"） */
    private static String resolveOnPath(String name, boolean windows) {
        final String finder = windows ? "where" : "which";
        try {
            final ProcessBuilder pb = new ProcessBuilder(finder, name);
            pb.redirectErrorStream(true);
            final Process process = pb.start();
            String first = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                first = reader.readLine();
            }
            process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (first != null && !first.isBlank() && Files.isRegularFile(Path.of(first.trim()))) {
                return first.trim();
            }
        } catch (Throwable ignored) {
        }
        // 兜底：PATH 目录逐一探测（where/which 不可用时）
        final String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                final Path exe = Path.of(dir, windows ? name + ".exe" : name);
                if (Files.isRegularFile(exe)) {
                    return exe.toString();
                }
            }
        }
        return null;
    }
}
