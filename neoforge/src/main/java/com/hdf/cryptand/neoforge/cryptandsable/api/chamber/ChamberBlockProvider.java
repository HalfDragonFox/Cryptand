package com.hdf.cryptand.neoforge.cryptandsable.api.chamber;

/**
 * 气室注册接口（供 mod 把"方块 → 气室属性"注册进核心）。
 *
 * <p>每个被注册为气室的方块/结构块给核心提供：等效容积系数 + 气密性 + 可含气体。
 * core/chamber 据此在 step 前计算净浮力/气压变化并应用到刚体。
 */
public interface ChamberBlockProvider {
    /**
     * 该方块在结构中的等效容积系数 [0..1]（0=不贡献容积，1=满格容积）。
     * 半空方块/玻璃等可给 0.5 之类。
     */
    double volumeCoefficient();

    /** 该方块自身的气密性贡献 [0..1]。 */
    double sealContribution();

    /** 可容纳的气体类型（见 ChamberData.GAS_*）。 */
    default int gasType() {
        return ChamberData.GAS_AIR;
    }
}