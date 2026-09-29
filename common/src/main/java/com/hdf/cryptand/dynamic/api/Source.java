package com.hdf.cryptand.dynamic.api;

import java.nio.file.Path;

/**
 * 一个候选来源项（jar 文件 / 目录 / 资源包条目）。
 *
 * @param kind   来源种类（见常量）
 * @param file   实际文件或目录
 * @param origin 人类可读的来源描述（如 {@code dir:dynamic/ui}、{@code mod:foo}）
 */
public record Source(String kind, Path file, String origin) {

    public static final String KIND_DIR = "dir";
    public static final String KIND_MOD = "mod";
    public static final String KIND_BUILTIN = "builtin";
    public static final String KIND_PACK = "pack";

    public static Source dir(Path file, String origin) {
        return new Source(KIND_DIR, file, origin);
    }

    public static Source mod(Path file, String origin) {
        return new Source(KIND_MOD, file, origin);
    }

    public static Source builtin(Path file, String origin) {
        return new Source(KIND_BUILTIN, file, origin);
    }

    public static Source pack(Path file, String origin) {
        return new Source(KIND_PACK, file, origin);
    }

    public String name() {
        final Path n = file.getFileName();
        return n == null ? file.toString() : n.toString();
    }

    @Override
    public String toString() {
        return kind + ":" + name();
    }
}
