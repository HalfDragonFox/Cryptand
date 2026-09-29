package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

/**
 * 引擎分发规则（EngineDispatchRule）—— 多引擎模式下「按什么规则把参数路由到哪台引擎」。
 *
 * <p>2026-09-02 动态引擎分发：核心只认 {@link EngineApi}；本规则决定
 * 每个刚体（结构/虚拟体）落到哪台引擎（f32/f64/Box3D...）。
 *
 * <p>内置规则：
 * <ul>
 *   <li>{@link CoordThresholdRule} —— 按世界坐标路由：|坐标| 小 → 低精度引擎（默认 f32），
 *       |坐标| 大 → 高精度引擎（默认 f64）。f32 远离原点坐标会抖动，f64 稳定——正是用户需求
 *       （「坐标小时使用 F32 模型，坐标大时采用 F64」）。</li>
 *   <li>{@link SingleIdRule} —— 全部路由到某 id（等效单引擎，供退化/诊断）。</li>
 * </ul>
 *
 * <p>可扩展：实现本接口即可接入其他「一定规则」（如按结构类型/体积/运行时负载）。
 */
public interface EngineDispatchRule {

    /** 给定刚体世界坐标（anchor/质心），返回目标引擎 id。 */
    String selectEngineId(double x, double y, double z);

    // ===== 内置实现 =====

    /**
     * 按坐标阈值路由：max(|x|,|y|,|z|) &gt;= threshold → 高精度引擎；否则低精度引擎。
     * 两引擎 id 可由配置指定（默认 low=rapier-f32 / high=rapier-f64）。
     */
    record CoordThresholdRule(String lowPrecisionId, String highPrecisionId, double threshold)
            implements EngineDispatchRule {

        /** 坐标路由默认阈值：1e6 格（超过 → f64 高精度；sable 大坐标可达上千万级）。 */
        public static final double DEFAULT_COORD_THRESHOLD = 1_000_000.0;

        public CoordThresholdRule {
            if (threshold < 0) threshold = 0;
            if (lowPrecisionId == null || lowPrecisionId.isBlank()) lowPrecisionId = "rapier-f32";
            if (highPrecisionId == null || highPrecisionId.isBlank()) highPrecisionId = "rapier-f64";
        }

        public CoordThresholdRule(String low, String high) {
            this(low, high, DEFAULT_COORD_THRESHOLD);
        }

        @Override
        public String selectEngineId(double x, double y, double z) {
            double m = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z));
            return m >= this.threshold ? this.highPrecisionId : this.lowPrecisionId;
        }
    }

    /** 全部路由到固定引擎（等效单引擎；默认/退化/诊断用）。 */
    record SingleIdRule(String engineId) implements EngineDispatchRule {
        @Override
        public String selectEngineId(double x, double y, double z) {
            return this.engineId;
        }
    }
}