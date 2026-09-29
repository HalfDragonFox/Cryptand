package com.hdf.cryptand.integratednetwork;

import java.util.List;
import java.util.Map;

/**
 * Pipez 通用类型解析器（2026-08-30 用户：多定义——基础解析器 + 子类继承实现
 * 各传输类型：物品/流体/气体/能量等）。
 * <p>
 * 通用逻辑：
 * <ul>
 *   <li>过滤（{@link PipezFilterParam} 黑/白名单 + {@link PipezBufferedItem} id）——各
 *       传输类型的过滤条目都是各自类型注册名（物品/流体/气体 id）；能量无过滤
 *       （按容量），可传 ACCEPT_ALL；</li>
 *   <li>分配规则（NEAREST/FURTHEST/ROUND_ROBIN/RANDOM/EQUALIZE/ORDERED/FASTEST）；</li>
 *   <li>输入消耗 = 缓冲物品按所属输入接口归属合并。</li>
 * </ul>
 * 子类只需继承 + 指定 id（如 “pipez:fluid”）；参数强转由解析器完成。
 */
public abstract class PipezAbstractResolver implements TransportResolver {

    /** 过滤模式（对齐 Pipez UpgradeTileEntity.FilterMode：BLACKLIST 为反向） */
    public enum FilterMode {
        WHITELIST, BLACKLIST
    }

    /** 输出接口过滤参数（平台层把 Pipez 过滤条目映射为 Object id 列表） */
    public record PipezFilterParam(FilterMode mode, List<Object> itemIds) {
        public static final PipezFilterParam ACCEPT_ALL =
                new PipezFilterParam(FilterMode.WHITELIST, List.of());

        /** 该条目 id 是否被接受 */
        public boolean accepts(Object itemId) {
            if (itemId == null) return true;
            boolean inList = itemIds == null ? false : itemIds.contains(itemId);
            if (mode == FilterMode.BLACKLIST) {
                return !inList; // 黑名单：表内拒绝
            }
            // 白名单：表空=全接受；表非空=仅表内接受
            return itemIds == null || itemIds.isEmpty() ? true : inList;
        }
    }

    /** 输入缓冲条目（平台层把真实内容映射为 id+数量；data 即此类） */
    public record PipezBufferedItem(Object itemId, double amount) {
    }

    /** 传输类型（供子类校验/说明） */
    protected final TransferType type;

    protected PipezAbstractResolver(TransferType type) {
        this.type = type == null ? TransferType.GENERIC : type;
    }

    @Override
    public TransportPlan resolve(TransportResolveContext ctx) {
        if (ctx.buffered.isEmpty() || ctx.outputs.isEmpty()) return TransportPlan.empty();
        java.util.List<TransportResolveContext.BufferedItem> buffered = ctx.buffered;
        int nOut = ctx.outputs.size();
        // 每个输出的可接受总量（按过滤参数 + 该传输类型 id）
        double[] accept = new double[nOut];
        int idx = 0;
        for (TransportResolveContext.ResolvedInterface o : ctx.outputs) {
            PipezFilterParam fp = o.param() instanceof PipezFilterParam f ? f
                    : PipezFilterParam.ACCEPT_ALL;
            double acc = 0;
            for (TransportResolveContext.BufferedItem b : buffered) {
                Object id = b.data() instanceof PipezBufferedItem p ? p.itemId()
                        : b.data();
                if (fp.accepts(id)) acc += Math.max(0, b.amount());
            }
            accept[idx++] = acc;
        }
        double totalAccept = 0;
        for (double a : accept) totalAccept += a;
        if (totalAccept <= 0) return TransportPlan.empty();

        Map<Object, Double> allocs = new java.util.LinkedHashMap<>();
        switch (ctx.rule) {
            case ORDERED -> {
                for (int i = 0; i < nOut; i++) {
                    if (accept[i] > 0) {
                        allocs.put(ctx.outputs.get(i).id(), totalAccept);
                        break;
                    }
                }
            }
            case NEAREST, FURTHEST, RANDOM -> {
                java.util.List<TransportResolveContext.ResolvedInterface> viable =
                        new java.util.ArrayList<>();
                for (int i = 0; i < nOut; i++) {
                    if (accept[i] > 0) viable.add(ctx.outputs.get(i));
                }
                if (viable.isEmpty()) return TransportPlan.empty();
                TransportResolveContext.ResolvedInterface chosen;
                if (ctx.rule == DistributionRule.RANDOM) {
                    chosen = viable.get(new java.security.SecureRandom().nextInt(viable.size()));
                } else {
                    chosen = viable.stream()
                            .min(java.util.Comparator.comparingInt(
                                    o -> o.id() instanceof String s ? s.hashCode() : 0))
                            .orElse(viable.get(0));
                }
                allocs.put(chosen.id(), totalAccept);
            }
            case ROUND_ROBIN -> {
                int ac = 0;
                for (double a : accept) if (a > 0) ac++;
                if (ac == 0) return TransportPlan.empty();
                double per = totalAccept / ac;
                for (int i = 0; i < nOut; i++) {
                    if (accept[i] > 0) allocs.put(ctx.outputs.get(i).id(), per);
                }
            }
            case EQUALIZE, FASTEST -> {
                double per = totalAccept / Math.max(1, nOut);
                for (int i = 0; i < nOut; i++) {
                    if (accept[i] > 0) {
                        allocs.put(ctx.outputs.get(i).id(), Math.min(accept[i], per));
                    }
                }
            }
        }

        Map<Object, Double> consumes = new java.util.LinkedHashMap<>();
        double totalAlloc = allocs.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalAlloc <= 0) return TransportPlan.empty();
        for (TransportResolveContext.BufferedItem b : buffered) {
            Object inId = b.inputId() == null
                    ? (ctx.inputs.isEmpty() ? null : ctx.inputs.get(0).id()) : b.inputId();
            if (inId == null) continue;
            consumes.merge(inId, Math.max(0, b.amount()), Double::sum);
        }
        if (consumes.isEmpty()) {
            consumes.put(ctx.inputs.isEmpty() ? null : ctx.inputs.get(0).id(), totalAlloc);
        }
        return new TransportPlan(consumes, allocs);
    }
}
