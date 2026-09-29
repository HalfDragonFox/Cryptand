package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import org.joml.Vector3dc;

/**
 * 环境快照（EnvironmentSnapshot）。
 *
 * <p>worker 线程不持 Level → 主线程把当前维度/区域解析出的最终环境属性
 * （重力/介质密度/气压等）作为【不可变快照】发给 worker 缓存使用。
 * 对齐数据归属矩阵：主线程只读 Level 产快照，worker 只消费快照。
 */
public record EnvironmentSnapshot(
        MediaProperties properties,
        long updatedTick
) {
    public static final EnvironmentSnapshot DEFAULT =
            new EnvironmentSnapshot(MediaProperties.DEFAULT_GROUND, 0L);

    /** 便捷：重力矢量（绝不可变复制返回）。 */
    public Vector3dc gravity() {
        return properties.gravity();
    }

    /** 便捷：介质密度 [kg/m³]。 */
    public double mediumDensity() {
        return properties.mediumDensity();
    }

    /** 便捷：介质类型。 */
    public MediaType mediaType() {
        return properties.mediaType();
    }

    /** 便捷：是否可呼吸。 */
    public boolean breathable() {
        return properties.breathable();
    }
}