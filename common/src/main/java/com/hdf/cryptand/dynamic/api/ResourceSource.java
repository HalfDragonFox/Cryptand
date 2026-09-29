package com.hdf.cryptand.dynamic.api;

import java.util.List;

/**
 * ===== 来源扩展点（与具体核心正交）=====
 *
 * <p>目录 / mod 内嵌 / 内置 / 原版资源包 / URL 各自实现一个来源。新增来源不改框架。</p>
 */
public interface ResourceSource {

    String id();

    /** 发现候选资源（允许并行调用；实现须线程安全）。 */
    List<Source> discover(FrameworkContext ctx);
}
