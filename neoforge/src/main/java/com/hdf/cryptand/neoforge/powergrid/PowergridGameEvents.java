/**
 * ===== PowerGrid 子包：游戏事件自持注册（2026-09-07 子包隔离迁移） =====
 *
 * 原挂在 CryptandNeoForge 构造器内的 powergrid 相关 GAME 总线监听整体迁入本类：
 * SQLite/导线图/端子/放置即建网/玩家加入强同步/拓扑异步调度停止。
 *
 * <ul>
 *   <li>@EventBusSubscriber 由 NeoForge 按 mod 扫描自动注册（game bus）——注册与否
 *       只取决于【类是否存在】：删除本子包目录 → 类消失 → 全部监听自动消失，
 *       core 零引用（隔离验收：core 与其余子包照常编译运行）。</li>
 *   <li>与迁移前主类行为一致：监听无条件注册（不读 enablePowergridSupport——原主类
 *       亦无条件；内容级 gate 由各方法内部原样保留，如 PowerGridWireConverter.isEnabled）。</li>
 *   <li>全部方法沿用原 try/catch(Throwable) 兜底语义。</li>
 * </ul>
 */
package com.hdf.cryptand.neoforge.powergrid;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.FanCoolingRegistry;
import com.hdf.cryptand.neoforge.powergrid.persistence.CryptandSqlite;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheTable;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.persistence.SimulationCircuitFolder;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** PowerGrid 子包游戏事件。
 *  ⚠ 2026-08-30 用户架构（子包开关必须真正生效）：原 {@code @EventBusSubscriber}
 *  由 NeoForge【自动注册】——子包 enabled=false 时监听仍生效（每 tick 扫描/存档补开
 *  等旁路开销）。改为【由 PowergridEntry.init 显式 bus.register(...)】——
 *  enabled=false → init 不调用 → 本类监听完全不注册。 */
public final class PowergridGameEvents {

    private PowergridGameEvents() {
    }

    /**
     * ===== 区块加载 → 批量「开启」电气设备绑定（2026-09-11 用户）=====
     *
     * 用户：绑定增加一个 bool 表示是否加载；区块加载时对被该区块加载的 BE 电气设备
     * 的组装器批量发送「开启」，区块卸载反之；创建 BE 电气设备时默认为加载。
     * <p>语义：loaded=true「开启」/ false「关闭」——【绝不删除电路与模型】
     *（区块卸载时电路仍旧保留计算），仅标记可用性；真正移除仍由真实破坏事件处理。
     */
    @SubscribeEvent
    public static void onChunkLoad(final net.neoforged.neoforge.event.level.ChunkEvent.Load ev) {
        applyChunkLoadState(ev.getLevel(), ev.getChunk(), true, "load");
    }

    /** 区块卸载 → 批量「关闭」（电路/模型保留，不做任何删除）。 */
    @SubscribeEvent
    public static void onChunkUnload(
            final net.neoforged.neoforge.event.level.ChunkEvent.Unload ev) {
        applyChunkLoadState(ev.getLevel(), ev.getChunk(), false, "unload");
    }

    /** 遍历区块内电气 BE → 其绑定批量置 loaded + 发 DEVICE_LOADED/DEVICE_UNLOADED。 */
    private static void applyChunkLoadState(net.minecraft.world.level.LevelAccessor lvlAcc,
            net.minecraft.world.level.chunk.ChunkAccess chunk, boolean loaded, String why) {
        try {
            if (!(lvlAcc instanceof net.minecraft.world.level.Level level)) return;
            if (level.isClientSide || chunk == null) return;
            if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk lc)) return;
            if (!CryptandTopologyManager.simulationEnabled()) return;
            int n = 0;
            for (java.util.Map.Entry<net.minecraft.core.BlockPos,
                    net.minecraft.world.level.block.entity.BlockEntity> e
                    : lc.getBlockEntities().entrySet()) {
                try {
                    if (!cryptand$isElectric(e.getValue())) continue;
                    com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding b =
                            com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding
                                    .forPos(e.getKey());
                    if (b == null) continue;
                    b.setLoaded(loaded);
                    b.onMessage(com.hdf.cryptand.engine.EngineMessage.of(
                            loaded
                                    ? com.hdf.cryptand.engine.EngineMessage.Type.DEVICE_LOADED
                                    : com.hdf.cryptand.engine.EngineMessage.Type.DEVICE_UNLOADED,
                            e.getKey()));
                    n++;
                } catch (Throwable ignored) {
                }
            }
            if (n > 0) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[DeviceLoad] chunk {} ({},{}) {} devices={}",
                        why, lc.getPos().x, lc.getPos().z,
                        loaded ? "ON" : "OFF", n);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 电气设备判定（电机/设备/接线柱）。 */
    private static boolean cryptand$isElectric(
            net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof org.patryk3211.powergrid.electricity.base.IElectricEntity
                || be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity
                || be instanceof org.patryk3211.powergrid.electricity.deviceconnector
                        .DeviceConnectorBlockEntity;
    }
    /** 服务端停止：停 CryptandTopologyManager 的异步调度线程（若有）。 */
    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent ev) {
        CryptandTopologyManager.get().stop();
        // 2026-09-12：清空风扇冷却登记（按风扇位置 + 位置累积值），防跨世界/重新
        // 进入存档时残留上一局的额外散热（风扇 BE 会重新 tick 并重新登记）
        try {
            com.hdf.cryptand.neoforge.powergrid.device.thermal.FanCoolingRegistry.clear();
        } catch (Throwable ignored) {
        }
    }

    /** 服务端 tick：SQLite 兜底打开（2026-08-14 重进丢失：LevelEvent.Load 可能未触发
     *  open → 首个服务端 tick 补开，保证导线存档可用；失败冷却见 CryptandSqlite）。 */
    @SubscribeEvent
    public static void onServerTickPost(final ServerTickEvent.Post ev) {
        final MinecraftServer srv = ev.getServer();
        if (srv == null) return;
        for (final ServerLevel lvl : srv.getAllLevels()) {
            try {
                CryptandSqlite.ensureOpen(lvl);
            } catch (final Throwable ignored) {
            }
        }
        // 风扇冷却看门狗（2026-09-12 用户："只要风扇停止运行就停止额外散热"）：
        // 心跳超时的风扇（区块卸载/卡死/未走 setRemoved）→ 撤销其额外散热贡献。
        // 内部自带 1s 节流，稳态开销可忽略。
        try {
            com.hdf.cryptand.neoforge.powergrid.device.thermal.FanCoolingRegistry.sweepStale();
        } catch (final Throwable ignored) {
        }
    }

    /** 玩家加入：强制同步一次自管导线图（绕过 version 去重——lastSyncedVer
     *  是全局的，第二个玩家加入时图可能未变，普通去重会跳过 → 新玩家看不到
     *  任何线；强制发送保证新客户端拿到全量图）。
     *  ⚠ 2026-08-15 卡世界修复：不能在本事件里【同步】发——此时登录流程
     *  （placeNewPlayer）尚未完成，sendEdges → getExactPosition →
     *  getBlockState → ServerChunkCache.getChunk(...).join() 会在 Server
     *  thread 上自锁（区块加载任务等主线程 → 主线程等区块）→ 玩家加入卡世界。
     *  改为排到下一 tick 再发（登录完成、区块正常加载中）。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(
            final net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent ev) {
        try {
            if (ev.getEntity().level() instanceof ServerLevel sl) {
                // 排到下一 tick 发（登录完成后区块正常加载）；传当前 ServerLevel
                // 计算端子位置（2026-08-17 修复：无参版本依赖 CRYPTAND_LAST_LEVEL，
                // 玩家加入瞬间可能未设置 → 导线渲染在模型中心）
                sl.getServer().execute(() -> PowerGridWireConverter.syncGraphToClientsForce(sl));
            }
        } catch (final Throwable ignored) {
        }
    }

    // ===== 元件放置即建网（2026-08-15 用户要求）=====
    // 每个实际元件（含接线端子块）放下 → 其全部声明端子作为【同一单点网络】
    // 加入自管图（端子间无边不导电）：未接线即可被 /cryptand schematic 等
    // 识别；接线时 addEdge 正常 merge。世界重载后的设备点恢复走 SavedData
    // 持久化（WireSavedData.devices，2026-08-20 治本，替代 ChunkLoad 补丁）。
    @SubscribeEvent
    public static void onEntityPlace(final BlockEvent.EntityPlaceEvent ev) {
        try {
            if (ev.getLevel() == null || ev.getLevel().isClientSide()) return;
            if (!PowerGridWireConverter.isEnabled()) return;
            // ⚠ getEntity() 在 EntityPlaceEvent 返回 Entity；按位置取刚放置的
            // 方块实体（放置事件时 BE 已创建）
            final net.minecraft.world.level.block.entity.BlockEntity be =
                    ev.getLevel().getBlockEntity(ev.getPos());
            // 2026-08-20 修复"电机不建模"：ConstantSpeedMotor/ElectricMotor
            // 是 IElectricEntity（非 ElectricBlockEntity 子类，但有电气端子
            // buildCircuit）→ 必须同样 addDevice 入自管图，否则自管图无电机
            // 点 → 不建模 → 无 EMF 反馈 → 电流固定/假功率爆炸。
            if (!(be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity)
                    && !(be instanceof org.patryk3211.powergrid.electricity.base.IElectricEntity)) {
                return;
            }
            WireNetworkManager.get().addDevice(be.getBlockPos(),
                    PhasorNetworkBuilder.declaredTerminalCount(be));
            // 2026-09-13 用户："不管怎么样所有网络都会进行求解，元件可以不求解但是
            //  模型是必须的，孤立网络也要" —— 放置即建【温度模型】（不等网络求解/
            //  组装器运行）。此前孤立设备（未接线）永远拿不到模型，温度表显示「无」。
            //  电机族走族级损耗公式，其余走通用模型；已有模型原样保留（幂等）。
            com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore
                    .ensureModelFor(be);
            // ⚠ 2026-09-11 事件驱动（用户：接线柱存在性检测全部事件驱动，废除定时全扫）：
            // 元件放置后【按位置】精确检测——
            //   ① 该位置本身是接线柱 → 检测 facing 设备存在性（存在 → 更新真实组装器
            //      引用 + 非同网络则合并；不存在 → 引用置 null 不建模）；
            //   ② 邻位中【facing 指向本位置】的接线柱 → 同样检测（设备放在接线柱前方
            //      → 触发设备孤立网络并入接线柱网络）。
            // 语义与拓扑钩子一致：同网络 → 仅刷新引用（重建由网络版本/缓存校验按需
            // 触发）；不同网络 → addEdge 合并。
            try {
                if (ev.getLevel() instanceof net.minecraft.world.level.Level lv) {
                    com.hdf.cryptand.neoforge.powergrid.device.connector
                            .ProxyConnectorAssembler.detectNear(lv, ev.getPos());
                }
            } catch (final Throwable ignored) {
            }
        } catch (final Throwable ignored) {
        }
    }

    // ===== SQLite 存储生命周期（2026-08-13 用户架构：本 mod 提供 SQLite 存储接口，
    // 适用于其他 mod；写走单写线程异步队列，与求解线程池并行不阻塞）：
    //   世界加载 → 打开 <world>/cryptand/data.sqlite + 建 KV/NBT 表
    //   世界卸载 → flush 落盘 + 关连接 =====

    @SubscribeEvent
    public static void onLevelLoad(final LevelEvent.Load ev) {
        try {
            CryptandNeoForge.WAF_LOGGER.info("[Sqlite] LevelEvent.Load level={}",
                    ev.getLevel().getClass().getSimpleName());
            if (ev.getLevel() instanceof ServerLevel sl) {
                // 2026-08-14 修复"退出再进卡在加载世界"：SQLite/导线只在主世界
                // open/load。此前每维度都 open（内部先 close 旧连接再重开）——
                // integrated server 下 the_end 第二次 close 的 conn.close() 无
                // 超时可永久卡死 Server thread，世界加载卡住。
                if (sl.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                    CryptandSqlite.open(sl);
                    // 仿真电路文件夹（2026-08-15）：建 <world>/cryptand/simulation_circuit
                    // + 打开网表库 + 恢复 + 重建稳定 64 位 id 映射
                    SimulationCircuitFolder.onWorldLoad(sl);
                    // 自管导线拓扑（2026-08-13 阶段1）：新世界 → 清空重建
                    WireNetworkManager.get().onWorldLoad(sl);
                }
            }
        } catch (final Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(final LevelEvent.Unload ev) {
        try {
            if (ev.getLevel() instanceof ServerLevel sl) {
                // 与 Load 一致：只主世界处理（避免每维度重复 close）
                if (sl.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
                // ⚠ 顺序：先异步保存自管导线拓扑（close 前排队），再 close（flush
                // 落盘）——反序会因 store 置 null 导致导线存档被跳过 + close 的
                // flush 忙等无写任务可等
                WireNetworkManager.get().onWorldUnload();
                // 仿真电路文件夹：采集最终快照落盘 + 关网表库（内部 flush 等所有
                // 排队写完成）
                SimulationCircuitFolder.onWorldUnload();
                CryptandSqlite.close();
                // 端子注册表清理（2026-08-13 完全接管端子）：世界卸载 → 位置端子
                // 全部失效（跨世界残留会污染下一个世界）。
                TerminalRegistry.clear();
            }
        } catch (final Throwable ignored) {
        }
    }

    // ===== 存档网络表缓存（2026-08-13 用户架构：彻底取消 NBT——NBT 会导致 NBT
    // 过大等问题）。世界保存时采集【未加载区】虚拟设备参数 → DeviceCacheTable
    // （SQLite 强类型列存储，无 NBT）→ 跨会话持久化；进世界时 worldSynchronize
    // 从 SQLite 恢复（未加载区设备参数立即可用，强化世界加载）。
    // ⚠ 只缓存未加载区（虚拟）设备——已加载区进世界后重建自动重新生成，
    // 不冗余占用数据库。 =====

    @SubscribeEvent
    public static void onLevelSave(final LevelEvent.Save ev) {
        try {
            if (ev.getLevel() instanceof ServerLevel sl) {
                final DeviceCacheTable cache = CryptandSqlite.deviceCache();
                if (cache != null) cache.saveAsync(sl);
                // 仿真电路网表快照保存（2026-08-15：网络/组装器/导线/渲染器
                // 四表单事务异步写）
                SimulationCircuitFolder.onWorldSave(sl);
                // 自管导线拓扑保存（2026-08-14 原版 SavedData：随世界存档自动
                // 落盘，无需 SQLite）
                WireNetworkManager.get().onWorldSave(sl);
            }
        } catch (final Throwable ignored) {
        }
    }
}
