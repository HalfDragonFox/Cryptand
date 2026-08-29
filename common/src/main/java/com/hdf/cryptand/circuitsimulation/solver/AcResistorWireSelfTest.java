package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * AC 源 + 电阻 + 导线回路自测（2026-08-16 用户要求）。
 * <p>
 * 模拟放置：AC 源（50Hz）→ 导线1 → 电阻 → 导线2 → 回到源，构成闭合回路。
 * 电路：
 * <pre>
 *   n0(地) ──AC源── n1 ──导线1── n2 ──电阻── n3 ──导线2── n0
 * </pre>
 * 测试流程：
 *   1) 源 100V 峰值 / 电阻 100Ω → 求解 → 打印电阻电压 / 回路电流（期望
 *      I_peak ≈ 1A，V_res_peak ≈ 100V）；
 *   2) 源改 200V（setAmplitude，走参数变化路径，不重建网络）→ 再求解 →
 *      打印（期望 I_peak ≈ 2A，V_res_peak ≈ 200V，线性放大 2 倍）。
 * <p>
 * 运行：{@code ./gradlew :common:runAcResistorWireTest}
 */
public final class AcResistorWireSelfTest {

    /** 导线电阻（Ω/条，铜线级：远小于 100Ω，回路电流 ≈ V/R 理论值） */
    private static final double WIRE_R = 0.01;
    /** AC 源内阻（Ω，理想源 → 很小） */
    private static final double SOURCE_R = 0.1;
    /** 电阻（Ω） */
    private static final double LOAD_R = 100.0;

    public static void main(String[] args) {
        // 确定走 double 求解器（纯引擎测试环境无 native）
        Solvers.floatEnabled = false;

        // ===== 搭建电路：AC源(50Hz) + 导线1 + 电阻 + 导线2 =====
        Network net = new Network();
        net.frequency = 50.0;
        var n0 = net.addNode(); // 地（源负极 / 导线2 回端）
        var n1 = net.addNode(); // 源正极 / 导线1 起点
        var n2 = net.addNode(); // 导线1 终点 / 电阻一端
        var n3 = net.addNode(); // 电阻另一端 / 导线2 起点

        AcVoltageSource src = new AcVoltageSource(n1.id, n0.id, 100.0, 0.0, SOURCE_R);
        net.addElement(src);
        net.addComposite(new WireComposite(n1.id, n2.id, WIRE_R, null, "wire1"));
        net.addElement(new Resistor(n2.id, n3.id, LOAD_R));
        net.addComposite(new WireComposite(n3.id, n0.id, WIRE_R, null, "wire2"));

        System.out.println("==== AC源 + 电阻 + 导线 仿真（50Hz） ====");
        System.out.printf("电路：AC源(50Hz) → 导线1(R=%.3fΩ) → 电阻(%.1fΩ) → 导线2(R=%.3fΩ) → 回源%n",
                WIRE_R, LOAD_R, WIRE_R);

        // ===== 测试1：源 100V 峰值 =====
        System.out.println("\n---- 测试1：源 100V 峰值 / 电阻 100Ω ----");
        boolean t1 = solveAndPrint(net, src, 100.0, 1.0);

        // ===== 测试2：源改 200V（setAmplitude 走参数变化路径，网络不重建） =====
        System.out.println("\n---- 测试2：源改 200V 峰值 / 电阻 100Ω 不变 ----");
        src.setAmplitude(200.0);
        boolean t2 = solveAndPrint(net, src, 200.0, 2.0);

        // ===== 线性验证：源电压加倍 → 电压/电流应同样加倍 =====
        System.out.println("\n==== 验证结果 ====");
        boolean pass = t1 && t2;
        System.out.println(pass
                ? "✅ AC源+电阻+导线 仿真自测通过（100V→200V 线性放大正常）"
                : "❌ AC源+电阻+导线 仿真自测失败");
        System.exit(pass ? 0 : 1);
    }

    /** 求解一次并打印结果；返回是否符合期望（电流 = 期望值 ± 0.5%，电压同） */
    private static boolean solveAndPrint(Network net, AcVoltageSource src,
                                         double expectV, double expectI) {
        Solver solver = Solvers.create(net);
        SolveResult r = solver.solve(net);
        if (r == null || !r.converged || r.complex == null) {
            System.out.println("  求解失败: " + r);
            return false;
        }
        Complex vRes = r.complex[2].sub(r.complex[3]);   // 电阻两端相量
        Complex vSrc = r.complex[1].sub(r.complex[0]);   // 源两端相量
        double vResPeak = vRes.abs();
        double vSrcPeak = vSrc.abs();
        double iPeak = vResPeak / LOAD_R;                // 回路电流 = |Vres| / R
        double vResRms = vResPeak / Math.sqrt(2);
        double iRms = iPeak / Math.sqrt(2);
        // 导线压降（相量差分）
        double vWire1 = r.complex[1].sub(r.complex[2]).abs();
        double vWire2 = r.complex[3].sub(r.complex[0]).abs();

        System.out.printf("  求解: mode=%s converged=%b iters=%d 耗时=%.2fms%n",
                r.mode, r.converged, r.iterations, r.solveNanos / 1e6);
        System.out.printf("  源电压: %.4fV 峰值 (RMS %.4fV)%n", vSrcPeak, vSrcPeak / Math.sqrt(2));
        System.out.printf("  电阻电压: %.4fV 峰值 (RMS %.4fV)  期望≈%.1fV峰值%n",
                vResPeak, vResRms, expectV);
        System.out.printf("  回路电流: %.4fA 峰值 (RMS %.4fA)  期望≈%.1fA峰值%n",
                iPeak, iRms, expectI);
        System.out.printf("  导线压降: 导线1=%.6fV  导线2=%.6fV（小阻值，可忽略）%n",
                vWire1, vWire2);

        boolean vOk = Math.abs(vResPeak - expectV) <= expectV * 0.005 + 0.5;
        boolean iOk = Math.abs(iPeak - expectI) <= expectI * 0.005 + 0.01;
        System.out.println("  电压正常: " + (vOk ? "✅" : "❌") + "  电流正常: "
                + (iOk ? "✅" : "❌") + "  源电压=" + String.format("%.1fV", src.amplitude));
        return vOk && iOk;
    }

    private AcResistorWireSelfTest() {
    }
}
