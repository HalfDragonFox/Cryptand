/**
 * ===== 电机类设备（PowerGrid kinetics.motor / kinetics.generator）=====
 *
 * 最新架构适配（2026-08-12）：统一使用 {@link ElectroMachineModel} 机电复合模型：
 *   - 电动机（ElectricMotor / ConstantSpeedMotor）：绕组 R-L（电能→机械能）
 *   - 发电机（Generator）：EMF 源 + 绕组内阻 R-L（机械能→电能），EMF = K·ω
 *     （转速驱动，磁场简化恒磁），每轮参数刷新
 *   - 换向器（Commutator）：电枢 R（L≈0）
 * 集成最新统一架构：温度模型（ThermalDevice）+ 能量模型（EnergyDevice）+
 * 绑定数据（ElementBinding）+ 事件接收（ElementEventSink 过热爆炸）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.motor;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ConstantSpeedMotorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.InductionMotorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.NormalMotorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ServoMotorModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.adapter.EngineBus;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.device.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class MotorAssembler implements SourceCacheAssembler {

    public static final MotorAssembler INSTANCE = new MotorAssembler();

    /** EMF 常数（V per rad/s，魔法数字：EMF = K_EMF × ω） */
    private static final double EMF_K = 0.1;

    private MotorAssembler() {}

    /** 原版直流电机（普通/恒速/换向器/伺服）：只支持 DC，超频温度惩罚
     *  （Generator 发电机除外——分发处按类排除，见 PhasorNetworkBuilder）。 */
    @Override public boolean dcOnly() { return true; }

    /** 主线程每 tick：读 coilWire/coil R-L（或换向器电枢 R）→ 原子写输入槽。
     *  amplitude 字段存发电机标志（1=GEN，0=MOTOR）。转速仍走 EngineBus 消息，
     *  不走缓存（单向 Source 即可，无需 Bidi）。 */
    @Override
    public void refreshCache(BlockEntity be, DeviceCache cache) {
        try {
            Object w = DeviceWire.field(be, "coilWire");
            if (w == null) w = DeviceWire.field(be, "coil");
            double r = 0;
            double l = 0;
            if (w != null) {
                DeviceWire dw = DeviceWire.of(w);
                r = (dw.hasResistance() && dw.resistance > 0) ? dw.resistance : 0;
                l = Math.max(dw.inductance, 0);
            }
            // 2026-08-20 兜底：coil 反射读不到 R（buildCircuit 未执行 / coil 未
            // 创建 / getResistance()=0）→ 直接调 IElectricEntity.resistance()（接口
            // 方法，PowerGrid ResistanceValues 配置电阻，非 0）。否则引擎把电机
            // 建模成 GMIN 兜底（10MΩ）→ 不导电 → 不转。
            if (r <= 0 && be instanceof org.patryk3211.powergrid.electricity.base
                    .IElectricEntity ie) {
                try {
                    float ri = ie.resistance();
                    if (ri > 0) r = ri;
                } catch (Throwable ignored) {
                }
            }
            // 换向器（Commutator）：电枢电阻（L≈0，无 coil 字段）
            if (r <= 0) {
                Object res = DeviceWire.field(be, "resistance");
                if (res instanceof Number n && n.doubleValue() > 0) r = n.doubleValue();
            }
            // 2026-08-27 用户：模型拆三份（普通/恒速/伺服）。amplitude 复用为
            // 【电机类型码】0=普通 1=发电机 2=恒速 3=伺服（后台 assembleFromCache
            // 按码创建对应模型类）。
            int mtype = motorTypeOf(be);
            boolean gen = mtype == 1;
            // 2026-08-26 用户规格：电动机（普通/恒速/伺服）内部【主电阻固定 25.6Ω】
            // ——不读线圈实际电阻（PowerGrid 线圈随线材/长度可变，曾出现 512Ω →
            // 电流 ~0.05A 卡 powered 阈值 → 应力波动/转速低）。发电机/换向器保持读取。
            if (!gen && r > 0) r = 25.6;
            DeviceCache.Data old = cache.in();
            cache.setIn(new DeviceCache.Data(r, l, (double) mtype, 0, 0, true,
                    old.version + 1));
            // 诊断（节流 5s）：确认 R 是否读成功（10MΩ=GMIN 兜底排查）
            long now = System.currentTimeMillis();
            if (now - cacheDbgLast >= 5000) {
                cacheDbgLast = now;
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[MotorCache] pos={} coilClass={} R={} L={} gen={}",
                        be.getBlockPos(), w == null ? "null" : w.getClass().getSimpleName(),
                        String.format("%.4f", r), String.format("%.4f", l), gen);
            }
            return;
        } catch (Throwable ignored) {
        }
    }

    /** 缓存诊断节流 */
    private static volatile long cacheDbgLast;

    /** 后台：从缓存输入槽构建 ElectroMachineModel（原子读，不碰 BE）；
     *  发电机 EMF 由引擎侧虚拟转速算（emfConstant），转速事件仍发 EngineBus。 */
    @Override
    public CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                            int a, int b, Network net) {
        try {
            DeviceCache.Data d = cache.in();
            double r = d.resistance;
            // 诊断（节流 5s）：确认组装器是否被调用 + 缓存 R 值（10MΩ 排查）
            long now = System.currentTimeMillis();
            if (now - asmDbgLast >= 5000) {
                asmDbgLast = now;
                // 2026-08-20 排查"交流源+电阻+电机 导线 457A 烧毁"：打印完整网络
                // （节点数 + 元素类型 + 导线段）——确认电机是否正确并入含源/导线
                // 的网络（若 nodes 小/无导线段 → 网络分裂 → 源短路烧导线）
                StringBuilder as = new StringBuilder();
                try {
                    for (com.hdf.cryptand.circuitsimulation.model.Element el : net.elements()) {
                        as.append(" [").append(el.type()).append('(')
                                .append(el.nodeA()).append(',').append(el.nodeB())
                                .append(')').append(']');
                    }
                    as.append(" segs=");
                    int segN = 0;
                    for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce
                            : net.composites()) {
                        if (ce instanceof com.hdf.cryptand.circuitsimulation.model.composite.WireComposite) {
                            segN++;
                        }
                    }
                    as.append(segN);
                } catch (Throwable ignored) {
                }
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[MotorAsm] pos={} R={} L={} a={} b={} nodes={}{}",
                        pos, String.format("%.4f", r), String.format("%.4f", d.inductance),
                        a, b, net.nodeCount(), as);
            }
            if (r > 0) {
                int x = net.addNode().id;
                // 2026-08-27 分类型创建模型（amplitude 复用为类型码）
                int mtype = (int) Math.round(d.amplitude);
                boolean gen = mtype == 1; // 发电机标志（类型码 1）
                com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel eng =
                        new com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel(1.0);
                com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                        DeviceThermalStore.thermalFor(pos);
                ElectroMachineModel em;
                switch (mtype) {
                    case 2: // 恒速电机
                        em = new ConstantSpeedMotorModel(a, b, x, r, d.inductance,
                                th, eng, false, 0);
                        break;
                    case 3: // 伺服电机
                        em = new ServoMotorModel(a, b, x, r, d.inductance,
                                th, eng, false, 0);
                        break;
                    case 1: // 发电机（保持基类 + 发电机标志）
                        em = new ElectroMachineModel(a, b, x, r, d.inductance,
                                th, eng, true, 0);
                        break;
                    default: // 普通电机（默认）= 单相 AC 感应电机（2026-08-28）
                        em = new InductionMotorModel(a, b, x, r, d.inductance,
                                th, eng, false, 0);
                }
                if (gen) em.emfConstant = EMF_K;
                // 2026-08-27 恢复（此前主动能丢失的修复）：跨重建保持转子状态——
                // 每轮网络重建会创建【新的 ElectroMachineModel】→ rotorSpeedRadS 默认
                // 0。若不复原，每次重建从 0 爬升（tau=1.5s，5s 也到不了满速）→
                // EMF 低 → I=(Vab−EMF)/R 大（3.7~5.8A）持续 → PowerGrid 火花无限；
                // 且用户剪线（重建）后转速归零 = "断电瞬停无惯性"。
                // MotorStateStore（上一轮 ROTOR_SPEED 消息写）恢复
                // {radS, emf, stress}。纯数据无 MC 依赖。
                try {
                    com.hdf.cryptand.neoforge.powergrid.adapter.MotorStateStore
                            .restore(pos, em);
                } catch (Throwable ignored) {
                }
                // 转速事件 → EngineBus 消息（BE 速度差→force 跟随）
                em.bindShaftListener((radS, rpm) -> EngineBus.post(
                        EngineBus.Type.ROTOR_SPEED, pos, null, em,
                        new double[]{radS, rpm}));
                return em;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 组装诊断节流 */
    private static volatile long asmDbgLast;

    @Override
    public CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        try {
            Object w = DeviceWire.field(be, "coilWire");
            if (w == null) w = DeviceWire.field(be, "coil");
            if (w != null) {
                DeviceWire dw = DeviceWire.of(w);
                // 诊断（节流 5s，2026-08-12）：确认电机 coil 是否创建/电阻值。
                long mNow = System.currentTimeMillis();
                if (mNow - motorDbgLast >= 5000) {
                    motorDbgLast = mNow;
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[MotorBuild] pos={} coilClass={} R={} L={} a={} b={}",
                            be.getBlockPos(), w.getClass().getSimpleName(),
                            String.format("%.4f", dw.resistance),
                            String.format("%.4f", dw.inductance), a, b);
                }
                if (dw.hasResistance() && dw.resistance > 0) {
                    int x = net.addNode().id; // R-L 串联内部节点
                    // 2026-08-27 分类型创建模型（按 BE 类名；发电机保持基类）
                    int mtype = motorTypeOf(be);
                    boolean gen = mtype == 1;
                    // 2026-08-15 机电引擎化（用户设计：一切模拟入虚拟计算，MC 交互走消息）：
                    //   - EMF 由引擎侧虚拟转速计算（advanceShaft：EMF = K_EMF·ω），
                    //     不再每轮读 BE rotorBehaviour
                    //   - 转速事件 → EngineBus 消息 → BE 侧速度差→force 跟随
                    // 2026-08-26 用户规格：电动机绕组主电阻固定 25.6Ω（不读线圈）
                    double windingR = gen ? dw.resistance : 25.6;
                    com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel eng =
                            new com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel(1.0);
                    com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                            DeviceThermalStore.thermalFor(be.getBlockPos());
                    ElectroMachineModel em;
                    switch (mtype) {
                        case 2: // 恒速电机
                            em = new ConstantSpeedMotorModel(a, b, x, windingR,
                                    Math.max(dw.inductance, 0), th, eng, false, 0);
                            break;
                        case 3: // 伺服电机
                            em = new ServoMotorModel(a, b, x, windingR,
                                    Math.max(dw.inductance, 0), th, eng, false, 0);
                            break;
                        case 1: // 发电机
                            em = new ElectroMachineModel(a, b, x, windingR,
                                    Math.max(dw.inductance, 0), th, eng, true,
                                    readEmf(be));
                            break;
                        default: // 普通电机（默认）= 单相 AC 感应电机（2026-08-28）
                            em = new InductionMotorModel(a, b, x, windingR,
                                    Math.max(dw.inductance, 0), th, eng, false, 0);
                    }
                    if (gen) em.emfConstant = EMF_K;
                    final net.minecraft.core.BlockPos mpos = be.getBlockPos();
                    em.bindShaftListener((radS, rpm) -> EngineBus.post(
                            EngineBus.Type.ROTOR_SPEED, mpos, null, em,
                            new double[]{radS, rpm}));
                    return em;
                }
                return null;
            }
            // Commutator：电枢电阻（反电动势相量近似为阻抗，L≈0）
            Object r = DeviceWire.field(be, "resistance");
            if (r instanceof Number n && n.doubleValue() > 0) {
                int x = net.addNode().id;
                return new ElectroMachineModel(a, b, x, n.doubleValue(), 0,
                        DeviceThermalStore.thermalFor(be.getBlockPos()),
                        new EnergyModel(1.0), false, 0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 发电机 EMF 刷新源（每轮）：EMF = K_EMF × 转子角速度（rad/s）
     *  2026-08-15 机电引擎化：EMF 由引擎侧虚拟转速计算（ElectroMachineModel.
     *  advanceShaft，EMF = K_EMF·ω），不再读 BE rotorBehaviour → 本方法空实现
     *  （保留签名兼容；消除主线程读 BE 依赖 = 组装器缓存化关键）。 */
    @Override
    public void registerParams(BlockPos pos, CompositeModel cm,
                                        List<PhasorNetworkContext.ParamSource> paramSources) {
        // 2026-08-15：EMF 引擎侧自治，无需注册 BE 刷新源
    }

    /** 读发电机 EMF：转子角速度（rad/s）× EMF_K（磁场简化恒磁，魔法数字） */
    private static double readEmf(BlockEntity be) {
        try {
            Object rb = DeviceWire.field(be, "rotorBehaviour");
            if (rb != null) {
                Object ang = rb.getClass().getMethod("getAngularVelocity").invoke(rb);
                if (ang instanceof Number n) {
                    return n.doubleValue() * EMF_K;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 电机建模诊断节流（2026-08-12） */
    private static volatile long motorDbgLast;

    /**
     * 电机类型码（2026-08-27 用户：模型拆三份）。按 BE 类名判断：
     * <pre>
     *   0 = 普通电机（InductionMotorModel 单相AC感应，默认；2026-08-28）
     *   1 = 发电机（基类 ElectroMachineModel + generatorMode）
     *   2 = 恒速电机（ConstantSpeedMotorModel）
     *   3 = 伺服电机（ServoMotorModel）
     * </pre>
     */
    private static int motorTypeOf(BlockEntity be) {
        String cn = (be == null) ? "" : be.getClass().getSimpleName();
        if (cn.contains("Generator")) return 1;
        if (cn.contains("ConstantSpeed")) return 2;
        if (cn.contains("Servo")) return 3;
        return 0;
    }
}
