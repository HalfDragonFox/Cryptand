package com.hdf.cryptand.dynamic.api;

/**
 * ===== 动态加载流水线阶段（2026-09-29 定案）=====
 *
 * <p>并发策略按阶段由具体核心声明（{@link ExecutorPlan}）：</p>
 * <ul>
 *   <li>DISCOVER / PROBE / EXTRACT —— 纯 IO，默认并行（虚拟线程）；</li>
 *   <li>LOAD —— 默认串行（插件类的 {@code <clinit>} 可能触碰 MC 全局）；</li>
 *   <li>ATTACH —— 默认主线程（UI 树等只能在主线程建）。</li>
 * </ul>
 */
public enum Stage {
    DISCOVER, PROBE, EXTRACT, LOAD, ATTACH
}
