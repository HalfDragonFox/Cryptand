/**
 * ===== 亚层持久化 sable 原版风格后端（2026-08-31） =====
 *
 * 模仿官方 sable 的 SubLevelStorage（按亚层/区域存 NBT 文件），但适配我们的
 * 内存语义（存 PersistedSubLevel：方块快照列表，不需要官方 region/storage 文件
 * 复杂度和 chunk 布局）：
 *
 *   <save>/sable_sublevels/<dimension>/<uuid>.dat   （NbtIo.writeCompressed）
 *
 * 优点：与 sable 原版一致（文件数量级 = 亚层数），无外部依赖；调试直观。
 * 缺点：大量亚层时文件数多（查询/批量删慢）→ 用户："大规模可能sqlite好"。
 */
package com.hdf.cryptand.neoforge.cryptandsable.persistence;

import net.minecraft.FileUtil;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class SableStyleSubLevelBackend implements ISubLevelPersistenceBackend {

    private static final Logger LOGGER = LogManager.getLogger("cryptand");

    private final Path root;
    private volatile boolean closed = false;

    public SableStyleSubLevelBackend(Path root) {
        this.root = root;
    }

    private Path dimensionDir(ServerLevel level) {
        return root.resolve(level.dimension().location().toString().replaceAll("[^a-zA-Z0-9_.-]", "_"));
    }

    private Path fileFor(ServerLevel level, UUID uuid) {
        return dimensionDir(level).resolve(uuid + ".dat");
    }

    @Override
    public void save(ServerLevel level, PersistedSubLevel data) {
        if (closed || data == null) return;
        try {
            final Path dir = dimensionDir(level);
            FileUtil.createDirectoriesSafe(dir);
            final Path tmp = fileFor(level, data.subLevelId()).resolveSibling(data.subLevelId() + ".tmp");
            NbtIo.writeCompressed(data.toTag(), tmp);
            Files.move(tmp, fileFor(level, data.subLevelId()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sable-style save failed for {}", data.subLevelId(), e);
        }
    }

    @Override
    public PersistedSubLevel load(ServerLevel level, UUID subLevelId) {
        if (closed || subLevelId == null) return null;
        try {
            final Path p = fileFor(level, subLevelId);
            if (!Files.exists(p)) return null;
            try (var in = Files.newInputStream(p)) {
                return PersistedSubLevel.fromTag(NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.create(0x7FFFFFFF)));
            }
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sable-style load failed for {}", subLevelId, e);
            return null;
        }
    }

    @Override
    public List<PersistedSubLevel> loadAll(ServerLevel level) {
        if (closed) return List.of();
        final Path dir = dimensionDir(level);
        if (!Files.exists(dir)) return List.of();
        final List<PersistedSubLevel> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".dat")).forEach(p -> {
                try {
                    final String name = p.getFileName().toString();
                    final UUID uuid = UUID.fromString(name.substring(0, name.length() - 4));
                    try (var in = Files.newInputStream(p)) {
                        out.add(PersistedSubLevel.fromTag(
                                NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.create(0x7FFFFFFF))));
                    }
                } catch (Exception ignored) {
                    // 坏文件跳过
                }
            });
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sable-style loadAll failed", e);
        }
        return out;
    }

    @Override
    public void delete(ServerLevel level, UUID subLevelId) {
        if (closed || subLevelId == null) return;
        try {
            Files.deleteIfExists(fileFor(level, subLevelId));
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sable-style delete failed for {}", subLevelId, e);
        }
    }

    @Override
    public void clearAll(ServerLevel level) {
        if (closed) return;
        try {
            final Path dir = dimensionDir(level);
            if (!Files.exists(dir)) return;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".dat")).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sable-style clearAll failed", e);
        }
    }

    @Override
    public void flush() {
        // 文件法同步写（writeCompressed 已落盘）；无队列
    }

    @Override
    public void close() {
        closed = true;
    }
}
