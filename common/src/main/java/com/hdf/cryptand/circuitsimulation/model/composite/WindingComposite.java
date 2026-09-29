package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * ===== 励磁线圈复合元件（2026-09-12 用户）=====
 *
 * 用户："铁耗/铜耗/风阻等通过定义线圈、转子等复合元件然后组装器中温度模型实现相关
 * 温度计算即可，完全取消原版计算全部元件进入自管，相当于原版仅保留 BE 模型"
 *
 * 把原先写在 {@code WindingBlockEntityAcMixin}（BE 侧 tick）里的线圈发热公式整体搬到
 * 引擎侧的复合元件里，温度由构造时挂上的 {@link ThermalModel} 统一推进：
 *
 *   ① 铜耗：{@code I_eff² · R_dc}，其中 {@code I_eff = V_rms / |Z|}，
 *      {@code Z = √(R_dc² + (2π·f_eff·L_phys)²)} —— 用【物理电感】而非 PowerGrid 时域
 *      离散电感，避免高频（5kHz）下电流物理失真（原 20A → 应 <0.1A）。
 *   ② 铁耗：{@code (0.15·f + 0.0016·f²) · B²}，f 封顶 500Hz（原版校准系数）。
 *      磁通 B 由【电压】建立（V≈4.44·f·N·A·B）：{@code B = min(2, 1.5·tanh(V/230) + 0.05·(V/230))}，
 *      饱和封顶 2.0；直流（ω≈0）无交变磁通 → 铁耗 0。
 *
 * 配置值（物理电感/直流电阻/最小 AC 频率）由组装器（neoforge 侧）以构造参数传入，
 * 使本类保持 common 纯 Java、零 MC/配置依赖。
 */
public class WindingComposite extends MotorModel {

    /** 物理电感（H）——频率感抗用的真实励磁绕组电感 */
    private final double lPhys;
    /** 直流电阻（Ω）——铜耗与阻抗实部 */
    private final double rDc;
    /** 最小 AC 判定频率（Hz）——防频率瞬态归零被当直流短路 */
    private final double minAcFreq;

    public WindingComposite(int a, int b, int x, double resistance, double inductance,
                            ThermalModel thermal, double lPhys, double rDc, double minAcFreq) {
        super(a, b, x, resistance, inductance, thermal);
        this.lPhys = Math.max(lPhys, 0);
        this.rDc = (rDc > 0) ? rDc : Math.max(resistance, 1e-9);
        this.minAcFreq = Math.max(minAcFreq, 0);
    }

    /**
     * 线圈总损耗（平均功率 W）= 铜耗 + 铁耗。引擎每轮用它推进 ThermalModel
     * （与原版 {@code applyTickPower} 的语义差异由温度模型侧统一换算）。
     */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        try {
            double freq = Math.max(omega / (2 * Math.PI), 0);
            // 相量幅值 → RMS（与校准时的 vWire RMS 一致）
            double vRms = (va == null || vb == null) ? 0 : va.sub(vb).abs() / Math.sqrt(2.0);
            if (vRms <= 0) return 0;
            // ① 有效电流（物理阻抗，含频率感抗）
            double fEff = Math.max(freq, minAcFreq);
            double xl = 2 * Math.PI * fEff * lPhys;
            double z = Math.hypot(rDc, xl);
            double iEff = (z > 1e-9) ? vRms / z : 0;
            double cu = iEff * iEff * rDc;
            // ② 铁耗（仅交流；磁通由电压建立，磁饱和封顶）
            double iron = 0;
            if (freq > 0) {
                double vRatio = vRms / 230.0;
                double b = Math.min(2.0, 1.5 * Math.tanh(vRatio) + 0.05 * vRatio);
                double fIron = Math.min(freq, 500.0);
                iron = (0.15 * fIron + 0.0016 * fIron * fIron) * (b * b);
            }
            double total = cu + iron;
            return Double.isFinite(total) && total > 0 ? total : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return "WindingComposite{" + nodeA() + "-" + nodeB() + " Rdc=" + rDc
                + " Lphys=" + lPhys + " thermal=" + (thermal() == null ? "none" : "on") + "}";
    }
}
