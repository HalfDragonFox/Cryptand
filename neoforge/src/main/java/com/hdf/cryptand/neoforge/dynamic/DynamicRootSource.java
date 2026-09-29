package com.hdf.cryptand.neoforge.dynamic;

import com.hdf.cryptand.dynamic.api.DynamicApi;
import com.hdf.cryptand.dynamic.api.FrameworkContext;
import com.hdf.cryptand.dynamic.api.ResourceSource;
import com.hdf.cryptand.dynamic.api.Source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ===== 统一目录来源（形态②：{@code GAMEDIR/cryptand/dynamic/}）=====
 *
 * <p>扫描 {@code <root>/}（平铺）与 {@code <root>/<coreId>/}（按核心分类，一层），
 * 并兼容历史 UI 目录 {@code cryptand/dyui/}（命中时打日志提示迁移）。</p>
 *
 * <p>来源只负责"发现"，不按扩展名过滤 —— 能不能处理由核心的 {@code canHandle} 决定。</p>
 */
public final class DynamicRootSource implements ResourceSource {

    private final Path legacyDir;

    public DynamicRootSource(Path legacyDir) {
        this.legacyDir = legacyDir;
    }

    @Override
    public String id() {
        return "dynamic-root";
    }

    @Override
    public List<Source> discover(FrameworkContext ctx) {
        final List<Source> out = new ArrayList<>();
        collect(out, ctx.root(), 2, Source::dir);
        if (legacyDir != null && Files.isDirectory(legacyDir) && !legacyDir.equals(ctx.root())) {
            final int before = out.size();
            collect(out, legacyDir, 1, (p, origin) -> Source.dir(p, DynamicApi.LEGACY_UI_DIR));
            if (out.size() > before) {
                ctx.log("[dynamic] 检测到历史目录 " + DynamicApi.LEGACY_UI_DIR
                        + "，建议把内容迁到 " + DynamicApi.ROOT_DIR + "/（当前仍兼容扫描）");
            }
        }
        return out;
    }

    private interface SourceFactory {
        Source make(Path p, String origin);
    }

    private static void collect(List<Source> out, Path dir, int depthMax, java.util.function.BiFunction<Path, String, Source> factory) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        collect(out, dir, depthMax, dir.toString(), factory);
    }

    private static void collect(List<Source> out, Path dir, int depthLeft, String origin,
                                java.util.function.BiFunction<Path, String, Source> factory) {
        try (var s = Files.list(dir)) {
            for (final Path p : s.sorted().toList()) {
                if (Files.isDirectory(p)) {
                    if (depthLeft > 1) {
                        collect(out, p, depthLeft - 1, origin, factory);
                    }
                } else if (Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) {
                    out.add(factory.apply(p, origin));
                }
            }
        } catch (IOException ex) {
            // 目录不可读：跳过（不拖垮整次扫描）
        }
    }
}
