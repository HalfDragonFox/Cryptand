package com.hdf.cryptand.neoforge.cee;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.cee.config.ConfigCee;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import com.hdf.cryptand.neoforge.railway.catenary.CatenaryHolderBlock;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;

/**
 * CEE 铁路电气化内容的【自管电网接线端子支持】（2026-08-22 "CEE 全自管电路"）。
 * <p>
 * 让 cryptand:cee_catenary_holder / cee_pantograph 成为 Cryptand 自管
 * WireNetwork 的【可接线端子】（像 PowerGrid 方块那样能插 Cryptand 导线物品）：
 *   - 端子身份：isCeeTerminal / terminalCount（两端子方块均为单端子，term=0）
 *   - 精确端子位置：terminalPos（接触网 = 顶部绝缘子端 / 受电弓 = 底座端子）
 *   - 受电弓滑触头几何：closestPointOnWire / checkCatenary（静态，供触点搜索复用）
 * <p>
 * 纯 MC 适配层（主线程/渲染可读 level）；不承接任何计算。
 */
public final class CeeTerminalSupport {

    /** 接触网导线段渲染/识别 id（SaggingWireRegistry 注册，见 RailwayWireType） */
    public static final String CATENARY_RENDERER_ID = "catenary";

    /** CEE mod id（ModList.isLoaded 用；分离性） */
    public static final String CEE_MOD_ID = "electroenergetics";

    /** CEE 电气设备方块接口（精确识别；反射，无硬依赖） */
    private static final String DEVICE_BLOCK_IFACE =
            "com.george_vi.electroenergetics.devices.device.DeviceBlock";

    /** 分离性：CEE 支持开关开启 && 仿真核心启用（CEE 端子接入自管电网的前提） */
    public static boolean ceeEnabled() {
        try {
            // ⚠ 构造期 spec 未加载 → 预读配置文件（与运行时判定一致）
            final boolean ceeOn = ConfigCee.SPEC.isLoaded()
                    ? ConfigCee.ENABLE_CEE_SUPPORT.get()
                    : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("cee", "enableCeeSupport", true);
            if (!ceeOn) return false;
            if (ConfigCircuit.SPEC.isLoaded()) {
                return ConfigCircuit.ENABLE_CRYPTAND_SIMULATION.get()
                        || ConfigCircuit.ENABLE_CRYPTAND_SOLVER.get();
            }
            return com.hdf.cryptand.neoforge.core.config.ConfigLoad
                    .preloadBoolean("circuit", "enableCryptandSimulation", true)
                    || com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("circuit", "enableCryptandSolver", true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 分离性：CEE mod 是否已加载（未加载 → CEE 外部端子识别关闭） */
    public static boolean ceeModLoaded() {
        try {
            return net.neoforged.fml.ModList.get().isLoaded(CEE_MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private CeeTerminalSupport() {
    }

    /** 是否是 CEE 电气端子方块（接触网悬挂块 / 受电弓） */
    public static boolean isCeeTerminal(BlockState state) {
        if (state == null) return false;
        return state.getBlock() instanceof CatenaryHolderBlock
                || state.getBlock() instanceof PantographBlock;
    }

    public static boolean isCeeTerminal(BlockGetter level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (!ceeEnabled()) return false;             // 分离：CEE 支持/核心关闭 → 全关
        if (isCeeTerminal(level.getBlockState(pos))) return true;
        // M4/CEE 外部方块：仅 CEE mod 已加载时识别（精确 DeviceBlock 接口反射）
        return ceeModLoaded() && isExternalCeeTerminal(level, pos);
    }

    // ==================== M4：CEE 外部端子反射通用接入 ====================

    /** CEE 外部方块包前缀（反射识别，无硬依赖——CEE 未装则恒 false） */
    private static final String CEE_PKG = "com.george_vi.electroenergetics";

    /** CEE 电气设备接口（精确识别；ElectricalDeviceBlock extends DeviceBlock） */
    private static final String ELECTRICAL_DEVICE_BLOCK_IFACE =
            "com.george_vi.electroenergetics.foundation.device.ElectricalDeviceBlock";

    /** 反射【精确】：块是否 CEE 电气设备方块（实现 ElectricalDeviceBlock/DeviceBlock
     *  接口——含父类/父接口链）。
     *  ⚠ 2026-08-22 修复：CEE 方块继承 SimpleElectricalDeviceBlock（父类实现接口），
     *  直接 getInterfaces() 扫不到 → 必须向上扫父类链；且识别【接口】而非包前缀。 */
    public static boolean isCeeExternalBlock(net.minecraft.world.level.block.Block b) {
        if (b == null) return false;
        try {
            if (implementsInterface(b.getClass(), DEVICE_BLOCK_IFACE)) return true;
            if (implementsInterface(b.getClass(), ELECTRICAL_DEVICE_BLOCK_IFACE)) return true;
            // 兜底：getDevice() 返回类型落在 CEE devices 包（DeviceBlock 契约）
            java.lang.reflect.Method m = b.getClass().getMethod("getDevice");
            String rt = m.getReturnType().getName();
            return rt.startsWith(CEE_PKG + ".devices.");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 类/父类/接口链是否实现指定接口（全限定名匹配；向上扫父类 + 父接口） */
    private static boolean implementsInterface(Class<?> type, String ifaceName) {
        java.util.ArrayDeque<Class<?>> stack = new java.util.ArrayDeque<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> it : c.getInterfaces()) stack.push(it);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!stack.isEmpty()) {
            Class<?> it = stack.pop();
            if (it == null || !seen.add(it.getName())) continue;
            if (ifaceName.equals(it.getName())) return true;
            for (Class<?> sup : it.getInterfaces()) stack.push(sup);
        }
        return false;
    }

    /** 反射：CEE 外部方块是否电气端子（getDevice() 返回非 null）
     *  ⚠ 2026-08-22 前置 CEE mod 加载检查（未加载快速返回 false） */
    public static boolean isExternalCeeTerminal(BlockGetter level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (!ceeModLoaded()) return false;   // 分离：CEE 未加载 → 关闭对应识别
        try {
            BlockState st = level.getBlockState(pos);
            net.minecraft.world.level.block.Block b = st.getBlock();
            if (!isCeeExternalBlock(b)) return false;
            java.lang.reflect.Method getDevice = b.getClass().getMethod("getDevice");
            Object device = getDevice.invoke(b);
            return device != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 反射：CEE 外部端子数（getNodePositions().size()），失败回退 1 */
    public static int externalTerminalCount(Level level, BlockPos pos, BlockState state) {
        try {
            if (state == null || state.getBlock() == null) return 1;
            java.lang.reflect.Method m = state.getBlock().getClass()
                    .getMethod("getNodePositions", Level.class, BlockPos.class, BlockState.class);
            Object map = m.invoke(state.getBlock(), level, pos, state);
            if (map instanceof java.util.Map<?, ?> mm && !mm.isEmpty())
                return mm.size();
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /** 反射：CEE 外部端子局部坐标（getNodePosition(level,pos,state,term)），失败回退中心 */
    public static Vec3 externalTerminalPos(Level level, BlockPos pos, BlockState state, int term) {
        try {
            if (state == null || state.getBlock() == null) return null;
            java.lang.reflect.Method m = state.getBlock().getClass()
                    .getMethod("getNodePosition", Level.class, BlockPos.class,
                            BlockState.class, int.class);
            Object v = m.invoke(state.getBlock(), level, pos, state, term);
            if (v instanceof Vec3 vec) return new Vec3(pos.getX() + vec.x, pos.getY() + vec.y, pos.getZ() + vec.z);
        } catch (Throwable ignored) {
        }
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    // ==================== 端子数 / 位置（含外部） ====================

    /** 端子数（两种方块均 1 端子；CEE 外部反射端子数） */
    public static int terminalCount(BlockState state) {
        return 1;
    }

    /** 端子局部坐标（方块内 0..1） */
    public static Vec3 terminalPos(BlockState state, BlockPos pos) {
        if (state != null && state.getBlock() instanceof CatenaryHolderBlock) {
            boolean low = state.getValue(CatenaryHolderBlock.STYLE).isLow();
            return new Vec3(0.5, (low ? 0.5 : 22.0) / 16.0, 0.5);
        }
        if (state != null && state.getBlock() instanceof PantographBlock) {
            return new Vec3(0.5, 0.375, 0.5); // 受电弓底座端子
        }
        return new Vec3(0.5, 0.5, 0.5);
    }

    /** 端子世界坐标（渲染/接线端点位置解析用；无 term → 节点 0）。
     *  ⚠ 2026-08-23 物理化：装置内端子经 CeePoseUtil.toWorld 变换真实世界坐标。 */
    public static Vec3 terminalPosWorld(Level level, BlockPos pos) {
        return terminalPosWorld(level, pos, 0);
    }

    /** 端子世界坐标（2026-08-23 修复"直接连到中央"：CEE 节点 id 未必从 0 起，
     *  必须按【排序后索引 term → 真实 node id】反射 getNodePosition；失败回退中心） */
    public static Vec3 terminalPosWorld(Level level, BlockPos pos, int term) {
        if (level == null || pos == null) return null;
        Vec3 out = null;
        BlockState st = level.getBlockState(pos);
        if (isCeeExternalBlock(st.getBlock())) {
            try {
                java.util.List<Integer> ids = ceeNodeIds(level, pos, st);
                if (ids != null && !ids.isEmpty() && term >= 0 && term < ids.size()) {
                    Vec3 v = externalTerminalPosFor(level, pos, st, ids.get(term));
                    if (v != null) out = v;
                }
                if (out == null)
                    out = externalTerminalPos(level, pos, st, 0);
            } catch (Throwable ignored) {
            }
            if (out == null)
                out = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        } else {
            Vec3 local = terminalPos(st, pos);
            out = new Vec3(pos.getX() + local.x, pos.getY() + local.y, pos.getZ() + local.z);
        }
        return CeePoseUtil.toWorld(level, pos, out);
    }

    /** 按点击世界坐标选择最近 CEE 节点（返回排序索引；失败 0） */
    public static int nearestNode(Level level, BlockPos pos, BlockState state, Vec3 clickWorld) {
        try {
            java.util.List<Integer> ids = ceeNodeIds(level, pos, state);
            if (ids == null || ids.isEmpty()) return 0;
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < ids.size(); i++) {
                Vec3 wp = externalTerminalPosFor(level, pos, state, ids.get(i));
                if (wp == null) continue;
                double d = clickWorld == null ? 0 : wp.distanceToSqr(clickWorld);
                if (d < bestD) { bestD = d; best = i; }
            }
            return best;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 反射：CEE 节点 id 列表（getNodePositions keySet，排序保证 term 稳定） */
    @SuppressWarnings("unchecked")
    private static java.util.List<Integer> ceeNodeIds(Level level, BlockPos pos, BlockState state) {
        try {
            java.lang.reflect.Method m = state.getBlock().getClass()
                    .getMethod("getNodePositions", Level.class, BlockPos.class, BlockState.class);
            Object map = m.invoke(state.getBlock(), level, pos, state);
            if (map instanceof java.util.Map<?, ?> mm) {
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                for (Object k : mm.keySet()) if (k instanceof Number n) ids.add(n.intValue());
                java.util.Collections.sort(ids);
                return ids;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反射：指定 node id 的世界坐标（getNodePosition(level,pos,state,id)） */
    private static Vec3 externalTerminalPosFor(Level level, BlockPos pos, BlockState state, int id) {
        try {
            java.lang.reflect.Method m = state.getBlock().getClass()
                    .getMethod("getNodePosition", Level.class, BlockPos.class,
                            BlockState.class, int.class);
            Object v = m.invoke(state.getBlock(), level, pos, state, id);
            if (v instanceof Vec3 vec) {
                return new Vec3(pos.getX() + vec.x, pos.getY() + vec.y, pos.getZ() + vec.z);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** CEE 端子 → 自管端点键（"Bpos#0"，与 Cryptand 图/TerminalRegistry 一致） */
    public static WirePoint toPoint(BlockPos pos) {
        return new WirePoint("B" + pos + "#0");
    }

    /** 是否接触网段（渲染/滑触头只作用于无下垂的接触网导线）。
     *  ⚠ 2026-08-23 放宽：catenary 渲染器 id 或【注册器线型 sag==0】（CEE 原版
     *  接触网线/铁轨/母线等无下垂线也可贴合，CEE 原版语义 getSag()!=0→跳过）。 */
    public static boolean isCatenaryEdge(WireEdge e) {
        if (e == null) return false;
        if (CATENARY_RENDERER_ID.equals(e.rendererId)) return true;
        try {
            SaggingWireType wt =
                    SaggingWireRegistry
                            .byRendererId(e.rendererId);
            return wt != null && wt.sag() <= 0.001f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 接触网边一端端子方块位置（解析 "Bpos#t" 键；失败 null） */
    public static BlockPos holderPosOf(WirePoint p) {
        if (p == null || p.key == null || !p.key.startsWith("B")) return null;
        try {
            int hash = p.key.indexOf('#');
            if (hash < 0) return null;
            int[] xyz = WireKeyUtil.xyzOf(p.key);
            if (xyz == null) return null;
            return new BlockPos(xyz[0], xyz[1], xyz[2]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 线段上距 checker 最近点的参数 t∈[0,1]（纯数学；CEE 语义） */
    public static float closestPointOnWire(Vec3 start, Vec3 end, Vec3 checker) {
        double t = 0;
        Vec3 ab = end.subtract(start);
        Vec3 ap = checker.subtract(start);
        double denom = ab.lengthSqr();
        if (denom != 0)
            t = ap.dot(ab) / denom;
        if (t < -0.01)
            return 0;
        if (t > 1.01)
            return 1;
        return (float) t;
    }

    /**
     * 判断受电弓滑触头是否在接触网导线的触达范围内（CEE checkCatenary 语义，
     * 静态化：facingYRot = 受电弓 FACING.toYRot()）。命中返回最近接触点，
     * 否则 null。
     */
    public static Vec3 checkCatenary(Vec3 start, Vec3 end, Vec3 pantographPos,
                                     float closestT, float halfPantoReach, float facingYRot) {
        Vec3 closest = start.lerp(end, closestT);
        Vec3 distance = pantographPos.subtract(closest);
        if (distance.y > 0.8)
            return null;
        distance = distance.yRot((float) ((facingYRot + 90) * java.lang.Math.PI / 180));
        float xTol = (float) ((-distance.y + halfPantoReach) * 0.2f + 0.125f);
        if (Math.abs(distance.z) < 1.5 && Math.abs(distance.x) < xTol && Math.abs(distance.y) < halfPantoReach)
            return closest;
        return null;
    }

    /** 受电弓朝向的 yaw（度）；未知时 0 */
    public static float facingYRotOf(BlockState state) {
        if (state != null && state.hasProperty(FACING))
            return state.getValue(FACING).toYRot();
        return 0;
    }

    /** 接触网按键产出（无）→ 剪线不返回；此方块无法用剪线返回物品 */
    /** 是否 CEE 原版接触网悬挂块（反射：External 且类名 CatenaryHolderBlock） */
    public static boolean isCeeExternalHolder(BlockState state) {
        if (state == null || !isCeeExternalBlock(state.getBlock())) return false;
        String cn = state.getBlock().getClass().getName();
        return cn.contains(".railway_electrification.catenary.CatenaryHolderBlock");
    }

    /** 是否接触网悬挂块（自家 cryptand:cee_catenary_holder 或 CEE 原版） */
    public static boolean isCeeHolder(BlockState state) {
        return (state != null && state.getBlock() instanceof CatenaryHolderBlock)
                || isCeeExternalHolder(state);
    }

    /** 是否接触网悬挂块（方块） */
    public static boolean isCeeHolder(BlockGetter level, BlockPos pos) {
        if (level == null || pos == null) return false;
        return isCeeHolder(level.getBlockState(pos));
    }
}