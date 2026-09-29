package com.hdf.cryptand.neoforge.net;

import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * 维度网络物品缓存（2026-08-29 用户方案 B：预提取 → 网络缓存所有权 → 交付）。
 * <p>
 * 每个维度网络总管理类（{@link DimensionNetworkManager}）持有一个，按【网络 key】
 * 分池（一个网络 = 一个池）。生命周期：
 * <ul>
 *   <li>【预提取】{@link #add}：主线程把源容器真实物品提取入池——《物品已
 *       归属网络》，核心异步计算期间源被拿走/清空不再影响（竞态消除）；</li>
 *   <li>【交付】{@link #take}：按核心执行表从池取真实物品写目标；目标满 →
 *       差额 {@link #putBack} 回池（物品不丢），下轮重新上报重试；</li>
 *   <li>【拆除】{@link #drain}：设备移除时该网络剩余物品取出 → 平台层掉落
 *       ItemEntity（不静默吞物品）；</li>
 *   <li>【持久化】{@link #snapshot}：随维度 SavedData 落盘，退出世界缓存仍在
 *       → 重新进世界继续传输。</li>
 * </ul>
 * 真实物品（ItemStack）只在这里驻留；common 核心全程只见数量（纯 Java，零 MC）。
 * 主线程单线程使用（ServerTickEvent.Post），无需并发容器。
 */
public final class NetworkBuffer {

    /** 网络 key → 缓存物品池（FIFO，先进先出保持物品到达顺序） */
    private final Map<Object, ArrayDeque<ItemStack>> pools = new HashMap<>();
    /** 网络 key → 输入接口 id → 该接口入池的物品数（2026-08-30 用户：物品归属
     *  输入接口，解析器按"物品所在接口 id"计算分配） */
    private final Map<Object, Map<Object, Integer>> ifaceCounts = new HashMap<>();

    /** 预提取入池（按网络；自动过滤空堆叠） */
    public void add(Object networkKey, Collection<ItemStack> stacks) {
        add(networkKey, null, stacks);
    }

    /** 预提取入池（按网络；指定来源输入接口 id 记录归属） */
    public void add(Object networkKey, Object sourceIfaceId, Collection<ItemStack> stacks) {
        if (networkKey == null || stacks == null || stacks.isEmpty()) return;
        ArrayDeque<ItemStack> q = pools.computeIfAbsent(networkKey, k -> new ArrayDeque<>());
        Map<Object, Integer> cm = ifaceCounts.computeIfAbsent(networkKey,
                k -> new HashMap<>());
        for (ItemStack st : stacks) {
            if (st != null && !st.isEmpty()) {
                q.add(st.copy());
                if (sourceIfaceId != null) {
                    cm.merge(sourceIfaceId, st.getCount(), Integer::sum);
                }
            }
        }
    }

    /** 单堆叠入池 */
    public void add(Object networkKey, ItemStack stack) {
        add(networkKey, null, List.of(stack));
    }

    /** 该网络缓存的物品总量 */
    public int count(Object networkKey) {
        ArrayDeque<ItemStack> q = pools.get(networkKey);
        if (q == null) return 0;
        int n = 0;
        for (ItemStack st : q) n += st.getCount();
        return n;
    }

    /** 该网络按输入接口 id 统计的物品量快照（2026-08-30 解析器用） */
    public Map<Object, Integer> ifaceCounts(Object networkKey) {
        Map<Object, Integer> m = ifaceCounts.get(networkKey);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    /** 该网络是否有任何缓存 */
    public boolean hasStored(Object networkKey) {
        return count(networkKey) > 0;
    }

    /**
     * 从缓存取出最多 {@code amount} 个物品（真实堆叠；不足则全部取出）。
     * 返回的堆叠即「将从缓存写入目标的真实物品」——物品身份不丢失。
     * 扣减接口归属统计（先扣最早入池接口）。
     */
    public List<ItemStack> take(Object networkKey, int amount) {
        ArrayDeque<ItemStack> q = pools.get(networkKey);
        if (q == null || amount <= 0) return List.of();
        Map<Object, Integer> cm = ifaceCounts.get(networkKey);
        List<ItemStack> out = new ArrayList<>();
        int need = amount;
        while (need > 0 && !q.isEmpty()) {
            ItemStack st = q.peek();
            if (st.isEmpty()) { q.poll(); continue; }
            int n = st.getCount();
            int takeN = Math.min(n, need);
            // 扣减归属统计（按 FIFO 次序隐式归属，不精确到堆叠；总量一致）
            if (cm != null && !cm.isEmpty()) {
                Object first = cm.keySet().iterator().next();
                int c = cm.get(first);
                if (c <= takeN) cm.remove(first); else cm.put(first, c - takeN);
            }
            if (n <= need) {
                q.poll();
                out.add(st);
                need -= n;
            } else {
                out.add(st.split(takeN));
                need -= takeN;
            }
        }
        return out;
    }

    /** 交付失败回池（目标写不进去的真实物品原样放回，下轮重试） */
    public void putBack(Object networkKey, Collection<ItemStack> remaining) {
        add(networkKey, remaining);
    }

    /** 取出该网络全部缓存（拆除掉落用）；池被清空 */
    public List<ItemStack> drain(Object networkKey) {
        ArrayDeque<ItemStack> q = pools.remove(networkKey);
        ifaceCounts.remove(networkKey);
        if (q == null || q.isEmpty()) return List.of();
        return new ArrayList<>(q);
    }

    /** 取出该网络缓存快照（存档用；不改变池） */
    public List<ItemStack> snapshot(Object networkKey) {
        ArrayDeque<ItemStack> q = pools.get(networkKey);
        if (q == null || q.isEmpty()) return List.of();
        List<ItemStack> out = new ArrayList<>(q.size());
        for (ItemStack st : q) out.add(st.copy());
        return out;
    }

    /** 已注册缓存的网络数（诊断） */
    public int poolCount() {
        return pools.size();
    }

    /** 当前缓存的网络 key 集合（诊断/存档） */
    public java.util.Set<Object> poolKeys() {
        return pools.keySet();
    }

    @Override
    public String toString() {
        return "NetworkBuffer{pools=" + pools.size() + "}";
    }
}