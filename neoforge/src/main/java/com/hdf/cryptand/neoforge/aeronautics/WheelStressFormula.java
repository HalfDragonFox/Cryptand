/**
 * ===== 轮子应力换算公式（2026-08-30 用户简化：去掉摩擦力/衰减/空转固定模型） =====
 *
 * 用户：\"应力消耗固定消耗为16x，即1转速16应力的基础上进行调节，然后现在的话
 * 其他摩擦力之类的都不需要了，直接根据此轮胎所受的重量来额外消耗应力\"。
 *
 * 模型（Create 系数×转速语义，天然静止=0）：
 *    系数 = baseImpactPerRpm + N_kg × weightPerKg
 *    SU   = 系数 × |rpm|
 *   - baseImpactPerRpm：基础系数（原版轮子 16：1 转速=16 应力；256RPM→4096 基线）
 *   - N_kg：轮胎所受重量（kg，Sable 结构质量；客户端经 NBT 同步）
 *   - weightPerKg：每 kg 载荷给系数加的额外值（\"按重量额外消耗应力\"）
 *
 * 规律（涌现）：空载 N=0 → 维持原版 16×rpm；重载 → 系数↑ → 应力↑；静止 rpm=0 → 0。
 * 纯公式零 MC 依赖，可单元测试。铁律 5：无阈值分支，clamp 仅数值稳定。
 */
package com.hdf.cryptand.neoforge.aeronautics;

public final class WheelStressFormula {

    private WheelStressFormula() {
    }

    /**
     * 轮子应力（SU）。
     *
     * @param baseImpactPerRpm 基础系数（默认 16：1 转速=16 应力）
     * @param normalN          轮胎所受重量 N（kg；≥0；0=悬空/无载）
     * @param weightPerKg      每 kg 载荷额外系数（≥0；0 = 只基础不按重量）
     * @param rpm              转速（Create getSpeed RPM 语义；取绝对值）
     * @return 应力 SU（≥0；静止 rpm=0 → 0）
     */
    public static double stressOf(double baseImpactPerRpm, double normalN,
                                  double weightPerKg, double rpm) {
        double base = (Double.isFinite(baseImpactPerRpm) && baseImpactPerRpm >= 0)
                ? baseImpactPerRpm : 0;
        double n = (Double.isFinite(normalN) && normalN >= 0) ? normalN : 0;
        double w = (Double.isFinite(weightPerKg) && weightPerKg >= 0)
                ? weightPerKg : 0;
        double sp = Double.isFinite(rpm) ? Math.abs(rpm) : 0;
        double coef = base + n * w;
        return coef * sp;
    }
}