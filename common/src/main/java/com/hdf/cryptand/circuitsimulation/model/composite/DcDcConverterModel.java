package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * DC-DC 变换器复合模型（buck/boost/buck-boost，理想变比，为未来准备）。
 * <p>
 * 现实物理：开关电源通过斩波 + 电感/电容滤波实现电压变换：
 *   - Buck（降压）：V_out = D·V_in（D = 占空比）
 *   - Boost（升压）：V_out = V_in/(1−D)
 *   - Buck-Boost（升降压）：V_out = −D/(1−D)·V_in（反相）
 * 效率 η：P_out = η·P_in。
 * <p>
 * 相量域简化：理想变换器（输入侧 = 输出负载折算电阻 R_in = R_load/k²，
 * 输出侧 = 可控 DC 电压源 V_out = k·V_in）。k 由占空比决定（外部更新 →
 * 参数消息 → 重解）。
 * <p>
 * 端口：vin/vgnd = 输入；vout/ognd = 输出。电感 L 建模能量传递（串联输入）。
 */
public class DcDcConverterModel extends CompositeModel {

    /** 输入/输出端子 + 电感内部节点 */
    public final int vin, vgnd, vout, ognd, x;
    /** 变换比 k = V_out/V_in（buck: D, boost: 1/(1−D), 反相为负） */
    private volatile double ratio;
    /** 电感（H）、输入内阻（Ω） */
    public final double inductance, inputR;
    /** 输出 DC 源（setter 更新电压） */
    private final DcVoltageSource outSource;

    public DcDcConverterModel(int vin, int vgnd, int vout, int ognd, int x,
                              double ratio, double inductance, double inputR) {
        super(build(vin, vgnd, vout, ognd, x, ratio, inductance, inputR));
        this.vin = vin; this.vgnd = vgnd; this.vout = vout; this.ognd = ognd; this.x = x;
        this.ratio = ratio;
        this.inductance = inductance;
        this.inputR = inputR;
        DcVoltageSource src = null;
        for (Element e : simple) {
            if (e instanceof DcVoltageSource ds && ds.nodeA() == vout) {
                src = ds;
                break;
            }
        }
        this.outSource = src;
    }

    /** 组合：输入电感 + 输入内阻 + 输出可控 DC 源（电压 = 0 起步，setter 更新） */
    private static Element[] build(int vin, int vgnd, int vout, int ognd, int x,
                                   double ratio, double l, double rIn) {
        return new Element[]{
                new Inductor(vin, x, Math.max(l, 1e-9)),
                new Resistor(x, vgnd, Math.max(rIn, 1e-9)),
                new DcVoltageSource(vout, ognd, 0.0, 1e-4)
        };
    }

    /** 设置变换比 k（外部按占空比算好传入）→ 更新输出源电压（需先设置输入） */
    public void setRatio(double k) {
        this.ratio = k;
    }

    /** 由输入电压更新输出源电压 V_out = k·V_in（外部每轮调用） */
    public void updateFromInput(double vIn) {
        if (outSource != null) {
            double vOut = ratio * vIn;
            if (Math.abs(vOut) < 1e-9) vOut = 0;
            // DcVoltageSource.voltage 为 final → 用反射 set？改为重建不可行；
            // 因此本模型输出源用"近零内阻源 + 外部更新"约定，见 updateVoltage。
            updateVoltage(vOut);
        }
    }

    /** 直接设置输出源电压（DcVoltageSource.voltage 是 final，此处用动态近似：
     *  通过 setter 模拟——实际接入时建议用可变 Dc 源替代；保留占位） */
    private void updateVoltage(double v) {
        // 占位：DcVoltageSource.voltage 不可变。未来可改用可变 DC 源基础元件。
        // 当前作为"结构占位"，电压由外部网络约束（串联内阻分压）决定。
    }

    public double ratio() { return ratio; }

    /** 序列化参数：[ratio, L, R_in, 端口] */
    public double[] params() {
        return new double[]{ratio, inductance, inputR, vin, vgnd, vout, ognd, x};
    }

    @Override
    public String toString() {
        return "DcDcConverterModel{" + vin + "-" + vgnd + "→" + vout + "-" + ognd
                + " k=" + ratio + " L=" + inductance + "}";
    }
}
