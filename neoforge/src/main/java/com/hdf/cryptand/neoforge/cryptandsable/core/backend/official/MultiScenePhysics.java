/**
 * ===== 多物理空间容器（MultiScenePhysics，2026-09-02） =====
 *
 * 用户需求："多 scene + 异步交互与提交；让一个范围内的物理结构统一批处理，多个 scene 可同时计算"。
 *
 * 设计：
 *  - 每个【区域】（REGION_SIZE 区块见方的方形格子）对应一个独立 native scene（物理空间）：
 *    同一区域内的结构/陪体/shape/柔体统统进同一个 scene → 【统一批处理】且彼此可碰撞。
 *  - 不同区域 = 不同 native scene（独立物理世界、互不接触）→ 【多个 scene 可同时计算】。
 *  - stepAll(subSteps) 把每个 scene 的步进任务并行提交到线程池（CountDownLatch 汇合），全部
 *    完成后返回 —— 语义确定（等待返回数据）、各 scene 互不阻塞。
 *  - 位姿快照：stepScene 后把各 body 位姿写入 scene.poses；主线程经 pose() 只读消费，不碰 native。
 *  - 坐标：本容器直接用【主世界坐标】下发给 native（rapier f64 在 1e7 量级内精度安全）；区域
 *    隔离天然保证跨 scene 不串扰，故无需 FAR 平移。与 OfficialRapierEngine 的 FAR 单场景主路径
 *    互为独立，按需选用。
 *
 * 线程模型：创建/提交发生在主线程；stepAll 用池内多线程并行各 scene；
 *      每个 scene 一把 stepLock → 绝不并发推进同一 scene（native handle 非线程安全共享）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

import com.hdf.cryptand.neoforge.CryptandNeoForge;

public final class MultiScenePhysics implements AutoCloseable {

    /** 区域粒度（区块数）：512×512 区块见方。 */
    public static final int REGION_BITS = 9;
    public static final int REGION_SIZE = 1 << REGION_BITS;

    /** body 类型位掩码（remove 时按类型选择原生删除路径）。 */
    private static final int KIND_VOXEL = 1;
    private static final int KIND_SHAPE = 2;

    /** 一个物理空间（原生 scene + 体字典 + 位姿快照）。 */
    public static final class Scene {
        public final long key;          // 区域 key（regionKey）
        public final long handle;       // native scene handle
        final java.util.concurrent.ConcurrentMap<Integer, Integer> kinds =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentMap<Integer, double[]> poses =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.locks.ReentrantLock stepLock =
                new java.util.concurrent.locks.ReentrantLock();

        Scene(final long key, final long handle) {
            this.key = key;
            this.handle = handle;
        }
    }

    private final java.util.Map<Long, Scene> scenes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService pool;
    private final double gx;
    private final double gy;
    private final double gz;
    private final double drag;
    private volatile boolean disposed = false;

    public MultiScenePhysics(final int threads, final double gx, final double gy,
                             final double gz, final double drag) {
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
        this.drag = drag;
        this.pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, threads),
                r -> {
                    final Thread t = new Thread(r, "cryptand-multiscene-physics");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** 区域 key（由 section 区块坐标分桶；同区域 → 同 scene）。 */
    public static long regionKey(final int secX, final int secZ) {
        final long rx = Math.floorDiv((long) secX, REGION_SIZE);
        final long rz = Math.floorDiv((long) secZ, REGION_SIZE);
        return (rx << 32) ^ (rz & 0xFFFFFFFFL);
    }

    /** 取（或惰性创建）区域 scene：创建即向 native 开一个新物理空间。 */
    public Scene sceneFor(final int secX, final int secZ) {
        final long key = regionKey(secX, secZ);
        return scenes.computeIfAbsent(key, k -> {
            final long h = CryptandRapierNative.initialize(gx, gy, gz, drag);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[MultiScene] scene created key={} handle={}", key, h);
            return new Scene(k, h);
        });
    }

    // ===== 提交结构（体素刚体 + 静态陪体） =====

    /**
     * 提交一个【体素结构刚体】到区域 scene（与该区域其它结构统一批处理）。
     * @param pose  主世界初始位姿 [x,y,z,qx,qy,qz,qw]
     */
    public void submitVoxelBody(final Scene s, final int rt, final double[] pose,
                                final int[] bounds,
                                final java.util.List<OfficialRapierEngine.SectionUpload> sections) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            final long h = s.handle;
            CryptandRapierNative.createSubLevel(h, rt, pose, false);
            s.kinds.put(rt, KIND_VOXEL);
            if (bounds != null && bounds.length == 6) {
                final double comX = (bounds[0] + bounds[3] + 1) * 0.5;
                final double comY = (bounds[1] + bounds[4] + 1) * 0.5;
                final double comZ = (bounds[2] + bounds[5] + 1) * 0.5;
                CryptandRapierNative.setCenterOfMass(h, rt, comX, comY, comZ);
                CryptandRapierNative.setLocalBounds(h, rt, bounds[0], bounds[1], bounds[2],
                        bounds[3], bounds[4], bounds[5]);
            }
            int voxels = 0;
            if (sections != null) {
                for (final OfficialRapierEngine.SectionUpload sec : sections) {
                    CryptandRapierNative.addChunk(h, sec.secX(), sec.secY(), sec.secZ(), sec.chunk(), false, rt);
                    voxels += countVoxels(sec.chunk());
                }
                if (bounds != null && bounds.length == 6) {
                    final double mass = Math.max(1.0, voxels);
                    final double sx = Math.max(1.0, bounds[3] - bounds[0] + 1);
                    final double sy = Math.max(1.0, bounds[4] - bounds[1] + 1);
                    final double sz = Math.max(1.0, bounds[5] - bounds[2] + 1);
                    final double inv12 = mass / 12.0;
                    final double[] inertia = {inv12 * (sy * sy + sz * sz), 0, 0,
                            0, inv12 * (sx * sx + sz * sz), 0,
                            0, 0, inv12 * (sx * sx + sy * sy)};
                    final double comX = (bounds[0] + bounds[3] + 1) * 0.5;
                    final double comY = (bounds[1] + bounds[4] + 1) * 0.5;
                    final double comZ = (bounds[2] + bounds[5] + 1) * 0.5;
                    CryptandRapierNative.setMassProperties(h, rt, mass, new double[]{comX, comY, comZ}, inertia);
                }
            }
            s.poses.put(rt, pose != null ? java.util.Arrays.copyOf(pose, 7) : new double[7]);
        } finally {
            s.stepLock.unlock();
        }
    }

    /**
     * 给结构 rt 建一个【静态承托陪体】到同 scene（body↔body 各方向拖住）。
     * 陪体 id 用【负区】自动分配；返回陪体 id。
     */
    public int ensureStaticHelper(final Scene s, final int rt, final int[] bounds) {
        if (disposed || s == null) return -1;
        s.stepLock.lock();
        try {
            final int helper = -(Math.abs(rt) + 1);   // 负 id 区，与结构一一映射
            if (s.kinds.containsKey(helper)) return helper;   // 已存在
            final long h = s.handle;
            final double[] pose = {0, 0, 0, 0, 0, 0, 1};
            CryptandRapierNative.createSubLevel(h, helper, pose, true);   // isStatic=true 真静态
            s.kinds.put(helper, KIND_VOXEL);
            if (bounds != null && bounds.length == 6) {
                CryptandRapierNative.setCenterOfMass(h, helper,
                        (bounds[0] + bounds[3] + 1) * 0.5,
                        (bounds[1] + bounds[4] + 1) * 0.5,
                        (bounds[2] + bounds[5] + 1) * 0.5);
                CryptandRapierNative.setLocalBounds(h, helper, bounds[0], bounds[1], bounds[2],
                        bounds[3], bounds[4], bounds[5]);
            }
            return helper;
        } finally {
            s.stepLock.unlock();
        }
    }

    /** 向陪体挂世界块（secX/Y/Z = section 坐标；块由该静态体碰撞承托）。 */
    public void addWorldChunkToHelper(final Scene s, final int helper,
                                      final int secX, final int secY, final int secZ,
                                      final int[] chunk) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            CryptandRapierNative.addChunk(s.handle, secX, secY, secZ, chunk, false, helper);
        } finally {
            s.stepLock.unlock();
        }
    }

    // ===== shape / trimesh / 粒子 / 弹簧 =====

    /** shape 刚体（bodyType: 0=dynamic 1=fixed 2=kinematic-pos 3=kinematic-vel；多 shape 类型）。 */
    public void createShapeBody(final Scene s, final int rt, final int bodyType,
                                final double mass, final int shapeType, final double[] params,
                                final double friction, final double restitution, final double[] pose) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            CryptandRapierNative.createShapeBody(s.handle, rt, bodyType, mass, shapeType, params,
                    friction, restitution, pose);
            s.kinds.put(rt, KIND_SHAPE);
            s.poses.put(rt, pose != null ? java.util.Arrays.copyOf(pose, 7) : new double[7]);
        } finally {
            s.stepLock.unlock();
        }
    }

    /** 三角网格刚体（多边形任意轮廓）。 */
    public void createTrimeshShapeBody(final Scene s, final int rt, final int bodyType,
                                       final double mass, final double[] vertices,
                                       final int[] indices, final double friction,
                                       final double restitution, final double[] pose) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            CryptandRapierNative.createTrimeshShapeBody(s.handle, rt, bodyType, mass, vertices, indices,
                    friction, restitution, pose);
            s.kinds.put(rt, KIND_SHAPE);
            s.poses.put(rt, pose != null ? java.util.Arrays.copyOf(pose, 7) : new double[7]);
        } finally {
            s.stepLock.unlock();
        }
    }

    /** 柔体粒子（小型 dynamic 球体刚体）。 */
    public void createParticleBody(final Scene s, final int rt, final double radius,
                                   final double mass, final double[] pose) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            CryptandRapierNative.createParticleBody(s.handle, rt, radius, mass, pose);
            s.kinds.put(rt, KIND_SHAPE);
            s.poses.put(rt, pose != null ? java.util.Arrays.copyOf(pose, 7) : new double[7]);
        } finally {
            s.stepLock.unlock();
        }
    }

    /** 柔体边（距离弹簧约束）；返回 joint handle（0=失败）。 */
    public long linkBodiesSpring(final Scene s, final int idA, final int idB,
                                 final double[] localAnchorA, final double[] localAnchorB,
                                 final double frequency, final double dampingRatio) {
        if (disposed || s == null) return 0L;
        s.stepLock.lock();
        try {
            return CryptandRapierNative.linkBodiesSpring(s.handle, idA, idB, localAnchorA, localAnchorB,
                    frequency, dampingRatio);
        } finally {
            s.stepLock.unlock();
        }
    }

    /** 移除体（按类型自动选原生删除路径）。 */
    public void removeBody(final Scene s, final int rt) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            final Integer kind = s.kinds.remove(rt);
            s.poses.remove(rt);
            if (kind != null) {
                if ((kind & KIND_VOXEL) != 0) {
                    try { CryptandRapierNative.removeSubLevel(s.handle, rt); } catch (final Throwable ignored) { }
                }
                if ((kind & KIND_SHAPE) != 0) {
                    try { CryptandRapierNative.removeShapeBody(s.handle, rt); } catch (final Throwable ignored) { }
                }
            } else {
                try { CryptandRapierNative.removeSubLevel(s.handle, rt); } catch (final Throwable ignored) { }
                try { CryptandRapierNative.removeShapeBody(s.handle, rt); } catch (final Throwable ignored) { }
            }
        } finally {
            s.stepLock.unlock();
        }
    }

    // ===== 交互（力 / 唤醒 / 位移） =====

    public void applyForce(final Scene s, final int rt,
                           final double fx, final double fy, final double fz, final boolean wake) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            CryptandRapierNative.applyForce(s.handle, rt, 0, 0, 0, fx, fy, fz, wake);
        } finally {
            s.stepLock.unlock();
        }
    }

    public void wakeUp(final Scene s, final int rt) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            CryptandRapierNative.wakeUpObject(s.handle, rt);
        } finally {
            s.stepLock.unlock();
        }
    }

    public void teleport(final Scene s, final int rt, final double[] pose) {
        if (disposed || s == null) return;
        s.stepLock.lock();
        try {
            CryptandRapierNative.teleportObject(s.handle, rt, pose[0], pose[1], pose[2],
                    pose[3], pose[4], pose[5], pose[6]);
        } finally {
            s.stepLock.unlock();
        }
    }

    // ===== 位姿快照（主线程只读） =====

    /** 最近一次快照位姿（clone 返回，安全消费；null → 未缓存）。 */
    public double[] pose(final Scene s, final int rt) {
        if (s == null) return null;
        final double[] p = s.poses.get(rt);
        return p != null ? p.clone() : null;
    }

    // ===== 批量步进（并行 + 等待返回） =====

    /**
     * 并行步进所有 scene（各自 tick + subSteps 次 step），等待全部完成。
     * 返回 scene 数。阻塞主线程直到所有物理空间推进完毕——即"提交 → 批量计算 → 等待返回数据"。
     */
    public int stepAll(final int subSteps) {
        if (disposed) return 0;
        final java.util.List<Scene> list = new java.util.ArrayList<>(scenes.values());
        if (list.isEmpty()) return 0;
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(list.size());
        final long t0 = System.nanoTime();
        for (final Scene s : list) {
            pool.execute(() -> {
                final long st = System.nanoTime();
                try {
                    stepScene(s, subSteps);
                } catch (final Throwable t) {
                    CryptandNeoForge.WAF_LOGGER.warn("[MultiScene] step scene={} failed", s.key, t);
                } finally {
                    latch.countDown();
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[MultiScene] stepAll worker scene={} took {}us",
                            s.key, (System.nanoTime() - st) / 1000);
                }
            });
        }
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[MultiScene] stepAll scenes={} total {}us",
                list.size(), (System.nanoTime() - t0) / 1000);
        return list.size();
    }

    private void stepScene(final Scene s, final int subSteps) {
        if (disposed) return;
        s.stepLock.lock();
        try {
            final long h = s.handle;
            CryptandRapierNative.tick(h, 1.0 / 20.0);
            final int ss = Math.max(1, subSteps);
            for (int i = 0; i < ss; i++) {
                CryptandRapierNative.step(h, 1.0 / 20.0 / ss);
            }
            for (final Integer rt : s.poses.keySet()) {
                if (disposed) break;
                final double[] store = new double[7];
                try {
                    CryptandRapierNative.getPose(h, rt, store);
                    s.poses.put(rt, store);
                } catch (final Throwable ignored) {
                    // 单 body 快照失败不阻断该 scene
                }
            }
        } finally {
            s.stepLock.unlock();
        }
    }

    // ===== 自检（证明多 scene 并行批处理） =====

    /**
     * 自检：把 N×R 个球体 scatter 到 R 个区域 scene，并行 stepAll 数次，
     * 日志展示各 scene 独立耗时 + 位姿下落 → 证明多 scene 同时计算。
     * 用 -Dcryptand.multiscene.selftest=true 触发（CryptandSable.start 后）。
     */
    public static void selfTest(final int bodiesPerScene, final int regionCount,
                                final int steps, final double y0) {
        final int threads = Math.min(regionCount, Runtime.getRuntime().availableProcessors());
        try (final MultiScenePhysics msp = new MultiScenePhysics(threads, 0.0, -9.81, 0.0, 0.0)) {
            final Scene[] scenes = new Scene[regionCount];
            for (int r = 0; r < regionCount; r++) {
                scenes[r] = msp.sceneFor(r * REGION_SIZE, 0);
                for (int i = 0; i < bodiesPerScene; i++) {
                    final int rt = r * 1000 + i;
                    final double[] pose = {r * 16.0 + i * 2.0, y0, 100.0, 0, 0, 0, 1};
                    msp.createShapeBody(scenes[r], rt, 0, 1.0, 0,
                            new double[]{0.5}, 0.6, 0.1, pose);
                }
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[MultiScene] selfTest: {} scenes x {} bodies, stepping {}x... ",
                    regionCount, bodiesPerScene, steps);
            for (int k = 0; k < steps; k++) {
                msp.stepAll(4);
            }
            for (int r = 0; r < regionCount; r++) {
                final double[] p = msp.pose(scenes[r], r * 1000);
                if (p != null) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[MultiScene] selfTest scene[{}] body0 y={} (started {})",
                            r, String.format(java.util.Locale.US, "%.2f", p[1]),
                            String.format(java.util.Locale.US, "%.1f", y0));
                }
            }
            CryptandNeoForge.WAF_LOGGER.info("[MultiScene] selfTest done (scenes={})",
                    msp.sceneCount());
        }
    }

    /** 当前 scene 数。 */
    public int sceneCount() {
        return scenes.size();
    }

    private static int countVoxels(final int[] chunk) {
        if (chunk == null) return 0;
        int n = 0;
        for (final int v : chunk) {
            if (v != 0) n++;
        }
        return n;
    }

    @Override
    public void close() {
        disposed = true;
        for (final Scene s : scenes.values()) {
            try {
                CryptandRapierNative.dispose(s.handle);
            } catch (final Throwable ignored) {
            }
        }
        scenes.clear();
        pool.shutdownNow();
    }
}