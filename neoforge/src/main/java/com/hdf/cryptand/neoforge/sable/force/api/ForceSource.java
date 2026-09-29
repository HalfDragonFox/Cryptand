/**
 * ===== 力源（框架 API，2026-09-14） =====
 *
 * <p>【扩展点】把"某个物理结构上的某种力"接进力学可视化的唯一入口。
 * 实现本接口并 {@link CryptandForceDisplay#registerSource} 即可；框架负责采集调度、
 * 网络同步与渲染，实现者不需要关心这两层。
 *
 * <p>生命周期（服务端，每游戏 tick）：
 * <pre>
 *   SableForceCollector
 *     └─ for 每个已物理化的结构 subLevel:
 *          ├─ 构建 ForceContext（预算好质心/质量/重力/局部→世界变换，各源复用）
 *          └─ for 每个已注册 ForceSource:
 *               source.collect(ctx)  →  ctx.emit(...) 产出 ForceSample
 * </pre>
 *
 * <p>线程：collect 在【服务端主线程】调用（物理 tick 之后），实现必须非阻塞、不做 IO、
 * 不长期持有任何对象引用（context 仅在本 tick 内有效）。
 *
 * <p>约定：一个力源可以产出【多个力 id】的样本（例如"官方 Sable 力组桥接源"一次
 * 覆盖 sable:lift / sable:drag / sable:levitation / sable:propulsion …），
 * 每个样本自带 id，因此注册的是"采集器"而非"单一力"。
 */
package com.hdf.cryptand.neoforge.sable.force.api;

import net.minecraft.resources.ResourceLocation;

public interface ForceSource {

    /** 本力源的唯一标识（用于诊断/去重/开关；与样本里的力 id 无关）。 */
    ResourceLocation id();

    /** 采集优先级（小者先跑；默认 0）。 */
    default int priority() {
        return 0;
    }

    /** 该力源是否启用（默认 true；可读配置实现运行时开关）。 */
    default boolean enabled() {
        return true;
    }

    /**
     * 采集一个结构的力。
     *
     * @param ctx 本次采集上下文（质心/质量/重力/坐标变换/emit 出口）
     */
    void collect(ForceContext ctx);
}
