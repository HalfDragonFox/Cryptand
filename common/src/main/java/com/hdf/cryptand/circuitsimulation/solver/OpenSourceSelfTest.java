package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 临时自测（2026-08-20）：验证求解器原生支持开路分支计算。
 *   - 孤立电压源 → 开路电压 = 源幅值（诺顿等效，求解器原生）
 *   - 孤立电流源 → 求解器标记开路 → 不注入 → 不发散（悬空由 GMin 兜底）
 *   - 闭合电路（电流源 + 电阻）→ 正常电流（不受开路标记影响）
 *
 * 运行：./gradlew :common:runOpenSourceTest
 */
public class OpenSourceSelfTest {

    public static void main(String[] args) {
        Solvers.floatEnabled = false; // 纯引擎测试环境无 native，走 double

        boolean ok1 = isolatedVoltageSource();
        boolean ok2 = isolatedCurrentSource();
        boolean ok3 = closedCurrentSourceCircuit();
        boolean ok4 = openVoltageSourceWithLoad();
        boolean ok5 = closedVoltageSourceCircuit();
        boolean pass = ok1 && ok2 && ok3 && ok4 && ok5;
        System.out.println("\n==== 结果 ====");
        System.out.println(pass ? "✅ 求解器原生开路支持自测通过" : "❌ 自测失败");
        System.exit(pass ? 0 : 1);
    }

    /** 孤立电压源：V_ab = 源幅值（10V）。 */
    private static boolean isolatedVoltageSource() {
        Network net = new Network();
        net.frequency = 50.0;
        var n0 = net.addNode(); // 地
        var n1 = net.addNode(); // 源正极
        net.addElement(new AcVoltageSource(n1.id, n0.id, 10.0, 0.0, 1e-4));
        SolveResult r = Solvers.create(SolveMode.COMPLEX_AC, net).solve(net);
        double v = r.complex[n1.id].sub(r.complex[n0.id]).abs();
        System.out.printf("孤立电压源: V=%.4f V (期望 10V)%n", v);
        return Math.abs(v - 10.0) < 0.05;
    }

    /** 孤立电流源：求解器标记开路 → 不注入 → 不发散（0V，悬空由 GMin 兜底）。 */
    private static boolean isolatedCurrentSource() {
        Network net = new Network();
        net.frequency = 50.0;
        var n0 = net.addNode();
        var n1 = net.addNode();
        CurrentSource cs = new CurrentSource(n1.id, n0.id, 1.0, 50.0); // 1A，两端无闭合回路
        net.addElement(cs);
        SolveResult r = Solvers.create(SolveMode.COMPLEX_AC, net).solve(net);
        boolean markedOpen = cs.isOpenCircuit();
        double v = r.complex[n1.id].sub(r.complex[n0.id]).abs();
        System.out.printf("孤立电流源: openCircuit=%s V=%.6f V (期望 0V 不发散)%n",
                markedOpen, v);
        return markedOpen && v < 1.0;
    }

    /** 闭合电路（电流源 + 电阻）：电流源两端连通 → 不标记开路 → 正常电流。 */
    private static boolean closedCurrentSourceCircuit() {
        Network net = new Network();
        net.frequency = 50.0;
        var n0 = net.addNode();
        var n1 = net.addNode();
        CurrentSource cs = new CurrentSource(n1.id, n0.id, 1.0, 50.0); // 1A
        net.addElement(cs);
        net.addElement(new Resistor(n1.id, n0.id, 10.0)); // 负载 10Ω（形成闭合回路）
        SolveResult r = Solvers.create(SolveMode.COMPLEX_AC, net).solve(net);
        boolean notOpen = !cs.isOpenCircuit();
        double v = r.complex[n1.id].sub(r.complex[n0.id]).abs();
        double i = v / 10.0; // 负载电流
        System.out.printf("闭合电流源+10Ω: isOpen=%s V=%.4f V I=%.4f A (期望 V≈10V I≈1A)%n",
                !notOpen, v, i);
        return notOpen && Math.abs(v - 10.0) < 0.05 && Math.abs(i - 1.0) < 0.05;
    }

    /**
     * 电压源+导线+电阻【开路】（2026-08-23 用户：开路时导线 500KA）：
     * 源 a 接导线(1mΩ)+电阻(10Ω) 末端悬空、源 b 悬空 → 源应标记开路（不注入）
     * → 导线电流 ≈ 0（不爆炸）。孤立源仍保持 10V（见 isolatedVoltageSource）。
     */
    private static boolean openVoltageSourceWithLoad() {
        Network net = new Network();
        net.frequency = 50.0;
        var sa = net.addNode(); // 源 a
        var sb = net.addNode(); // 源 b（悬空）
        var w = net.addNode();  // 导线另一端
        var rEnd = net.addNode(); // 电阻末端（悬空）
        AcVoltageSource vs = new AcVoltageSource(sa.id, sb.id, 100.0, 0.0, 1e-4);
        net.addElement(vs);
        net.addElement(new Resistor(sa.id, w.id, 1e-3)); // 导线 1mΩ
        net.addElement(new Resistor(w.id, rEnd.id, 10.0)); // 负载 10Ω，末端悬空
        SolveResult r = Solvers.create(SolveMode.COMPLEX_AC, net).solve(net);
        boolean open = vs.isOpenCircuit();
        double vWire = r.complex[sa.id].sub(r.complex[w.id]).abs();
        double iWire = vWire / 1e-3; // 导线电流 = 压差/1mΩ
        System.out.printf("开路电压源+1mΩ导线+10Ω悬空: openCircuit=%s 导线I=%.6f A "
                + "(期望 open=true, I≈0 不爆)%n", open, iWire);
        return open && iWire < 1.0;
    }

    /** 闭合电路（电压源+导线+电阻）：源两端连通 → 不标记开路 → 正常电流 ≈5A。 */
    private static boolean closedVoltageSourceCircuit() {
        Network net = new Network();
        net.frequency = 50.0;
        var sa = net.addNode();
        var sb = net.addNode();
        var w = net.addNode();
        AcVoltageSource vs = new AcVoltageSource(sa.id, sb.id, 100.0, 0.0, 1e-4);
        net.addElement(vs);
        net.addElement(new Resistor(sa.id, w.id, 1e-3));
        net.addElement(new Resistor(w.id, sb.id, 10.0)); // 闭合
        SolveResult r = Solvers.create(SolveMode.COMPLEX_AC, net).solve(net);
        boolean notOpen = !vs.isOpenCircuit();
        double vWire = r.complex[sa.id].sub(r.complex[w.id]).abs();
        double i = vWire / 1e-3;
        System.out.printf("闭合电压源+1mΩ+10Ω: openCircuit=%s I=%.4f A "
                + "(期望 false, I≈10A)%n", !notOpen, i);
        return notOpen && Math.abs(i - 10.0) < 1.0;
    }
}
