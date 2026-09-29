package com.hdf.cryptand.dynamic.api;

import java.nio.file.Path;
import java.util.List;

/** 给 {@link ResourceSource} 的上下文（框架传入）。 */
public interface FrameworkContext {

    /** 统一根目录（MC 侧 = {@code GAMEDIR/cryptand/dynamic}；离线 = 传入目录）。 */
    Path root();

    /** 当前已注册的核心（只读）。 */
    List<DynamicCoreSpi<?>> cores();

    /** 某核心的目录名（用于按核心分目录扫描）。 */
    default String folderOf(String coreId) {
        for (final DynamicCoreSpi<?> c : cores()) {
            if (c.id().equals(coreId)) {
                return c.folder();
            }
        }
        return coreId;
    }

    void log(String msg);
}
