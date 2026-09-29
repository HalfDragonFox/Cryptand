package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.composite.InductionMotorModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;

/**
 * 单相感应电机【稳态开环】离线闸门（2026-09-27）。
 *
 * <p>背景：2026-09-27 用户报“电机会来回跳转速和应力”。根因不在电气侧，而在机械侧
 * 把 ω 当积分状态（J·dω/dt）而 friction=0 → 阻尼比 ζ≈0.001（几乎无阻尼）；感应转矩
 * 在同步点附近又是强负反馈（斜率 ≈33·T_rated/ω_s），二者构成无阻尼谐振子：一旦冲过
 * 同步点就 0↔2ω_s 长期摆动（游戏日志 ω 27↔112、Pout 正负交替、应力 406114 超上限）。
 *
 * <p>修复：取消机械反馈积分，改【稳态开环输出】——转差由负载率 + 电压平方代数给出，
 * ω 直接赋值（与原版电机一致的“电压→输出”式）；再加启动能力判据（启动转矩压不过
 * 轴承静摩擦 + 本机负载 → 堵转）。交流侧公式（Kloss 转矩曲线 + 低速补偿 + V² 修正）照旧。
 *
 * <p>验证：稳态无振荡、空载/满载工作点、欠压、堵转、0Hz、断电滑行、应力上限。
 *
 * <p>运行：{@code ./gradlew :common:runInductionMotorTest}
 */
public final class InductionMotorOpenLoopSelfTest {

    private static final double DT = 0.01;
    private static final int ROUNDS = 2000;
    /** 跳过前若干轮的建立瞬态后再统计“是否振荡” */
    private static final int SETTLE = 5;

    private InductionMotorOpenLoopSelfTest() {
    }

    /** 8 极 2kW 单相感应电机（与游戏内一致：ω_s=78.54 rad/s、T_rated≈26.3 N·m） */
    private static InductionMotorModel motor() {
        return new InductionMotorModel(8, 0, 1, 2, 25.6, 0.0, null,
                new EnergyModel(1.0), false, 0.0);
    }

    /** @return [最终 ω, 最终应力, 建立后最大相邻轮 Δω] */
    private static double[] run(InductionMotorModel m, double vPeak, double loadSU,
                                double freqHz, int rounds) {
        Complex va = new Complex(vPeak, 0);
        Complex vb = new Complex(0, 0);
        m.setMotorLoadStressSU(loadSU);
        double prev = m.rotorSpeedRadS;
        double maxJump = 0;
        for (int i = 0; i < rounds; i++) {
            m.advanceShaft(va, vb, freqHz, DT);
            if (i >= SETTLE) {
                maxJump = Math.max(maxJump, Math.abs(m.rotorSpeedRadS - prev));
            }
            prev = m.rotorSpeedRadS;
        }
        return new double[]{m.rotorSpeedRadS, m.lastStressSU, maxJump};
    }

    private static boolean check(String name, boolean pass, String detail) {
        System.out.printf("%-44s %s  %s%n", name, pass ? "PASS" : "FAIL", detail);
        return pass;
    }

    public static void main(String[] args) {
        Solvers.floatEnabled = false;
        boolean ok = true;

        InductionMotorModel ref = motor();
        double sync = ref.syncRadS();
        double maxStress = ref.maxStress;
        double sigmaRated = ref.tRated() * 16.0 * InductionMotorModel.stressScale();
        System.out.println("==== induction motor open-loop steady-state self test ====");
        System.out.printf("sync=%.2f rad/s (%.0f RPM)  T_rated=%.2f N.m  stressMax=%.0f  sigmaRated=%.0f%n%n",
                sync, sync * 60 / (2 * Math.PI), ref.tRated(), maxStress, sigmaRated);

        // 1) 空载 230V
        double[] nl = run(motor(), 230, 0, 50, ROUNDS);
        ok &= check("A no-load near sync (w >= 0.97 ws)", nl[0] >= 0.97 * sync,
                String.format("w=%.2f (%.0f RPM)", nl[0], nl[0] * 60 / (2 * Math.PI)));
        ok &= check("B no-load steady, no oscillation", nl[2] < 1e-9,
                String.format("maxDw=%.3e", nl[2]));

        // 2) 满载 230V：额定转差 3.3%
        InductionMotorModel fm = motor();
        double[] full = run(fm, 230, fm.maxStress, 50, ROUNDS);
        ok &= check("C full-load = 0.967 ws", Math.abs(full[0] - sync * 0.967) < 0.02 * sync,
                String.format("w=%.2f expect %.2f", full[0], sync * 0.967));
        ok &= check("D full-load steady, no oscillation", full[2] < 1e-9,
                String.format("maxDw=%.3e", full[2]));
        ok &= check("E full-load stress ~ rated", Math.abs(full[1] - sigmaRated) < 0.08 * sigmaRated,
                String.format("sigma=%.0f expect %.0f", full[1], sigmaRated));

        // 3) 空载欠压仍能起转（空载转差本来就小）
        double[] uv = run(motor(), 115, 0, 50, 200);
        ok &= check("F no-load 115V still starts", uv[0] >= 0.9 * sync,
                String.format("w=%.2f (%.0f%%)", uv[0], 100 * uv[0] / sync));

        // 4) 空载深欠压：启动转矩压不过静摩擦 → 堵转
        double[] dv = run(motor(), 35, 0, 50, 200);
        ok &= check("G no-load 35V stalls (no torque)", dv[0] < 1e-3,
                String.format("w=%.3f", dv[0]));

        // 5) 满载欠压：起不来（欠压 + 带载）
        InductionMotorModel fm2 = motor();
        double[] lv = run(fm2, 115, fm2.maxStress, 50, 200);
        ok &= check("H full-load 115V stalls", lv[0] < 1e-3, String.format("w=%.3f", lv[0]));

        // 6) 0Hz：没有旋转磁场 → 起不来
        double[] dc = run(motor(), 230, 0, 0, 200);
        ok &= check("I 0Hz no rotating field -> 0", dc[0] < 1e-3, String.format("w=%.3f", dc[0]));

        // 7) 断电滑行：指数衰减归零，不反向、不发散
        InductionMotorModel co = motor();
        run(co, 230, 0, 50, 200);
        double wBefore = co.rotorSpeedRadS;
        double[] coast = run(co, 0, 0, 50, 400);
        ok &= check("J power-off coasts to 0 (no reverse)", coast[0] >= 0 && coast[0] < 1e-3,
                String.format("w: %.2f -> %.5f", wBefore, coast[0]));

        // 8) 过压：应力不超模型上限
        double[] ov = run(motor(), 380, 0, 50, 200);
        ok &= check("K over-voltage stress <= maxStress", ov[1] <= maxStress + 1e-6,
                String.format("sigma=%.0f / max %.0f", ov[1], maxStress));

        System.out.println(ok ? "\nALL PASS" : "\nSOME FAILED");
        if (!ok) {
            System.exit(1);
        }
    }
}
