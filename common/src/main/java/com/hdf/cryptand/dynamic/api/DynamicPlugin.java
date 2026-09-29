package com.hdf.cryptand.dynamic.api;

/**
 * ===== 动态插件主类的最小契约（2026-09-29 定案：永不扩张这三样）=====
 *
 * <p>id 规范：{@code <namespace>:<name>}，namespace 用 modid 风格（小写、点分），
 * name 用小写/连字符。裸名兼容（namespace = {@code default}）。</p>
 */
public interface DynamicPlugin {

    /** 唯一 id（重载/卸载/依赖按它引用）。 */
    String id();

    /** 插件声明的 API 版本（宿主按区间协商；不匹配即拒绝，且不加载任何类）。 */
    default int apiVersion() {
        return 1;
    }

    /** 卸载：只清理自己的资源（宿主负责注销登记与关闭类加载器）。 */
    default void unload() {
    }
}
