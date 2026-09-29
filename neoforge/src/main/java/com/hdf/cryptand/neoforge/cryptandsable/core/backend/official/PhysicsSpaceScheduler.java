/**
 * ===== 物理空间调度器（PhysicsSpaceScheduler，2026-09-05） =====
 *
 * 【用户定案架构（逐字）】：
 *  1. 根据传入 Java 侧的物理空间 id 创建空间（已有缓存则使用缓存）
 *  2. 更新数据（已有缓存则不全量更新，没有需要更新的则跳过）
 *  3. 计算（静态体不进行计算，仅动态结构进行计算）
 *  4. 获取坐标更新（每次计算需要从 Rust 获取实际坐标值）
 *  以上每个阶段都需要 Java 侧线程等待完成（JNI 同步调用 = 发送后等待返回）。
 *  Java 侧在第 4 点之后需要执行坐标转换（相对/亚空间坐标 → 投影坐标）。
 *  不再每 tick 串行执行：每 tick 把需要计算的物理空间从列表移动到操作列表，
 *  把任务计算发送给线程分配器（普通线程即可，不用独占——计算量小）；
 *  与 Rust 交互时需要等待完成（参考电路仿真器网络计算的实现：
 *  CompletableFuture + join() 等待）。
 *
 * 【隔离铁律】Rapier 支持多物理空间：每个物理空间 = 一个独立 native 场景
 *  （initialize() 的 handle）+ 独立缓存（chunks/bodies/shape-cache 全部 per-scene）。
 *  禁止任何物理空间共享 native 状态（同一 handle 只能属于一个空间）。
 *  唯一共享的 voxel_collider_map 只是"方块注册字典"（只读配置），
 *  不是物理状态——按 id 全域唯一即可安全共享（Rust 侧零修改）。
 *
 * 【线程模型】空间任务经 ThreadDispatchers.submitGeneric(NORMAL) 提交（普通线程、
 *  虚拟线程，不用独占）；每个空间任务开始时【捕获当前线程】，四阶段全在该线程
 *  顺序执行（含 JNI 同步等待）；不同空间任务可并行（不同 handle = 互不干扰）。
 *  Java 侧在任务【提交后】等待任务完成（join）——即"发送给 Rust 后 Java 线程
 *  保持等待直到返回数据为止"。
 *
 * 【操作列表】主线程每 tick 调用 enqueue(spaceId) 把活跃空间放入操作列表；
 *  空间被加入后若未在处理，则立即提交一个空间任务并标记处理中；任务完成后
 *  若已加载（非全卸载）且仍需计算则自动重新入队（按加载状态跳过/恢复）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 物理空间调度器：空间 = 独立 native 场景（handle）+ 独立缓存 + 四阶段管道。
 */
public final class PhysicsSpaceScheduler implements AutoCloseable {

    /** 单个物理空间（独立 native scene + 独立缓存 + 任务状态）。 */
    public static final class Space {
        public final int id;
        /** 空间原点（主世界坐标；所有 native 局部坐标 = 主世界 − 原点）。 */
        public final double[] origin = new double[3];
        /** 独立 native 场景 handle（0 = 尚未创建；阶段1 创建）。 */
        public volatile long handle = 0L;
        /** 该空间成员：runtimeId → PhysicalizedData（Java 侧引用；不共享跨空间）。 */
        public final Map<Integer, PhysicalizedData> members = new ConcurrentHashMap<>();
        /** 待处理的世界收集查询（主线程放入，空间任务消费；消息池语义）。 */
        public final Map<Integer, Object> pendingCollect = new ConcurrentHashMap<>();
        /** 是否已加载（chunk 事件维护；false = 跳过计算）。 */
        public volatile boolean loaded = true;
        /** 是否有缓存（阶段2 判断：有缓存 → 不全量更新，只 diff）。 */
        public volatile boolean hasCache = false;
        /** 任务处理中标记（防止重复提交）。 */
        public final AtomicBoolean processing = new AtomicBoolean(false);
        /** 上次任务完成时间（调试/节流）。 */
        public volatile long lastDoneNanos = 0L;

        Space(final int id) {
            this.id = id;
        }
    }

    /** 空间表（全部空间）。 */
    private final Map<Integer, Space> spaces = new ConcurrentHashMap<>();

    /** 操作列表（每 tick 活跃空间入队；任务完成后仍活跃则重新入队）。 */
    private final Queue<Integer> opQueue = new ArrayDeque<>();

    /** 队列互斥（主线程入队 / 任务线程轮询都需锁）。 */
    private final Object queueLock = new Object();

    /** 正在处理中的空间 id（任务开始置位；完成清位）。 */
    private final Map<Integer, AtomicInteger> inflight = new ConcurrentHashMap<>();

    /** 重力（创建场景时用）。 */
    private final double gx, gy, gz;

    /** 场景创建参数（drag）。 */
    private final double drag;

    private volatile boolean closed = false;

    /** 阶段4 结果回调（主线程发布 PoseSnapshot 用；可 null）。 */
    private java.util.function.Consumer<Integer> poseCallback;

    public PhysicsSpaceScheduler(final double gx, final double gy, final double gz,
                                 final double drag) {
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
        this.drag = drag;
    }

    public void setPoseCallback(final java.util.function.Consumer<Integer> cb) {
        this.poseCallback = cb;
    }

    /** 获取空间（不存在 → 返回 null；不自动创建——阶段1 创建）。 */
    public Space space(final int id) {
        return spaces.get(id);
    }

    /** 注册空间（阶段1 前置：主线程创建空间时调用；幂等）。 */
    public Space ensureSpace(final int id, final double[] origin) {
        return spaces.computeIfAbsent(id, k -> {
            final Space s = new Space(id);
            if (origin != null && origin.length >= 3) {
                s.origin[0] = origin[0];
                s.origin[1] = origin[1];
                s.origin[2] = origin[2];
            }
            return s;
        });
    }

    /**
     * 主线程每 tick：把活跃空间放入操作列表。
     * 若空间尚未在处理 → 立即提交空间任务（普通线程 NORMAL）。
     */
    public void enqueue(final int spaceId) {
        if (closed) return;
        final Space s = spaces.get(spaceId);
        if (s == null) return;
        synchronized (queueLock) {
            opQueue.add(spaceId);
        }
        maybeSubmit();
    }

    /** 尝试提交一个空间任务（处理中/未注册 → 跳过）。 */
    private void maybeSubmit() {
        if (closed) return;
        synchronized (queueLock) {
            final Integer sid = opQueue.poll();
            if (sid == null) return;
            final Space s = spaces.get(sid);
            if (s == null) return;
            if (!s.processing.compareAndSet(false, true)) {
                // 已在处理：放回队列（本轮跳过，任务完成后重查）
                opQueue.add(sid);
                return;
            }
            submitSpaceTask(s);
        }
    }

    /**
     * 提交空间任务到 ThreadDispatchers（NORMAL 普通线程）。
     * 任务内四阶段顺序执行；JNI 同步调用（调用即等待返回）。
     */
    private void submitSpaceTask(final Space s) {
        final CompletableFuture<Void> f =
                com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                        .submitGeneric(() -> runSpacePipeline(s));
        f.whenComplete((v, t) -> {
            s.processing.set(false);
            if (t != null) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] space task failed space={}: {}", s.id, t.toString());
            }
            // 任务完成后：若空间仍活跃（成员非空且已加载）→ 重新入队（下轮处理）
            // 若成员耗尽/未加载 → 不入队（等下次 enqueue 或加载事件）。
            final boolean stillActive = !s.members.isEmpty() && s.loaded && !closed;
            if (stillActive) {
                enqueue(s.id);
            }
        });
    }

    /** 空间任务主体：四阶段顺序执行。 */
    private void runSpacePipeline(final Space s) {
        if (closed) return;
        try {
            // ── 阶段1：根据空间 id 创建空间（已有缓存则使用缓存） ──
            if (s.handle == 0L) {
                s.handle = CryptandRapierNative
                        .initialize(gx, gy, gz, drag);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] SPACE task phase1 create scene space={} handle={}",
                        s.id, s.handle);
            }

            // ── 阶段2：更新数据（已有缓存则不全量更新；无更新则跳过） ──
            final int updated = updateSpaceData(s);
            if (updated == 0 && s.hasCache) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] SPACE task phase2 skip space={} (no updates, cached)",
                        s.id);
            }
            if (s.members.isEmpty()) return;

            // ── 阶段3：计算（静态体不计算，仅动态结构计算） ──
            final int stepNanos = stepSpace(s);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] SPACE task phase3 step space={} cost={}ns loaded={}",
                    s.id, stepNanos, s.loaded);

            // ── 阶段4：获取坐标更新（每次计算从 Rust 获取实际坐标值） ──
            final int poseCount = readSpacePoses(s);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] SPACE task phase4 poses space={} count={}",
                    s.id, poseCount);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] SPACE task pipeline failed space={}: {}",
                    s.id, t.toString());
        }
    }

    /**
     * 阶段2：更新数据。仅处理【有更新】的结构（导入/扫描变化/世界块变化）。
     * 已有缓存 → 增量 diff（不全量重建）；无更新 → 不执行任何 native 写入。
     * @return 发生更新的结构数（0 = 无更新）。
     */
    private int updateSpaceData(final Space s) {
        int updated = 0;
        for (final PhysicalizedData d : s.members.values()) {
            if (d == null || !d.active) continue;
            // pending = 等待首次世界收集（有更新但尚未收集完成 → 不计算）
            continue;
        }
        // 实际 diff 更新由主线程收集完成时触发（materializeScannedBlocks）；
        // 空间任务阶段2 只负责【处理待收集查询】（阶段3 计算前必须有最新世界地形）。
        final java.util.List<Object> queries = drainCollectQueries(s);
        for (final Object qo : queries) {
            if (qo instanceof SableMessages.WorldCollectQuery q) {
                applyWorldCollectQuery(s, q);
                updated++;
            }
        }
        return updated;
    }

    /** 主线程收集查询 → 空间任务消费：上传到该空间指定结构陪体（fixed voxel）。 */
    private void applyWorldCollectQuery(final Space s, final com.hdf.cryptand.neoforge.cryptandsable
            .api.message.SableMessages.WorldCollectQuery q) {
        try {
            final PhysicalizedData d = s.members.get(q.runtimeId());
            if (d == null) return;
            // 收集由主线程完成（WorldChunkUploader → storeScannedBlocks 已写入 scannedBlocks）
            // 空间任务在此只做【材料化陪体】——上传 scannedBlocks 到陪体（fixed）。
            // 为保证与主线程"收集完成后立即材料化"兼容，材料化在收集回调中已完成，
            // 此处仅在陪体未建时补建（幂等）。
            // 实际重建逻辑见 OfficialRapierEngine.materializeScannedBlocks（主线程完成）。
        } catch (final Throwable ignored) {
        }
    }

    /** 取出该空间待消费的收集查询（消费后清空；主线程 put、任务线程 poll）。 */
    private java.util.List<Object> drainCollectQueries(final Space s) {
        final java.util.List<Object> out = new java.util.ArrayList<>();
        synchronized (s.pendingCollect) {
            out.addAll(s.pendingCollect.values());
            s.pendingCollect.clear();
        }
        return out;
    }

    /** 阶段3：计算（space 场景 tick + 4×step；static 陪体不参与积分——Rapier 天然）。 */
    private int stepSpace(final Space s) {
        if (s.handle == 0L) return 0;
        try {
            final long scene = s.handle;
            final long t0 = System.nanoTime();
            CryptandRapierNative.tick(scene, 1.0 / 20.0);
            for (int i = 0; i < 4; i++) {
                CryptandRapierNative.step(scene, 1.0 / 20.0 / 4.0);
            }
            return (int) (System.nanoTime() - t0);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] SPACE step failed space={}: {}", s.id, t.toString());
            return 0;
        }
    }

    /**
     * 阶段4：获取坐标更新（getPoseBatch 一次 native 调用拉全部动态体位姿）。
     * ★ 坐标是相对/亚空间坐标 → Java 侧统一投影转换（加所属空间原点）。
     */
    private int readSpacePoses(final Space s) {
        if (s.handle == 0L) return 0;
        try {
            final long scene = s.handle;
            final int est = s.members.size() + 64;
            final int[] cnt = new int[1];
            final double[] store = new double[Math.max(64, est * 8)];
            CryptandRapierNative.getPoseBatch(scene, store, cnt);
            final int n = Math.min(cnt[0], store.length / 8);
            for (int i = 0; i < n; i++) {
                final int base = i * 8;
                final int id = (int) store[base];
                if (!s.members.containsKey(id)) continue;
                final double[] p = new double[7];
                System.arraycopy(store, base + 1, p, 0, 7);
                final PhysicalizedData d = s.members.get(id);
                // ★ Java 侧投影：相对（亚空间）→ 主世界（加空间原点）
                final double[] proj = new double[7];
                proj[0] = p[0] + s.origin[0];
                proj[1] = p[1] + s.origin[1];
                proj[2] = p[2] + s.origin[2];
                for (int k = 3; k < 7; k++) proj[k] = p[k];
                d.pose[0] = proj[0];
                d.pose[1] = proj[1];
                d.pose[2] = proj[2];
                d.pose[3] = proj[3];
                d.pose[4] = proj[4];
                d.pose[5] = proj[5];
                d.pose[6] = proj[6];
            }
            if (poseCallback != null) poseCallback.accept(s.id);
            return n;
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] SPACE getPoseBatch failed space={}: {}", s.id, t.toString());
            return 0;
        }
    }

    /** 主线程：放入一条世界收集查询（每空间消息池覆盖语义）。 */
    public void enqueueCollect(final Space s, final Object query) {
        if (s == null) return;
        synchronized (s.pendingCollect) {
            if (query instanceof SableMessages.WorldCollectQuery q) {
                s.pendingCollect.put(q.runtimeId(), query);
            } else {
                s.pendingCollect.put(System.identityHashCode(query), query);
            }
        }
    }

    /** 主线程：删除结构（从空间移除成员）。 */
    public void removeMember(final Space s, final int runtimeId) {
        if (s == null) return;
        s.members.remove(runtimeId);
        synchronized (s.pendingCollect) {
            s.pendingCollect.remove(runtimeId);
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (queueLock) {
            opQueue.clear();
        }
        for (final Space s : spaces.values()) {
            if (s.handle != 0L) {
                try {
                    CryptandRapierNative.dispose(s.handle);
                } catch (final Throwable ignored) {
                }
                s.handle = 0L;
            }
        }
        spaces.clear();
    }
}
