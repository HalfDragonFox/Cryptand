/**
 * ===== Cryptand 平台服务注册表（core.api · 2026-09-07） =====
 *
 * core 对外的轻量【服务注册/查询 API】（风格参考 NeoForge 服务与 capability
 * 的"注册能力 → 运行时查询"思路，接口定义在 core、实现由子包/其他 mod 提供）：
 *
 * <pre>
 *   // 接口（core.api 内定义，如 CryptandLibraryTarget）
 *   // 子包或其他 mod 注册实现：
 *   CryptandServices.register(CryptandLibraryTarget.class, new MyTarget());
 *   // core 运行时查询：
 *   for (CryptandLibraryTarget t : CryptandServices.all(CryptandLibraryTarget.class)) ...
 * </pre>
 *
 * 语义：
 * - 注册表按接口类型索引，允许同一接口多实现（按注册顺序返回）；
 * - 线程安全；空实现/空查询安全返回；
 * - core 编译期只依赖【接口】（core.api），不依赖任何子包实现类——
 *   删除子包目录 → 该实现不再注册 → core 行为自动降级（隔离验收）。
 *
 * 当前用途：
 * - {@link CryptandLibraryTarget}：/cryptand library set 的目标方块处理器
 *   （core 命令查表，powergrid 的 ProgrammableComponentBlockEntity 注册实现）。
 */
package com.hdf.cryptand.neoforge.core.api;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Cryptand 平台服务注册表（静态单例；线程安全）。 */
public final class CryptandServices {

    private CryptandServices() {
    }

    private static final ConcurrentHashMap<Class<?>, List<Object>> SERVICES =
            new ConcurrentHashMap<>();

    /** 注册一个服务实现（同类型可多实现；注册顺序 = 查询顺序）。 */
    @SuppressWarnings("unchecked")
    public static <T> void register(final Class<T> type, final T impl) {
        if (type == null || impl == null) return;
        SERVICES.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(impl);
    }

    /** 查询某接口的全部实现（未注册 → 空表）。 */
    @SuppressWarnings("unchecked")
    public static <T> List<T> all(final Class<T> type) {
        if (type == null) return List.of();
        final List<Object> list = SERVICES.get(type);
        if (list == null || list.isEmpty()) return List.of();
        return (List<T>) List.copyOf(list);
    }

    /** 查询某接口的第一个实现（未注册 → null）。 */
    public static <T> T first(final Class<T> type) {
        final List<T> list = all(type);
        return list.isEmpty() ? null : list.get(0);
    }
}
