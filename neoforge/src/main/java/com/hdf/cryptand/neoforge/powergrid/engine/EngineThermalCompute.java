package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.circuitsimulation.compute.*;
import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.*;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.network.DestructionQueue;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireSegments;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCurrent;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceVoltageStore;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerHeatStore;
import com.hdf.cryptand.neoforge.powergrid.state.WireThermalStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;
import java.util.Map;

/**
 * ===== 求解后计算：发热 / 储能 / 降温 =====
 *
 * <p>从 {@code PhasorEngine} 拆出。本类只做一件事——<b>把一轮求解结果折算成
 * 世界侧的物理状态量</b>：变压器铜损铁损、设备 I²R 发热、导线段发热与烧毁、
 * 电容/电感储能推进、全量降温。
 *
 * <p>四个 {@code compute*Unified} 是同一模式的四个实例：一轮 round 会携带多个
 * 网络上下文（变压器把原/副边隔成两张网、跨导线也一样），同一台设备因此会在
 * 多个 ctx 里各出现一次。统一入口先按「设备 key → 最高频率的那份 ctx」去重，
 * 再逐台推进一次——保证「计算可以在任意一个网络里做，但只能做一次」。
 *
 * <p>这些方法在<b>后台调度线程</b>的 round 内执行（{@code level == null} 即纯
 * 虚拟模式：不碰 BE、只算数据）；只有降温 {@code coolAllTemperatures} 由主线程
 * 每 tick 驱动（纯静态存储，不读世界）。线程安全依赖各 Store 自身的并发容器。
 */
public final class EngineThermalCompute {

    private EngineThermalCompute() {}

    /**
     * 求解后：按【真实变压器算法】计算损耗 → TransformerHeatStore。
     * 模型（互感建模，2026-08-11）：
     *   原边 pa1 --rCp-- x(a1) --(rCore ∥ 互感绕组1)-- pa2，
     *   副边 b1(w) --rCs-- pb1，互感绕组2 (w, pb2)（MutualInductor 对称耦合）：
     *   - 初级总电流 I_p = (Vpa1 - Vx) / rCp（相量，互感耦合已由 MutualInductor
     *     的 Y 矩阵体现，铜阻电流即绕组总电流：磁化+负载反射）
     *   - 铁损电流 I_core = (Vx - Vpa2) / rCore——铁损 P = |I_core|²·rCore/2
     *   - 次级电流 I_s = (Vw - Vpb1) / rCs（副边铜阻电流）
     *   - 铜损 P_cu = |I_p|²·rCp/2 + |I_s|²·rCs/2；峰值→平均 /2
     * 次级悬空 → I_s≈0 → 铜损≈0 → 不发热/无声（解决"只有一根线也有声音"）。
     */
    /** 统一变压器发热处理（round 级，2026-08-12）：变压器【隔开】原边/副边网络，
     *  同一 pos 会在多个网络 ctx 的 transformerModels 出现。每 tick 每个 pos 只选
     *  【最高频率】网络处理一次（AC 优先；全 DC → 清状态 + 自然冷却）——
     *  满足"计算/发热放在两个网络的任意一处，但只能有一处"。 */
    static void computeTransformerHeatUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<BlockPos, Object[]> best = new java.util.HashMap<>();
        NetSweep.sweep(nets, (ctx, res, freq, batchHash) -> {
            if (ctx.transformerModels.isEmpty()) return;
            for (BlockPos pos : ctx.transformerModels.keySet()) {
                Object[] cur = best.get(pos);
                if (cur == null || (Double) cur[2] < freq) {
                    best.put(pos, new Object[]{ctx, res, freq});
                }
            }
        });
        for (java.util.Map.Entry<BlockPos, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            try {
                computeTransformerHeatOne(level, (PhasorNetworkContext) o[0],
                        (SolveResult) o[1], (Double) o[2], e.getKey());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 单变压器发热处理（每 tick 每 pos 一次） */
    private static void computeTransformerHeatOne(Level level, PhasorNetworkContext ctx,
            SolveResult res, double frequency, BlockPos pos) {
        if (ctx == null || res == null) return;
        double minHz = ConfigPowerGrid.TRANSFORMER_MIN_FREQUENCY_HZ.get();
        // 直流（freq=0）或低频（< 最低通过频率）→ DC 隔离：清状态（无声/停热）+ 自然冷却
        if (res.complex == null || frequency < minHz) {
            TransformerHeatStore.resetElectrical(pos);
            com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel thc =
                    TransformerHeatStore.getThermal(pos);
            if (thc != null) thc.advanceStep(0, ctx.stepDt()); // 统一仿真步长（倍率）
            return;
        }
        PhasorNetworkContext.TransformerModel m = ctx.transformerModels.get(pos);
        if (m == null) return;
        int n = res.complex.length;
                if (m.pa1 < 0 || m.x < 0 || m.pa2 < 0 || m.w < 0 || m.pb1 < 0 || m.pb2 < 0
                        || m.pa1 >= n || m.x >= n || m.pa2 >= n
                        || m.w >= n || m.pb1 >= n || m.pb2 >= n) {
                    // 副边支路被剔除（次级端子悬空/无回路）→ 次级电流=0：
                    // 全 0 put → STATE.remove → 次级电流立即归 0 → 声音停止、发热停止。
                    // （否则 STATE 残留旧 iS/iP → "断开负载仍嗡鸣"）
                    TransformerHeatStore.put(pos, 0, 0, 0, 0, 0);
                    // 温度以 0 功率自然冷却（保留热惯性，不冻结不突变）
                    com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th0 =
                            TransformerHeatStore.getThermal(pos);
                    if (th0 != null) th0.advanceStep(0, ctx.stepDt()); // 统一仿真步长
                    return;
                }
                double omega = 2 * Math.PI * frequency;
                // 原边支路电流：pa1 → x 经 (rCp + jω·lLp) 串联阻抗（真实铜阻+漏感）
                Complex zP = new Complex(m.rCp, omega * m.lLp);
                Complex vP = res.complex[m.pa1].sub(res.complex[m.x]);
                Complex iP = zP.abs() < 1e-12 ? Complex.ZERO : vP.div(zP);
                // 铁损电流：x → pa2 经 rCore（励磁支路）
                Complex iCore = res.complex[m.x].sub(res.complex[m.pa2]).scale(1.0 / m.rCore);
                // 副边支路电流：w → pb1 经 (rCs + jω·lLs) 串联阻抗。
                Complex zS = new Complex(m.rCs, omega * m.lLs);
                Complex vS = res.complex[m.w].sub(res.complex[m.pb1]);
                Complex iS = zS.abs() < 1e-12 ? Complex.ZERO : vS.div(zS);
                // ⚠ 副边电流物理校验（2026-08-12）：副边开路/悬空时 pb1 是浮动节点
                // （无闭合回路），求解器给浮动电压 → (Vw−Vpb1)/zS 算出虚假巨大 iS
                // （日志：iP=0.02A 但 iS=533A → 铜损 71106W → 空载瞬间爆炸）。
                // 物理：理想变压器 I2 = I1/ratio（反射）。若 |iS| 远超反射值（8 倍，
                // 留短路余量）→ 无真实负载电流 → 置 0（开路不发热/不响）。
                double maxIS = Math.max(iP.abs() / Math.max(m.ratio, 1e-6) * 8.0, 0.05);
                if (iS.abs() > maxIS) iS = Complex.ZERO;
                // 诊断（节流）：副边电流来源（各端子电压/压差）——定位"接开路导线
                // 到绘图仪后电机误转"：副边端子是否意外有压差（形成电流路径）。
                // ⚠ 2026-09-13 用户："所有需要依赖主线程的参数全部采用下发更新的方式"
                //   —— 原实现是后台线程 level.getGameTime() % 40（访问 Level = 违反
                //   "引擎禁止访问 Level/BE"）。诊断节流改用 AdapterDiag 的墙钟门控，
                //   与 PhasorEngine 里的 [AdvHeat] 等诊断完全一致。
                if (com.hdf.cryptand.neoforge.powergrid.engine.AdapterDiag
                        .gate("TfDiag", 2000)) {
                    try {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[TfDiag] pos={} freq={} ratio={} pa1={} x={} pa2={} w={} pb1={} pb2={} "
                                        + "vpa1={} vx={} vpa2={} vw={} vpb1={} vpb2={} iP={} iS={} maxIS={}",
                                pos, String.format("%.1f", frequency), String.format("%.2f", m.ratio),
                                m.pa1, m.x, m.pa2, m.w, m.pb1, m.pb2,
                                tfb(res.complex, m.pa1), tfb(res.complex, m.x),
                                tfb(res.complex, m.pa2), tfb(res.complex, m.w),
                                tfb(res.complex, m.pb1), tfb(res.complex, m.pb2),
                                tfb2(iP), tfb2(iS), String.format("%.3f", maxIS));
                    } catch (Throwable ignored) {
                    }
                }
                // 真实铜损（原边+副边）+ 铁损；峰值 → 平均功率 /2
                double cuP = iP.abs() * iP.abs() * m.rCp / 2.0;
                double cuS = iS.abs() * iS.abs() * m.rCs / 2.0;
                double coreLoss = iCore.abs() * iCore.abs() * m.rCore / 2.0;
                TransformerHeatStore.put(pos, cuP + cuS, coreLoss, iP.abs(), iS.abs(), frequency);
                // 温度模型（静态持久：跨网络重建保留温度与冷却）。
                // 2026-08-20 用户要求"移除变压器的单独支持，改成通用支持"：
                // 冷却倍率不再在此单独反射同步——统一由 FanCoolingMixin 在吹风
                // 检测时直接设置（TransformerHeatStore 温度模型，与普通设备一致）。
                com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                        TransformerHeatStore.thermalFor(pos);
                // 温度推进用【瞬时功率】：平滑统一由 ThermalModel 内部 EMA 处理
                // （避免双重平滑响应过慢）；解析解 + 内部平滑 → 温度稳定趋稳。
                th.advanceStep(cuP + cuS + coreLoss, ctx.stepDt()); // 统一仿真步长（倍率）
                // 诊断：节流打印变压器温度曲线（服务端）
                if (com.hdf.cryptand.neoforge.powergrid.engine.AdapterDiag
                        .gate("TfHeat", 2500)) { // 同上：墙钟节流，不读 Level
                    try {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[TfHeat] pos={} P={}+{} T={}°C G={}",
                                pos,
                                String.format("%.1f", cuP + cuS),
                                String.format("%.1f", coreLoss),
                                String.format("%.1f", th.tempCelsius()),
                                String.format("%.2f", th.effectiveConductance()));
                    } catch (Throwable ignored) {
                    }
                }
    }

    /** 相量幅值格式化（诊断用） */
    private static String tfb(com.hdf.cryptand.circuitsimulation.solver.Complex[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return "-";
        try {
            return String.format("%.1f", arr[idx].abs());
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /** 相量幅值格式化（诊断用） */
    private static String tfb2(com.hdf.cryptand.circuitsimulation.solver.Complex c) {
        return c == null ? "-" : String.format("%.3f", c.abs());
    }

    /**
     * 求解后：按设备复合模型（ThermalDevice）算平均损耗 → 推进温度模型
     * （DeviceThermalStore 持久）。电机/加热器/电磁铁/灯具等所有带温度设备：
     * 损耗由模型 {@code lossPower} 提供（如绕组铜耗 I²·R/2），
     * 风扇冷却检测提高散热系数（与变压器一致）。
     */
    static void computeDeviceHeatUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<BlockPos, Object[]> best = new java.util.HashMap<>();
        NetSweep.sweep(nets, (ctx, res, freq, batchHash) -> {
            // 2026-08-22 供电/亮度：对所有参与求解的设备（含无发热设备如灯座）写
            // 设备电流缓存——先前只写 deviceThermals（发热设备），灯座 SwitchModel
            // 非 ThermalDevice → 电流恒 0 → 灯泡不亮/不烧。
            try {
                if (ctx.blockTerminals != null && ctx.network != null) {
                    for (BlockPos p : ctx.blockTerminals.keySet()) {
                        DeviceCurrent.write(p, ctx.network, res);
                        // 2026-09-12：同一轮求解顺带记录【各端子对地电压】——伺服
                        // 接管层要用控制线电压（端子 2−1）复现原版角度算法
                        DeviceVoltageStore.write(p, res);
                    }
                }
            } catch (Throwable ignored) {
            }
            if (ctx.deviceThermals == null || ctx.deviceThermals.isEmpty()) return;
            for (BlockPos pos : ctx.deviceThermals.keySet()) {
                Object[] cur = best.get(pos);
                if (cur == null || (Double) cur[2] < freq) {
                    best.put(pos, new Object[]{ctx, res, freq});
                }
            }
        });
        for (java.util.Map.Entry<BlockPos, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            PhasorNetworkContext bctx = (PhasorNetworkContext) o[0];
            SolveResult bres = (SolveResult) o[1];
            try {
                computeDeviceHeatOne(level, bctx, bres, (Double) o[2], e.getKey());
                // 2026-08-22 用户需求【供电判断走电流】：求解 round 计算设备端子
                // 电流（内部元件电流）写入组装器 DeviceCache，主线程 tick 检测。
                DeviceCurrent.write(e.getKey(), bctx.network, bres);
            } catch (Throwable ignored) {
            }
        }
    }

    /** DC 超频温度惩罚基准功率（W）：惩罚 = (freq/maxFreq)³ − 1 × 本基准。
     *  轻微超频(≈1) → 小惩罚；频率越高指数越大（2 倍超频 → 8×基准）。 */
    private static final double DC_PENALTY_BASE_W = 2000.0;

    /** 单设备发热处理（每 tick 每 pos 一次）：温度推进由后台 solveAll 的伪时域
     *  推进（advanceState，固定节拍步长 dt）完成。
     *  2026-08-21 用户要求【全部接管原版算法，不写回原版内容】：不再反射写回原版
     *  ThermalBehaviour（护目镜/方块温度计等读取方统一由 Cryptand 接管，直接读
     *  DeviceThermalStore/WireThermalStore）。本方法只注入【DC 超频惩罚】，不写回原版。
     *  <p>2026-08-22 用户需求：原版 DC 设备（灯/风扇/铃/加热器等）只支持 DC——
     *  网络频率超过 dcDeviceMaxFrequencyHz 时温度【指数型】惩罚：轻微超频不明显、
     *  频率越高越严重（按超额倍数 o³ 放大）。0 = 关闭惩罚。 */
    private static void computeDeviceHeatOne(Level level, PhasorNetworkContext ctx,
            SolveResult res, double frequency, BlockPos pos) {
        try {
            if (!DeviceThermalStore.isDcOnly(pos)) return;
            double maxF = ConfigPowerGrid.DC_DEVICE_MAX_FREQUENCY_HZ.get();
            if (maxF <= 0 || frequency <= maxF) return; // 未超频/关闭 → 无惩罚
            double over = frequency / maxF;          // >1：超频倍数
            double factor = Math.pow(over, 3);       // 指数放大（2x 超频 → 8）
            double penaltyW = (factor - 1) * DC_PENALTY_BASE_W;
            // J/次推进（tick≈0.05s）；温度推进仍由引擎伪时域完成
            DeviceThermalStore.thermalFor(pos).addHeat(penaltyW * 0.05);
        } catch (Throwable ignored) {
        }
    }

    /** 统一储能推进（round 级，2026-08-12 用户要求：电容/电池能量模型）：
     *  储能复合模型（{@code EnergyDevice}，电容/电池）求解后从端口相量电压
     *  同步储能电荷（时间相关变量绑定）——隔直通交由基础元件相量导纳提供，
     *  瞬态/DC 下相量求不出时由电荷状态驱动电压（类似电池）。每 pos 选最高
     *  频率网络处理一次（与变压器/设备/导线一致）。 */
    static void computeEnergyUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<BlockPos, Object[]> best = new java.util.HashMap<>();
        NetSweep.sweep(nets, (ctx, res, freq, batchHash) -> {
            if (ctx.energyDevices == null || ctx.energyDevices.isEmpty()) return;
            for (java.util.Map.Entry<BlockPos, com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice> e
                    : ctx.energyDevices.entrySet()) {
                Object[] cur = best.get(e.getKey());
                if (cur == null || (Double) cur[1] < freq) {
                    best.put(e.getKey(), new Object[]{e.getValue(), freq, res});
                }
            }
        });
        for (java.util.Map.Entry<BlockPos, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            try {
                com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice ed =
                        (com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice) o[0];
                com.hdf.cryptand.circuitsimulation.solver.SolveResult res =
                        (com.hdf.cryptand.circuitsimulation.solver.SolveResult) o[2];
                if (ed == null || res == null || res.complex == null) continue;
                int n = res.complex.length;
                int na = ed.nodeA(), nb = ed.nodeB();
                if (na < 0 || nb < 0 || na >= n || nb >= n) continue;
                ed.syncCharge(res.complex[na], res.complex[nb]);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 统一导线段发热处理（round 级，2026-08-12）：导线连续段 = 复合元件
     *  WireComposite（Resistor + 温度模型），用统一复合元件生命周期 update()
     *  算损耗 → 推进温度（散热与发热同时，解析解）。段 key = 路径签名；
     *  同一段在多网络 ctx 出现时只选最高频率处理一次（与变压器/设备一致）。 */
    static void computeWireHeatUnified(Level level, java.util.List<Object[]> nets) {
        java.util.Map<String, Object[]> best = new java.util.HashMap<>();
        java.util.Set<String> activeKeys = new java.util.HashSet<>();
        NetSweep.sweep(nets, (ctx, res, freq, batchHash) -> {
            if (ctx.wireSegments == null || ctx.wireSegments.isEmpty()) return;
            for (com.hdf.cryptand.circuitsimulation.model.composite.WireComposite seg : ctx.wireSegments) {
                if (seg.compositeKey() == null) continue;
                activeKeys.add(seg.compositeKey());
                Object[] cur = best.get(seg.compositeKey());
                if (cur == null || (Double) cur[2] < freq) {
                    // 第5元素 = 批内冻结指纹（校验用，勿读 ctx.solveHash——被覆盖）
                    best.put(seg.compositeKey(),
                            new Object[]{ctx, res, freq, seg, batchHash});
                }
            }
        });
        for (java.util.Map.Entry<String, Object[]> e : best.entrySet()) {
            Object[] o = e.getValue();
            try {
                long bh = (o.length >= 5 && o[4] instanceof Long l) ? l : 0;
                computeWireHeatOne(level, (PhasorNetworkContext) o[0],
                        (SolveResult) o[1],
                        (com.hdf.cryptand.circuitsimulation.model.composite.WireComposite) o[3],
                        bh);
            } catch (Throwable ignored) {
            }
        }
        // 段消失清理（节流 ~5s）：拆线/烧毁后残留温度模型移除（防泄漏 + 防旧温
        // 度影响重接后的新段——新段 key 不同自然不冲突）
        long now = System.currentTimeMillis();
        if (now - WIRE_CLEANUP_LAST >= 5000) {
            WIRE_CLEANUP_LAST = now;
            try {
                WireThermalStore.retainOnly(activeKeys);
            } catch (Throwable ignored) {
            }
            try {
                WireSegments
                        .retainSegments(activeKeys);
            } catch (Throwable ignored) {
            }
        }
    }
    private static volatile long WIRE_CLEANUP_LAST;

    /** 导线段烧毁阈值（°C；与 WireThermalStore 构造最高温度 473.15K=200°C 一致） */
    private static final double WIRE_BURN_TEMP_C = 200.0;

    /** 导线段烧毁防反复冷却（2026-08-18：烧毁请求后 30s 内同段不重复——后台
     *  求解每轮检测到段温度仍 >200 会反复 requestWire → 反复重建风暴 → 卡死） */
    private static final java.util.Map<String, Long> WIRE_BURN_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long WIRE_BURN_COOLDOWN_MS = 30_000L;


    /**
     * 全量温度降温（2026-08-23 用户：开路/无功率 → 温度【持续计算】——按散热
     * 每 tick 驱动（ServerTickEvent.Post）；静态全局存储（导线/设备/变压器）。
     */
    public static void coolAllTemperatures(double dt) {
        try { WireThermalStore.coolAll(dt); }
        catch (Throwable ignored) { }
        try { DeviceThermalStore.coolAll(dt); }
        catch (Throwable ignored) { }
        try { TransformerHeatStore.coolAll(dt); }
        catch (Throwable ignored) { }
    }

    /** 单导线段发热处理（每 tick 每段一次）：统一复合元件生命周期
     *  WireComposite.update —— 算 I²R 损耗 → 推进温度模型。
     *  开路/悬空分支无电流（I=0）→ 自然冷却；真实回路才有电流/发热。
     *  段温度超阈值 → 【同段统一烧毁】（移除段内全部导线，2026-08-14 用户要求）。 */
    private static void computeWireHeatOne(Level level, PhasorNetworkContext ctx,
            SolveResult res,
            com.hdf.cryptand.circuitsimulation.model.composite.WireComposite seg,
            long batchHash) {
        if (ctx == null || res == null || res.complex == null || seg == null) return;
        int n = res.complex.length;
        int na = seg.nodeA(), nb = seg.nodeB();
        if (na < 0 || nb < 0 || na >= n || nb >= n) return;
        if (seg.resistance <= 1e-9) return;
        // ⚠ 2026-08-24 断点数据实锤（用户）：res.complex=[-4.23,199.97,199.99,
        // -0.0138,0]（n1≈n2=200V 但 n0=-4.23V → 线2 压降 4.2V/0.0038≈1110A 而线1
        // 压降 0.014V≈3.6A —— 同一串联环 KCL 矛盾）→ res 与当前网表节点【错配】
        //（旧轮/另一网络解被当成当前解 → 假大电流 → 误烧线）。防护：① res 节点
        // 数必须与 ctx.network 节点数一致；② res 网表指纹必须等于【批内冻结指纹】
        // batchHash（== res.networkHash，同轮同源；不能用 ctx.solveHash 字段——
        // ctx 跨轮复用，字段会被下轮覆盖 → 假 mismatch 实锤）。
        if (ctx.network != null) {
            if (res.complex.length != ctx.network.nodeCount()
                    || (batchHash != 0 && res.networkHash != batchHash)) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[WireHeatMismatch] resN={} netNodes={} hash={}/{} key={} SKIP",
                        res.complex.length, ctx.network.nodeCount(),
                        res.networkHash, batchHash,
                        seg.compositeKey());
                return;
            }
        }
        double omega = 2 * Math.PI * (ctx.frequency > 0 ? ctx.frequency : 0);
        // 统一生命周期：算损耗（I²R/2）→ 推进温度（发热与散热【一起计算】后
        // 再加减：ΔT = (P − G·(T−Tamb))/C × dt——ThermalModel.linearStep）
        seg.update(res.complex[na], res.complex[nb], omega, System.nanoTime());
        // 正常工况诊断（2026-08-23 用户“23A 温度上升很快”）：节流每 key 5s 打印
        // 电流/段R/损耗/温度——核对发热−散热平衡（金线 23A：P≈0.4W → 稳态温升
        // ≈0.2K，T≈25.2°C；若 T 快升 → 看 P 与 R 是否异常）。
        try {
            if (seg.thermal() != null) {
                double vd = res.complex[na].sub(res.complex[nb]).abs();
                double iRms = vd / seg.resistance / 1.41421356237;
                // ⚠ 2026-09-12 诊断放宽（用户报"导线温度没有变"）：原先要求
                //   iRms>0.5 且 T>30 才打印——温度不升时永远看不到日志。现在只要有
                //   电流就打印（节流 5s/段），可据此判断是"没电流/没推进/没散热"。
                if (iRms > 0.1) {
                    if (AdapterDiag.gate("engine.wireHeatNorm:" + seg.compositeKey(), 5000)) {
                        double p = vd * vd / seg.resistance / 2.0;
                        // 2026-08-23 网表摘要（电机短路烧线定位）：打印段所在网表
                        // 节点数 + 全部元素（类型+端点）——确认电机 EMF 是否在本
                        // 网表（无电机元素 → 源+双线直接短路线 → 烧毁）
                        StringBuilder es = new StringBuilder();
                        int emfN = 0;
                        try {
                            if (ctx.network != null) {
                                es.append(" nodes=").append(ctx.network.nodeCount());
                                for (com.hdf.cryptand.circuitsimulation.model.Element el
                                        : ctx.network.elements()) {
                                    es.append(" [").append(el.type()).append('(')
                                            .append(el.nodeA()).append(',')
                                            .append(el.nodeB()).append(')').append(']');
                                    if (el instanceof com.hdf.cryptand.circuitsimulation.model.elements
                                            .AcVoltageSource av) {
                                        emfN++;
                                        es.append(':').append(String.format("%.1f/%.2f",
                                                av.amplitude, av.seriesResistance));
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                            es.append(" esEX");
                        }
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[WireHeatNorm] key={} T={}C i={}A R={} P={}W G={} C={}"
                                        + " | seg=({},{}) emf={}{}",
                                seg.compositeKey(),
                                String.format("%.1f", seg.thermal().tempCelsius()),
                                String.format("%.1f", iRms),
                                String.format("%.4f", seg.resistance),
                                String.format("%.2f", p),
                                String.format("%.2f", seg.thermal().conductance),
                                String.format("%.1f", seg.thermal().heatCapacity),
                                na, nb, emfN, es);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 烧毁检测：段温度超阈值 → 【先移除】段内导线（立即从自管图移除，后续
        // 求解不再含此段——否则下一轮又检测到过热 → 反复烧毁 + 重建风暴 → 卡死）
        // + 冷却防反复 → 再请求销毁（清理温度/爆炸/客户端同步）。
        // ⚠ 用户要求：移入销毁队列前必须先从网络移除该元件，确保不再被引用。
        // 诊断（2026-08-23 物理化温度定位）：段温度反常偏高（>150°C 未到烧毁）
        // 节流打印段参数——R/长度/电压差/电流/模型散热，定位"温度非常大的值"
        //（物理化后坐标/长度/模型错位）。
        try {
            if (seg.thermal() != null && seg.thermal().tempCelsius() > 150.0) {
                if (AdapterDiag.gate("engine.wireHeat", 5000)) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[WireHeatHi] key={} T={}C R={} G={} C={} vA={} vB={}",
                            seg.compositeKey(),
                            String.format("%.1f", seg.thermal().tempCelsius()),
                            String.format("%.4f", seg.resistance),
                            String.format("%.2f", seg.thermal().conductance),
                            String.format("%.1f", seg.thermal().heatCapacity),
                            String.format("%.2f", res.complex[na].abs()),
                            String.format("%.2f", res.complex[nb].abs()));
                }
            }
        } catch (Throwable ignored) {
        }
        if (seg.thermal() != null && seg.thermal().tempCelsius() > WIRE_BURN_TEMP_C) {
            try {
                String key = seg.compositeKey();
                // ⚠ 2026-08-24 烧毁详细日志（用户要求，key 级 5s 节流）：打印当前
                // 轮 res/网表全部数据，供定位"误烧/真烧"——KCL 校验：段两端电压差 +
                // 网表元素（含 EMF 幅值/内阻）+ res 全数组。
                if (AdapterDiag.gate("engine.wireBurn:" + key, 5000)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[WireBurnDbg] key=").append(key)
                            .append(" T=").append(String.format("%.1f",
                                    seg.thermal().tempCelsius()))
                            .append("C seg=(").append(na).append(',').append(nb).append(')')
                            .append(" R=").append(String.format("%.6f", seg.resistance))
                            .append(" | resN=").append(n)
                            .append(" netNodes=").append(ctx.network == null
                                    ? -1 : ctx.network.nodeCount())
                            .append(" freq=").append(ctx.frequency)
                            .append(" conv=").append(res.converged)
                            .append(" iters=").append(res.iterations);
                    // 段两端电压（复数值）
                    if (na < res.complex.length && nb < res.complex.length) {
                        sb.append(" | v").append(na).append('=')
                                .append(String.format("%.4f", res.complex[na].abs()))
                                .append(" v").append(nb).append('=')
                                .append(String.format("%.4f", res.complex[nb].abs()))
                                .append(" vd=")
                                .append(String.format("%.6f",
                                        res.complex[na].sub(res.complex[nb]).abs()));
                    }
                    // res 全数组
                    sb.append(" | res=[");
                    for (int ri = 0; ri < res.complex.length; ri++) {
                        if (ri > 0) sb.append(',');
                        sb.append(String.format("%.4f", res.complex[ri].abs()));
                    }
                    sb.append(']');
                    // 网表元素（type(nodeA,nodeB);Ac:幅值/内阻）
                    if (ctx.network != null) {
                        sb.append(" | net[");
                        int ei = 0;
                        for (com.hdf.cryptand.circuitsimulation.model.Element el
                                : ctx.network.elements()) {
                            if (ei++ > 0) sb.append(' ');
                            sb.append(el.type()).append('(')
                                    .append(el.nodeA()).append(',').append(el.nodeB()).append(')');
                            if (el instanceof com.hdf.cryptand.circuitsimulation.model.elements
                                    .AcVoltageSource av) {
                                sb.append(':').append(String.format("%.2f/%.4f",
                                        av.amplitude, av.seriesResistance));
                            }
                        }
                        sb.append(']');
                    }
                    CryptandNeoForge.WAF_LOGGER.info(sb.toString());
                }
                java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge> edges =
                        WireSegments
                                .segmentEdges(key);
                if (key != null && edges != null && !edges.isEmpty()) {
                    long nowC = System.currentTimeMillis();
                    Long lastC = WIRE_BURN_COOLDOWN.get(key);
                    if (lastC == null || nowC - lastC >= WIRE_BURN_COOLDOWN_MS) {
                        WIRE_BURN_COOLDOWN.put(key, nowC);
                        // 【先移除】：立即从自管图移除段内全部导线（线程安全
                        //  writeLock）——确保该段不再被任何网络引用/求解
                        var mgr = WireNetworkManager.get();
                        for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge ed : edges) {
                            try {
                                if (ed.a != null && ed.b != null
                                        && mgr.contains(ed.a) && mgr.contains(ed.b)) {
                                    mgr.removeEdge(ed.a, ed.b);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        // 【后销毁】：请求清理（温度模型/爆炸/客户端同步）
                        DestructionQueue.requestWire(
                                key, edges,
                                "过热 " + String.format("%.0f",
                                        seg.thermal().tempCelsius()) + "°C");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 诊断（时间戳节流 5s + 只打温度 >60°C——computeWireHeatOne 在后台求解
        // 线程跑，gameTime 可能不变导致 %100 节流失效 → 刷屏 37 万行卡死）
        try {
            double tNow = seg.thermal() == null ? 20.0 : seg.thermal().tempCelsius();
            // 2026-08-20 排查"5A 电流导线 300°C"：临时降阈值到 30°C + 打印段
            // 长度/散热/热容参数（定位温度虚高根因：R 过大 / G 过小 / 电流虚高）
            if (tNow > 30.0 && AdapterDiag.gate("engine.wireHeatHi", 5000)) {
                double vDiff = res.complex[na].sub(res.complex[nb]).abs(); // 峰值压降
                double iPeak = vDiff / seg.resistance;
                double pAvg = iPeak * iPeak * seg.resistance / 2.0;
                double lenM = 0;
                double G = 0, C = 0;
                try {
                    if (seg.thermal() instanceof com.hdf.cryptand.circuitsimulation.model
                            .thermal.ThermalModel tm) {
                        G = tm.effectiveConductance();
                        C = tm.heatCapacity;
                    }
                    // 段长度（Σ 边长度；convertWires 导入的边 length=0 → 0）
                    java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge> edgs =
                            WireSegments
                                    .segmentEdges(seg.compositeKey());
                    if (edgs != null) {
                        for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge ed : edgs) {
                            lenM += Math.max(ed.length, 0);
                        }
                    }
                } catch (Throwable ignored) {
                }
                String sk = seg.compositeKey();
                if (sk != null && sk.length() > 40) sk = sk.substring(0, 40) + "...";
                CryptandNeoForge.WAF_LOGGER.info(
                        "[WireHeat] R={}Ω I={}A(峰值) P={}W T={}°C len={}m G={}W/K "
                                + "C={}J/K key=[{}]",
                        String.format("%.4f", seg.resistance),
                        String.format("%.2f", iPeak),
                        String.format("%.1f", pAvg),
                        String.format("%.1f", tNow),
                        String.format("%.1f", lenM),
                        String.format("%.2f", G),
                        String.format("%.1f", C),
                        sk);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清除上下文涉及的所有变压器发热状态（DC 隔离 / 低频跳过时防残留声音/发热）。
     *  用 blockTerminals 遍历（含 transformerModels 为空的低频场景）。 */
    private static void clearTransformerStates(Level level, PhasorNetworkContext ctx) {
        try {
            if (ctx == null || ctx.blockTerminals == null) return;
            for (BlockPos pos : ctx.blockTerminals.keySet()) {
                if (isTransformer(pos)) {
                    TransformerHeatStore.remove(pos);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 是否变压器 —— 读【主线程下发的类名】，不碰 Level/BE。
     *
     * ⚠ 2026-09-13 用户："所有需要依赖主线程的参数全部采用下发更新的方式"。
     *   原实现是后台线程 {@code level.getBlockEntity(pos) instanceof Transformer…}
     *   —— 后台读世界 = 违反铁律"引擎禁止访问 Level/BE"（且非线程安全）。
     *   现在改读 {@link DeviceParamCache} 里主线程写入的 {@code deviceClass}
     *   （= {@code Assemblers.fqcnKey(be)}：继承链各级类名的拼接，含子类），
     *   因此寄生接管后的自有 BE 子类同样能识别。
     */
    private static boolean isTransformer(BlockPos pos) {
        try {
            com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.Entry e =
                    com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.get(pos);
            if (e == null || e.deviceClass == null) return false;
            return e.deviceClass.contains("TransformerBlockEntity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 反射读取实例字段（沿继承链查找）；失败返回 null */
    private static Object reflectField(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
