/**
 * ===== 接线端子注册表（2026-08-22 用户架构统一入口） =====
 *
 * 所有【接线端子】（导线连接端口）统一经本注册表识别/建模：
 *   - 按 BE 类简单名注册 {@link WireTerminalAssembler}（空元件组装器）
 *   - 统一判定：isTerminal / terminalCount / terminalOffset / terminalPosWorld
 *   - 覆盖：CEE 自家（接触网悬挂块 / 受电弓底座）、CEE 外部端子（反射）、
 *     PowerGrid / 任意 IElectric 方块（默认支持）
 *
 * 新增一种端子 = 实现 WireTerminalAssembler + 注册（自带 CEE 两例示范）；
 * 核心路径（CryptandWirePlacement 放置 / RailwayPlacementHook 建网 / PhasorNetworkBuilder
 * 段边界）只认本注册表，不再 per-mod 专门分支。
 */
package com.hdf.cryptand.neoforge.powergrid.device.terminal;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.neoforge.cee.CeePoseUtil;
import com.hdf.cryptand.neoforge.cee.CeeTerminalSupport;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.device.Assemblers;
import com.hdf.cryptand.neoforge.railway.catenary.CatenaryHolderBlock;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WireTerminals {

    private static final Map<String, WireTerminalAssembler> BY_CLASS = new ConcurrentHashMap<>();

    /** 注册接线端子组装器（按 BE 类简单名，如 "CatenaryHolderBlockEntity"） */
    public static void register(String simpleName, WireTerminalAssembler t) {
        if (simpleName != null && t != null) BY_CLASS.put(simpleName, t);
    }

    public static WireTerminalAssembler byClass(String simpleName) {
        return simpleName == null ? null : BY_CLASS.get(simpleName);
    }

    public static WireTerminalAssembler byBe(BlockEntity be) {
        if (be == null) return null;
        // 2026-09-13：自有 BE 子类（XxxBE extends 原版）沿继承链回退，
        // 否则端子识别 MISS → 设备端子丢失
        for (String n : com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                .simpleNames(be.getClass())) {
            WireTerminalAssembler t = BY_CLASS.get(n);
            if (t != null) return t;
        }
        return null;
    }

    // ==================== CEE 自家接线端子（空元件组装器） ====================

    /** 接触网悬挂块：1 端子（顶部绝缘子端），空元件 */
    public static final WireTerminalAssembler CATENARY_HOLDER_TERMINAL =
            new WireTerminalAssembler() {
                @Override
                public boolean isTerminal(BlockGetter level, BlockPos pos, BlockState state) {
                    return state != null && state.getBlock() instanceof CatenaryHolderBlock;
                }

                @Override
                public Vec3 terminalOffset(BlockGetter level, BlockPos pos, BlockState state, int term) {
                    boolean low = state != null
                            && state.getBlock() instanceof CatenaryHolderBlock holder
                            && state.getValue(CatenaryHolderBlock.STYLE).isLow();
                    return new Vec3(0.5, (low ? 0.5 : 22.0) / 16.0, 0.5);
                }

                @Override
                public int terminalCount() {
                    return 1;
                }
            };

    /** 受电弓底座端子：1 端子（底座中心偏下），空元件（受电弓建模另行组装） */
    public static final WireTerminalAssembler PANTOGRAPH_TERMINAL =
            new WireTerminalAssembler() {
                @Override
                public boolean isTerminal(BlockGetter level, BlockPos pos, BlockState state) {
                    return state != null && state.getBlock() instanceof PantographBlock;
                }

                @Override
                public Vec3 terminalOffset(BlockGetter level, BlockPos pos, BlockState state, int term) {
                    return new Vec3(0.5, 0.375, 0.5);
                }

                @Override
                public int terminalCount() {
                    return 1;
                }
            };

    static {
        register("CatenaryHolderBlockEntity", CATENARY_HOLDER_TERMINAL);
        register("PantographBlockEntity", PANTOGRAPH_TERMINAL);
        // 接线端子 = 空元件组装器，正常进入电路网络（Assemblers 统一分发表）：
        // 设备建模循环 assemble→null → 计算自然忽略，仅保留连接节点/段边界；
        // 端子方块自身渲染/绑定由各自 BE（渲染器）处理。
        Assemblers.register("CatenaryHolderBlockEntity", CATENARY_HOLDER_TERMINAL);
        Assemblers.register("PantographBlockEntity", PANTOGRAPH_TERMINAL);
    }

    // ==================== 分离性门控（2026-08-22） ====================
    // 用户要求：两个 mod 的端子识别互相独立；mod 未加载 / 关闭仿真核心时
    // 关闭【对应】识别，不互相干扰：
    //   - CEE     端子（自家 holder/pantograph + CEE 外部方块）← ceeActive()
    //   - PowerGrid 端子（IElectric 设备）            ← powergridActive()
    //   - 通用组装器（第三方注册端子）                 ← coreActive()

    /** 仿真核心总闸（求解/模拟任一开启；否则自管电网不运行） */
    public static boolean coreActive() {
        try {
            return ConfigCircuit.ENABLE_CRYPTAND_SIMULATION.get()
                    || ConfigCircuit.ENABLE_CRYPTAND_SOLVER.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** PowerGrid 端子识别启用：PowerGrid 支持开关 && 仿真核心 && PowerGrid mod 已加载 */
    public static boolean powergridActive() {
        try {
            if (!ConfigPowerGrid.ENABLE_POWERGRID_SUPPORT.get())
                return false;
            if (!coreActive()) return false;
            return net.neoforged.fml.ModList.get().isLoaded("powergrid");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** CEE 端子识别启用：CEE 支持开关 && 仿真核心（自家方块不要求 CEE mod 加载；
     *  CEE 外部方块在 CeeTerminalSupport 内部另查 ceeModLoaded） */
    public static boolean ceeActive() {
        return CeeTerminalSupport.ceeEnabled();
    }

    // ==================== 统一判定 ====================

    /** 是否任意接线端子（注册表 CEE 自家 / CEE 外部精确反射 / PowerGrid IElectric） */
    public static boolean isTerminal(BlockGetter level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) return false;
        if (ceeActive()) {
            if (CATENARY_HOLDER_TERMINAL.isTerminal(level, pos, state)
                    || PANTOGRAPH_TERMINAL.isTerminal(level, pos, state))
                return true;
            if (CeeTerminalSupport.isExternalCeeTerminal(level, pos))
                return true;
        }
        if (powergridActive() && level instanceof Level lv && IElectric.getAt(lv, pos) != null)
            return true;
        if (coreActive() && level instanceof Level lv) {
            WireTerminalAssembler byBe = byBe(lv.getBlockEntity(pos));
            if (byBe != null && byBe.isTerminal(level, pos, state))
                return true;
        }
        return false;
    }

    /** 端子数（统一；PowerGrid 走声明式端子数） */
    public static int terminalCount(Level level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) return 1;
        if (ceeActive() && (CATENARY_HOLDER_TERMINAL.isTerminal(level, pos, state)
                || PANTOGRAPH_TERMINAL.isTerminal(level, pos, state)))
            return 1;
        if (ceeActive() && CeeTerminalSupport.isExternalCeeTerminal(level, pos))
            return Math.max(1, CeeTerminalSupport.externalTerminalCount(level, pos, state));
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            if (coreActive()) {
                WireTerminalAssembler byBe = byBe(be);
                if (byBe != null) return Math.max(1, byBe.terminalCount());
            }
            if (powergridActive())
                return Math.max(1, PhasorNetworkBuilder.declaredTerminalCount(be));
        }
        return 1;
    }

    /** 端子相对方块位置（0..1；世界 = pos + offset） */
    public static Vec3 terminalOffset(Level level, BlockPos pos, BlockState state, int term) {
        if (level != null && pos != null && state != null) {
            if (ceeActive() && CATENARY_HOLDER_TERMINAL.isTerminal(level, pos, state))
                return CATENARY_HOLDER_TERMINAL.terminalOffset(level, pos, state, term);
            if (ceeActive() && PANTOGRAPH_TERMINAL.isTerminal(level, pos, state))
                return PANTOGRAPH_TERMINAL.terminalOffset(level, pos, state, term);
            if (coreActive()) {
                BlockEntity be = level.getBlockEntity(pos);
                WireTerminalAssembler byBe = byBe(be);
                if (byBe != null)
                    return byBe.terminalOffset(level, pos, state, term);
            }
        }
        return new Vec3(0.5, 0.5, 0.5);
    }

    /** 端子世界坐标（统一精确位置：CEE 自家/外部 → CeeTerminalSupport；PowerGrid → 精确端点）。
     *  ⚠ 2026-08-23 航空学物理化：所有端子局部世界坐标经 CeePoseUtil.toWorld
     *  （Sable logicalPose）变换为真实世界坐标 → 装置移动/物理化后网络/渲染/放置正确。 */
    public static Vec3 terminalPosWorld(Level level, BlockPos pos, BlockState state, int term) {
        Vec3 out = terminalLocalWorld(level, pos, state, term);
        if (out == null) return null;
        return CeePoseUtil.toWorld(level, pos, out);
    }

    /** 端子【局部世界坐标】（未投影；物理化亚层内 = 亚层坐标。
     *  2026-08-23 渲染跟随：客户端每帧用 CeePoseUtil.toWorldInterp（last/logical
     *  pose + partialTicks）插值投影——与物理化结构方块渲染同步，不跳变）。 */
    public static Vec3 terminalLocalWorld(Level level, BlockPos pos, BlockState state, int term) {
        if (level == null || pos == null) return null;
        Vec3 out = null;
        if (ceeActive() && (CATENARY_HOLDER_TERMINAL.isTerminal(level, pos, state)
                || PANTOGRAPH_TERMINAL.isTerminal(level, pos, state)
                || CeeTerminalSupport.isCeeExternalBlock(state.getBlock()))) {
            out = CeeTerminalSupport.terminalPosWorld(level, pos, term);
        } else {
            try {
                Vec3 ep = new BlockWireEndpoint(pos, term).getExactPosition(level);
                if (ep != null) out = ep;
            } catch (Throwable ignored) {
            }
            if (out == null) {
                Vec3 off = terminalOffset(level, pos, state, term);
                out = new Vec3(pos.getX() + off.x, pos.getY() + off.y, pos.getZ() + off.z);
            }
        }
        return out;
    }

    /**
     * 通用端子元件组装（2026-08-22 用户：接线端子不用空元件，使用端子元件；
     * CEE 多端子设备按声明端子数组装相应数量、每个端子一个独立端子元件）。
     * <p>幂等：Network 已有同 deviceKey+terminalIndex 的端子 → 跳过；缺失端子
     * （未接线/悬空）→ 补建独立引擎节点 + TerminalElement（独立测试点）。
     * <p>后台构建调用（无 BE；pos + count 来自 DeviceParamCache 同步声明）。
     */
    public static void assembleTerminals(BlockPos pos, int count, Integer[] nodeIds,
                                         Network net, boolean registerTerminals) {
        if (pos == null || net == null || count < 1) return;
        try {
            java.util.Set<String> have = new java.util.HashSet<>();
            for (com.hdf.cryptand.circuitsimulation.model.TerminalElement te : net.terminals()) {
                if (("B" + pos).equals(te.deviceKey)) {
                    have.add(Integer.toString(te.terminalIndex));
                }
            }
            Integer[] arr = nodeIds != null ? nodeIds : new Integer[0];
            for (int t = 0; t < count; t++) {
                String key = Integer.toString(t);
                if (have.contains(key)) continue;
                Integer id = t < arr.length ? arr[t] : null;
                if (id == null) id = net.addNode().id;   // 悬空端子：独立引擎节点
                com.hdf.cryptand.circuitsimulation.model.TerminalElement te =
                        new com.hdf.cryptand.circuitsimulation.model.TerminalElement("B" + pos, t, id);
                net.addTerminal(te);
                if (registerTerminals) {
                    TerminalRegistry
                            .register(pos, t, te);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【统一端子索引层】（2026-08-23 用户：统一识别层，便于多 mod 识别）：
     * 点击/准星世界坐标 → 该方块端子索引：
     *   - PowerGrid（IElectric）：按点击位置解析（terminalIndexAt）
     *   - CEE 外部（ElectricalDeviceBlock）：按点击位置选最近节点（nearestNode）
     *   - 注册表端子组装器：0
     * 非端子 / 异常 → -1。所有导线（注册器注册）与预览渲染只认本入口。
     */
    public static int terminalIndexAt(Level level, BlockPos pos, BlockState state, Vec3 worldClick) {
        try {
            if (level == null || pos == null || state == null) return -1;
            if (!isTerminal(level, pos, state)) return -1;
            org.patryk3211.powergrid.electricity.base.IElectric electric =
                    IElectric.getAt(level, pos);
            if (electric != null) {
                return electric.terminalIndexAt(state,
                        worldClick.subtract(pos.getX(), pos.getY(), pos.getZ()));
            }
            if (CeeTerminalSupport.isCeeExternalBlock(state.getBlock())) {
                return CeeTerminalSupport.nearestNode(level, pos, state, worldClick);
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private WireTerminals() {
    }
}