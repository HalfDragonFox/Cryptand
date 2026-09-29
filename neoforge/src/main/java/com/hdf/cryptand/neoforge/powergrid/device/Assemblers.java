/**
 * ===== 相量设备注册表 =====
 *
 * 按 PowerGrid 方块类简单名分发到对应设备模型（按包结构组织的独立适配类）。
 * 让相量核心认识所有原版设备——未被建模的设备在相量网络里会被导线合并成
 * 短路，导致电压分配错误。
 *
 * 已由 PhasorNetworkBuilder 直接处理的类【不】注册：
 *   ResistorBlockEntity（instanceof 电阻分支）、TransformerBlockEntity
 *   （4 端口变压器）、WindingBlockEntity（R-L 串联）、AcCreativeSourceBlockEntity。
 */

package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.neoforge.powergrid.device.basinheater.BasinHeaterAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.battery.BatteryAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.bell.BellAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.carbonpile.CarbonPileAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.connector.ConnectorAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.electromagnet.ElectromagnetAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.fan.FanAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.gauge.GaugeAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.grounding.GroundingRodAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.heater.HeaterAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.light.LightAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.motor.MotorAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.plotter.PlotterAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.rheostat.RheostatAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.source.CreativeSourceAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.switchdevice.SwitchAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.variac.VariacAssembler;
import com.hdf.cryptand.neoforge.powergrid.motor.singlephase.SinglePhaseAsyncMotorBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;

public final class Assemblers {

    private static final Map<String, Assembler> MODELS = new HashMap<>();

    /* ===== 反射字段读取（继承链上溯 + 缓存，2026-09-13）=====
     * ⚠ 寄生式接管的第三个系统性陷阱：自有 BE 一律是原版 BE 的【子类】，
     *   任何 be.getClass().getDeclaredField("x") 都只查子类自身声明的字段 →
     *   父类字段一律 NoSuchFieldException，而调用点通常 catch 后走默认分支
     *   ⇒ 静默出错（如 Winding 的 mainBE：代理方块被误判为主方块 → 重复建模；
     *     windingMainPos 返回 null）。统一走本方法（沿继承链 + 结果缓存）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> FIELD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object MISS = new Object();

    /** 反射读实例字段（沿继承链；字段不存在或读取失败 → null）。按 类名#字段名 缓存。 */
    public static Object field(Object obj, String name) {
        if (obj == null || name == null) return null;
        try {
            Class<?> cls = obj.getClass();
            String key = cls.getName() + '#' + name;
            Object c = FIELD_CACHE.get(key);
            if (c == null) {
                java.lang.reflect.Field f = null;
                for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
                    try {
                        f = k.getDeclaredField(name);
                        f.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                    } catch (Throwable ignored) {
                        break;
                    }
                }
                c = (f == null) ? MISS : (Object) f;
                FIELD_CACHE.put(key, c);
            }
            return c == MISS ? null : ((java.lang.reflect.Field) c).get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static {
        // —— electricity.battery / equipment.portablebattery ——
        MODELS.put("BatteryBlockEntity", BatteryAssembler.INSTANCE);
        MODELS.put("PotatoBatteryBlockEntity", BatteryAssembler.INSTANCE);
        MODELS.put("PortableBatteryBlockEntity", BatteryAssembler.INSTANCE);
        // —— electricity.creative ——
        MODELS.put("CreativeSourceBlockEntity", CreativeSourceAssembler.INSTANCE);
        // —— electricity.heater ——
        MODELS.put("HeaterBlockEntity", HeaterAssembler.INSTANCE);
        // —— electricity.light.fixture ——
        MODELS.put("LightFixtureBlockEntity", LightAssembler.INSTANCE);
        // —— electricity.bell ——
        MODELS.put("AlarmBellBlockEntity", BellAssembler.INSTANCE);
        // —— electricity.fan ——
        MODELS.put("ElectricFanBlockEntity", FanAssembler.INSTANCE);
        // —— electricity.basinheater ——
        MODELS.put("BasinHeaterBlockEntity", BasinHeaterAssembler.INSTANCE);
        // —— electricity.electromagnet ——
        MODELS.put("ElectromagnetBlockEntity", ElectromagnetAssembler.INSTANCE);
        // —— electricity.carbonpile ——
        MODELS.put("CarbonPileBlockEntity", CarbonPileAssembler.INSTANCE);
        MODELS.put("CarbonPileCoilBlockEntity", CarbonPileAssembler.INSTANCE);
        // —— electricity.electricswitch / contactor / fuse ——
        MODELS.put("SwitchBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("HvSwitchBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("HvBreakerBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("ContactorBlockEntity", SwitchAssembler.INSTANCE);
        MODELS.put("FuseHolderBlockEntity", SwitchAssembler.INSTANCE);
        // —— kinetics.motor / kinetics.generator / kinetics.servo ——
        // ⚠ 2026-08-29 原版普通电机（ElectricMotorBlockEntity）退回仿真 DC 直流
        //   电机模型（MotorAssembler 默认分支 DcBrushedMotorModel）；2026-08-30
        //   单相异步电机（SinglePhaseAsyncMotorBlockEntity，2/4/6/8 极共用一类）
        //   分到感应电机模型（MotorAssembler 按 BE 极数创建 InductionMotorModel）。
        MODELS.put("ElectricMotorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("SinglePhaseAsyncMotorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("ConstantSpeedMotorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("GeneratorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("CommutatorBlockEntity", MotorAssembler.INSTANCE);
        MODELS.put("ServoBlockEntity", MotorAssembler.INSTANCE);
        // —— electricity.gauge ——
        MODELS.put("GaugeBlockEntity", GaugeAssembler.INSTANCE);
        MODELS.put("VoltageGaugeBlockEntity", GaugeAssembler.INSTANCE);
        MODELS.put("CurrentGaugeBlockEntity", GaugeAssembler.INSTANCE);
        MODELS.put("PowerGaugeBlockEntity", GaugeAssembler.INSTANCE);
        // —— electricity.grounding ——
        MODELS.put("GroundingRodBlockEntity", GroundingRodAssembler.INSTANCE);
        // —— kinetics.variac ——
        MODELS.put("VariacBlockEntity", VariacAssembler.INSTANCE);
        // —— kinetics.rheostat ——
        MODELS.put("RheostatBlockEntity", RheostatAssembler.INSTANCE);
        // —— kinetics.plotter ——
        MODELS.put("PlotterBlockEntity", PlotterAssembler.INSTANCE);
        // —— electricity.socket / wireconnector / deviceconnector / compat.tfmg ——
        MODELS.put("SocketBlockEntity", ConnectorAssembler.INSTANCE);
        MODELS.put("ConnectorBlockEntity", ConnectorAssembler.INSTANCE);
        MODELS.put("CordJunctionBlockEntity", ConnectorAssembler.INSTANCE);
        // ⚠ 2026-08-30 设备接线柱改【代理组装器】——代理 facing 设备的真实组装器
        //（设备模型经接线柱接入，用户方案）
        MODELS.put("DeviceConnectorBlockEntity",
                com.hdf.cryptand.neoforge.powergrid.device.connector
                        .ProxyConnectorAssembler.INSTANCE);
        MODELS.put("TFMGCompatDeviceConnectorBlockEntity", ConnectorAssembler.INSTANCE);
        // ⚠ 2026-08-30 全部接管（用户）：未显式建模的原版组件 → UncoveredAssembler
        // （灯/电阻/泵/太阳能/绕组精确建模；机械无电气=null；电子=占位直通）
        // —— 灯族
        MODELS.put("CeilingTileLampBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("FactoryLightBlockEntity", UncoveredAssembler.INSTANCE);
        // —— 电阻
        MODELS.put("ResistorBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("CreativeResistorBlockEntity", UncoveredAssembler.INSTANCE);
        // —— 泵 / 太阳能 / 绕组
        MODELS.put("ElectricPumpBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("SolarPanelBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("CeilingTileSolarBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("TunedBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("WindingBlockEntity", UncoveredAssembler.INSTANCE);
        // —— 机械（无电气端子 → null 不建模，走 Create 网络）
        MODELS.put("RotorBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("InductionRotorBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("LargeInductionRotorBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("GeneratorClutchBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("SolarPanelBearingBlockEntity", UncoveredAssembler.INSTANCE);
        // —— 电子/信号/测量（占位直通连通）
        MODELS.put("CRTBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("FEInverterBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("RedstoneConverterBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("ModularDisplayBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("CircuitBoardBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("CircuitDesignTableBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("EnergyMeterBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("PunchCardReaderBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("SparkGapBlockEntity", UncoveredAssembler.INSTANCE);
        // —— 瓷砖连接（导线合并处理 → null）
        MODELS.put("CeilingTileConnectorBlockEntity", UncoveredAssembler.INSTANCE);
        MODELS.put("CeilingTileJunctionBlockEntity", UncoveredAssembler.INSTANCE);
        // —— 下界变压器（ctx.transformerModels 已接管；占位保险）
        MODELS.put("NetherTransformerBlockEntity", UncoveredAssembler.INSTANCE);
    }

    /* ==================== 类名解析（2026-09-13 寄生式接管修复）====================
     * 自有 BE 均为「XxxBE extends 原版XxxBlockEntity」⇒ getClass().getSimpleName()
     * 返回【子类名】（HeaterBE / CryptandElectricMotorBE…），而注册表键是【原版名】
     * （HeaterBlockEntity / ElectricMotorBlockEntity…）→ 直接查表 MISS ⇒ 组装器拿不到：
     *   · 加热器等设备【放置即崩溃】（无组装器）
     *   · 电机【不建模 → 无设备电流 → 转速恒 0】（"电机不转"根因）
     * 因此所有"按 BE 类名查表/匹配"的地方统一走下面的 helper（子类 → 逐级父类）。
     * ========================================================================== */

    /**
     * 沿继承链解析"原版简单类名"（2026-09-13）：
     *   ① 自身或任一祖先在注册表（MODELS）里 → 返回它（原版实例走这条，行为不变）
     *   ② 未注册的兜底族（UncoveredAssembler 按字符串字面量分发：灯/电阻/泵/绕组…）
     *      → 返回继承链上第一个"原版命名"的父类名（以 BlockEntity / Entity 结尾）
     *   ③ 都没有 → 自身简单名
     * 自有 BE 均为「XxxBE extends 原版XxxBlockEntity」⇒ ② 一定命中原版名。
     */
    public static String beKey(BlockEntity be) {
        if (be == null) return null;
        Class<?> c = be.getClass();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            if (MODELS.containsKey(k.getSimpleName())) {
                return k.getSimpleName();
            }
        }
        for (Class<?> k = c.getSuperclass(); k != null && k != Object.class;
                k = k.getSuperclass()) {
            String s = k.getSimpleName();
            if (s.endsWith("BlockEntity") || s.endsWith("Entity")) {
                return s;
            }
        }
        return c.getSimpleName();
    }

    /**
     * 该类及其全部父类的简单名（子类在前）——"按类名查表"的通用回退用。
     * 例：CryptandBlockEntities.HeaterBE → [HeaterBE, HeaterBlockEntity, ...]
     * 调用方按顺序尝试即可命中注册表里的【原版名】。
     */
    public static java.util.List<String> simpleNames(Class<?> c) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(4);
        Class<?> k = c;
        while (k != null && k != Object.class) {
            out.add(k.getSimpleName());
            k = k.getSuperclass();
        }
        return out;
    }

    /**
     * 沿继承链解析"原版全限定名"（与 {@link #beKey} 同规则，供 cls 字段 / endsWith 判定用）。
     * 自有 BE 子类 → 原版父类的全限定名 ⇒ 下游所有 endsWith("XxxBlockEntity") 判定自动生效。
     */
    public static String fqcnKey(BlockEntity be) {
        if (be == null) return "";
        Class<?> c = be.getClass();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            if (MODELS.containsKey(k.getSimpleName())) {
                return k.getName();
            }
        }
        for (Class<?> k = c.getSuperclass(); k != null && k != Object.class;
                k = k.getSuperclass()) {
            String s = k.getSimpleName();
            if (s.endsWith("BlockEntity") || s.endsWith("Entity")) {
                return k.getName();
            }
        }
        return c.getName();
    }

    /** 继承链上是否存在该简单名的类（替代 endsWith("XxxBlockEntity") 式判断） */
    public static boolean classChainHas(BlockEntity be, String simpleName) {
        Class<?> c = be == null ? null : be.getClass();
        while (c != null && c != Object.class) {
            if (c.getSimpleName().equals(simpleName)) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** 按方块类简单名取设备模型（沿继承链回退；未注册返回 null） */
    public static Assembler get(BlockEntity be) {
        if (be == null) return null;
        return getByClass(beKey(be));
    }

    /** 注册额外组装器（如 CEE 接线端子组装器；按 BE 类简单名）。
     *  <p>2026-08-22 用户架构：接线端子 = 空元件的组装器（仅连接、不建模内部
     *  元件）—— 由设备建模循环 {@code assemble()→null} 自然忽略，无需专门分支。 */
    public static void register(String simpleName, Assembler m) {
        if (simpleName != null && m != null) MODELS.put(simpleName, m);
    }

    /** 按类简单名取设备模型（后台缓存驱动：DeviceParamCache.deviceClass 反查） */
    public static Assembler getByClass(String simpleName) {
        return simpleName == null ? null : MODELS.get(simpleName);
    }

    /** 该方块是否为【有源设备】（按注册表模型 {@code isSource()} 判断，2026-08-13
     *  用户架构：接口化判断函数替代反射类名匹配；未注册/无源 → false）。
     *  注：发电机/换向器/变压器等直接处理类不在注册表 → 由调用方类名双保险兜底。 */
    public static boolean isSource(BlockEntity be) {
        if (be == null) return false;
        Assembler m = getByClass(beKey(be));
        return m != null && m.isSource();
    }

    /** 是否为闭合接地棒（相量网络设参考地用） */
    public static boolean isActiveGround(BlockEntity be) {
        return classChainHas(be, "GroundingRodBlockEntity")
                && GroundingRodAssembler.isGrounded(be);
    }

    private Assemblers() {}
}
