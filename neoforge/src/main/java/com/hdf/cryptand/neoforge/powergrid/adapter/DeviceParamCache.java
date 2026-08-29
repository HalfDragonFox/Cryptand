/**
 * ===== 线程安全设备参数缓存（2026-08-15 完全异步架构基石） =====
 *
 * 用户架构："主线程只接受消息，不需要进行计算" —— 电力计算 100% 在异步线程，
 * 主线程只【同步数据】（=发消息）与【执行世界副作用】（=收消息执行）。
 *
 * 本类 = 主线程 → 异步线程 的【设备参数消息通道】：
 *   - 主线程每 tick 调 {@link #sync}：遍历自管 WireGraph 所有方块点 → 读 BE 参数
 *     → 写入线程安全缓存（ConcurrentHashMap）。这是"发消息"，不是计算。
 *   - 异步求解线程只调 {@link #get} 读缓存，【绝不碰 level/BE】——避免 MC 的
 *     BE 表（主线程 HashMap）并发读写崩溃（2026-08-12 [NullBe] 教训）。
 *
 * 与参数消息总线（ParamChangeSource/paramVersion++）互补：
 *   - 本缓存：主线程把【方块当前值】同步过来（异步构建/刷参的数据源）
 *   - 消息总线：元件 setter 变化 → 参数版本++ → 触发重解（不重建网络）
 *
 * 覆盖设备类型（对应 PhasorNetworkBuilder.addElement 的分发）：
 *   AC 源（电压/电流）、电容、电感、电阻、绕组（线圈）、变压器、可编程组件、其他。
 * 端子映射不走本缓存：由 TerminalRegistry（pos#term → TerminalElement）承担。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.powergrid.creative.AcCreativeSourceBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.creative.CapacitorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.creative.InductorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.resistor.ResistorBlockEntity;

import java.util.concurrent.ConcurrentHashMap;

public final class DeviceParamCache {

    /** 设备类型（对应构建分发） */
    public enum Kind {
        NONE, AC_VOLTAGE_SRC, AC_CURRENT_SRC, CAPACITOR, INDUCTOR,
        RESISTOR, WINDING, TRANSFORMER, PROGRAMMABLE, POWER_GAUGE, OTHER
    }

    /** 设备参数快照（不可变；主线程写新快照替换旧引用） */
    public static final class Entry {
        public final Kind kind;
        /** 电阻（Ω）；非电阻设备 = 0 */
        public final double resistance;
        /** 电容（F）；非电容设备 = 0 */
        public final double capacitance;
        /** 电感（H）；非电感设备 = 0 */
        public final double inductance;
        /** AC 源幅值（V 或 A） */
        public final double amplitude;
        /** AC 源相位（度） */
        public final double phaseDeg;
        /** AC 源频率（Hz）；非 AC 源 = 0 */
        public final double frequencyHz;
        /** 设备类全名（组装器反查用；OTHER 设备必填） */
        public final String deviceClass;
        /** 绕组是否主方块（唯一持 coilWire 者） */
        public final boolean windingMain;
        /** 接地棒是否闭合接地 */
        public final boolean grounded;
        /** 声明端子数（放置建网/设备端子簇扩展用；Transformer=4 等） */
        public final int terminalCount;
        /** 同步版本（每次 sync 该设备 +1；后台据此判断参数是否变化） */
        public final long version;

        Entry(Kind kind, double resistance, double capacitance, double inductance,
              double amplitude, double phaseDeg, double frequencyHz, String deviceClass,
              boolean windingMain, boolean grounded, int terminalCount, long version) {
            this.kind = kind;
            this.resistance = resistance;
            this.capacitance = capacitance;
            this.inductance = inductance;
            this.amplitude = amplitude;
            this.phaseDeg = phaseDeg;
            this.frequencyHz = frequencyHz;
            this.deviceClass = deviceClass;
            this.windingMain = windingMain;
            this.grounded = grounded;
            this.terminalCount = terminalCount;
            this.version = version;
        }
    }

    private static final ConcurrentHashMap<BlockPos, Entry> CACHE = new ConcurrentHashMap<>();

    /** 变压器线圈参数快照（2026-08-15 主线程预读纯值；后台构建用，不碰 BE） */
    public record TransformerParams(boolean pcOk, boolean scOk,
                                    int n1, int n2,
                                    int c1t1, int c1t2, int c2t1, int c2t2,
                                    double coreAl, double couplingFactor) {
    }

    private static final ConcurrentHashMap<BlockPos, TransformerParams> TRANSFORMERS =
            new ConcurrentHashMap<>();

    /** 变压器任意 PART/端子格 pos → 主 BE 位置（2026-08-15 跨 PART 端子合并用；
     *  主线程 sync 填写，后台构建纯缓存读，无 level 依赖） */
    private static final ConcurrentHashMap<BlockPos, BlockPos> TF_MAIN =
            new ConcurrentHashMap<>();

    /** 后台读变压器主 BE 位置（无 = 尚未同步/非变压器） */
    public static BlockPos transformerMainOf(BlockPos pos) {
        return pos == null ? null : TF_MAIN.get(pos);
    }

    /** 后台读变压器线圈参数（无 = 尚未同步/非变压器） */
    public static TransformerParams transformer(BlockPos pos) {
        return pos == null ? null : TRANSFORMERS.get(pos);
    }

    /** 可编程组件固化电路引用（2026-08-15 主线程预存；ResolvedCircuit 纯数据、
     *  引用替换式更新 → 后台只读展开安全） */
    private static final ConcurrentHashMap<BlockPos, com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit>
            PROGRAMMABLE = new ConcurrentHashMap<>();

    /** 后台读可编程组件固化电路（无 = 未配置/未同步） */
    public static com.hdf.cryptand.circuitsimulation.lib.ResolvedCircuit programmableCircuit(BlockPos pos) {
        return pos == null ? null : PROGRAMMABLE.get(pos);
    }

    /** CEE 设备参数快照（2026-08-26 恢复：CeeDeviceSupport#refresh 主线程低频
     *  写入；后台建模只读，无 level 依赖）。 */
    private static final ConcurrentHashMap<BlockPos, com.hdf.cryptand.neoforge.cee
            .CeeDeviceSupport.CeeParams> CEE = new ConcurrentHashMap<>();

    /** 后台读 CEE 设备参数快照（无 = 未同步/非 CEE 设备） */
    public static com.hdf.cryptand.neoforge.cee.CeeDeviceSupport.CeeParams ceeParams(
            BlockPos pos) {
        return pos == null ? null : CEE.get(pos);
    }

    /** 主线程写 CEE 设备参数快照（低频刷新；见 CeeDeviceSupport#refresh） */
    public static void putCee(net.minecraft.core.BlockPos pos,
                              com.hdf.cryptand.neoforge.cee.CeeDeviceSupport.CeeParams cp) {
        if (pos == null) return;
        if (cp == null) CEE.remove(pos);
        else CEE.put(pos, cp);
    }

    private DeviceParamCache() {
    }

    /** 后台线程读设备参数（绝不碰 level）；null = 尚未同步/该位置无电气设备 */
    public static Entry get(BlockPos pos) {
        return pos == null ? null : CACHE.get(pos);
    }

    /** 缓存大小（诊断） */
    public static int size() {
        return CACHE.size();
    }

    /** 全部已登记位置快照（2026-08-20 设备点补全扫描用：无导线孤立设备兜底） */
    public static java.util.List<BlockPos> allPositions() {
        return new java.util.ArrayList<>(CACHE.keySet());
    }

    /** 失效某位置（设备移除/网络重建清理） */
    public static void invalidate(BlockPos pos) {
        if (pos != null) CACHE.remove(pos);
    }

    /**
     * 主线程同步（=发消息）：遍历自管 WireGraph 所有方块点 → 读 BE 参数 → 写缓存。
     * 遍历源 = WireNetworkManager.pointList()（自管图全量点，含所有电气方块端子）；
     * 读不到的（方块已拆/未加载）→ 移除缓存条目（后台构建时该设备自动跳过）。
     */
    public static void sync(Level level) {
        if (level == null) return;
        try {
            java.util.Set<BlockPos> seen = new java.util.HashSet<>();
            for (WirePoint p : WireNetworkManager.get().pointList()) {
                if (!WireKeyUtil.isBlock(p.key)) continue;
                BlockPos pos = PhasorNetworkBuilder.pointPosOfPublic(p.key);
                if (pos == null) continue;
                if (!seen.add(pos)) continue; // 同方块多端子去重
                try {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) {
                        // 2x2 变压器非主方块（PART 2/3 端子所在）无 BE：方块方法
                        // 换算回主方块（1x1 无此情况）。取到则预存变压器参数/标记；
                        // 否则视为非电气方块，移除。
                        org.patryk3211.powergrid.electricity.transformer
                                .TransformerBlockEntity t2 = transformerBeAt(level, pos);
                        if (t2 != null) {
                            try {
                                Entry old2 = CACHE.get(pos);
                                long v2 = (old2 == null) ? 0 : old2.version;
                                Entry e2 = readEntry(t2, v2 + 1);
                                if (e2 != null) CACHE.put(pos, e2);
                                TF_MAIN.put(pos, t2.getBlockPos());
                                var pc = t2.getPrimary();
                                var sc = t2.getSecondary();
                                if (pc != null && sc != null) {
                                    TRANSFORMERS.put(pos, new TransformerParams(
                                            pc.isDefined(), sc.isDefined(),
                                            pc.getTurns(), sc.getTurns(),
                                            pc.getTerminal1(), pc.getTerminal2(),
                                            sc.getTerminal1(), sc.getTerminal2(),
                                            t2.coreAl(), t2.couplingFactor()));
                                } else {
                                    TRANSFORMERS.remove(pos);
                                }
                            } catch (Throwable ignored) {
                            }
                        } else {
                            CACHE.remove(pos);
                            TF_MAIN.remove(pos);
                        }
                        continue;
                    }
                    Entry old = CACHE.get(pos);
                    long v = (old == null) ? 0 : old.version;
                    Entry e = readEntry(be, v + 1);
                    if (e != null) CACHE.put(pos, e);
                    // ⚠ 2026-08-15 修复：主线程【预注册】设备缓存（CacheAssembler）
                    // ——原注册发生在后台线程首轮构建（cacheFor 惰性）→ 首轮读空
                    // 缓存建模缺失（设备开路）；预注册后主线程每 tick refreshCache
                    // 填数据，后台首轮构建即命中。
                    try {
                        com.hdf.cryptand.neoforge.powergrid.device.Assembler asm2 =
                                com.hdf.cryptand.neoforge.powergrid.device.Assemblers.get(be);
                        if (asm2 instanceof com.hdf.cryptand.neoforge.powergrid.device
                                .CacheAssembler ca2) {
                            ca2.cacheFor(pos);
                        }
                    } catch (Throwable ignored) {
                    }
                    // 变压器线圈参数（主线程预读纯值；后台构建用）
                    if (be instanceof org.patryk3211.powergrid.electricity.transformer
                            .TransformerBlockEntity t) {
                        try {
                            TF_MAIN.put(pos, t.getBlockPos());
                            var pc = t.getPrimary();
                            var sc = t.getSecondary();
                            if (pc != null && sc != null) {
                                TRANSFORMERS.put(pos, new TransformerParams(
                                        pc.isDefined(), sc.isDefined(),
                                        pc.getTurns(), sc.getTurns(),
                                        pc.getTerminal1(), pc.getTerminal2(),
                                        sc.getTerminal1(), sc.getTerminal2(),
                                        t.coreAl(), t.couplingFactor()));
                            } else {
                                TRANSFORMERS.remove(pos);
                            }
                        } catch (Throwable ignored) {
                        }
                    } else {
                        TRANSFORMERS.remove(pos);
                        TF_MAIN.remove(pos);
                    }
                    // 可编程组件固化电路（主线程预存引用；后台只读展开）
                    if (be instanceof com.hdf.cryptand.neoforge.powergrid.creative
                            .ProgrammableComponentBlockEntity pb) {
                        try {
                            var c = pb.getResolvedCircuit();
                            if (c != null && !c.elements.isEmpty()) {
                                PROGRAMMABLE.put(pos, c);
                            } else {
                                PROGRAMMABLE.remove(pos);
                            }
                        } catch (Throwable ignored) {
                        }
                    } else {
                        PROGRAMMABLE.remove(pos);
                    }
                    // 虚拟设备快照（2026-08-15 主线程同步：后台 bindAll 不再深读
                    // BE；有源设备 of() 返回 null 自动排除）
                    try {
                        VirtualDevice vd = VirtualDevice.of(be);
                        if (vd != null
                                && (vd.resistance > 0 || vd.inductance > 0)) {
                            VirtualDeviceStore.put(pos, vd);
                        }
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                    // 单点失败不影响整批同步
                }
            }
            // ⚠ 2026-08-20 移除世界级设备点补全（反射遍历 chunkMap.visibleChunkMap）：
            // 设备点现在由【SavedData 设备点持久化】保证（WireSavedData.devices →
            // restoreDevicePoints），世界重载后自动恢复；放置时 EntityPlace addDevice。
            // 无需每 tick 反射遍历全部已加载区块的 BE（性能/耦合差且是补丁式修复）。
        } catch (Throwable ignored) {
        }
    }

    /** 读单个 BE 参数（主线程调用；返回 null = 非电气设备，不缓存） */
    private static Entry readEntry(BlockEntity be, long version) {
        String cls = be == null ? "" : be.getClass().getName();
        int tc = com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder
                .declaredTerminalCount(be);
        if (be instanceof AcCreativeSourceBlockEntity src) {
            return new Entry(src.isVoltageSourceType()
                            ? Kind.AC_VOLTAGE_SRC : Kind.AC_CURRENT_SRC,
                    0, 0, 0, src.getAmplitude(), src.getPhaseDegrees(),
                    src.getFrequencyHz(), cls, false, false, tc, version);
        }
        if (be instanceof CapacitorBlockEntity cap) {
            return new Entry(Kind.CAPACITOR, 0, cap.getCapacitance(), 0, 0, 0,
                    0, cls, false, false, tc, version);
        }
        if (be instanceof InductorBlockEntity ind) {
            return new Entry(Kind.INDUCTOR, 0, 0, ind.getInductance(), 0, 0,
                    0, cls, false, false, tc, version);
        }
        if (be instanceof ResistorBlockEntity) {
            float r = readResistor(be);
            if (r > 0) return new Entry(Kind.RESISTOR, r, 0, 0, 0, 0, 0, cls,
                    false, false, tc, version);
            return null;
        }
        if (be != null && be.getClass().getName().endsWith("PowerGaugeBlockEntity")) {
            // 万用表：series（测流低阻）/shunt（测压高阻）→ 主线程预读
            double rs = DeviceWire.wireResistance(be, "series");
            double rsh = DeviceWire.wireResistance(be, "shunt");
            return new Entry(Kind.POWER_GAUGE, rs, 0, 0, rsh, 0, 0, cls, false,
                    false, tc, version);
        }
        if (be instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
            // 变压器参数由构建时从 TransformerParameters 读（配置驱动），这里只标记类型
            return new Entry(Kind.TRANSFORMER, 0, 0, 0, 0, 0, 0, cls,
                    false, false, tc, version);
        }
        if (be != null && be.getClass().getName().endsWith("WindingBlockEntity")) {
            return new Entry(Kind.WINDING, 0, 0, 0, 0, 0, 0, cls,
                    isWindingMain(be), false, tc, version);
        }
        if (be != null && be.getClass().getName().endsWith("GroundingRodBlockEntity")) {
            // 接地棒闭合态（反射读 wire → SwitchedWire.enabled）
            return new Entry(Kind.OTHER, 0, 0, 0, 0, 0, 0, cls, false,
                    isGroundRodActive(be), tc, version);
        }
        if (be instanceof org.patryk3211.powergrid.kinetics.generator.inductionrotor
                .CommutatorBlockEntity comm) {
            // 换向器频率（主线程预读：反射 source → GeneratorCoupling → |ω|/2π）
            double f = MultimeterDebug.commutatorFrequencyHz(comm);
            return new Entry(Kind.OTHER, 0, 0, 0, 0, 0, f, cls, false,
                    false, tc, version);
        }
        // 可编程组件/其他设备：只标记存在（参数由构建时反射/消息读）
        return new Entry(Kind.OTHER, 0, 0, 0, 0, 0, 0, cls, false, false, tc, version);
    }

    /** 取 pos 处变压器 BE（2x2：BE 只由主方块 PART 0 持有，方块方法换算回主方块；
     *  1x1：返回自身）。非变压器返回 null。
     *  ⚠ 2026-08-15 修复：PART 方块 getBlockEntity 换算失败时（个别子类/结构）
     *  兜底查相邻 8 格中的主 BE（2x2 内 PART 与主格必相邻）。 */
    private static org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity
            transformerBeAt(Level level, BlockPos pos) {
        try {
            if (level == null || pos == null) return null;
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
            net.minecraft.world.level.block.Block blk = st.getBlock();
            if (blk instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlock tb) {
                org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity t =
                        tb.getBlockEntity(level, pos, st).orElse(null);
                if (t != null) return t;
            }
            // 兜底：2x2 结构 PART 换算失败 → 相邻格找主 BE
            int[][] offs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
            for (int[] o : offs) {
                BlockPos c = pos.offset(o[0], 0, o[1]);
                net.minecraft.world.level.block.entity.BlockEntity b = level.getBlockEntity(c);
                if (b instanceof org.patryk3211.powergrid.electricity.transformer
                        .TransformerBlockEntity t2) {
                    return t2;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 变压器主方块位置（2x2 结构任意 PART/端子格 → 主 BE 位置；非变压器 null）。
     *  供构建时跨 PART 端子合并用（端子分散在不同 PART 方块，需归到同一主 pos）。 */
    public static BlockPos transformerMainPos(Level level, BlockPos pos) {
        org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity t =
                transformerBeAt(level, pos);
        return t == null ? null : t.getBlockPos();
    }

    /** 绕组是否主方块（反射 mainBE 字段；null 或 ==be 为主） */
    private static boolean isWindingMain(BlockEntity be) {
        try {
            java.lang.reflect.Field f = be.getClass().getDeclaredField("mainBE");
            f.setAccessible(true);
            Object main = f.get(be);
            return main == null || main == be;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 接地棒是否闭合（反射 wire → SwitchedWire.enabled） */
    private static boolean isGroundRodActive(BlockEntity be) {
        try {
            Object wire = DeviceWire.field(be, "wire");
            if (wire instanceof org.patryk3211.powergrid.electricity.sim.SwitchedWire sw) {
                return sw.getState();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 电阻值（反射读 protected value 字段 → getResistance；兜底读内部 wire） */
    private static float readResistor(BlockEntity be) {
        // ===== 2026-08-16 诊断（定位"电阻电路电流过低"：建模值 10MΩ 异常） =====
        try {
            long now = System.currentTimeMillis();
            if (now - RES_DBG_LAST >= 5000) {
                RES_DBG_LAST = now;
                Object v = null;
                try {
                    java.lang.reflect.Field f = ResistorBlockEntity.class.getDeclaredField("value");
                    f.setAccessible(true);
                    v = f.get(be);
                } catch (Throwable ignored) {
                }
                Object wire = DeviceWire.field(be, "wire");
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[ResDbg] pos={} valueCls={} valueRes={} wireCls={} wireRes={}",
                        be.getBlockPos(),
                        v == null ? "null" : v.getClass().getSimpleName(),
                        (v instanceof org.patryk3211.powergrid.electricity.resistor
                                .ResistorValueBehaviour rvb) ? String.format("%.4g", rvb.getResistance()) : "?",
                        wire == null ? "null" : wire.getClass().getSimpleName(),
                        (wire instanceof org.patryk3211.powergrid.electricity.sim.ElectricWire ew)
                                ? String.format("%.4g", ew.getResistance()) : "?");
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field f = ResistorBlockEntity.class.getDeclaredField("value");
            f.setAccessible(true);
            Object v = f.get(be);
            if (v instanceof org.patryk3211.powergrid.electricity.resistor.ResistorValueBehaviour rvb) {
                float r = rvb.getResistance();
                if (r > 0) return r;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object wire = DeviceWire.field(be, "wire");
            if (wire instanceof org.patryk3211.powergrid.electricity.sim.ElectricWire ew) {
                double r = ew.getResistance();
                if (Double.isFinite(r) && r > 0) return (float) r;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 电阻读取诊断节流（2026-08-16） */
    private static volatile long RES_DBG_LAST;
}
