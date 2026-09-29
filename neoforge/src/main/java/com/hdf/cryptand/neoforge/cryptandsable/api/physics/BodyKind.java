package com.hdf.cryptand.neoforge.cryptandsable.api.physics;

/**
 * 物理体类型（BodyKind）。
 *
 * <p>核心对刚体/柔体不区分具体结构（对齐 C8）——它们只是 {@link Kind#RIGID} 与
 * {@link Kind#SOFT} 两种模拟模式的差异，由 {@link BodyParams} 参数分隔，而非两种实体。
 */
public enum BodyKind {
    /** 刚体：单一体姿积分（位置/朝向/速度/惯量）。 */
    RIGID,
    /** 柔体：质量粒子 + 内部约束网络积分（绳索/悬挂/可展结构）。 */
    SOFT
}