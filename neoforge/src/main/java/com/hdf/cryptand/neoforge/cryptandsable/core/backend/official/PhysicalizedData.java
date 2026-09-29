/**
 * ===== 物理化数据类（PhysicalizedData，2026-09-02） =====
 *
 * 每个【物理化结构】单独一个实例，封装该结构的全部物理化相关内容：
 *  - runtimeId / 位姿（当前物理位姿 + 初始锚）
 *  - 结构自身方块（实际物理化方块表：section → int[4096] 体素编码）
 *  - 扫描世界方块（扫描表：section → int[4096]；隔离，不合并进结构表）
 *  - 结构包围盒 / 质量 / 质心（构建虚拟物理结构体用）
 *
 * 计算（step）时只需【取该结构的数据】→ 建立【虚拟物理结构体】参与碰撞：
 *  虚拟物理结构体 = 结构自身块 + 扫描块 → 挂载原生（addChunk id=结构）
 *  每个结构独立（单数据类实例），互不索引。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

public final class PhysicalizedData {

    /** 结构 runtimeId（原生 body id）。 */
    public final int runtimeId;

    /** ★ 2026-09-05 【结构 UUID（ECS 唯一标识）】跨世界/持久化稳定；核心数据类
     *  （PhysicsWorldData.structuredTable）与绿色全局表按它索引。 */
    public final java.util.UUID uuid = java.util.UUID.randomUUID();

    /** 当前物理位姿 [x,y,z,qx,qy,qz,qw]（原生 getPose 实时；虚拟结构体构建基准）。 */
    public final double[] pose = new double[7];

    /** 初始锚（BlockPos 世界坐标；结构包围盒中心）。 */
    public final double[] anchor = new double[3];

    /** 结构局部包围盒（minX..maxX 六值；Rust localBounds）。 */
    public final int[] localBounds = new int[6];

    /** 结构自身方块表（实际物理化：section 坐标 → 4096 体素编码）。 */
    public final java.util.Map<SectionKey, int[]> structureBlocks = new java.util.HashMap<>();

    /** 扫描世界方块表（隔离：收集的世界块；不合并进 structureBlocks）。 */
    public final java.util.Map<SectionKey, int[]> scannedBlocks = new java.util.HashMap<>();

    /** 质量（体素数近似；构建虚拟体用）。 */
    public double mass = 1.0;

    // ===== ★ 2026-09-05 【材质感知合并 + 重量 + 三种力】（参考原版 MassTracker /
    //   FloatingBlockController；主线程构图材质编码 → 核心纯数据计算） =====

    /** 结构质量汇总（质量/质心/惯量/升力中心/浮力中心，世界坐标；null=未计算）。 */
    public CompoundShapeMerger.MassResult massProps = null;

    /** 结构级综合摩擦（box 体积加权平均）。 */
    public volatile double friction = 0.6;

    /** 结构级综合弹性（box 材质 max）。 */
    public volatile double restitution = 0.0;

    /** 升力中心（世界坐标；无升力方块 → NaN）。 */
    public volatile double liftX = Double.NaN, liftY = Double.NaN, liftZ = Double.NaN;

    /** 结构总升力强度（N；无升力方块 → 0）。 */
    public volatile double totalLift = 0.0;

    /** 浮力中心（世界坐标；无浮力方块 → NaN）。 */
    public volatile double buoyX = Double.NaN, buoyY = Double.NaN, buoyZ = Double.NaN;

    /** 结构总排水体积（无浮力方块 → 0）。 */
    public volatile double totalVolume = 0.0;

    /** ★ 2026-09-05 环境介质密度 [kg/m³]（水≈1000、空气≈1.225、空间 0）——浮力缩放。
     *  主线程环境解析可更新（MVP 默认陆地空气）。 */
    public volatile double mediumDensity = 1.225;

    /** ★ 2026-09-05 环境重力大小 |g| [m/s²]（宇宙微重力→小值）——升力/浮力缩放。
     *  主线程环境解析可更新（MVP 默认主世界）。 */
    public volatile double gravityMag = 9.81;

    /** 是否已物理化（createBody 完成）。 */
    public boolean active = false;

    // ===== ★ 2026-09-04 ECS 组件：收集/冻结/陪体状态（原引擎平行集合） =====

    /** 是否等待首次世界碰撞收集（冻结；collect 完成前不计算）。 */
    public boolean pending = false;

    /** 首次物理化（必须等待，永不超时解冻）。 */
    public boolean awaitFirst = false;

    /** 距上次发送已累计计算次数（达到 sendInterval 才发）。 */
    public int sendCounter = 0;

    /** 距上次发送后等待的计算次数。 */
    public int waitCounter = 0;

    /** 超时总触发计数。 */
    public int timeoutTotal = 0;

    /** ★ shape 陪体 id（-11000000 区；0=未建）。 */
    public int shapeCompanionId = 0;

    /** ★ shape 陪体【已挂载 box 偏移集】（增量 diff 基准：packPos 世界坐标；box collider 位置）。 */
    public final java.util.Set<Long> companionBoxes = new java.util.HashSet<>();

    /** ★ voxel 陪体 id（-10000000 区；0=未建）。 */
    public int voxelCompanionId = 0;

    /** ★ voxel 陪体已挂载段（去重；陪体 id → Set<SectionKey>）。 */
    public final java.util.Set<SectionKey> companionBlocks = new java.util.HashSet<>();

    /** ★ 2026-09-05 速度动态收集：上次位姿（速度差分基准；getPose 拉实时）。 */
    public final double[] lastPose = new double[7];

    /** ★ 2026-09-05 速度动态收集：当前运动速度（块/秒；由 emitCollectQuery 差分更新，两方向平方和）。 */
    public volatile double speed2 = 0.0;

    /** ★ 2026-09-05 陪体重建指纹：{上次重建位姿[3] 固定 0, 扫描表哈希, 已重建标记}。
     *  重建触发 = 位姿移动 OR 扫描表变化（用户定案 2026-09-05："物理结构被移动或者
     *  扫描变化时都进行重建"）。null=从未重建。 */
    public volatile long rebuildStamp = -1L;

    /** ★ 2026-09-05 上次重建时扫描表指纹（section 数 + 实心格数快速哈希；变化 → 重建）。 */
    public volatile long scanFingerprint = 0L;

    /** ★ 2026-09-05 上次重建时结构位姿（移动检测基准）。 */
    public volatile double lastRebuildX = Double.NaN;
    public volatile double lastRebuildY = Double.NaN;
    public volatile double lastRebuildZ = Double.NaN;

    /** ★ 2026-09-06 【质心（世界坐标）变化检测基准】上次重建/挂载时的结构质心
     *  （localBounds 中心）。结构内容（方块）变化 → 质心改变 → 立即重建该结构 body。 */
    public volatile double lastComX = Double.NaN;
    public volatile double lastComY = Double.NaN;
    public volatile double lastComZ = Double.NaN;

    /** 结构当前质心（世界坐标；localBounds 中心）。 */
    public double comWorldX() { return (localBounds[0] + localBounds[3] + 1) * 0.5; }
    public double comWorldY() { return (localBounds[1] + localBounds[4] + 1) * 0.5; }
    public double comWorldZ() { return (localBounds[2] + localBounds[5] + 1) * 0.5; }

    /** ★ 2026-09-06 结构质心（world）是否自上次重建后改变（localBounds 变化 = 内容变化）。
     *  NaN=从未记录（首次物理化 → 不判为变化）。 */
    public boolean comChanged() {
        if (Double.isNaN(lastComX)) return false;
        return Math.abs(comWorldX() - lastComX) > 0.01
                || Math.abs(comWorldY() - lastComY) > 0.01
                || Math.abs(comWorldZ() - lastComZ) > 0.01;
    }

    /** ★ 2026-09-05 【重建节流】上次重建陪体时间（nanoTime）；<500ms 不重复重建
     *  （世界方块变化合并延迟重建——防主线程被反复 4000 box 重建淹没冻结）。 */
    public volatile long lastRebuildNanos = 0L;

    // ===== ★ 2026-09-05 扫描区域（clip 红框，世界方块坐标）=====

    /** ★ 2026-09-05 扫描区域最小角（世界方块坐标 = 结构 bounds 边缘 ± radius 外扩）。 */
    public volatile int scanMinX = 0, scanMinY = 0, scanMinZ = 0;

    /** ★ 2026-09-05 扫描区域最大角（世界方块坐标）。 */
    public volatile int scanMaxX = 0, scanMaxY = 0, scanMaxZ = 0;

    /** ★ 2026-09-05 扫描区域是否已设置（chunk 加载补扫的前提）。 */
    public volatile boolean scanRangeSet = false;

    // ===== ★ 2026-09-05 多物理空间（space/桶）绑定 =====

    /** ★ 2026-09-05 所属物理空间 id（0=未分配；失败结构→自动单开（space=负 id 自建））。
     *  同一空间的局部坐标共用同一原点（toLocal/toWorld 用 space 的原点）。 */
    public volatile int spaceId = 0;

    /** ★ 2026-09-05 该结构在与空间原点关联的【局部世界坐标】（供 BFS 在同一空间内寻
     *  找；未分配时=主世界坐标）。⚠ 空间内 BFS 只作用于此字段，不碰 Level。 */
    public final double[] spacePose = new double[3];

    public PhysicalizedData(final int runtimeId) {
        this.runtimeId = runtimeId;
        // ★ 2026-09-06 初始质心基准（创建前 localBounds 未填；NaN 表示未记录，首次不判变化）
    }

    /** 结构包围盒中心（虚拟体初始位姿；float 几何中心）。 */
    public double centerX() { return (localBounds[0] + localBounds[3] + 1) * 0.5; }
    public double centerY() { return (localBounds[1] + localBounds[4] + 1) * 0.5; }
    public double centerZ() { return (localBounds[2] + localBounds[5] + 1) * 0.5; }

    /** 非空体素数（质量统计）。 */
    public int nonEmptyVoxels() {
        int n = 0;
        for (final int[] d : structureBlocks.values()) {
            if (d == null) continue;
            for (final int v : d) if (v != 0) n++;
        }
        return n;
    }

    /** 总 section 数（结构 + 扫描）。 */
    public int totalSections() {
        return structureBlocks.size() + scannedBlocks.size();
    }

    /** 调试摘要。 */
    public String summary() {
        return "rt=" + runtimeId + " structureSec=" + structureBlocks.size()
                + " scannedSec=" + scannedBlocks.size()
                + " voxels=" + nonEmptyVoxels() + " mass=" + mass
                + " pending=" + pending;
    }
}
