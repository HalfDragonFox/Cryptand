/**
 * ===== 亚层持久化门面（2026-08-31） =====
 *
 * 用户要求：持久化交给一个类处理，可选择 sqlite 或 sable 原版方法
 * （大规模可能 sqlite 好）。
 *
 * 配置：config/cryptand/cryptand-sable.toml → persistence = "sqlite" | "sable"（默认 sqlite）。
 * 后端启动时按配置创建（世界加载时：loadAll 恢复亚层）。
 *
 * 生命周期（由 CryptandSable.install 挂钩）：
 *   LevelEvent.Load   → onWorldLoad：创建后端 + 恢复全部亚层（重建 subLevel/MOVED/核心体）
 *   LevelEvent.Unload → onWorldUnload：flush + close
 *
 * 集成（由 CryptandSubLevelApi 调用）：
 *   physicalize 保存 / disassemble 删除。
 */
package com.hdf.cryptand.neoforge.cryptandsable.persistence;

import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.SubLevelCommandBus;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class CryptandSubLevelPersistence {

    private static final Logger LOGGER = LogManager.getLogger("cryptand");

    /** 后端选择（配置决定，创建后固定）。 */
    public enum BackendKind { SQLITE, SABLE_STYLE }

    private static volatile CryptandSubLevelPersistence INSTANCE;

    private volatile ISubLevelPersistenceBackend backend;
    private volatile ServerLevel boundLevel;
    private volatile boolean closed = false;

    public static CryptandSubLevelPersistence instance() {
        if (INSTANCE == null) {
            synchronized (CryptandSubLevelPersistence.class) {
                if (INSTANCE == null) INSTANCE = new CryptandSubLevelPersistence();
            }
        }
        return INSTANCE;
    }

    private CryptandSubLevelPersistence() {
    }

    /** 从配置读取后端类型（NeoForge 官方 spec；默认 sable 原版方法）。 */
    public static BackendKind configuredKind() {
        try {
            final String mode = ConfigCryptandSable.SABLE_PERSISTENCE.get();
            if ("sqlite".equalsIgnoreCase(mode)) return BackendKind.SQLITE;
        } catch (Throwable t) {
            // spec 不可用（极早期）→ 默认 sable 原版
        }
        return BackendKind.SABLE_STYLE;
    }

    /** 世界加载：创建后端（按配置）并恢复该世界全部亚层。 */
    public synchronized void onWorldLoad(ServerLevel level) {
        if (closed || level == null || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            return;
        }
        closeBackend();
        boundLevel = level;
        final Path saveDir = level.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("sable_sublevels");
        final BackendKind kind = configuredKind();
        try {
            backend = switch (kind) {
                case SQLITE -> new SqliteSubLevelBackend(saveDir.resolve("sable_sublevels.sqlite"));
                case SABLE_STYLE -> new SableStyleSubLevelBackend(saveDir);
            };
            LOGGER.info("[SubLevelPersistence] backend={} dir={}", kind, saveDir);
        } catch (Throwable t) {
            LOGGER.error("[SubLevelPersistence] backend create failed, fallback memory-null", t);
            backend = null;
        }
        // 恢复已保存亚层
        if (backend != null) {
            final List<PersistedSubLevel> saved = backend.loadAll(level);
            int restored = 0;
            for (final PersistedSubLevel p : saved) {
                if (CryptandSubLevelApi.restorePersisted(level, p)) {
                    restored++;
                }
            }
            LOGGER.info("[SubLevelPersistence] restored {} sub-level(s) for {}", restored, level.dimension());
        }
    }

    /** 世界卸载：flush + close。 */
    public synchronized void onWorldUnload(ServerLevel level) {
        closeBackend();
        boundLevel = null;
    }

    /** 保存（API 物理化后调；幂等，异步）。 */
    public void save(ServerLevel level, PersistedSubLevel data) {
        final ISubLevelPersistenceBackend b = backend;
        if (b != null && data != null) {
            try {
                b.save(level, data);
            } catch (Throwable t) {
                LOGGER.error("[SubLevelPersistence] save failed", t);
            }
        }
    }

    /** 读一个亚层持久化记录（诊断/导出用；null=不存在）。 */
    public PersistedSubLevel load(ServerLevel level, UUID subLevelId) {
        final ISubLevelPersistenceBackend b = backend;
        if (b != null && subLevelId != null) {
            try {
                return b.load(level, subLevelId);
            } catch (Throwable t) {
                LOGGER.error("[SubLevelPersistence] load failed for {}", subLevelId, t);
            }
        }
        return null;
    }

    /**
     * 异步保存（2026-08-31 存储优化）：经 {@link SubLevelCommandBus} 按亚层 uuid
     * 投递写盘——不阻塞主线程（sable 文件法 NBT 压缩写盘较慢）；同亚层串行、
     * 异亚层并行。世界卸载时 {@link #flush()} 等待落盘。
     */
    public void saveAsync(ServerLevel level, PersistedSubLevel data) {
        final ISubLevelPersistenceBackend b = backend;
        if (b != null && data != null && level != null) {
            try {
                SubLevelCommandBus
                        .instance().post("persist-save:" + data.subLevelId(), () -> {
                            try {
                                b.save(level, data);
                            } catch (Throwable t) {
                                LOGGER.error("[SubLevelPersistence] async save failed for {}",
                                        data.subLevelId(), t);
                            }
                        });
            } catch (Throwable t) {
                LOGGER.error("[SubLevelPersistence] saveAsync submit failed", t);
            }
        }
    }

    /** 删除（API 拆卸后调）。 */
    public void delete(ServerLevel level, UUID subLevelId) {
        final ISubLevelPersistenceBackend b = backend;
        if (b != null && subLevelId != null) {
            try {
                b.delete(level, subLevelId);
            } catch (Throwable t) {
                LOGGER.error("[SubLevelPersistence] delete failed for {}", subLevelId, t);
            }
        }
    }

    /** 强制清空某维度全部亚层持久化记录（removeAll 用；即使个别 delete 失败也兜底）。 */
    public void clearAll(ServerLevel level) {
        final ISubLevelPersistenceBackend b = backend;
        if (b != null && level != null) {
            try {
                b.clearAll(level);
                return;
            } catch (Throwable t) {
                LOGGER.error("[SubLevelPersistence] clearAll failed, try force file wipe", t);
            }
        }
        // 兜底：直接清空 sable_sublevels 目录下该维度全部 .dat（即使后端异常/未创建）
        try {
            final Path saveDir = level != null && level.getServer() != null
                    ? level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("sable_sublevels")
                    : Path.of("sable_sublevels");
            final Path dimDir = saveDir.resolve(
                    level != null && level.dimension() != null
                            ? level.dimension().location().toString().replaceAll("[^a-zA-Z0-9_.-]", "_")
                            : "_");
            if (java.nio.file.Files.exists(dimDir)) {
                try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dimDir)) {
                    files.filter(p -> p.toString().endsWith(".dat")).forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
            // 目录不存在/不可删 → 清空失败但已尽力
        }
    }

    public void flush() {
        final ISubLevelPersistenceBackend b = backend;
        if (b != null) {
            try {
                b.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    public void closeAll() {
        closeBackend();
    }

    private synchronized void closeBackend() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Throwable ignored) {
            }
            backend = null;
        }
    }

    public boolean isActive() {
        return backend != null;
    }
}
