/**
 * ===== CEE 全电气设备接管（2026-08-22 用户"所有电气设备接管/参考交错电网实现"） =====
 *
 * 参照 PowerGrid 接管链（DeviceParamCache 主线程同步 + 后台纯缓存建模）：
 *   - 主线程 {@link #refresh(ServerLevel)}：反射 CEE DevicesSavedData（DEVICES_BY_POS
 *     全部设备实例）→ 参数快照 {@link CeeParams} 缓存（电压/电阻/开关/电荷/类型 id/
 *     端子数）。CEE 设备是【Block 无 BE】，不能走 readEntry(BE)——独立缓存路径。
 *   - 后台 PhasorNetworkBuilder：读 {@link DeviceParamCache#ceeParams} → {@link #build}
 *     按 CEE 设备类型 id 分派映射为我们的元件（Resistor / Dc&AcVoltageSource /
 *     DiodeElement / 开关 / 1:1 互感 / 接线盒空等）。
 * 纯反射，无 CEE 编译期硬引用（CEE 未装 → 静默跳过）。
 */
package com.hdf.cryptand.neoforge.cee;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CeeDeviceSupport {

    /** CEE 设备参数快照（主线程反射填充；不可变） */
    public record CeeParams(
            String typeId,          // CEE SimulatedDeviceType id path（"resistor" 等）
            int terminalCount,      // getNodePositions 数量
            double voltage,         // 源电压（creative_battery/converter/accu 近似）
            double resistance,      // 设备电阻（converter 输出内阻等）
            boolean closed,         // 开关闭合（cut_off_switch/fuse/hv_switch...）
            boolean source,         // 是否有源（converter.isSource）
            double frequency,       // AC 源频率（creative_battery.acFrequency）
            double phase,           // 相位（度）
            double charge1, double charge2, // 蓄电池电荷（accumulator 两单元）
            double speed) {         // Create 发电机转速（rad/s；电机/泵等动力学）
    }

    private static final String DEVICES_SD = "com.george_vi.electroenergetics.devices"
            + ".device.DevicesSavedData";
    private static final String DEVICES_MAP_FIELD = "DEVICES_BY_POS";

    private CeeDeviceSupport() {
    }

    /** 反射：CEE 设备类型 id path（block.getDevice().id()） */
    public static String typeIdOf(Block b) {
        try {
            Method m = b.getClass().getMethod("getDevice");
            Object type = m.invoke(b);
            if (type == null) return null;
            Method idm = type.getClass().getMethod("id");
            Object rl = idm.invoke(type);
            if (rl instanceof net.minecraft.resources.ResourceLocation r)
                return r.getPath();
            return rl == null ? null : rl.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射：CEE 设备端子数（getNodePositions 大小；失败回退 1） */
    public static int terminalCountOf(net.minecraft.world.level.Level lv, BlockPos pos, BlockState st) {
        try {
            Method m = st.getBlock().getClass()
                    .getMethod("getNodePositions", net.minecraft.world.level.Level.class,
                            BlockPos.class, BlockState.class);
            Object map = m.invoke(st.getBlock(), lv, pos, st);
            if (map instanceof java.util.Map<?, ?> mm && !mm.isEmpty()) return mm.size();
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /** 反射：单个设备实例参数（DEVICES_BY_POS.get(pos) → public 字段） */
    public static CeeParams readParams(ServerLevel level, BlockPos pos) {
        try {
            Class<?> sd = Class.forName(DEVICES_SD);
            Object sdObj = sd.getMethod("load", ServerLevel.class).invoke(null, level);
            if (sdObj == null) return null;
            Field mapF = sd.getDeclaredField(DEVICES_MAP_FIELD);
            mapF.setAccessible(true);
            Object map = mapF.get(sdObj);
            if (map == null) return null;
            Method get = map.getClass().getMethod("get", long.class);
            Object dev = get.invoke(map, pos.asLong());
            if (dev == null) return null;
            // type id（SimulatedDevice.type 字段——沿父类链查找）
            String typeId = null;
            try {
                Field tf = findField(dev.getClass(), "type");
                tf.setAccessible(true);
                Object type = tf.get(dev);
                if (type != null) {
                    Method idm = type.getClass().getMethod("id");
                    Object rl = idm.invoke(type);
                    if (rl instanceof net.minecraft.resources.ResourceLocation r)
                        typeId = r.getPath();
                }
            } catch (Throwable ignored) {
            }
            // 公共字段参数
            double voltage = fieldD(dev, "voltage", 0);
            double resistance = fieldD(dev, "resistance", 0);
            boolean closed = fieldB(dev, "isClosed", false);
            boolean source = fieldB(dev, "isSource", false);
            double freq = fieldD(dev, "acFrequency", 0);
            double phase = fieldD(dev, "phaseOffset", 0);
            double c1 = fieldD(dev, "cell1Charge", 0);
            double c2 = fieldD(dev, "cell2Charge", 0);
            // Create 发电机转速（ElectricMotorDevice.be → GeneratingKineticBlockEntity.getSpeed()）
            double speed = readSpeed(dev);
            int tCount = 1;
            try {
                BlockState st = level.getBlockState(pos);
                tCount = terminalCountOf(level, pos, st);
            } catch (Throwable ignored) {
            }
            return new CeeParams(typeId, tCount, voltage, resistance, closed, source,
                    freq, phase, c1, c2, speed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 主线程低频刷新全部 CEE 设备参数（反射枚举 DEVICES_BY_POS）→ 写 DeviceParamCache */
    public static void refresh(ServerLevel level) {
        try {
            Class<?> sd = Class.forName(DEVICES_SD);
            Object sdObj = sd.getMethod("load", ServerLevel.class).invoke(null, level);
            if (sdObj == null) return;
            Field mapF = sd.getDeclaredField(DEVICES_MAP_FIELD);
            mapF.setAccessible(true);
            Object map = mapF.get(sdObj);
            if (map == null) return;
            java.util.Set<Object> keys = new java.util.HashSet<>();
            Method keySet = map.getClass().getMethod("keySet");
            if (keySet.getReturnType().isPrimitive()) return;
            Object ks = keySet.invoke(map);
            if (ks instanceof java.util.Set<?> s) keys.addAll(s);
            for (Object k : keys) {
                long l = ((Number) k).longValue();
                BlockPos pos = BlockPos.of(l);
                CeeParams cp = readParams(level, pos);
                if (cp != null) {
                    com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParamCache
                            .putCee(pos, cp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 后台建模：按 CEE 设备类型分派映射为我们的元件（参照 PowerGrid 组装器链）。
     * 端子元件补全（assembleTerminals 幂等）+ 元件挂到 a/b 节点。
     */
    public static void build(net.minecraft.world.level.Level level,
                             com.hdf.cryptand.circuitsimulation.model.Network engine,
                             BlockPos pos, Integer[] arr,
                             CeeParams cp, boolean registerTerminals) {
        try {
            int[] ab = firstTwo(arr);
            // 端子元件补全（CEE 多端子：connector 2 / triple 3 / quad 4...）
            com.hdf.cryptand.neoforge.powergrid.device.terminal.WireTerminals
                    .assembleTerminals(pos, Math.max(1, cp.terminalCount()), arr,
                            engine, registerTerminals);
            if (ab == null) return; // 无已接线引擎节点（悬空设备：端子补全已建节点，跳过元件）
            int a = ab[0], b = ab[1];
            String t = cp.typeId() == null ? "" : cp.typeId();
            switch (t) {
                // —— 接线盒/纯端子（无内部元件）——
                case "connector", "insulator", "concrete_pole", "temporary",
                     "pantograph", "energy_meter", "tri_polar_energy_meter",
                     "alternator_brushes", "three_phase_alternator_brushes":
                    return;
                // —— 电阻类 ——
                case "resistor":
                    engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                            .Resistor(a, b, cp.resistance() > 0 ? cp.resistance() : 100.0));
                    return;
                case "bulb":
                    engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                            .Resistor(a, b, cp.resistance() > 0 ? cp.resistance() : 10.0));
                    return;
                // —— 有源/储能 ——
                case "creative_battery":
                    if (cp.frequency() > 0) {
                        engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                                .AcVoltageSource(a, engine.groundNode,
                                        cp.voltage() > 0 ? cp.voltage() : 230, cp.phase(), 0.01));
                    } else {
                        engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                                .DcVoltageSource(a, engine.groundNode,
                                        cp.voltage() > 0 ? cp.voltage() : 48, 0.01));
                    }
                    return;
                case "accumulator":
                    // 简化恒压：两单元电荷 → 每格 ~1.5V（后续可接 EnergyDevice 荷电演化）
                    double v = (cp.charge1() + cp.charge2()) * 1.5;
                    engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                            .DcVoltageSource(a, engine.groundNode,
                                    v > 0.1 ? v : 1.5, 0.1));
                    return;
                case "converter":
                    if (cp.source()) {
                        engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                                .DcVoltageSource(a, engine.groundNode,
                                        cp.voltage() > 0 ? cp.voltage() : 12,
                                        cp.resistance() > 0 ? cp.resistance() : 0.01));
                    } else {
                        engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                                .Resistor(a, b, cp.resistance() > 0 ? cp.resistance() : 0.01));
                    }
                    return;
                // —— 开关（闭合=近似 1mΩ；断开=开路无元件）——
                case "cut_off_switch", "double_switch", "fuse", "high_voltage_switch",
                     "redstone_relay":
                    if (cp.closed())
                        engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                                .Resistor(a, b, 0.001));
                    return;
                // —— 二极管 ——
                case "diode":
                    engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                            .DiodeElement(a, b));
                    return;
                // —— 变压器/稳压器：1:1 理想互感（简化）——
                case "transformer", "voltage_regulator":
                    engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                            .IdealTransformer(a, engine.groundNode, b, engine.groundNode,
                                    engine.addNode().id, 1.0));
                    return;
                // —— 接地棒：近 0Ω 接入地 ——
                case "ground":
                    engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                            .Resistor(a, engine.groundNode, 0.001));
                    engine.addElement(new com.hdf.cryptand.circuitsimulation.model.elements
                            .Resistor(b, engine.groundNode, 0.001));
                    return;
                // —— 2026-08-24 用户：已删除 CEE 电机/泵兼容建模（自兼容以来问题
                //   丛生；电机全部走 PowerGrid 对接，CEE 电机不建模不干预）——
                case "electric_motor", "electric_pump": {
                    return;
                }
                default:
                    // 未知 CEE 设备保守不建模（保持端点/接线盒语义）
                    return;
            }
        } catch (Throwable ignored) {
        }
    }

    private static int[] firstTwo(Integer[] arr) {
        if (arr == null) return null;
        Integer a = null, b = null;
        for (Integer id : arr) {
            if (id == null) continue;
            if (a == null) a = id;
            else { b = id; break; }
        }
        if (a == null) return null;
        return b == null ? new int[]{a, a} : new int[]{a, b};
    }

    private static double fieldD(Object o, String name, double def) {
        try {
            Field f = o.getClass().getField(name);
            Object v = f.get(o);
            return v instanceof Number n ? n.doubleValue() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean fieldB(Object o, String name, boolean def) {
        try {
            Field f = o.getClass().getField(name);
            Object v = f.get(o);
            return v instanceof Boolean bool ? bool : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    /** 读 Create 发电机转速（device.be → getSpeed） */
    private static double readSpeed(Object dev) {
        try {
            Field beF = findField(dev.getClass(), "be");
            beF.setAccessible(true);
            Object be = beF.get(dev);
            if (be == null) return 0;
            try {
                Method m = be.getClass().getMethod("getSpeed");
                Object v = m.invoke(be);
                if (v instanceof Number n) return n.doubleValue();
            } catch (Throwable ignored) {
            }
            try {
                Method m = be.getClass().getMethod("getKineticSpeed");
                Object v = m.invoke(be);
                if (v instanceof Number n) return n.doubleValue();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 沿类/父类链查找字段（CEE 设备继承层次深） */
    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new RuntimeException("field not found: " + name);
    }
}
