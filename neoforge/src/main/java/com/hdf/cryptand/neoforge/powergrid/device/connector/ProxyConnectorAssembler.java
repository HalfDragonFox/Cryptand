package com.hdf.cryptand.neoforge.powergrid.device.connector;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import com.hdf.cryptand.neoforge.powergrid.device.Assemblers;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ===== 设备接线柱【代理组装器】（2026-08-30 用户方案：代理组装器+代理 BE，
 * 包含真实组装器与真实 BE 的类引用） =====
 *
 * DeviceConnector 的代理组装器：代理 BE（接线柱）定位【facing 设备 BE】→
 * 按设备 BE 类查【真实组装器】（Assemblers.get）→ 完全复用真实组装器：
 *  - refreshCache（主线程）：真实组装器.refreshCache(设备BE, cache)（设备参数
 *    快照进接线柱输入槽）+ 输入槽存【真实设备 BE 类名】；
 *  - assembleFromCache（后台）：按类名查真实组装器 →
 *    真实组装器.assembleFromCache(pos, cache, a, b, net)——设备模型（加热器
 *    R-L / 电机绕组 / 太阳能源…）装到接线柱端子 a-b，经接线柱接入电路。
 *
 * 核心：不复制任何设备逻辑——代理只是【路由层】；无 facing 设备
 * （BE 接口为 null）→ 断开（enabled=false，不建模——悬空由 GMIN 兜底）。
 *
 * ⚠ 2026-08-30 用户方案确认："按固定方向一格获取 BE 电气设备 → 取其类引用 →
 * 通过 BE 反查组装器 → 持有真实组装器引用给代理"——本类【持有真实组装器
 * 引用】（per-pos 缓存：refreshCache 解析 facing 设备并更新引用，assemble
 * 直接调引用），不再每次按类名查。
 */
public final class ProxyConnectorAssembler implements SourceCacheAssembler {

    public static final ProxyConnectorAssembler INSTANCE = new ProxyConnectorAssembler();

    /** ⚠ 2026-08-30 持有真实组装器引用（pos → 真实组装器；refreshCache 解析
     *  facing 设备更新；设备移除 → 置 null）。后台 assemble 直接调引用。 */
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.core.BlockPos, Assembler>
            REAL_ASSEMBLERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前 pos 持有的真实组装器引用（诊断/测试） */
    public static Assembler realAssemblerAt(net.minecraft.core.BlockPos pos) {
        return REAL_ASSEMBLERS.get(pos);
    }

    /** ⚠ 2026-08-30 清理接线柱注册表条目。简化方案（用户）：设备破坏→接线柱
     *  随删（原版天然）→ 无需外部调用——detectAll（重建时）检测接线柱 BE 在？
     *  getBlockEntity null → remove 覆盖；本方法保留供显式调用（诊断/测试）。 */
    public static void removeConnector(net.minecraft.core.BlockPos pos) {
        if (pos != null) REAL_ASSEMBLERS.remove(pos);
    }

    private ProxyConnectorAssembler() {}

    /** 定位 facing 设备 BE（接线柱朝向的方块实体；无 → null） */
    private static BlockEntity facingTarget(BlockEntity be) {
        try {
            net.minecraft.core.Direction facing = be.getBlockState().getValue(
                    org.patryk3211.powergrid.electricity.deviceconnector.DeviceConnectorBlock.FACING);
            return be.getLevel().getBlockEntity(be.getBlockPos().relative(facing));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 真实组装器（facing 设备 BE 类 → Assemblers 查）。
     *  ⚠ 2026-08-30 用户："代理组装器仅检测可被代理组装器"——只接受
     *  {@link ProxiableAssembler}（加热器/电磁铁/绕组/太阳能/接触器等——原版
     *  IAcceptConnector 设备）；非可被代理设备 → null（不代理/不贴靠）。 */
    private static Assembler realAssembler(BlockEntity target) {
        try {
            Assembler a = Assemblers.get(target); // 按 BE 类简单名查真实组装器
            return a instanceof com.hdf.cryptand.neoforge.powergrid.device
                    .ProxiableAssembler ? a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** ⚠ 2026-08-30 设备接线柱【网络合并路径】（用户："设备接线柱应该有和导线
     *  类似的网络合并路径"）：被代理设备接入前是【孤立网络】（addDevice 只挂
     *  点无边）——接线柱 facing 设备时，把【设备端子 ↔ 接线柱端子】addEdge
     *  （0 电阻对接边）——NetworkGraphStore.addEdge 跨网络 merge（union）→
     *  设备孤立网络并入接线柱网络（像导线接线触发合并）。
     *  触发：设备/接线柱放置（onEntityPlace）+ 重建时（detectAll）。 */
    public static void bridgeDevice(net.minecraft.world.level.Level level,
                                    net.minecraft.world.level.block.entity.BlockEntity connectorBe) {
        try {
            net.minecraft.world.level.block.entity.BlockEntity target =
                    facingTarget(connectorBe);
            if (target == null) return;
            net.minecraft.core.BlockPos cp = connectorBe.getBlockPos();
            net.minecraft.core.BlockPos dp = target.getBlockPos();
            int ct = com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder
                    .declaredTerminalCount(connectorBe);
            int dt = com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder
                    .declaredTerminalCount(target);
            com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager mgr =
                    com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager.get();
            String tempKey = "D" + cp + "<>" + dp; // 对接边温度 key（去重）
            for (int c = 0; c < ct; c++) {
                for (int d = 0; d < dt; d++) {
                    com.hdf.cryptand.circuitsimulation.netgraph.WirePoint pa =
                            new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(
                                    "B" + cp + "#" + c);
                    com.hdf.cryptand.circuitsimulation.netgraph.WirePoint pb =
                            new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint(
                                    "B" + dp + "#" + d);
                    if (!mgr.contains(pa) || !mgr.contains(pb)) continue;
                    // ⚠ 2026-08-30 用户："检测到 BE，如果不是同网络 → 触发网络
                    // 合并"——同网络（已合并）跳过；不同/null（孤立）→ addEdge
                    //（NetworkGraphStore 跨网络 merge / 孤立新建——设备孤立网络
                    //  并入接线柱网络，像导线接线）
                    com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork na =
                            mgr.networkOf(pa);
                    com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork nb =
                            mgr.networkOf(pb);
                    if (na != null && na == nb) continue; // 已同网络 → 无需合并
                    mgr.addEdge(new com.hdf.cryptand.circuitsimulation.netgraph.WireEdge(
                            pa, pb, 1e-4, tempKey));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 主线程每 tick：登记 pos + 转发真实组装器（设备参数由其维护，不复制）。
     *  ⚠ 2026-08-30 用户方案：facing 检测【只在重建时统一检测】（detectAll）——
     *  本方法不做 facing 检测（省开销）；只：① 登记 pos（注册表供 detectAll
     *  遍历）；② 有 facing 设备 → 真实组装器.refreshCache(设备, cache)——设备
     *  参数写输入槽【由真实组装器维护，代理不复制】；无设备 → 引用置 null
     *  （detectAll 会清理，assembleFromCache 判 null 不建模）。 */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            BlockEntity target = facingTarget(be);
            Assembler real = target == null ? null : realAssembler(target);
            if (real == null || !(real instanceof SourceCacheAssembler sca)) {
                REAL_ASSEMBLERS.put(be.getBlockPos(), null); // 无设备 → 失效
                return;
            }
            REAL_ASSEMBLERS.put(be.getBlockPos(), real);      // 持有引用
            sca.refreshCache(target, cache);                  // 参数由真实组装器维护
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【事件驱动·单点检测】（2026-09-11 用户：接线柱存在性检测全部改事件驱动——
     * 放置线缆/电气设备后才检测相关网络，废除定时全表扫描）：
     *   ① 该位置不是接线柱 → 清理可能残留的注册条目；
     *   ② 是接线柱但 facing 设备已不存在 → 引用置 null（失效，不建模）；
     *   ③ 是接线柱且设备存在 → 更新真实组装器引用 + {@link #bridgeDevice}
     *     （同网络跳过；不同/孤立网络 → addEdge 合并）。
     * 主线程调用（读 BE / 方块状态）。
     */
    public static void detectAt(net.minecraft.world.level.Level level,
                                net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) return;
        try {
            net.minecraft.world.level.block.entity.BlockEntity cbe = level.getBlockEntity(pos);
            if (!(cbe instanceof org.patryk3211.powergrid.electricity.deviceconnector
                    .DeviceConnectorBlockEntity)) {
                if (REAL_ASSEMBLERS.containsKey(pos)) REAL_ASSEMBLERS.remove(pos);
                return;
            }
            net.minecraft.world.level.block.entity.BlockEntity target = facingTarget(cbe);
            if (target == null) {
                REAL_ASSEMBLERS.put(pos, null); // 设备没了 → 失效（assemble 判 null 不建模）
                return;
            }
            REAL_ASSEMBLERS.put(pos, realAssembler(target)); // 更新引用（参数由真实组装器维护）
            bridgeDevice(level, cbe);                        // 同网络跳过 / 不同网络合并
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【事件驱动·位置相关检测】（放置/移除方块后调用）：
     *   ① pos 本身（若是接线柱）；
     *   ② pos 的 6 邻中【facing 指向 pos】的接线柱（设备放在接线柱前方 / 设备被移除）。
     * 覆盖语义：设备放置 → 接线柱检测到 BE → 非同网络则合并；同网络则仅刷新引用
     * （网络重建由拓扑版本/缓存校验按需触发）。
     */
    public static void detectNear(net.minecraft.world.level.Level level,
                                  net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) return;
        detectAt(level, pos);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            try {
                net.minecraft.core.BlockPos np = pos.relative(dir);
                net.minecraft.world.level.block.entity.BlockEntity nb = level.getBlockEntity(np);
                if (!(nb instanceof org.patryk3211.powergrid.electricity.deviceconnector
                        .DeviceConnectorBlockEntity)) continue;
                net.minecraft.core.Direction f = nb.getBlockState().getValue(
                        org.patryk3211.powergrid.electricity.deviceconnector
                                .DeviceConnectorBlock.FACING);
                if (np.relative(f).equals(pos)) detectAt(level, np);
            } catch (Throwable ignored) {
            }
        }
    }
    /** ⚠ 2026-08-30 重建时统一检测（用户方案：只在重建时对全部代理器检测）：
     *  主线程遍历代理器注册表 → ① 接线柱 BE 是否还在（level.getBlockEntity）——
     *  不在 → 移除注册表（随设备一起删的接线柱）；② 在 → 接线柱 BE 检测
     *  facing 被代理 BE（设备）是否存在 → 存在 → enabled=true（活跃）；
     *  不存在 → enabled=false（失效，不建模）。结果写输入槽——后台组装读。 */
    public static void detectAll(net.minecraft.world.level.Level level) {
        if (level == null) return;
        try {
            for (java.util.Map.Entry<net.minecraft.core.BlockPos, Assembler> e
                    : REAL_ASSEMBLERS.entrySet()) {
                net.minecraft.core.BlockPos pos = e.getKey();
                try {
                    // ① 接线柱 BE 存在性（随设备破坏的接线柱——没了即清理）
                    net.minecraft.world.level.block.entity.BlockEntity cbe =
                            level.getBlockEntity(pos);
                    if (cbe == null) {
                        REAL_ASSEMBLERS.remove(pos, e.getValue());
                        continue;
                    }
                    // ② 接线柱检测 facing 被代理 BE（设备）存在性——只更新引用
                    // （参数不复制：输入槽由真实组装器.refreshCache 维护——代理
                    // 不碰 DeviceCache.Data；引用 null = 失效 → assembleFromCache
                    // 判 null 不建模）
                    net.minecraft.world.level.block.entity.BlockEntity target =
                            facingTarget(cbe);
                    if (target == null) {
                        REAL_ASSEMBLERS.put(pos, null); // 设备没了 → 失效
                    } else {
                        REAL_ASSEMBLERS.put(pos, realAssembler(target)); // 更新引用
                        // ⚠ 2026-08-30 重建时确保网络合并（用户：设备接线柱
                        // 检测到 BE → 非同网络 → 合并）
                        bridgeDevice(level, cbe);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 后台：直接访问【持有的真实组装器引用】的具体类（用户：参数不复制——
     *  直接调引用方法；输入槽参数由真实组装器维护）。引用 null（无设备/失效）
     *  → 不建模；否则直接调真实组装器.assembleFromCache——设备模型装到接线柱
     *  端子 a-b（经接线柱接入电路）。 */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            Assembler real = REAL_ASSEMBLERS.get(pos); // 持有的真实组装器引用
            if (real instanceof SourceCacheAssembler sca) {
                return sca.assembleFromCache(pos, cache, a, b, net); // 直接调
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
