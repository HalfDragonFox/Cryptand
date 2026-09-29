package com.hdf.cryptand.dynamic.core;

/**
 * 一个已发布条目的可观测快照（只读；框架内部用不可变视图发布，避免半状态可见）。
 *
 * @param id          插件 id（{@code <namespace>:<name>}）
 * @param coreId      处理它的具体核心
 * @param sourceKind  来源种类（dir/mod/builtin/pack）
 * @param origin      来源描述
 * @param stage       当前阶段名（见 {@code Stage}）
 * @param apiVersion  插件 API 版本
 * @param loadMillis  加载+装配耗时
 * @param error       失败原因（成功为 null）
 */
public record Entry(String id, String coreId, String sourceKind, String origin, String stage,
                    int apiVersion, long loadMillis, String error) {
}
