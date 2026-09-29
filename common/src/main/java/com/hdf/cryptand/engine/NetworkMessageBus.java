package com.hdf.cryptand.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== 网络消息总线（两级消息池，2026-09-11 用户架构）=====
 *
 * 用户："消息可以合并，比如参数更新，首先是主线程合并，然后每个网络可以定义消息缓存，
 * 上限默认 20 条，如果超过则异步线程进行一次合并，如果合并后无法减小则丢弃变更并打印消息，
 * 消息每个网络中有一个消息缓存池可用，全局也有一个网络 id 对应消息的池，每次异步计算前
 * 异步核心把消息从全局转入网络的消息缓存池中，然后再进行计算。"
 *
 * <pre>
 *   [BE 变化] → 主线程前处理：全局池（同位置覆盖式合并 = 第一级合并）
 *                  ↓  异步计算前：transferToPool(netId, ctx 覆盖的位置)
 *              网络消息缓存池（每网络一个，上限 20）
 *                  ↓  超上限 → 合并（同位置只留最新，第二级合并）
 *                  ↓  合并后仍超 → 丢弃最旧 + 打印
 *              计算（drainPool 取走并应用）
 * </pre>
 *
 * 键使用不透明 long（平台侧 BlockPos.asLong()）→ 引擎层零 MC 依赖。
 * 网络标识 netId 由平台侧提供（如自管分量 seedKey），引擎不解释。
 */
public final class NetworkMessageBus {

    /** 全局池（主线程前处理投递；同位置覆盖式合并） */
    private static final ConcurrentHashMap<Long, Entry> GLOBAL = new ConcurrentHashMap<>();

    /** 网络消息缓存池（netId → 池） */
    private static final ConcurrentHashMap<Object, NetPool> POOLS = new ConcurrentHashMap<>();

    /** 每网络消息缓存池上限（默认 20；平台经 {@link #setPoolLimit} 配置）。 */
    private static volatile int poolLimit = 20;

    /** 设置每网络消息处理缓存上限（默认 20；<1 → 忽略）。 */
    public static void setPoolLimit(int n) {
        if (n > 0) poolLimit = n;
    }

    public static int poolLimit() {
        return poolLimit;
    }

    /** 未匹配网络的消息最长保留时间（毫秒），超时丢弃 */
    public static final long MAX_KEEP_MS = 1000L;

    /** 丢弃/合并诊断输出（平台注入，如 mod logger；null → 静默） */
    private static volatile java.util.function.Consumer<String> reporter;

    private NetworkMessageBus() {
    }

    /** 平台注入诊断输出（引擎层不依赖 MC 日志）。 */
    public static void setReporter(java.util.function.Consumer<String> r) {
        reporter = r;
    }

    private static void report(String msg) {
        try {
            java.util.function.Consumer<String> r = reporter;
            if (r != null) r.accept(msg);
        } catch (Throwable ignored) {
        }
    }

    /** 一条待消费消息（含投递序号，用于丢弃最旧）。 */
    public static final class Entry {
        public final int type;
        public final Object payload;
        public final long postedMs;
        public final long seq;

        public Entry(int type, Object payload, long postedMs, long seq) {
            this.type = type;
            this.payload = payload;
            this.postedMs = postedMs;
            this.seq = seq;
        }
    }

    /** 消息类型（平台无关标签，引擎不解释 payload） */
    public static final int TYPE_PARAM = 1;   // 参数/状态上报（BE → 引擎）
    public static final int TYPE_LOAD = 2;    // 加载状态（区块加载/卸载）

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    // ==================== 第一级：全局池（主线程合并） ====================

    /** 【主线程 · 前处理】投递（同位置覆盖式合并 = 只保留最新一条）。 */
    public static void post(long posKey, int type, Object payload) {
        GLOBAL.put(posKey, new Entry(type, payload, System.currentTimeMillis(),
                SEQ.incrementAndGet()));
    }

    public static int globalSize() {
        return GLOBAL.size();
    }

    // ==================== 转入：全局池 → 网络池（异步计算前） ====================

    /**
     * 【异步 · 计算前】把全局池中属于本网络的消息转入该网络的缓存池。
     *
     * @param netId   网络标识（平台侧提供，如分量 seedKey）
     * @param posKeys 本网络覆盖的位置键（ctx.blockTerminals 转换）
     * @return 本次转入条数
     */
    public static int transferToPool(Object netId, Collection<Long> posKeys) {
        if (netId == null || posKeys == null || posKeys.isEmpty() || GLOBAL.isEmpty()) return 0;
        NetPool pool = POOLS.computeIfAbsent(netId, k -> new NetPool());
        int moved = 0;
        for (Long k : posKeys) {
            if (k == null) continue;
            Entry e = GLOBAL.remove(k);
            if (e == null) continue;
            pool.put(k, e);
            moved++;
        }
        if (moved > 0) pool.compact(netId); // 超上限 → 合并 → 仍超 → 丢弃 + 打印
        return moved;
    }

    // ==================== 第二级：网络池（上限 / 合并 / 丢弃） ====================

    /**
     * 单个网络的【消息处理缓存】（上限 = {@link #poolLimit}，默认 20）。
     * <p>全局池只保存【无状态消息】（pos → 最新一条，不记录网络归属）；
     * 网络计算前由 {@link #transferToPool} 对全局池做【压缩合并】（同位置取最新）
     * 后放入本池，再由 {@link #drainPool} 取走应用到元件。
     */
    public static final class NetPool {
        private final ConcurrentHashMap<Long, Entry> msgs = new ConcurrentHashMap<>();

        void put(Long key, Entry e) {
            msgs.put(key, e);
        }

        /**
         * 上限控制：超过 {@link #NET_POOL_LIMIT} → 先合并（同位置天然只有一条，
         * 故按"同类型 + 同位置前缀"合并无收益时）→ 若仍超限 → 丢弃【最旧】并打印。
         */
        synchronized void compact(Object netId) {
            int limit = poolLimit;
        if (msgs.size() <= limit) return;
            // 第二级合并：同位置仅保留最新（ConcurrentHashMap 已保证），
            // 这里按 seq 排序后丢弃最旧，直到回到上限内。
            List<java.util.Map.Entry<Long, Entry>> list = new ArrayList<>(msgs.entrySet());
            list.sort(Comparator.comparingLong(a -> a.getValue().seq));
            int drop = list.size() - limit;
            int dropped = 0;
            for (int i = 0; i < drop && i < list.size(); i++) {
                msgs.remove(list.get(i).getKey());
                dropped++;
            }
            if (dropped > 0) {
                report("[MsgPool] net=" + netId + " over limit(" + limit
                        + ") merged=" + (list.size() - dropped) + " dropped=" + dropped
                        + " (无法进一步合并 → 丢弃最旧变更)");
            }
        }

        /** 取走全部消息（计算时应用）。 */
        List<Object[]> drain() {
            List<Object[]> out = new ArrayList<>();
            for (var it = msgs.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                it.remove();
                out.add(new Object[]{e.getKey(), e.getValue()});
            }
            return out;
        }

        int size() {
            return msgs.size();
        }
    }

    /**
     * 【异步 · 计算时】取走该网络池的全部消息（先 transferToPool 再 drainPool）。
     * 池不存在 → 空列表。
     */
    public static List<Object[]> drainPool(Object netId) {
        if (netId == null) return List.of();
        NetPool pool = POOLS.get(netId);
        return pool == null ? List.of() : pool.drain();
    }

    /** 网络消失/重建 → 清空该池。 */
    public static void clearPool(Object netId) {
        if (netId != null) POOLS.remove(netId);
    }

    public static int poolSize(Object netId) {
        NetPool p = netId == null ? null : POOLS.get(netId);
        return p == null ? 0 : p.size();
    }

    // ==================== 全局维护 ====================

    /** 超时清扫：全局池中未匹配网络的陈旧消息丢弃。 */
    public static void sweepStale() {
        long now = System.currentTimeMillis();
        GLOBAL.entrySet().removeIf(e -> now - e.getValue().postedMs > MAX_KEEP_MS);
    }

    public static void clear() {
        GLOBAL.clear();
        POOLS.clear();
    }
}
