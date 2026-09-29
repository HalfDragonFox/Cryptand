package com.hdf.cryptand.dynamic.api;

import java.util.List;

/**
 * 格式探测视图：核心据此判断"能不能处理这个文件"，而<b>不必把整个 jar 读进来</b>
 * （性能关键路径 —— probe 只读 zip 中央目录）。
 */
public interface FormatProbe {

    String fileName();

    long size();

    /** 是否 zip/jar 容器。 */
    boolean zip();

    /** 容器条目名（最多 max 个；非容器返回空列表）。实现须缓存，避免重复打开文件。 */
    List<String> entries(int max);

    /** 是否含指定条目名（精确匹配）。 */
    default boolean hasEntry(String name) {
        return entries(Integer.MAX_VALUE).contains(name);
    }

    /** 文件名后缀（含点；无后缀返回空串）。 */
    default String suffix() {
        final String n = fileName();
        final int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i);
    }
}
