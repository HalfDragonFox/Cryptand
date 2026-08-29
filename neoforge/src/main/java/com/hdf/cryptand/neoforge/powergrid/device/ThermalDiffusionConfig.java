/**
 * 温度扩散配置（2026-08-18 用户需求）：
 *   - 类型（仅输出 / 仅输入 / 双向）
 *   - 导热系数（W/K）
 *   - 方向（默认全方向 6 面；可设置特定方向，如加热器只向上加热）
 *   - 扩散距离 distance（格数，默认 1 = 仅相邻方块；可传播更远）
 *   - 衰减度 attenuation（0~1，每格衰减比例；<b>0 = 到扩散距离上限前都是同样
 *     传递</b>——每格收到相同热量；0.5 = 每格减半；1 = 只第 1 格）
 *   - 前后方向 forwardBackward（true = 只向方块朝向 + 反方向扩散，按 BE 朝向）
 *   - 风机增强 blownBonus（被鼓风机/风机吹时额外增加的扩散距离格数）
 *
 * 由组装器 {@link Assembler#thermalDiffusion()} 返回；null = 不扩散（普通设备）。
 */
package com.hdf.cryptand.neoforge.powergrid.device;

import net.minecraft.core.Direction;

import java.util.EnumSet;
import java.util.Set;

public final class ThermalDiffusionConfig {

    /** 扩散类型 */
    public final ThermalDiffusionType type;
    /** 导热系数（W/K）——每度温差每秒传导的热量（第 1 格） */
    public final double conductance;
    /** 扩散方向（默认全方向 6 面；forwardBackward=true 时忽略，按朝向前后） */
    public final Set<Direction> directions;
    /** 扩散距离（格数；默认 1 = 仅相邻方块） */
    public final int distance;
    /** 衰减度（0~1，每格衰减比例；0 = 到距离上限前同样传递） */
    public final double attenuation;
    /** 前后方向（true = 只向方块朝向 + 反方向扩散） */
    public final boolean forwardBackward;
    /** 被鼓风机/风机吹时额外增加的扩散距离（格数；0 = 无增强） */
    public final int blownBonus;

    private static final Set<Direction> ALL = EnumSet.allOf(Direction.class);

    private ThermalDiffusionConfig(ThermalDiffusionType type, double conductance,
                                   Set<Direction> dirs, int distance, double attenuation,
                                   boolean forwardBackward, int blownBonus) {
        this.type = type;
        this.conductance = Math.max(conductance, 0);
        this.directions = (dirs == null || dirs.isEmpty()) ? ALL : EnumSet.copyOf(dirs);
        this.distance = Math.max(1, distance);
        this.attenuation = Math.max(0, Math.min(1, attenuation));
        this.forwardBackward = forwardBackward;
        this.blownBonus = Math.max(0, blownBonus);
    }

    /** 仅输出扩散模型（只向周围传热，全方向，距离 1） */
    public static ThermalDiffusionConfig output(double conductance) {
        return new ThermalDiffusionConfig(ThermalDiffusionType.OUTPUT_ONLY, conductance, ALL,
                1, 0, false, 0);
    }

    /** 仅输出扩散模型（距离 + 衰减；衰减 0 = 到距离上限前同样传递） */
    public static ThermalDiffusionConfig output(double conductance, int distance,
                                                double attenuation) {
        return new ThermalDiffusionConfig(ThermalDiffusionType.OUTPUT_ONLY, conductance, ALL,
                distance, attenuation, false, 0);
    }

    /** 仅输入扩散模型（只接受外部传热，全方向，距离 1） */
    public static ThermalDiffusionConfig input(double conductance) {
        return new ThermalDiffusionConfig(ThermalDiffusionType.INPUT_ONLY, conductance, ALL,
                1, 0, false, 0);
    }

    /** 仅输入扩散模型（距离 + 衰减） */
    public static ThermalDiffusionConfig input(double conductance, int distance,
                                               double attenuation) {
        return new ThermalDiffusionConfig(ThermalDiffusionType.INPUT_ONLY, conductance, ALL,
                distance, attenuation, false, 0);
    }

    /** 双向扩散模型（热 → 冷双向交互，全方向，距离 1） */
    public static ThermalDiffusionConfig bidirectional(double conductance) {
        return new ThermalDiffusionConfig(ThermalDiffusionType.BIDIRECTIONAL, conductance, ALL,
                1, 0, false, 0);
    }

    /** 双向扩散模型（距离 + 衰减） */
    public static ThermalDiffusionConfig bidirectional(double conductance, int distance,
                                                       double attenuation) {
        return new ThermalDiffusionConfig(ThermalDiffusionType.BIDIRECTIONAL, conductance, ALL,
                distance, attenuation, false, 0);
    }

    /** 设置特定方向（如只向上/只向前；null/空 → 全方向） */
    public ThermalDiffusionConfig withDirections(Set<Direction> dirs) {
        return new ThermalDiffusionConfig(type, conductance, dirs, distance, attenuation,
                forwardBackward, blownBonus);
    }

    /** 前后方向（true = 只向方块朝向 + 反方向扩散，按 BE 朝向） */
    public ThermalDiffusionConfig forwardBackward(boolean fb) {
        return new ThermalDiffusionConfig(type, conductance, directions, distance, attenuation,
                fb, blownBonus);
    }

    /** 被鼓风机/风机吹时额外增加的扩散距离（格数） */
    public ThermalDiffusionConfig blownBonus(int bonus) {
        return new ThermalDiffusionConfig(type, conductance, directions, distance, attenuation,
                forwardBackward, bonus);
    }
}
