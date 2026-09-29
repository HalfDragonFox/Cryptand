/**
 * ===== 原版导线转换类（2026-08-13 用户架构：转换 + 接管删除实体） =====
 *
 * 检测世界中的原版 PowerGrid 导线/接线端子，【转换】为 Cryptand 自管内容：
 *   - 原版导线（TransmissionLine）→ WireGraph 边（WirePoint 端点 + WireEdge）
 *   - 原版端子（ElectricBehaviour.getTerminal / 接线端子）→ TerminalRegistry
 *     （pos#term → TerminalElement 稳定映射）
 *
 * 【转换 + 删除实体（2026-08-13 用户要求）】：
 *   转换成功后，世界中的原版导线实体（BaseWireEntity）【删除】——实体不再是
 *   拓扑载体（自管 WireGraph 是），视觉已由 Flywheel 接管。删除用
 *   WireEntityTakeoverMixin 拦截级联（保留 transmissionLines/connections 作为
 *   转换数据源，防止自管边被误删）。
 *
 * 门控（两开关且）：
 *   - com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid.ENABLE_POWERGRID_CONVERSION（默认 true，用户开关）
 *   - 仿真核心启用（ENABLE_CRYPTAND_SIMULATION 或 ENABLE_CRYPTAND_SOLVER 任一
 *     true）——转换是仿真接管的准备，仿真关闭时保持完全原版不转换。
 *
 * 调用点：WorldNetworksMixin 快照 WORLD_WIRES 后（主线程每 tick）。
 * 线程安全：主线程调用（与 WireGraphStore 同线程模型）；WireGraph 内部读写锁。
 */
package com.hdf.cryptand.neoforge.powergrid.network.wire;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.net.WireGraphSyncPayload;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PowerGridWireConverter {

    /** 转换诊断节流 */
    private static volatile long CONVERT_DBG_LAST;

    /** 已转换待删除的导线实体 UUID（WireEntityTakeoverMixin 读；删除后清理） */
    private static final Set<UUID> CONVERTED_ENTITIES = ConcurrentHashMap.newKeySet();

    /** 已导入自管图的导线（2026-09-11 事件驱动化）：convertWires 每 tick 只处理
     *  【新出现】的导线——已有导线零成本跳过（不再逐条做端点解析/类型查表/
     *  WireEdge 构造）。弱引用集合：导线对象移出世界导线表后自动回收。仅主线程访问。 */
    private static final Set<TransmissionLine> IMPORTED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private PowerGridWireConverter() {
    }

    /**
     * 导线接管是否启用（2026-09-11 用户架构：两个主开关分隔 + 导线【恒定接管】）。
     * <p>= 交错电网支持（enablePowergridSupport）× 电路仿真核心（enableCryptandSimulation）；
     * 原 enablePowergridConversion 开关已移除（导线接管是必要替换，恒启用）。
     * 两者之任一关闭 → 不接管（保持原版导线）。
     */
    public static boolean isEnabled() {
        try {
            return ConfigPowerGrid.ENABLE_POWERGRID_SUPPORT.get()
                    && ConfigCircuit.ENABLE_CRYPTAND_SIMULATION.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 实体是否被 Cryptand 标记为"已转换待删除"（WireEntityTakeoverMixin 读）。 */
    public static boolean isConvertedEntity(BaseWireEntity e) {
        return e != null && CONVERTED_ENTITIES.contains(e.getUUID());
    }

    /** 实体已处理（删除完成）→ 清理标记。 */
    public static void onEntityRemoved(BaseWireEntity e) {
        if (e != null) CONVERTED_ENTITIES.remove(e.getUUID());
    }

    /** 删除已转换的原版导线实体（转换成功后调用，主线程）。
     *  只删除【两端点已进入自管图】的导线实体（已被转换接管）；标记进
     *  CONVERTED_ENTITIES 后调 remove()（mixin 拦截级联，保留网络数据）。
     *  玩家剪线/烧毁的导线不在此（两端点不在自管图 → 跳过）。
     *  节流：大 AABB 全图扫描每 ~1s 一次（20 tick），删除延迟可接受。 */
    private static int removeScanTick;
    public static void removeConvertedWires(Level level) {
        if (!isEnabled() || level == null || level.isClientSide) return;
        try {
            var mgr = WireNetworkManager.get();
            if (mgr.nodeCount() == 0) return;
            if (++removeScanTick % 20 != 0) return; // 节流 ~1s
            // 遍历世界中已加载范围的导线实体（spawn 中心 ±2048；覆盖已加载区块区）
            // ⚠ 2026-08-26：原 ±1e7 全图盒被 Sable SubLevelInclusiveLevelEntityGetter
            // 判定 abnormal → abort（查询返回空）→ 已转换导线实体永不删除 + 每秒刷
            // ERROR "[Sable] Aborting entity get for abnormally large AABB"。缩小到
            // 已加载活动范围：恢复删除功能同时消除刷屏。
            java.util.List<BaseWireEntity> wires = level.getEntitiesOfClass(
                    BaseWireEntity.class,
                    net.minecraft.world.phys.AABB.ofSize(
                            level.getSharedSpawnPos().getCenter(), 4096.0, 4096.0, 4096.0));
            for (BaseWireEntity wire : wires) {
                if (CONVERTED_ENTITIES.contains(wire.getUUID())) continue;
                // 两端点是否都已进入自管图（该导线已被转换接管）
                IWireEndpoint ep1 = wire.getEndpoint1();
                IWireEndpoint ep2 = wire.getEndpoint2();
                WirePoint a = pointOf(ep1);
                WirePoint b = pointOf(ep2);
                if (a == null || b == null) continue;
                if (!mgr.contains(a) || !mgr.contains(b)) continue;
                // 标记 + 删除（mixin 拦截级联，只移除实体本身）
                CONVERTED_ENTITIES.add(wire.getUUID());
                try {
                    wire.remove(Entity.RemovalReason.DISCARDED);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 每 tick 增量转换：原版导线表 → 自管网络对象。
     * <p>
     * 只做数据翻译（不接管原版）：遍历当前全部原版导线 → 生成 WirePoint/
     * WireEdge → addEdge（幂等：同端点已存在则忽略）。
     * 幽灵导线（两端均无连接，已物理断开）不导入。
     * <p>
     * ⚠ 无差量移除（2026-08-14 用户架构：自管拓扑为真相）：自管模式下原版
     * 导线实体已被 removeConvertedWires 删除 → transmissionLines 里的导线
     * 失效（isEffectivelyConnected false）→ 若做"已不在世界则删除"的差量
     * 移除，会把自管图全部边误删（日志实证 [Convert] outEdges=0 →
     * [WireSync] edges=8→0）→ 导线无法传输 + 重进丢失。删除全部走自管路径：
     * 剪线（removeWiresAt）/ 方块破坏（ElectricBlockEntityRemoveMixin）/
     * 过热烧毁（DestructionQueue.requestWire）。
     * <p>
     * 门控：isEnabled() 为 false 时不转换（保持自管图为空 = 完全原版）。
     */
    public static void convertWires(java.util.List<TransmissionLine> worldWires) {
        if (!isEnabled()) return;
        if (worldWires == null) return;
        var mgr = WireNetworkManager.get();
        try {
            // ⚠ 2026-08-20 移除设备点反推补丁：设备点由【SavedData 设备点持久化】
            // 保证（WireSavedData.devices → restoreDevicePoints），世界重载自动恢复；
            // 放置时 EntityPlace addDevice。无需每 20 tick 从导线端点反查 BE 补全
            // （补丁式修复，已由持久化治本替代）。
            // 诊断（节流 5s）：转换输入（世界导线数）→ 输出（自管图边数）
            try {
                long now = System.currentTimeMillis();
                if (now - CONVERT_DBG_LAST >= 5000) {
                    CONVERT_DBG_LAST = now;
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[Convert] in={} outNodes={} outEdges={}",
                            worldWires.size(), mgr.nodeCount(), mgr.edgeCount());
                }
            } catch (Throwable ignored) {
            }
            // 增量导入（幂等）：把世界有效导线加入自管图。不做差量移除（见类注释）
            for (TransmissionLine tl : worldWires) {
                try {
                    // 事件驱动化：已同步过的导线直接跳过（新导线才做完整解析入图）
                    if (IMPORTED.contains(tl)) continue;
                    if (tl.getNode1() == null || tl.getNode2() == null) continue;
                    WirePoint a = pointOf(tl.getEndpoint1());
                    WirePoint b = pointOf(tl.getEndpoint2());
                    if (a == null || b == null || a.equals(b)) continue;
                    double r = tl.getResistance();
                    if (isEffectivelyConnected(tl)) {
                        // 导线类型（注册器：渲染器 id；未注册 → 默认渲染）
                        String itemId = wireItemIdOf(tl);
                        SaggingWireType wt =
                                SaggingWireRegistry
                                        .byItemId(itemId);
                        // 2026-08-14 渲染参数引用化：只存渲染器 id（不存 sag/color
                        // 副本）；渲染/存储时按 id 查 SaggingWireRegistry 取参数
                        String rendererId = wt != null ? wt.id()
                                : SaggingWireRegistry
                                        .rendererIdOf(itemId);
                        WireEdge e = new WireEdge(a, b, Math.max(r, 0), null,
                                0.0, rendererId, 0, false, itemId);
                        mgr.addEdge(e); // 幂等：同端点边已存在 → 忽略（不增 version）
                        IMPORTED.add(tl); // 标记已同步（下 tick 直接跳过）
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        // 转换成功后删除已接管的原版导线实体（世界清理；mixin 拦截级联保留网络数据）
        try {
            removeConvertedWires(PhasorPipeline
                    .CRYPTAND_LAST_LEVEL);
        } catch (Throwable ignored) {
        }
        // 自管图同步到客户端（渲染数据源：客户端不再依赖已删除的实体）
        try {
            syncGraphToClients(PhasorPipeline
                    .CRYPTAND_LAST_LEVEL);
        } catch (Throwable ignored) {
        }
    }

    /** 图同步节流计数/去重版本 */
    private static int syncTick;
    private static long lastSyncedVer = -1;

    /** 发送全量图边（服务端主线程；sendToAllPlayers）。
     *  ⚠ 2026-08-17 修复：端子位置必须用【当前 world Level】计算——原实现用
     *  静态 CRYPTAND_LAST_LEVEL（round() 才赋值），玩家新开世界/重进后第一次
     *  放线时它可能为 null/旧世界 → getTerminalPos 回退块中心 → 导线渲染在
     *  模型中心而非端子。 */
    private static void sendEdges(Level level) {
        var mgr = WireNetworkManager.get();
        // ⚠ 防御（2026-08-14 保存世界卡住）：Server 停止/无玩家时不发送——
        // 保存/退出期间 sendToAllPlayers 在连接关闭中可能阻塞 Server 线程
        try {
            net.minecraft.server.MinecraftServer srv =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null || srv.isStopped()) return;
            if (srv.getPlayerList() == null
                    || srv.getPlayerList().getPlayers().isEmpty()) return;
        } catch (Throwable ignored) {
        }
        java.util.List<WireGraphSyncPayload.WireEdgeData> edges =
                new java.util.ArrayList<>();
        for (WireEdge e : mgr.edgeList()) {
            WireGraphSyncPayload.WireEdgeData d = toEdgeData(e, level);
            if (d != null) edges.add(d);
        }
        // 诊断（节流）：服务端实际发出的图同步包
        try {
            if (++sendDiag % 5 == 0) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[WireSync] server send edges={} ver={} graphNodes={}",
                        edges.size(), mgr.version(), mgr.nodeCount());
            }
        } catch (Throwable ignored) {
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                new WireGraphSyncPayload(edges));
    }

    private static int sendDiag;

    /**
     * 服务端把自管 WireGraph 广播给所有玩家（WireGraphSyncPayload）。
     * 节流 10 tick 后委托 Now（version 去重）；客户端渲染层据此更新。
     */
    public static void syncGraphToClients() {
        syncGraphToClients(PhasorPipeline
                .CRYPTAND_LAST_LEVEL);
    }

    /** 带 Level 版本（2026-08-17）：用当前世界 level 计算端子精确位置 */
    public static void syncGraphToClients(Level level) {
        if (++syncTick % 10 != 0) return;
        syncGraphToClientsNow(level);
    }

    /**
     * 立即同步（放置/剪线/设备移除后调用）：跳过节流，仅 version 去重。
     * 修复 2026-08-14：placeWire 后若撞上节流点（syncTick%10!=0）同步包被吞
     * → 客户端永远看不到新放置的线（"依旧无法放置"实为看不到）。
     */
    public static void syncGraphToClientsNow() {
        syncGraphToClientsNow(PhasorPipeline
                .CRYPTAND_LAST_LEVEL);
    }

    /** 带 Level 版本（2026-08-17）：放置/剪线后立即用当前 world level 计算端子位置 */
    public static void syncGraphToClientsNow(Level level) {
        if (!isEnabled()) return;
        var mgr = WireNetworkManager.get();
        if (mgr == null) return;
        long ver = mgr.version();
        if (ver == lastSyncedVer) return;
        lastSyncedVer = ver;
        sendEdges(level);
    }

    /**
     * 强制同步（玩家加入/世界加载后调用）：绕过 version 去重，确保新客户端
     * 无论图是否变化都能拿到全量图。修复：lastSyncedVer 是全局的——第二个
     * 玩家加入时图可能未变 → 普通去重跳过 → 新玩家看不到任何线。
     */
    public static void syncGraphToClientsForce() {
        syncGraphToClientsForce(PhasorPipeline
                .CRYPTAND_LAST_LEVEL);
    }

    /** 带 Level 版本（2026-08-17）：玩家加入/世界加载后全量同步 */
    public static void syncGraphToClientsForce(Level level) {
        if (!isEnabled()) return;
        var mgr = WireNetworkManager.get();
        if (mgr == null) return;
        lastSyncedVer = mgr.version();
        sendEdges(level);
    }

    /** 图边 → 同步包边数据（端点 key 解析；B/J 点都支持；失败 null） */
    private static WireGraphSyncPayload.WireEdgeData toEdgeData(WireEdge e, Level level) {
        try {
            int[] a = endpointDataOf(e.a);
            int[] b = endpointDataOf(e.b);
            if (a == null || b == null) return null;
            // 端点【精确位置】（原版 getTerminalPos）；失败回退块中心
            float axF = a[0] + 0.5f, ayF = a[1] + 0.5f, azF = a[2] + 0.5f;
            float bxF = b[0] + 0.5f, byF = b[1] + 0.5f, bzF = b[2] + 0.5f;
            if (level != null) {
                net.minecraft.world.phys.Vec3 pa = exactEndpointPos(level, a);
                if (pa != null) { axF = (float) pa.x; ayF = (float) pa.y; azF = (float) pa.z; }
                net.minecraft.world.phys.Vec3 pb = exactEndpointPos(level, b);
                if (pb != null) { bxF = (float) pb.x; byF = (float) pb.y; bzF = (float) pb.z; }
            }
            return new WireGraphSyncPayload.WireEdgeData(
                    a[0], a[1], a[2], a[3],
                    b[0], b[1], b[2], b[3],
                    axF, ayF, azF, bxF, byF, bzF,
                    e.rendererId, e.colorOverride);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 边 → 原版导线贴图路径（注册器优先：itemId → SaggingWireType.texture；
     *  未注册 → WireItemEntry.texture；失败空串=默认）
     *  ⚠ 2026-08-14 已废弃（渲染参数引用化：客户端按 rendererId 查注册器取
     *  材质，不再随包传输 texture） */
    private static String textureOf(com.hdf.cryptand.circuitsimulation.netgraph.WireEdge e,
                                    net.minecraft.world.level.Level level) {
        try {
            if (e.itemId == null || level == null) return "";
            SaggingWireType wt =
                    SaggingWireRegistry.byItemId(e.itemId);
            if (wt != null && wt.texture() != null) return wt.texture().toString();
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.parse(e.itemId));
            if (item == null) return "";
            org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry entry =
                    org.patryk3211.powergrid.electricity.wire.registry.WireRegistry
                            .forItem(level, item);
            if (entry != null && entry.texture() != null) return entry.texture().toString();
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 端子精确位置（B 点：getExactPosition = 原版 getTerminalPos；J 点：null 回退块中心）。
     *  ⚠ 2026-08-15 卡世界修复：getExactPosition → IElectric.getAt →
     *  getBlockState → ServerChunkCache.getChunk(...).join()——玩家登录期间
     *  （PlayerLoggedInEvent → syncGraphToClientsForce）区块未加载时，Server
     *  thread 在 join() 上自锁（区块加载任务等主线程 → 主线程等区块）→ 卡世界。
     *  未加载区块直接回退块中心（不强制加载区块）。 */
    private static net.minecraft.world.phys.Vec3 exactEndpointPos(
            net.minecraft.world.level.Level level, int[] d) {
        try {
            if (d[3] >= 0) {
                net.minecraft.core.BlockPos pos =
                        new net.minecraft.core.BlockPos(d[0], d[1], d[2]);
                // 区块未加载 → 不查精确位置（getBlockState 会强制加载区块，
                // 登录期间会自锁 Server thread）；回退块中心。
                if (level == null || !level.isLoaded(pos)) return null;
                return new org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint(
                        pos, d[3]).getExactPosition(level);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 端点 key → [x, y, z, term]（WireKeyUtil 兼容两种格式；失败 null） */
    private static int[] endpointDataOf(WirePoint p) {
        try {
            int[] xyz = WireKeyUtil.xyzOf(p.key);
            if (xyz == null) return null;
            return new int[]{xyz[0], xyz[1], xyz[2], WireKeyUtil.termOf(p.key)};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 端点 → 稳定 WirePoint 键（"B"+pos+"#"+term / "J"+pos） */
    private static WirePoint pointOf(IWireEndpoint ep) {
        if (ep == null) return null;
        try {
            if (ep instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bep) {
                return new WirePoint("B" + bep.getPos() + "#" + bep.getTerminal());
            }
            if (ep instanceof org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint jep) {
                net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos
                        .containing(jep.getExactPosition(
                                PhasorPipeline
                                        .CRYPTAND_LAST_LEVEL));
                return new WirePoint("J" + pos);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 导线颜色（ARGB）：从 TransmissionLine 反射拿 owner（BaseWireEntity）→ getColor()。
     *  反射失败/无 owner → 默认暗红。运行时安全（不依赖编译时 owner API）。 */
    private static int wireColorOf(TransmissionLine tl) {
        try {
            Class<?> c = tl.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("owner");
                    f.setAccessible(true);
                    Object o = f.get(tl);
                    if (o instanceof BaseWireEntity we) return we.getColor() | 0xFF000000;
                    break;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0xFFFF3333; // 默认暗红
    }

    /** 导线物品 ID（剪线返回物品用）：从 TransmissionLine 反射 owner → getItem()。
     *  反射失败 → null（剪线时不返回物品）。 */
    private static String wireItemIdOf(TransmissionLine tl) {
        try {
            Class<?> c = tl.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("owner");
                    f.setAccessible(true);
                    Object o = f.get(tl);
                    if (o instanceof BaseWireEntity we && we.getItem() != null) {
                        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(we.getItem()).toString();
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 导线是否物理有效（幽灵过滤）：两端均无连接 → 断开 → 不导入。 */
    private static boolean isEffectivelyConnected(TransmissionLine tl) {
        try {
            IWireEndpoint e1 = tl.getEndpoint1();
            IWireEndpoint e2 = tl.getEndpoint2();
            boolean c1 = e1 instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint b1
                    ? PhasorNetworkBuilder
                            .endpointHasWireConnection(
                                    PhasorPipeline
                                            .CRYPTAND_LAST_LEVEL,
                                    b1)
                    : true;
            boolean c2 = e2 instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint b2
                    ? PhasorNetworkBuilder
                            .endpointHasWireConnection(
                                    PhasorPipeline
                                            .CRYPTAND_LAST_LEVEL,
                                    b2)
                    : true;
            return c1 || c2;
        } catch (Throwable ignored) {
            return true;
        }
    }
}
