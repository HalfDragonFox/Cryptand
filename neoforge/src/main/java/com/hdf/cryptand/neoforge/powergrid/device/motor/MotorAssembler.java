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
import com.hdf.cryptand.circuitsimulation.model.composite.*;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.neoforge.CryptandNeoForge;

import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.engine.EngineBus;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import com.hdf.cryptand.neoforge.powergrid.motor.singlephase.SinglePhaseAsyncMotorBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.state.MotorStateStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class MotorAssembler implements SourceCacheAssembler {

    public static final MotorAssembler INSTANCE = new MotorAssembler();

    /* ===== 电机族温度模型参数（2026-09-13 用户："温度模型需要精确到具体功能，
     *  比如电机，这样的话可以对电机一类都进行统一的温度计算"）=====
     * 交给 MotorThermalModel 的族级统一损耗公式：
     *   P_loss = I²R（铜损）+ k_fe·|ω|^1.5（铁损）+ k_fric·ω²（摩擦/风阻）
     * 系数由额定工况反推（额定 500W / 523.6 rad/s）：】
     */
    // 2026-09-13：系数统一放到 DeviceThermalStore（放置即建模型也要用同一组值，
    //  两处各写一份会漂移）——这里只做转发：
    /** 铁损系数（W/(rad/s)^1.5）：≈额定功率 5% @ 额定转速 */
    private static final double MOTOR_IRON_LOSS_K = DeviceThermalStore.MOTOR_IRON_LOSS_K;
    /** 摩擦/风阻系数（W/(rad/s)²）：≈额定功率 3% @ 额定转速 */
    private static final double MOTOR_FRICTION_LOSS_K = DeviceThermalStore.MOTOR_FRICTION_LOSS_K;
    /** 电机绕组电阻（Ω，与原版 resistance() 一致，铜损 I²R 用） */
    private static final double MOTOR_WINDING_R = DeviceThermalStore.MOTOR_WINDING_R;

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
            // 2026-09-15 去引用：原 BE_REF（pos → BlockEntity）已删除。
            //  组装器不再持有 BE 引用 —— 消息只带 pos + 纯数据，主线程按 pos 自己取 BE。
            // 诊断（节流 5s）：确认 R 是否读成功（10MΩ=GMIN 兜底排查）
            long now = System.currentTimeMillis();
            if (now - cacheDbgLast >= 5000) {
                cacheDbgLast = now;
                CryptandNeoForge.WAF_LOGGER.info(
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

    /* ===== 持久化信息（2026-09-15 用户："保存时需要组装器记住绑定的数据的相关
     *  信息……数值可以是通用变量，K 为保存字符串，V 为通用变量。"）=====
     *
     * 电机重建【必须】知道三件事：
     *   ① 节点 id —— a/b 是端子、x 是绕组内部节点。恢复时解码器按存档重建了
     *      同样数量的节点，所以这些 id 在新 Network 里仍然指向正确节点；
     *      光靠展开元件也能推 a/b，但 x 推不出来 ⇒ 必须保存。
     *   ② 规格（额定电压/最高转速/惯量…）—— 由 restoreFromInfo 里重新 setSpecs，
     *      不必保存（与温度模型一样：参数只在首次创建时生效）。
     *   ③ 运行状态（转速/EMF/应力）—— 不保存就会"重进世界电机从 0 重爬"，
     *      这正是用户明确抱怨过的现象。
     */

    @Override
    public java.util.Map<String, Object> persistInfo(net.minecraft.core.BlockPos pos,
            com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce) {
        try {
            if (!(ce instanceof com.hdf.cryptand.circuitsimulation.model.composite
                    .ElectroMachineModel em)) {
                return null;
            }
            java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
            kv.put("a", em.a);
            kv.put("b", em.b);
            kv.put("x", em.x);
            kv.put("radS", em.rotorSpeedRadS);   // 运行状态：转速（rad/s）
            kv.put("emf", em.lastEmfV);          // 反电动势（V）
            kv.put("stress", em.lastStressSU);   // 应力（SU）
            return kv;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement
            restoreFromInfo(net.minecraft.core.BlockPos pos, String compositeKey,
                            String className,
                            com.hdf.cryptand.circuitsimulation.model.Element[] expanded,
                            java.util.Map<String, Object> info) {
        if (pos == null) return null;
        try {
            int a = kvInt(info, "a", Integer.MIN_VALUE);
            int b = kvInt(info, "b", Integer.MIN_VALUE);
            int x = kvInt(info, "x", Integer.MIN_VALUE);
            if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE) {
                // 兜底：KV 缺失时从展开元件推主线两端（第一个元件即绕组主线）
                if (expanded != null) {
                    for (com.hdf.cryptand.circuitsimulation.model.Element e : expanded) {
                        if (e == null) continue;
                        if (a == Integer.MIN_VALUE) a = e.nodeA();
                        else if (b == Integer.MIN_VALUE) { b = e.nodeB(); break; }
                    }
                }
            }
            if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE) return null;
            if (x == Integer.MIN_VALUE) x = -1;
            // 参数从主线程同步下来的参数缓存读（引擎线程绝不碰 Level/BE）
            double r = MOTOR_WINDING_R;
            double l = 0;
            try {
                com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.Entry de =
                        com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.get(pos);
                if (de != null) {
                    if (de.resistance > 0) r = de.resistance;
                    l = Math.max(de.inductance, 0);
                }
            } catch (Throwable ignored) {
            }
            var em = vanillaMotorModel(pos, a, b, x, r, l);
            if (em == null) return null;
            // 运行状态回填（vanillaMotorModel 内部已做过 MotorStateStore.restore，
            //  这里再按本次保存的 KV 覆盖一次，保证与退出世界那一刻一致）
            try {
                double radS = kvDouble(info, "radS", Double.NaN);
                if (!Double.isNaN(radS)) {
                    em.restoreMotorState(radS, kvDouble(info, "emf", 0.0));
                }
                double st = kvDouble(info, "stress", Double.NaN);
                if (!Double.isNaN(st)) em.restoreStress(st);
            } catch (Throwable ignored) {
            }
            return em;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int kvInt(java.util.Map<String, Object> kv, String k, int def) {
        try {
            Object v = kv == null ? null : kv.get(k);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static double kvDouble(java.util.Map<String, Object> kv, String k,
                                   double def) {
        try {
            Object v = kv == null ? null : kv.get(k);
            if (v instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {
        }
        return def;
    }

    /* ===== 原版三电机的机械侧参数（2026-09-13）=====
     * torque() / capacity 都需要 Block/Level（引擎线程绝不能碰）——
     * 按用户要求"所有需要依赖主线程的参数全部采用下发更新的方式"，
     * 统一走 {@link com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache}
     * 的电机参数槽（主线程 putMotor 写纯值，后台 motorParams 只读，不碰 BE）。 */

    /* ===== BE 引用表（2026-09-13 用户："组装器绑定时直接绑定对应 BE 类引用"、
     *  "直接传递 BE 类引用作为消息参数传递到主线程然后使用"）=====
     * 主线程 refreshCache 写入真实 BE 引用 → 后台 assembleFromCache 取出绑到模型
     *  → listener 发 ROTOR_SPEED 时作为消息参数带上 → 主线程【直接用该引用】
     * （免去 level.getBlockEntity(pos) 查找；失效时仍回退按 pos 查找）。
     * ⚠ 引擎线程只持有引用、绝不调用其方法（铁律：引擎不碰 Level/BE）。 */
    /**
     * 转速消息载荷（2026-09-15 去引用）—— 把主线程应用转速所需的【全部模型状态】
     * 在引擎侧算成纯数据。消息里不再携带任何对象引用。
     * <p>
     * 载荷布局 = {@code double[]{radS, rpm, stressSU, powerW, emfV, tempC}}：
     * <ul>
     *   <li>radS / rpm —— 应用转速（Create 网络）</li>
     *   <li>stressSU   —— 应力（护目镜）</li>
     *   <li>powerW     —— 输出功率（万用表/护目镜）</li>
     *   <li>emfV       —— |emfConstant|·|ω|（反电动势读数）</li>
     *   <li>tempC      —— 绕组温度（温度表/过热判定）</li>
     * </ul>
     * ⚠ 每个字段单独 try/catch：消息通道绝不能因为某个字段读失败而整条断掉
     *（断了就是"电机不转/应力恒 0"这类静默故障）。
     */
    static double[] motorMsg(
            com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel em,
            double radS, double rpm) {
        double stress = 0, powerW = 0, emfV = 0, tempC = 25;
        try { stress = em.lastStressSU; } catch (Throwable ignored) { }
        try { powerW = em.lastOutputPowerW; } catch (Throwable ignored) { }
        try { emfV = Math.abs(em.emfConstant) * Math.abs(radS); } catch (Throwable ignored) { }
        try {
            if (em.thermal() != null) tempC = em.thermal().tempCelsius();
        } catch (Throwable ignored) { }
        return new double[]{radS, rpm, stress, powerW, emfV, tempC};
    }



    /**
     * 原版三电机模型（电气=纯电阻；机械=原版公式；每轮上报转速）。
     *
     * 用户架构："每次异步线程计算完成转速后发送消息更新到主线程" ——
     * 引擎每轮求解后经 lossPower 钩子算出 rpm → EngineBus.post(ROTOR_SPEED)
     * → 主线程 BE 应用（写 generatedSpeed/avgSpeed + Create 同步）。
     * 主线程 BE 不再自己算转速（纯应用消息）。
     */
    /**
     * 原版三电机（普通/恒速/伺服）的电气侧模型 —— 2026-09-13 用户："那就把原版 3 种
     * 电机按照普通 DC 电机来做"。
     *
     * 即：不再用"纯电阻 + 原版 V²/R 公式"（`VanillaMotorModel`），改用现成的
     * {@link com.hdf.cryptand.circuitsimulation.model.composite.DcBrushedMotorModel}
     * —— 完整的 DC 物理公式链（全部在引擎侧算，主线程只应用消息）：
     *
     *     EMF   = K_E·ω                （K_E = 230V / 5000RPM 对应角速度）
     *     I     = (V_ab − EMF)/R       （供电自动涌现；断电 I=0）
     *     T_em  = K_T·I                （K_T = K_E）
     *     J·dω/dt = T_em − T_load − b·ω（惯性 0.02、摩擦 0.0008）
     *     P     = min(500W, σ·ω)       （小功率封顶）
     *     断电   : 一阶指数制动 τ=0.3s（用户此前要求的"瞬停"）
     *
     * 转速事件 → `EngineBus.post(ROTOR_SPEED, pos, beRef, model, {radS, rpm, σ})`
     * （用户："应力和转速都通过每次计算发送"；σ 取模型 `lastStressSU`）。
     */
    private static com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel
            vanillaMotorModel(net.minecraft.core.BlockPos pos, int a, int b, int x,
                              double r, double inductance) {
        com.hdf.cryptand.circuitsimulation.model.composite.DcBrushedMotorModel em =
                new com.hdf.cryptand.circuitsimulation.model.composite.DcBrushedMotorModel(
                        a, b, x, r, Math.max(inductance, 0),
                        DeviceThermalStore.thermalForMotor(pos, MOTOR_WINDING_R, MOTOR_IRON_LOSS_K, MOTOR_FRICTION_LOSS_K),
                        new com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel(1.0),
                        false, 0);
        // ⚠⚠ 2026-09-13 实测修复（用户："电机还会来回跳"）：
        //   DcBrushedMotorModel 的构造把规格设成【额定 5000 RPM】（那是给自研 DC 电机
        //   的规格），而【原版三电机是 256 RPM】—— 日志实证：
        //     [MotorDrive] R=25.6000 torque=960.00 I=0.790 msgW=15.962 rpm=4378
        //     [MotorState] save radS=450.3 rpm=4300.2 emf=197.8 stress=14091
        //   转速跑到 4378 RPM、应力逼近 16384 上限 ⇒ Create 网络过载 ⇒ 转速来回跳。
        //   这里按【原版规格】重设：额定/最高转速 = PowerGrid.maxRPM()（默认 256），
        //   额定电压 230、最高 258、最大应力 16384 —— 与原版电机一致。
        try {
            int maxRpm = org.patryk3211.powergrid.PowerGrid.maxRPM();
            if (maxRpm > 0) {
                em.setSpecs(230.0, 258.0, 16384.0, maxRpm, false, 0);
            }
        } catch (Throwable ignored) {
        }
        // ===== 2026-09-13 用户："放下任意一个电气设备……所有电机会从 0 重新开始" =====
        // 根因：本分支（vanillaMotor）在 assembleFromCache 里【提前 return】——
        // 绕过了自研电机分支里的 `MotorStateStore.restore(pos, em)`。于是每次网络
        // 重建都新建一个 ω=0 的模型 ⇒ 转速归零重爬（日志实证：只有 [MotorState]
        // save，从未出现 restore）。这里补上恢复，跨重建保持转速/EMF/应力。
        // ⚠ 必须在 setSpecs 之后：restore 的范围校验用 em.ratedRadS（3 倍上限），
        //   规格没定之前校验基准是 DcBrushed 的 5000RPM，会放进不该放的状态。
        try {
            com.hdf.cryptand.neoforge.powergrid.state.MotorStateStore
                    .restore(pos, em);
        } catch (Throwable ignored) {
        }
        final net.minecraft.core.BlockPos mp = pos == null ? null : pos.immutable();
        em.bindShaftListener((radS, rpm) -> EngineBus.post(
                EngineBus.Type.ROTOR_SPEED, mp, motorMsg(em, radS, rpm)));
        return em;
    }

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
                CryptandNeoForge.WAF_LOGGER.info(
                        "[MotorAsm] pos={} R={} L={} a={} b={} nodes={}{}",
                        pos, String.format("%.4f", r), String.format("%.4f", d.inductance),
                        a, b, net.nodeCount(), as);
            }
            // 2026-09-12 用户："原版电机按照普通电机来搭建，逻辑对齐原版"——
            // 原版三电机（普通/恒速/伺服）引擎侧 = 固定电阻（不建内部节点、不建 EMF 模型）
            int mtype0 = (int) Math.round(d.amplitude);
            if (r > 0 && vanillaMotor(mtype0)) {
                // 电气侧 = 普通 DC 电机（EMF 链需要内部节点 x）
                int vx = net.addNode().id;
                return vanillaMotorModel(pos, a, b, vx, r, d.inductance);
            }
            if (r > 0) {
                int x = net.addNode().id;
                // 2026-08-27 分类型创建模型（amplitude 复用为类型码）
                int mtype = (int) Math.round(d.amplitude);
                boolean gen = mtype == 1; // 发电机标志（类型码 1）
                com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel eng =
                        new com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel(1.0);
                com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                        DeviceThermalStore.thermalForMotor(pos, MOTOR_WINDING_R, MOTOR_IRON_LOSS_K, MOTOR_FRICTION_LOSS_K);
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
                    case 12: case 14: case 16: case 18: // 单相异步电机（2/4/6/8 极，2kW，2026-08-30）
                        em = new InductionMotorModel(mtype - 10, a, b, x, r,
                                d.inductance, th, eng, false, 0);
                        break;
                    default: // 原版普通电机 = 仿真 DC 直流电机（500W/5000RPM/瞬开瞬停）
                        em = new DcBrushedMotorModel(a, b, x, r, d.inductance,
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
                    MotorStateStore
                            .restore(pos, em);
                } catch (Throwable ignored) {
                }
                // 转速事件 → EngineBus 消息（纯数据，主线程按 pos 取 BE）；
                // 2026-09-15 去引用：不再携带 BE/模型对象。
                em.bindShaftListener((radS, rpm) -> EngineBus.post(
                        EngineBus.Type.ROTOR_SPEED, pos, motorMsg(em, radS, rpm)));
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
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[MotorBuild] pos={} coilClass={} R={} L={} a={} b={}",
                            be.getBlockPos(), w.getClass().getSimpleName(),
                            String.format("%.4f", dw.resistance),
                            String.format("%.4f", dw.inductance), a, b);
                }
                // 2026-09-12 用户："原版电机按照普通电机来搭建，逻辑对齐原版"——
                // 原版三电机（普通/恒速/伺服）= 固定绕组电阻（25.6Ω，原版 resistance()），
                // 不建内部节点、不建 EMF/惯性模型（EMF 只留自研电机）
                if (dw.hasResistance() && dw.resistance > 0
                        && vanillaMotor(motorTypeOf(be))) {
                    int vx2 = net.addNode().id;
                    return vanillaMotorModel(be.getBlockPos(), a, b, vx2, 25.6,
                            Math.max(dw.inductance, 0));
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
                            DeviceThermalStore.thermalForMotor(be.getBlockPos(), MOTOR_WINDING_R, MOTOR_IRON_LOSS_K, MOTOR_FRICTION_LOSS_K);
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
                        case 12: case 14: case 16: case 18: // 单相异步电机（2/4/6/8 极，2kW，2026-08-30）
                            em = new InductionMotorModel(mtype - 10, a, b, x,
                                    windingR, Math.max(dw.inductance, 0), th, eng, false, 0);
                            break;
                        default: // 原版普通电机 = 仿真 DC 直流电机（500W/5000RPM/瞬开瞬停）
                            em = new DcBrushedMotorModel(a, b, x, windingR,
                                    Math.max(dw.inductance, 0), th, eng, false, 0);
                    }
                    if (gen) em.emfConstant = EMF_K;
                    final net.minecraft.core.BlockPos mpos = be.getBlockPos();
                    em.bindShaftListener((radS, rpm) -> EngineBus.post(
                            EngineBus.Type.ROTOR_SPEED, mpos, motorMsg(em, radS, rpm)));
                    return em;
                }
                return null;
            }
            // Commutator：电枢电阻（反电动势相量近似为阻抗，L≈0）
            Object r = DeviceWire.field(be, "resistance");
            if (r instanceof Number n && n.doubleValue() > 0) {
                int x = net.addNode().id;
                return new ElectroMachineModel(a, b, x, n.doubleValue(), 0,
                        DeviceThermalStore.thermalForMotor(be.getBlockPos(), MOTOR_WINDING_R, MOTOR_IRON_LOSS_K, MOTOR_FRICTION_LOSS_K),
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

    /**
     * 原版三种电机（普通 mtype=0 / 恒速 2 / 伺服 3）——引擎侧按【普通电机】搭建。
     *
     * <p>2026-09-12 用户："电机所有 emf 之类的由我们定义的额外的电机实现，原版电机
     * 按照普通电机来搭建，逻辑对齐原版"：原版电机的电气侧就是一根固定电阻
     * （原版 {@code buildCircuit}: {@code coil = builder.connect(resistance(), …)}），
     * 没有任何 EMF/惯性/负载反馈 → 引擎侧同样只建 {@code ResistorModel}（固定电阻 + 温度）。
     * EMF/惯性/反馈那套只保留给【自研电机】：发电机（1）与单相异步电机（12/14/16/18）。
     */
    private static boolean vanillaMotor(int mtype) {
        return mtype == 0 || mtype == 2 || mtype == 3;
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
     * 电机类型码（2026-08-27 用户：模型拆三份；2026-08-29 加电容启动）。按 BE 类名判断：
     * <pre>
     *   0 = 原版普通电机（ElectricMotorBlockEntity）→ DcBrushedMotorModel 仿真 DC 直流
     *       （用户 2026-08-29：功率小 500W / 最高 5000RPM / 瞬开瞬停）
     *   1 = 发电机（基类 ElectroMachineModel + generatorMode）
     *   2 = 恒速电机（ConstantSpeedMotorModel）
     *   3 = 伺服电机（ServoMotorModel）
     *   4 = 电容启动式单相异步电机（CapacitorStartMotorBlockEntity）→ InductionMotorModel
     *       （原普通电机的感应/电容启动逻辑全部转到这里，2026-08-29）
     * </pre>
     */
    private static int motorTypeOf(BlockEntity be) {
        // ⚠ 2026-08-30 审计 C5 根因：精确 instanceof 判断替代 contains 子串——
        // 子串匹配会误伤第三方 mod 扩展电机类（类名含 Generator/Servo/
        // ConstantSpeed/CapacitorStart 等被误分发到错误模型，且顺序敏感）。
        // 官方类用 PowerGrid FQN（与 BeMessageParser.of 一致）；电容启动机用
        // 本 mod 类。CapacitorStart 是 ElectricMotorBlockEntity 子类，故须在
        // default 前（它不命中前三个 instanceof，安全）。
        if (be == null) return 0;
        if (be instanceof org.patryk3211.powergrid.kinetics.base.GeneratorBlockEntity) return 1;
        if (be instanceof org.patryk3211.powergrid.kinetics.motor.ConstantSpeedMotorBlockEntity) return 2;
        if (be instanceof org.patryk3211.powergrid.kinetics.servo.ServoBlockEntity) return 3;
        // ⚠ 2026-08-30 单相异步电机（2/4/6/8 极）：10+poles（12/14/16/18）→ InductionMotorModel(poles)
        if (be instanceof com.hdf.cryptand.neoforge.powergrid.motor.singlephase.SinglePhaseAsyncMotorBlockEntity sme) {
            return 10 + sme.poles();
        }
        return 0;
    }
}
