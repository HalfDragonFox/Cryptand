package com.hdf.cryptand.neoforge.cryptandsable.api.model;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 核心自有亚层容器（CryptandSubLevelContainer）—— 完全独立于官方 sable 的容器。
 *
 * <p>替代官方 {@code SubLevelContainer/ServerSubLevelContainer/ClientSubLevelContainer}：
 * 核心自己持有的亚层注册表（{@code uuid → CryptandSubLevel}），按 world 实例分隔。
 * 与官方容器解耦后，第三方 mod 对官方容器的调用由【兼容包 mixin】转接到本容器。
 */
public final class CryptandSubLevelContainer {

    /** 按 Level 实例分隔的核心容器（Level 数量少；无官方依赖）。 */
    private static final Map<Level, CryptandSubLevelContainer> REGISTRY = new ConcurrentHashMap<>();

    private final Map<UUID, CryptandSubLevel> subLevels = new ConcurrentHashMap<>();

    private CryptandSubLevelContainer() {
    }

    /** 取某 world 的核心容器（惰性创建；永远非 null）。 */
    public static CryptandSubLevelContainer getContainer(final Level level) {
        return REGISTRY.computeIfAbsent(level, k -> new CryptandSubLevelContainer());
    }

    /** 移除某 world 的容器（世界卸载/removeAll 时调用，防泄漏）。 */
    public static void dropContainer(final Level level) {
        REGISTRY.remove(level);
    }

    public Map<UUID, CryptandSubLevel> getSubLevelMap() {
        return this.subLevels;
    }

    @Nullable
    public CryptandSubLevel getSubLevel(final UUID uuid) {
        return uuid == null ? null : this.subLevels.get(uuid);
    }

    public CryptandSubLevel registerSubLevel(final UUID uuid, final CryptandSubLevel sub) {
        return this.subLevels.put(uuid, sub);
    }

    @Nullable
    public CryptandSubLevel unregisterSubLevel(final UUID uuid) {
        return uuid == null ? null : this.subLevels.remove(uuid);
    }

    public boolean contains(final UUID uuid) {
        return uuid != null && this.subLevels.containsKey(uuid);
    }

    public int size() {
        return this.subLevels.size();
    }

    public Collection<CryptandSubLevel> getAllSubLevels() {
        return Collections.unmodifiableCollection(this.subLevels.values());
    }

    public void clear() {
        this.subLevels.clear();
    }
}