package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

/**
 * 介质类型枚举（CryptandSable 环境介质模型）。
 *
 * <p>支持陆地 / 海洋 / 空中 / 宇宙四类物理介质，是刚体/柔体所在物理环境的定性分类。
 * 每个位置的环境由 {@link DimensionEnvConfig}→{@link BiomeEnvConfig}→{@link EnvironmentRegion}
 * →{@link BlockEnvFlag} 分层合成（优先级由低到高）。
 */
public enum MediaType {
    /** 陆地：空气介质、标准重力、标准气压。 */
    GROUND,
    /** 海洋：水体介质、浮力、粘滞阻力、水压。 */
    WATER,
    /** 空中：空气介质、气压梯度、风。 */
    ATMOSPHERE,
    /** 宇宙：真空、无介质阻力、微重力/无重力、辐射。 */
    SPACE;

    /** 是否属于可在其中自由呼吸的环境（可呼吸介质）。 */
    public boolean breathable() {
        return this == GROUND || this == ATMOSPHERE;
    }

    /** 是否对物体产生浮力（与气室净浮力计算相关）。 */
    public boolean hasBuoyancy() {
        return this == WATER || this == ATMOSPHERE;
    }

    /** 介质是否充满整个空间（真空=false，无介质）。 */
    public boolean hasMedium() {
        return this != SPACE;
    }
}