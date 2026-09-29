package com.hdf.cryptand.neoforge.powergrid.state;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.FanCoolingRegistry;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备温度模型存储（静态持久：跨网络重建保留温度与冷却状态）。
 * <p>
 * 实际设备（电机/加热器/换向器等）通过【包含】{@link ThermalModel} 做温度模拟：
 * 每求解步用损耗功率推进温度，被鼓风机吹时提高散热系数（散热与发热同时算，
 * 解析解无条件稳定不跳变）。
 * <p>
 * 默认散热 4 W/K、热容 200 J/K（时间常数 τ=C/G=50s，快速趋稳后保持恒定）、
 * 环境 25°C、最高 200°C（2026-08-19 用户要求统一 25°C 室温；魔法数字，更新参数
 * 后重新组合即可重算）。
 */
public final class DeviceThermalStore {

    private static final Map<BlockPos, ThermalModel> THERMAL = new ConcurrentHashMap<>();

    /** 原版直流(DC)设备位置（2026-08-22）：灯/风扇/铃/加热器等只支持 DC，
     *  网络频率超 dcDeviceMaxFrequencyHz → 温度指数型惩罚。 */
    private static final java.util.Set<BlockPos> DC_ONLY = ConcurrentHashMap.newKeySet();

    /** 标记设备为原版直流(DC)设备（组装器 dcOnly() 时由构建方调用；幂等） */
    public static void markDcOnly(BlockPos pos, boolean dcOnly) {
        if (pos == null) return;
        if (dcOnly) DC_ONLY.add(pos); else DC_ONLY.remove(pos);
    }

    /** 该位置是否原版直流(DC)设备（超频温度惩罚用） */
    public static boolean isDcOnly(BlockPos pos) {
        return pos != null && DC_ONLY.contains(pos);
    }

    /** 获取/创建设备温度模型（跨网络重建持久）。
     *  ⚠ 创建时套用【风扇已登记的额外散热】：风扇按位置登记（先放风扇、后放设备
     *  同样享受冷却），温度模型晚于风扇创建时必须把累积值带上，否则这台设备要等到
     *  风扇下次登记（转向/转速变化）才有冷却。 */
    public static ThermalModel thermalFor(BlockPos pos) {
        return THERMAL.computeIfAbsent(pos,
                p -> withFanCooling(p, new ThermalModel(4.0, 200.0, 298.15, 473.15)));
    }

    /** 高耐温设备温度模型（加热器等【发热设备】：设计工作温度高，maxTemp 400°C
     *  = 673.15K——避免短路瞬态 200~300°C 就被误杀，真正过流到 400°C 才烧毁）。 */
    public static ThermalModel thermalForHighTemp(BlockPos pos) {
        return THERMAL.computeIfAbsent(pos,
                p -> withFanCooling(p, new ThermalModel(4.0, 200.0, 298.15, 673.15)));
    }

    /**
     * 电机族温度模型（2026-09-13 用户："温度模型、机械模型等需要精确到具体功能，
     * 比如电机，这样的话可以对电机一类都进行统一的温度计算"）。
     *
     * 首次调用创建 {@link com.hdf.cryptand.circuitsimulation.model.thermal
     * .MotorThermalModel}（族级统一损耗：I²R 铜损 + k_fe·|ω|^1.5 铁损 +
     * k_fric·ω² 机械损耗），之后复用（跨网络重建保留温度与冷却）。
     * 参数只在首次创建时生效 —— 与 {@link #thermalFor} 的语义一致。
     *
     * @param windingResistance 绕组电阻 R（Ω，铜损 I²R）
     * @param ironLossK         铁损系数（W/(rad/s)^1.5；磁滞+涡流）
     * @param frictionLossK     摩擦/风阻系数（W/(rad/s)²）
     */
    public static ThermalModel thermalForMotor(BlockPos pos, double windingResistance,
                                               double ironLossK, double frictionLossK) {
        return THERMAL.computeIfAbsent(pos, p -> withFanCooling(p,
                new com.hdf.cryptand.circuitsimulation.model.thermal.MotorThermalModel(
                        4.0, 200.0, windingResistance, ironLossK, frictionLossK)));
    }

    /* ===== 电机族温度模型参数（与 MotorAssembler 共用同一组常量，避免两处漂移）===== */
    /** 电机绕组电阻（Ω，与原版 resistance() 一致，铜损 I²R 用） */
    public static final double MOTOR_WINDING_R = 25.6;
    /** 铁损系数（W/(rad/s)^1.5）：≈额定功率 5% @ 额定转速 */
    public static final double MOTOR_IRON_LOSS_K = 0.00209;
    /** 摩擦/风阻系数（W/(rad/s)²）：≈额定功率 3% @ 额定转速 */
    public static final double MOTOR_FRICTION_LOSS_K = 5.47e-5;

    /**
     * ===== 放置即建温度模型（2026-09-13 用户："不管怎么样所有网络都会进行求解，
     *  元件可以不求解但是模型是必须的，孤立网络也要"）=====
     *
     * 设备放下时立即创建对应的温度模型，**不以"网络被求解"为前提** ——
     * 这样孤立设备也有真实的环境温度读数（温度表不再显示「无」），
     * 一旦接线即可被 `advancePseudoTime` 推进（它本来就"不管网络是否开路与闭合"）。
     *
     * 按设备族选模型：电机族 → 族级统一损耗公式的温度模型（见
     * {@link #thermalForMotor}），其余 → 通用 {@link ThermalModel}。
     * 幂等：已有模型原样保留（组装阶段不会覆盖）。
     */
    public static ThermalModel ensureModelFor(
            net.minecraft.world.level.block.entity.BlockEntity be) {
        if (be == null) return null;
        try {
            BlockPos pos = be.getBlockPos();
            ThermalModel existing = THERMAL.get(pos);
            if (existing != null) return existing;
            if (isMotorFamily(be)) {
                return thermalForMotor(pos, MOTOR_WINDING_R,
                        MOTOR_IRON_LOSS_K, MOTOR_FRICTION_LOSS_K);
            }
            return thermalFor(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 是否电机族（继承链判定，用户："不要再按类名匹配"）。
     * <p>2026-09-13 javap 实测：原版三个电机类彼此【没有】继承关系，都是直接
     * extends GeneratingKineticBlockEntity ——
     * <ul>
     *   <li>{@code ElectricMotorBlockEntity}（普通 DC 电机；Cryptand 自研单相异步
     *       {@code SinglePhaseAsyncMotorBlockEntity} 是它的子类，一并覆盖）</li>
     *   <li>{@code ConstantSpeedMotorBlockEntity}（恒速电机）</li>
     *   <li>{@code ServoBlockEntity}（伺服电机）</li>
     * </ul>
     * 三者必须并列列出，任何一个都不能代表其余两个。
     */
    private static boolean isMotorFamily(
            net.minecraft.world.level.block.entity.BlockEntity be) {
        return com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                .classChainHas(be, "ElectricMotorBlockEntity")
                || com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                .classChainHas(be, "ConstantSpeedMotorBlockEntity")
                || com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                .classChainHas(be, "ServoBlockEntity");
    }

    /** 只读温度模型（不存在返回 null，【不创建】）——风扇冷却应用/撤销时用：
     *  撤销某台风扇不应该反过来为它吹过的位置创建温度模型。 */
    public static ThermalModel peek(BlockPos pos) {
        return pos == null ? null : THERMAL.get(pos);
    }

    /** 新建温度模型时套用风扇累积额外散热（FanCoolingRegistry） */
    private static ThermalModel withFanCooling(BlockPos pos, ThermalModel t) {
        try {
            t.setExtraCoolingFactor(FanCoolingRegistry.extraFactor(pos));
        } catch (Throwable ignored) {
        }
        return t;
    }

    /** 方块被移除/卸载时清理 */
    public static void remove(BlockPos pos) {
        if (pos != null) {
            THERMAL.remove(pos);
            DC_ONLY.remove(pos);
        }
    }

    /** 该位置是否已有温度模型（热扩散只向已有温度模型的元件传导） */
    public static boolean contains(BlockPos pos) {
        return pos != null && THERMAL.containsKey(pos);
    }

    /** 当前温度模型条目数（诊断用：>0 说明有设备温度被推进） */
    public static int size() {
        return THERMAL.size();
    }

    /** 全部位置（副本，进世界一致性检测用） */
    public static java.util.Set<BlockPos> keys() {
        return new java.util.HashSet<>(THERMAL.keySet());
    }

    /**
     * 全量降温（2026-08-23 用户：设备无功率/开路 → 按散热持续降到室温，不重置；
     * PhasorEngine.coolAllTemperatures 每 tick 调用）。
     * <p>
     * ⚠ 2026-09-13 用户："不管怎么样所有网络都会进行求解，元件可以不求解但是模型
     * 是必须的，孤立网络也要" —— 这里【只降温、不删条目】。
     * <p>
     * 旧实现是"温度 &lt; 25.5°C 就把模型移除防泄漏"，后果：
     * <ul>
     *   <li>刚放置的设备（25°C 室温）下一 tick 模型即被删 → 温度表读不到 → 显示
     *       「无温度模型」。发热设备一旦停止发热降到室温，模型同样消失，再发热时
     *       又变回室温起步 —— 温度历史被静默丢失。</li>
     *   <li>与"模型必须存在"直接冲突：室温是设备的【正常状态】，不是"应该忘掉
     *       这台设备"的信号。</li>
     * </ul>
     * 泄漏由真正知道"设备没了"的地方负责，且已齐备：
     * {@code PhasorPipeline} 世界一致性检测（区块已加载且 getBlockEntity==null →
     * remove）、{@link com.hdf.cryptand.neoforge.powergrid.network.DestructionQueue}、
     * {@code DeviceBinding}（设备被破坏/替换时 remove）。
     */
    public static void coolAll(double dt) {
        if (dt <= 0) return;
        try {
            for (ThermalModel t : THERMAL.values()) {
                try {
                    t.update(0, dt);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 位置迁移（物理化 remap：DeviceThermalStore key 随坐标迁移） */
    public static void move(BlockPos oldPos, BlockPos newPos) {
        if (oldPos == null || newPos == null || oldPos.equals(newPos)) return;
        try {
            ThermalModel th = THERMAL.remove(oldPos);
            if (th != null) THERMAL.putIfAbsent(newPos, th);
            if (DC_ONLY.remove(oldPos)) DC_ONLY.add(newPos);
        } catch (Throwable ignored) {
        }
    }

    private DeviceThermalStore() {}
}
