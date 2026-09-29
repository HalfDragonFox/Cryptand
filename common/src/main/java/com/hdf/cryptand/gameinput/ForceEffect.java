package com.hdf.cryptand.gameinput;

/**
 * 力反馈效果类型（DirectInput FFB 标准效果；方向盘专属扩展能力）。
 * <ul>
 *   <li>{@link #CONSTANT}：恒定力（回正阻尼、路面恒定阻力）；</li>
 *   <li>{@link #SPRING}：弹簧回中力（随转向角偏移产生回正手感——方向盘首选）；</li>
 *   <li>{@link #DAMPER}：阻尼（随转向速度产生阻力，液压手感）；</li>
 *   <li>{@link #SINE}：正弦周期力（引擎振动/路面颠簸）；</li>
 *   <li>{@link #FRICTION}：摩擦力；</li>
 *   <li>{@link #INERTIA}：惯性力。</li>
 * </ul>
 */
public enum ForceEffect {
    CONSTANT, SPRING, DAMPER, SINE, FRICTION, INERTIA
}
