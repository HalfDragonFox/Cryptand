package com.hdf.cryptand.neoforge.dynamic;

import com.hdf.cryptand.dynamic.api.DynamicApi;
import com.hdf.cryptand.dynamic.api.FrameworkContext;
import com.hdf.cryptand.dynamic.api.ResourceSource;
import com.hdf.cryptand.dynamic.api.Source;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ===== mod 内嵌来源（形态①：独立资源 mod）=====
 *
 * <p>用户定案：「多 mod 分（资源 mod 与实际 mod）」。资源 mod 只需是普通 mod jar，
 * 里面放 `META-INF/cryptand-dynamic/<coreId>/*.jar`；宿主启动时把它们<b>提取</b>到
 * `<root>/<modId>/`（幂等，size 相同则跳过），之后按普通外部包走热重载路径。</p>
 *
 * <p>取证依据（javap，fancymodloader loader-4.0.42.jar）：`ModList.getModFiles()` →
 * `IModFileInfo.getFile()` → `IModFile.getFilePath()`、`IModFileInfo.getMods()` →
 * `IModInfo.getModId()`。JarJar 内嵌的 mod 同样在列表里有真实解压路径。</p>
 *
 * <p><b>优先级</b>：把本来源注册在目录来源<b>之前</b>，则外部目录同名包会后加载并接管（外部优先）。</p>
 */
public final class ModEmbeddedSource implements ResourceSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public String id() {
        return "mod-embedded";
    }

    @Override
    public List<Source> discover(FrameworkContext ctx) {
        final List<Source> out = new ArrayList<>();
        final Path root = ctx.root();
        for (final IModFileInfo info : ModList.get().getModFiles()) {
            final IModFile file = info.getFile();
            if (file == null) {
                continue;
            }
            final Path jar = file.getFilePath();
            if (jar == null || !Files.isRegularFile(jar)) {
                continue;
            }
            final String modId = modIdOf(info, jar);
            extract(jar, root.resolve(modId), out, modId);
        }
        return out;
    }

    private static String modIdOf(IModFileInfo info, Path jar) {
        try {
            if (info.getMods() != null && !info.getMods().isEmpty()) {
                return info.getMods().get(0).getModId();
            }
        } catch (Throwable ignored) {
            // 退回到文件名
        }
        final String n = jar.getFileName().toString();
        return n.endsWith(".jar") ? n.substring(0, n.length() - 4) : n;
    }

    /** 把 mod jar 里的内嵌动态包解到 outDir（幂等：size 相同跳过）。 */
    private static void extract(Path modJar, Path outDir, List<Source> out, String modId) {
        try (ZipFile zip = new ZipFile(modJar.toFile())) {
            final Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                final ZipEntry entry = it.nextElement();
                final String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(DynamicApi.MOD_EMBED_DIR)
                        || !name.endsWith(".jar")) {
                    continue;
                }
                final String rel = name.substring(DynamicApi.MOD_EMBED_DIR.length());
                if (rel.isBlank() || rel.contains("..")) {
                    continue;
                }
                final Path target = outDir.resolve(rel);
                try {
                    if (needsWrite(target, entry.getSize())) {
                        Files.createDirectories(target.getParent());
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        LOGGER.info("[dynamic] 从 mod {} 提取内嵌动态包 {} -> {}", modId, rel, target);
                    }
                    out.add(Source.mod(target, "mod:" + modId));
                } catch (IOException ex) {
                    LOGGER.warn("[dynamic] 提取 {} 的 {} 失败：{}", modId, rel, ex.toString());
                }
            }
        } catch (IOException ex) {
            // 很多 mod 不是"可直接开的普通 jar 文件"（JarJar 内嵌 / 目录型 / 虚拟文件系统）——
            // 这类没有内嵌动态包，属正常情况，降为 debug 免得刷屏
            LOGGER.debug("[dynamic] 跳过 mod {}（无法作为普通 jar 读取：{}）", modId, ex.toString());
        }
    }

    private static boolean needsWrite(Path target, long entrySize) {
        try {
            return !Files.isRegularFile(target) || Files.size(target) != entrySize;
        } catch (IOException ex) {
            return true;
        }
    }
}
