package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 环境介质属性（分层合成后的最终结果）。
 *
 * <p>由 {@link DimensionEnvConfig}→{@link BiomeEnvConfig}→{@link EnvironmentRegion}→{@link BlockEnvFlag}
 * 逐层覆盖合成。所有字段为不可变的环境物理参数，供核心的 mass/buoyancy/step 阶段消费。
 *
 * @param mediaType      介质类型
 * @param gravity        重力矢量 [m/s²]（宇宙微重力为小值或零）
 * @param mediumDensity  介质密度 [kg/m³]（水≈1000，空气≈1.225）
 * @param linearDrag     线性阻力系数 [1/s]
 * @param quadraticDrag  平方阻力系数 [1/m]
 * @param pressure       环境气压 [Pa]
 * @param breathable     是否可呼吸
 */
public record MediaProperties(
        MediaType mediaType,
        Vector3dc gravity,
        double mediumDensity,
        double linearDrag,
        double quadraticDrag,
        double pressure,
        boolean breathable
) {
    /** 标准陆地环境（主世界默认）。 */
    public static final MediaProperties DEFAULT_GROUND = new MediaProperties(
            MediaType.GROUND,
            new Vector3d(0.0, -9.81, 0.0),
            1.225,
            0.10,
            0.001,
            101325.0,
            true
    );

    /** 默认海洋环境。 */
    public static final MediaProperties DEFAULT_WATER = new MediaProperties(
            MediaType.WATER,
            new Vector3d(0.0, -9.81, 0.0),
            1000.0,
            1.5,
            0.35,
            101325.0,
            false
    );

    /** 默认空中环境（高空）。 */
    public static final MediaProperties DEFAULT_ATMOSPHERE = new MediaProperties(
            MediaType.ATMOSPHERE,
            new Vector3d(0.0, -9.81, 0.0),
            1.225,
            0.05,
            0.0005,
            40000.0,
            true
    );

    /** 默认宇宙环境（真空、微重力）。 */
    public static final MediaProperties DEFAULT_SPACE = new MediaProperties(
            MediaType.SPACE,
            new Vector3d(0.0, -0.1, 0.0),
            0.0,
            0.0,
            0.0,
            0.0,
            false
    );

    /** 以当前属性为基础，仅叠加某维度的"覆盖项"。 */
    public MediaProperties with(MediaProperties override) {
        return new MediaProperties(
                mediaType,
                override.gravity != null ? override.gravity : this.gravity,
                override.mediumDensity,
                override.linearDrag,
                override.quadraticDrag,
                override.pressure,
                override.breathable
        );
    }

    @Override
    public String toString() {
        return "MediaProperties[" + mediaType + ", g=" + gravity + "]";
    }
}