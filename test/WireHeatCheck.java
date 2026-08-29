/** 一次性验证：导线温度双推进 vs 单推进（修复"5A 电流导线 300°C"） */
public class WireHeatCheck {
    public static void main(String[] args) {
        // 模拟金导线 1m：R=0.0015Ω，5A RMS → 峰值 7.07A
        double R = 0.0015, len = 1.0;
        double G = 2.0 * len, C = 5.0 * len;  // 2 W/K/m, 5 J/K/m
        double amb = 298.15, max = 473.15;
        double tau = C / G, powerAvg = 25 * R; // I_rms²R = 0.0375W
        double Tss = amb + powerAvg / G;        // 稳态 ≈ 298.17K = 25.02°C

        // 模拟推进：真实时间每 100ms 推进（dedup），共 60s
        double T = amb;
        double dt = 0.1;
        // 解析解
        for (int i = 0; i < 600; i++) {
            T = Tss + (T - Tss) * Math.exp(-dt / tau);
        }
        System.out.printf("单推进 60s 后: %.2f°C (稳态 %.2f°C)\n", T - 273.15, Tss - 273.15);

        // 模拟双推进：同一段每 100ms 被推进两次（advancePseudoTime + computeWireHeatOne）
        double T2 = amb;
        for (int i = 0; i < 600; i++) {
            for (int k = 0; k < 2; k++) {
                T2 = Tss + (T2 - Tss) * Math.exp(-dt / tau);
            }
        }
        System.out.printf("双推进 60s 后: %.2f°C\n", T2 - 273.15);
        System.out.println("结论: " + (T2 > 300 ? "双推进 → 温度显著更高（与 300°C 症状吻合）" : "双推进影响不大"));
    }
}
