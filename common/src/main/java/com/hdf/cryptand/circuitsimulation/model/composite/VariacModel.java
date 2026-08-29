package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 自耦变压器复合模型（PowerGrid kinetics.variac）：单绕组抽头。
 * <p>
 * 电路解析（与 VariacDevice 原 stamp 同构）：
 *   a --primaryStray(R)-- x --mutual(R)-- b + IdealTransformer(x,b):(a,b)
 *   自耦连接（x 为抽头，变比 ratio）；1:1 或未知 → 直通小电阻。
 * <p>
 * 实现 {@link ThermalDevice}：绕组铜耗发热（简化近似——按端口电压与总串联
 * 铜阻估算电流，与现有"简化近似"一致；精度不够可收紧为内部电流精确解）。
 * 参数为固定量（魔法数字），更新并重新组合后即可重算。
 */
public class VariacModel extends CompositeModel implements ThermalDevice {

    /** 两端口 + 抽头内部节点 x + 理想变压器约束节点 k */
    public final int a, b, x, k;
    /** 变比（副边匝数/原边匝数）；≤0 或 1 → 直通 */
    public final double ratio;
    /** 总串联铜阻：primaryStray + mutual（Ω）——温度近似用 */
    public final double totalResistance;
    /** 温度模型（可 null） */
    public final ThermalModel thermal;

    public VariacModel(int a, int b, int x, int k, double primaryStray,
                       double mutual, double ratio, ThermalModel thermal) {
        super(build(a, b, x, k, primaryStray, mutual, ratio));
        this.a = a;
        this.b = b;
        this.x = x;
        this.k = k;
        this.ratio = ratio;
        this.totalResistance =
                (primaryStray > 0 ? primaryStray : 0) + (mutual > 0 ? mutual : 0);
        this.thermal = thermal;
    }

    /** 组合简单元件：a --R_ps-- x --R_mi-- b + 自耦理想变压器(x,b):(a,b)。 */
    private static Element[] build(int a, int b, int x, int k,
                                   double primaryStray, double mutual, double ratio) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (ratio <= 0 || ratio == 1) {
            // 1:1 或未知 → 直通（小电阻，无真实绕组 → 不计温度）
            els.add(new Resistor(a, b, 1e-3));
            return els.toArray(new Element[0]);
        }
        if (primaryStray > 0) els.add(new Resistor(a, x, primaryStray));
        if (mutual > 0) els.add(new Resistor(x, b, mutual));
        els.add(new IdealTransformer(x, b, a, b, k, ratio));
        return els.toArray(new Element[0]);
    }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 自耦绕组铜耗（简化近似）：I²·R_total/2，I = |Va−Vb|/R_total。
     *  1:1 直通（无真实绕组）→ 0（不发热，避免 R=1e-3 时 P 爆炸）。 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (ratio <= 0 || ratio == 1) return 0;
        double r = totalResistance;
        if (r <= 0) return 0;
        double iPeak = va.sub(vb).abs() / r;
        return iPeak * iPeak * r / 2.0;
    }

    @Override
    public ElementType type() { return ElementType.INDUCTOR; }

    /** 序列化参数：[R_total, ratio, a, b] */
    public double[] params() {
        return new double[]{totalResistance, ratio, a, b};
    }

    @Override
    public String toString() {
        return "VariacModel{" + a + "-" + b + " R=" + totalResistance
                + " ratio=" + ratio + " thermal=" + (thermal == null ? "none" : "on") + "}";
    }
}
