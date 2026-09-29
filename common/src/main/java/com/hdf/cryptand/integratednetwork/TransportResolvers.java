package com.hdf.cryptand.integratednetwork;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析器注册表（2026-08-30 用户：自定义解析器注册进网络集成核心）。
 * <p>
 * 全局注册表：解析器 id → {@link TransportResolver} 实例。内置默认
 * {@code "pipez:item"}（通用管道物品解析器）；平台/自定义 mod 可
 * {@link #register(TransportResolver)} 注册自己的解析器（如带过滤表、
 * 长度上限等 Object 参数类的具体实现）。
 */
public final class TransportResolvers {

    private static final Map<String, TransportResolver> REGISTRY = new ConcurrentHashMap<>();

    static {
        // 默认通用管道解析器（基础：总量均衡分配；解析器可自行强转 ctx 参数）
        register(new GenericPipeResolver());
        // Pipez 系列解析器（2026-08-30 用户：多定义——物品/流体/气体/能量，
        //   均继承 PipezAbstractResolver 基础实现，各注册 id）
        register(new PipezItemResolver());
        register(new PipezFluidResolver());
        register(new PipezGasResolver());
        register(new PipezEnergyResolver());
    }

    private TransportResolvers() {
    }

    /** 注册解析器（同 id 覆盖，幂等） */
    public static void register(TransportResolver resolver) {
        if (resolver != null && resolver.id() != null) {
            REGISTRY.put(resolver.id(), resolver);
        }
    }

    /** 按 id 取解析器（未注册返回 null） */
    public static TransportResolver get(String id) {
        return id == null ? null : REGISTRY.get(id);
    }

    /** 按 id 取解析器；仅当【显式设置】且匹配时返回，否则 null（→ 传统 allocate）。
     *  2026-08-30 修复：之前 null 回落 DEFAULT_ID → 所有网络被 PipezItemResolver
     *  劫持（测试第 15 项期望 1000 实际 10000）。neoforge 显式 setResolverId 时才启用。 */
    public static TransportResolver getOrDefault(String id) {
        return get(id);
    }

    /** 已注册解析器 id 集（诊断） */
    public static java.util.Set<String> ids() {
        return java.util.Set.copyOf(REGISTRY.keySet());
    }

    /**
     * 通用管道解析器（内置默认；"pipez:item"）。
     * 基础规则：输入总量按输出接口数量均分（EQUALIZE 风格），忽略参数细节
     * （通用兜底）；具体管道解析器应重写 resolve 实现过滤表/长度等。
     */
    static final class GenericPipeResolver implements TransportResolver {

        @Override
        public String id() {
            return DEFAULT_ID;
        }

        @Override
        public TransportPlan resolve(TransportResolveContext ctx) {
            if (ctx.buffered.isEmpty()) return TransportPlan.empty();
            double total = 0;
            for (TransportResolveContext.BufferedItem b : ctx.buffered) {
                total += Math.max(0, b.amount());
            }
            if (total <= 0 || ctx.outputs.isEmpty()) return TransportPlan.empty();
            // 输出均分；输入按缓冲量比例消耗
            double perOut = total / ctx.outputs.size();
            Map<Object, Double> allocs = new java.util.LinkedHashMap<>();
            for (TransportResolveContext.ResolvedInterface o : ctx.outputs) {
                allocs.put(o.id(), perOut);
            }
            Map<Object, Double> consumes = new java.util.LinkedHashMap<>();
            double remain = total;
            for (TransportResolveContext.ResolvedInterface i : ctx.inputs) {
                double amt = Math.min(remain, total / Math.max(1, ctx.inputs.size()));
                if (amt > 0) { consumes.put(i.id(), amt); remain -= amt; }
            }
            return new TransportPlan(consumes, allocs);
        }
    }
}
