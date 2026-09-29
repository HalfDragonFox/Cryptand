package com.hdf.cryptand.dynamic.api;

/**
 * ===== 加载窗口（2026-09-29 用户要求：抽象要适用整个 MC 周期）=====
 *
 * <p>动态框架贯穿 MC 生命周期，但**不同阶段能做的事不同**：mixin 门控必须在类加载期
 * （只能读配置预读）、注册类内容必须在 mod 构造期、UI/资源类内容必须等客户端 setup 之后。
 * 核心用 {@link DynamicCoreSpi#window()} 声明自己的最早可用阶段，框架在更早阶段会拒绝加载
 * 并给出明确错误 —— 避免第三方在错误阶段做危险操作。</p>
 */
public enum LoadWindow {

    /** 类加载期（mixin 门控；只能依赖配置预读，不能加载插件类）。 */
    CLASS_LOAD,

    /** mod 构造期（注册内容；不能碰世界）。 */
    MOD_CONSTRUCT,

    /** 客户端 setup 之后（UI/样式/音频等需要 MC 环境的内容）。 */
    CLIENT_SETUP,

    /** 任意阶段（离线/无 MC 依赖的纯逻辑核心）。 */
    ANY;

    /** 当前处于 {@code current} 阶段时，本窗口是否已就绪。 */
    public boolean readyAt(LoadWindow current) {
        if (this == ANY || current == ANY) {
            return true;
        }
        return current.ordinal() >= this.ordinal();
    }
}
