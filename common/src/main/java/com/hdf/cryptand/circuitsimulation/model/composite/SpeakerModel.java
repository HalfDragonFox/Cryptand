package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 扬声器复合模型（音圈 R-L + 声学阻尼，为未来准备——音频输出）。
 * <p>
 * 现实物理：扬声器音圈在磁场中受力（F = B·l·I），驱动振膜发声。电学等效：
 * 音圈电阻 R_vc + 音圈电感 L_vc + 反电动势（振膜运动）+ 声学机械阻尼折算电阻
 * R_mech（吸收电功率转声能）。阻抗曲线低频呈感性、谐振峰处阻抗最大。
 * <p>
 * 相量域简化：音圈 R-L 串联 + 机械阻尼电阻（并联或串联折算）。
 * 实现 {@link ThermalDevice}：音圈铜耗发热（音圈过热是常见失效模式）。
 * <p>
 * 端口：a/b = 扬声器输入端子。内部节点 x 分配。
 */
public class SpeakerModel extends CompositeModel implements ThermalDevice {

    /** 输入端子 + 内部节点（R-L 串联后） */
    public final int a, b, x;
    /** 音圈电阻（Ω）、电感（H）、机械阻尼折算电阻（Ω） */
    public final double voiceCoilR, voiceCoilL, mechR;
    /** 温度模型 */
    public final ThermalModel thermal;

    public SpeakerModel(int a, int b, int x,
                        double voiceCoilR, double voiceCoilL, double mechR,
                        ThermalModel thermal) {
        super(build(a, b, x, voiceCoilR, voiceCoilL, mechR));
        this.a = a; this.b = b; this.x = x;
        this.voiceCoilR = voiceCoilR;
        this.voiceCoilL = voiceCoilL;
        this.mechR = mechR;
        this.thermal = thermal;
    }

    /** 组合：音圈 R-L 串联（a-R-x-L-b）+ 机械阻尼电阻（a-b 并联折算） */
    private static Element[] build(int a, int b, int x, double rVc, double lVc, double rMech) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (rVc > 0) els.add(new Resistor(a, x, rVc));
        if (lVc > 0) els.add(new Inductor(x, b, lVc));
        if (rMech > 0) els.add(new Resistor(a, b, rMech)); // 声学负载（能量转声）
        return els.toArray(new Element[0]);
    }

    /** 电声效率 = 机械阻尼吸收功率 / 总输入功率（近似） */
    public double electroacousticEfficiency() {
        double t = voiceCoilR + mechR;
        return t <= 0 ? 0 : mechR / t;
    }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 音圈铜耗（平均）：I²·R_vc/2。2026-08-18 电流法：音圈内部电阻（a-x）
     *  支路电流（nodeVoltages 注入后精确；未注入用原并联估算兜底）。 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (voiceCoilR <= 0) return 0;
        double xl = omega * voiceCoilL;
        double z = Math.sqrt(voiceCoilR * voiceCoilR + xl * xl);
        Complex vx = nodeVoltage(x);
        Complex vaa = nodeVoltage(a);
        double iPeak;
        if (vx != null && vaa != null) {
            iPeak = vaa.sub(vx).abs() / voiceCoilR;
        } else {
            // 机械阻尼并联分流 → 音圈电流略小于总电流（简化按并联估算）
            double rPar = mechR > 0 ? (voiceCoilR * mechR) / (voiceCoilR + mechR) : voiceCoilR;
            iPeak = z < 1e-12 ? 0 : va.sub(vb).abs() / z * (rPar / (voiceCoilR + 1e-12));
        }
        return iPeak * iPeak * voiceCoilR / 2.0;
    }

    /** 序列化参数：[R_vc, L_vc, R_mech, 端口] */
    public double[] params() {
        return new double[]{voiceCoilR, voiceCoilL, mechR, a, b, x};
    }

    @Override
    public String toString() {
        return "SpeakerModel{" + a + "-" + b + " Rvc=" + voiceCoilR
                + " Lvc=" + voiceCoilL + " η="
                + String.format("%.0f%%", electroacousticEfficiency() * 100) + "}";
    }
}
