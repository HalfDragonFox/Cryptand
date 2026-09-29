package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

import com.hdf.cryptand.neoforge.cryptandsable.core.backend.SableNativeLoader;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;

/**
 * CryptandRapierNative —— CryptandSable 独立物理引擎的 JNI 门面类。
 *
 * <p>★ 2026-09-06 【符号重编】Rust DLL（excode/sable_rapier_f32|f64 自研引擎）导出符号已从
 * {@code Java_dev_ryanhcode_..._Rapier3D_*} 重编为
 * {@code Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_*}
 * （与官方 sable 彻底解耦——CryptandSable = 独立物理引擎，不依赖官方类/包）。
 *
 * <p>静态块经 {@link SableNativeLoader} 自主加载 f64/f32 DLL（由 enableSableRapier64 选择，
 * f32 资源未打包时回退 f64）；native 方法声明与 Rust 导出符号一一对应。
 *
 * <p>官方 sable 相关：本类不再引用官方任何类；官方原版的 Rapier3D（jarjar sable_rapier）
 * 只被官方自身 mixin/代码使用（模式 C 官方运行 / 模式 A 由 cryptandsable_compat 禁跑）。
 */
public final class CryptandRapierNative {
    static {
        // 配置选择 f64（enableSableRapier64）／f32（未打包遭回退 f64）；失败仅记日志
        boolean useF64 = isRapier64Enabled();
        SableNativeLoader.load(useF64);
    }

    private static boolean isRapier64Enabled() {
        // 官方模式：spec 加载后按 spec 值；类加载期未加载 → spec 默认 false
        return ConfigSable.SPEC.isLoaded()
                ? ConfigSable.ENABLE_SABLE_RAPIER64.get()
                : false;
    }

    // ===== native 声明（签名与官方一致，供 JVM 匹配 DLL 导出符号） =====
    // Rust 侧 JNI 符号为 Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_*
    // 参数类型：long→J, int→I, double→D, boolean→Z, long/double/int 数组→[J/[D/[I

    public static native long initialize(double gravityX, double gravityY, double gravityZ, double universalDrag);
    public static native void tick(long sceneHandle, double timeStep);
    public static native void step(long sceneHandle, double timeStep);
    public static native void createSubLevel(long sceneHandle, int id, double[] pose, boolean isStatic);
    public static native void removeSubLevel(long sceneHandle, int id);
    public static native void getPose(long sceneHandle, int id, double[] store);
    /** ★ 2026-09-05 【批量位姿（按空间更新）】一次返回全部 body：扁平 [id,x,y,z,qx,qy,qz,qw,...]；
     *  out_count 输出 body 数。native 侧读 raw store（需预分配 8×(体数+余量)）。 */
    public static native void getPoseBatch(long sceneHandle, double[] store, int[] out_count);
    /** ★ 2026-09-06 【OBJ 导出】导出指定 id 集合（结构+陪体）体素为 world 坐标 OBJ 文本写文件。 */
    public static native void exportObj(long sceneHandle, int[] ids, String path);
    public static native void setCenterOfMass(long sceneHandle, int id, double x, double y, double z);
    public static native void setLocalBounds(long sceneHandle, int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    public static native void addChunk(long sceneHandle, int x, int y, int z, int[] chunk, boolean global, int id);
    public static native void removeChunk(long sceneHandle, int x, int y, int z, boolean global);
    public static native void changeBlock(long sceneHandle, int x, int y, int z, int newState);
    public static native void setMassProperties(long sceneHandle, int index, double mass, double[] centerOfMass, double[] inertiaTensor);
    public static native void teleportObject(long sceneHandle, int id, double x, double y, double z, double i, double j, double k, double r);
    public static native void wakeUpObject(long sceneHandle, int id);
    public static native void addLinearAngularVelocities(long sceneHandle, int bodyId, double linearX, double linearY, double linearZ, double angularX, double angularY, double angularZ, boolean wakeUp);
    public static native double[] clearCollisions(long sceneHandle);
    public static native void applyForce(long sceneHandle, int bodyID, double x, double y, double z, double fx, double fy, double fz, boolean wakeUp);
    public static native void applyForceAndTorque(long sceneHandle, int bodyID, double fx, double fy, double fz, double tx, double ty, double tz, boolean wakeUp);
    public static native void getLinearVelocity(long sceneHandle, int bodyID, double[] store);
    public static native void getAngularVelocity(long sceneHandle, int bodyID, double[] store);
    static native void createKinematicContraption(long sceneHandle, int mountId, int id, double[] pose);
    static native void removeKinematicContraption(long sceneHandle, int id);
    static native void setKinematicContraptionTransform(long sceneHandle, int id, double[] centerOfMass, double[] pose, double[] velocities);
    static native void addKinematicContraptionChunkSection(long sceneHandle, int id, int x, int y, int z, int[] data);
    public static native long createRope(long sceneHandle, double pointRadius, double firstJointLength, double[] points, int pointCount);
    public static native long removeRope(long sceneHandle, long ropeId);
    public static native void setRopeAttachment(long sceneHandle, long ropeId, int subLevelId, double x, double y, double z, boolean end);
    public static native void addRopePointAtStart(long sceneHandle, long ropeId, double x, double y, double z);
    public static native void removeRopePointAtStart(long sceneHandle, long ropeId);
    public static native void wakeUpRope(long sceneHandle, long ropeId);
    public static native void setRopeFirstSegmentLength(long sceneHandle, long ropeId, double firstSegmentLength);
    public static native double[] queryRope(long sceneHandle, long ropeId);
    public static native void configSolverIterations(int solverIterations, int pgsIterations, int stabilizationIterations);
    public static native void configMinIslandSize(int islandSize);
    public static native void dispose(long sceneHandle);

    // ===== 体素碰撞器注册（2026-09-01：修复 addChunk 崩溃——colliderID 未注册） =====
    /**
     * newVoxelCollider 原为 private static native；DLL 导出符号
     * Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_newVoxelCollider 存在。
     * 注册一个体素碰撞器（friction/volume/restitution/isFluid/callback）。
     * 返回 collider handle（写入 chunk 的 <<16 = handle+1）。
     * ⚠ 签名必须与官方一致（JNI 按方法名+JVM 签名匹配）：callback 类型为官方接口。
     */
    public static native int newVoxelCollider(double frictionMultiplier, double volume,
                                              double restitution, boolean isFluid,
                                              BlockSubLevelCollisionCallback contactEvents);

    public static native void addVoxelColliderBox(int index, double[] bounds);

    public static native void clearVoxelColliderBoxes(int index);

    // ===== 形状刚体 / 多刚体类型 / 柔体（2026-09-02 shapes.rs 新增接口） =====
    // bodyType: 0=dynamic 1=fixed(真静态) 2=kinematic-position 3=kinematic-velocity
    // shapeType: 0=ball[radius] 1=capsule[halfHeight,radius] 2=box[halfX,halfY,halfZ]
    //            3=convex hull[nv, (x,y,z)*nv]
    public static native void createShapeBody(long sceneHandle, int id, int bodyType,
                                              double mass, int shapeType, double[] params,
                                              double friction, double restitution, double[] pose);

    public static native void createTrimeshShapeBody(long sceneHandle, int id, int bodyType,
                                                     double mass, double[] vertices, int[] indices,
                                                     double friction, double restitution, double[] pose);

    public static native void removeShapeBody(long sceneHandle, int id);

    /** 给已有 body 追加组合 collider（多边形/圆形结构可由多个 shape 拼合）。 */
    public static native void addShapeCollider(long sceneHandle, int id, int shapeType,
                                               double[] params, double friction, double restitution);

    /** ★ 2026-09-03 原生 shape 直接碰撞：给已有 body 在 (px,py,pz) 偏移处追加 collider
     *  （box[0.5,0.5,0.5] @ 方块坐标 = 每格一个完整物理块）。 */
    public static native void addShapeColliderAt(long sceneHandle, int id, int shapeType,
                                                 double[] params, double friction, double restitution,
                                                 double px, double py, double pz);

    /** ★ 2026-09-05 【凹形组合·轴合并大 box】一次创建刚体并挂 count 个轴对齐 box
     *  collider（concave compound：相邻实心块轴合并成的大 box，结构本体/陪体统一用）。
     *  halfAndCenter 扁平 [hx,hy,hz,cx,cy,cz]*count；boxCoeffs 可选（可 null）每 box
     *  [friction,restitution]*count（材质感知 per-box 系数；缺省回退 friction/restitution）；
     *  density=0 不加质量（结构质量由 setMassProperties 提供）；bodyType 0=dynamic 1=fixed
     *  （fixed 登记 shape 缓存）。 */
    public static native void createCompoundShapeBody(long sceneHandle, int id, int bodyType,
                                                      double mass, int count, double[] halfAndCenter,
                                                      double[] boxCoeffs,
                                                      double friction, double restitution,
                                                      double[] pose);

    /** ★ 2026-09-05 增量更新：从 body 上按 (px,py,pz) 偏移删除 collider（addShapeColliderAt 逆操作）。
     *  不存在则 no-op（幂等），用于陪体/结构 box 集合 diff。 */
    public static native void removeShapeColliderAt(long sceneHandle, int id,
                                                    double px, double py, double pz);

    /** ★ 2026-09-05 场景桶动态重居中：平移整个场景所有刚体 body (dx,dy,dz)（世界坐标单位）。
     *  collider 随 body 自动跟；唤醒所有 body；返回平移 body 数。用于分桶原点迁移。 */
    public static native int recenterScene(long sceneHandle, double dx, double dy, double dz);

    /** ★ 2026-09-04 【AABB 相交查询（物理空间重叠检测用）】查询指定 scene 中世界 AABB 与
     *  查询盒相交的结构 body（rigid_bodies 映射内），写入相交的 LevelColliderID。
     *  用于物理空间合并/拆分的重叠判定（走 Rust 物理碰撞检测，可靠）。
     *  @param sceneHandle 目标场景（物理空间独立 scene）
     *  @param minX..maxZ 查询盒世界坐标
     *  @param outIds 预分配 int[]（建议 ≥64）写入相交 body id
     *  @return 相交 body 数（≤ outIds 容量；超出截断） */
    public static native int queryAabbIntersecting(long sceneHandle,
                                                   double minX, double minY, double minZ,
                                                   double maxX, double maxY, double maxZ,
                                                   int[] outIds);

    /** ★ 2026-09-05 【陪体扫描区差异同步（差异计算完全交给 Rust）】
     *  传入陪体(id)最新完整扫描 section 集，Rust 对比自身旧 own chunk_map 只做增删改差异，
     *  再重建 octree。替代 Java 全量 removeSubLevel+addChunk 重建。
     *  @param sceneHandle 陪体所属场景
     *  @param id 陪体 body 自定义 id（shapeCompanionId）
     *  @param sectionCount 本次 section 数
     *  @param secKeys 扁平 [x,y,z]*sectionCount（局部 section 坐标）
     *  @param chunkData 扁平 int[4096]*sectionCount（xzy 序）
     *  @param minX..maxZ 最新局部 bounds（块坐标）
     *  @return 变化 section 数（0=无变化） */
    public static native int syncCompanionChunks(long sceneHandle, int id, int sectionCount,
                                                 int[] secKeys, int[] chunkData,
                                                 int minX, int minY, int minZ,
                                                 int maxX, int maxY, int maxZ);

    /** 柔体粒子 = 小型 dynamic 球体刚体。 */
    public static native void createParticleBody(long sceneHandle, int id, double radius,
                                                 double mass, double[] pose);

    /** 柔体边 / 距离弹簧约束（布料、链条）。返回 joint handle。 */
    public static native long linkBodiesSpring(long sceneHandle, int idA, int idB,
                                               double[] localAnchorA, double[] localAnchorB,
                                               double frequency, double dampingRatio);

    // ===== ★ 2026-09-04 shape 缓存上限（陪体缓存淘汰） =====

    /** 设置 shape 缓存上限（limit>=0 个数；-1 不限；0 禁用）。
     *  minEvict>0 时每次淘汰至少删除 minEvict 个（最小批量删除数）。
     *  缩小 → 立即执行一次淘汰；扩大/相等 → 只扩大不淘汰。 */
    public static native void setShapeCacheLimit(long sceneHandle, long limit, long minEvict);

    /** 当前 shape 缓存上限（-1=不限）。 */
    public static native long getShapeCacheLimit(long sceneHandle);

    /** 当前最小批量删除数。 */
    public static native long getShapeCacheMinEvict(long sceneHandle);

    /** 强制一次缓存淘汰（按权重低+footprint 小优先；至少删 minEvict 个）。返回删除数。 */
    public static native long evictShapeCache(long sceneHandle);
}