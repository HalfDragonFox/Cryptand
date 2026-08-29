package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 变频器 VFD 复合模型（AC→DC 母线→AC 可调频逆变，为未来准备）。
 * <p>
 * 现实物理：变频器（Variable Frequency Drive）把固定频率交流整流成直流母线，
 * 再经 IGBT 逆变桥 PWM 合成可变频率/电压的交流——控制异步电机转速
 * （f 调转速，V/f 恒压频比控制）。
 * <p>
 * 相量域简化（V/f 恒压频比近似）：
 *   - 输入侧：整流器等效为【电阻负载】R_in（吸收功率 ≈ P_out/η）
 *   - 直流母线：滤波电容 C_bus（母线平滑）
 *   - 输出侧：三相逆变 → 可变频率三相对称源（A/B/C 相位 0/120/240°，
 *     频率 outFreq，幅值 = V_in × k_f（V/f 比，输出频率越高电压越高））
 * <p>
 * 端口：lin/nin = 输入交流（或直流）端子；oa/ob/oc = 输出三相；on = 输出中性。
 * 内部节点：无额外（输入电阻/电容 + 输出源直接接端口）。
 * 输出频率/幅值可经字段更新（{@link #setOutputFrequency} → 参数消息 → 重解）。
 */
public class VfdModel extends CompositeModel {

    /** 输入端子 + 输出三相 + 输出中性 */
    public final int lin, nin, oa, ob, oc, on;
    /** 输入整流等效电阻（Ω）、直流母线电容（F） */
    public final double inputR, busCapacitance;
    /** V/f 恒压频比：输出幅值 = outFreq × vfRatio（V/Hz） */
    public final double vfRatio;

    /** 输出频率（Hz，volatile：外部更新 → setter 发参数消息 → 重解） */
    private volatile double outFreq;
    /** 输出三相源（相位固定，幅值随频率） */
    private final AcVoltageSource[] outSources;

    public VfdModel(int lin, int nin, int oa, int ob, int oc, int on,
                    double inputR, double busCapacitance, double vfRatio,
                    double initialOutFreq) {
        super(build(lin, nin, oa, ob, oc, on, inputR, busCapacitance,
                initialOutFreq, vfRatio));
        this.lin = lin; this.nin = nin;
        this.oa = oa; this.ob = ob; this.oc = oc; this.on = on;
        this.inputR = inputR;
        this.busCapacitance = busCapacitance;
        this.vfRatio = vfRatio;
        this.outFreq = initialOutFreq;
        this.outSources = new AcVoltageSource[3];
        for (Element e : simple) {
            if (e instanceof AcVoltageSource vs) {
                int idx = vs.nodeA() == oa ? 0 : (vs.nodeA() == ob ? 1 : 2);
                outSources[idx] = vs;
            }
        }
    }

    /** 组合：输入整流等效电阻 + 母线电容 + 输出三相对称源（V/f 幅值） */
    private static Element[] build(int lin, int nin, int oa, int ob, int oc, int on,
                                   double rIn, double cBus,
                                   double outFreq, double vfRatio) {
        double amp = outFreq * vfRatio; // V/f 恒压频比
        return new Element[]{
                new Resistor(lin, nin, Math.max(rIn, 1e-9)),
                new Capacitor(lin, nin, Math.max(cBus, 1e-12)),
                new AcVoltageSource(oa, on, amp, 0.0, 1e-4),
                new AcVoltageSource(ob, on, amp, 120.0, 1e-4),
                new AcVoltageSource(oc, on, amp, 240.0, 1e-4)
        };
    }

    /** 设置输出频率（V/f 恒压频比 → 幅值同步更新）→ 参数消息 → 重解 */
    public void setOutputFrequency(double f) {
        if (f < 0) return;
        if (Double.compare(f, outFreq) != 0) {
            outFreq = f;
            double amp = f * vfRatio;
            for (AcVoltageSource vs : outSources) {
                if (vs != null) vs.setAmplitude(amp);
            }
        }
    }

    public double outputFrequency() { return outFreq; }

    /** 序列化参数：[R_in, C_bus, vfRatio, outFreq, 端口] */
    public double[] params() {
        return new double[]{inputR, busCapacitance, vfRatio, outFreq,
                lin, nin, oa, ob, oc, on};
    }

    @Override
    public String toString() {
        return "VfdModel{in(" + lin + "," + nin + ") out(" + oa + "," + ob + ","
                + oc + ") f=" + outFreq + "Hz V/f=" + vfRatio + "}";
    }
}
