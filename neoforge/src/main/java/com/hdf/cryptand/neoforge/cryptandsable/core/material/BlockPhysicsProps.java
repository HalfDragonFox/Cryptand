/**
 * ===== 方块物理属性（BlockPhysicsProps，2026-09-05） =====
 *
 * 参考原版 sable（PhysicsBlockPropertyTypes + FloatingBlockMaterial）的方块物理属性
 * 数据语义，但在 Cryptand 框架下做【纯数据】建模——不持有 BlockState / Level 引用，
 * 全链路只传 materialId（int）+ 本 record，核心零 MC 依赖。
 *
 * 每个【材质】一套属性；方块类型 → 材质 的映射由 BlockPhysicsTable 负责（主线程
 * 构图时解析，纯数据进核心）。
 *
 * 字段语义（对齐原版）：
 *  - mass        方块质量 [kpg]，默认 1.0（原版 MASS 默认 1.0）
 *  - friction    摩擦系数，默认 0.6（Cryptand 原 createCompoundShapeBody 硬编码）
 *  - restitution 弹性 0~1，默认 0.0（原版 RESTITUTION 默认 0.0）
 *  - volume      浮力/排水体积乘数，默认 1.0（原版 VOLUME）
 *  - liftStrength 升力强度 [N/格]（>0 = 升力提供方块，如气球/机翼/浮力材质；
 *                原版 FloatingBlockMaterial.liftStrength / BlockSubLevelLiftProvider）
 *
 * 三种力建模（每个结构完整）：
 *  - 质心     ：由 mass 加权（Σm·pos/Σm）——刚体属性
 *  - 升力中心 ：liftStrength>0 的方块聚类加权位置，总升力作用于该点
 *  - 浮力中心 ：volume 加权（浸没/浮力方块），浮力作用于该点
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.material;

/** 方块物理属性（不可变纯数据；核心只认 materialId + 本 record）。 */
public record BlockPhysicsProps(
        double mass,
        double friction,
        double restitution,
        double volume,
        double liftStrength
) {

    /** 默认（普通硬方块）：质量 1.0、摩擦 0.6、无弹性、体积 1.0、无升力。 */
    public static final BlockPhysicsProps DEFAULT =
            new BlockPhysicsProps(1.0, 0.6, 0.0, 1.0, 0.0);

    // ===== 常用材质预设（质量≈真实密度比例；弹性/摩擦可调） =====

    /** 石头/石制：密度约 2.5。 */
    public static final BlockPhysicsProps STONE =
            new BlockPhysicsProps(2.5, 0.6, 0.0, 1.0, 0.0);

    /** 木材/木板：密度约 0.7，略有弹性。 */
    public static final BlockPhysicsProps WOOD =
            new BlockPhysicsProps(0.7, 0.4, 0.1, 1.0, 0.0);

    /** 金属/铁块：密度约 7.8，低摩擦。 */
    public static final BlockPhysicsProps METAL =
            new BlockPhysicsProps(7.8, 0.3, 0.0, 1.0, 0.0);

    /** 泥土/沙：密度约 1.5，高摩擦。 */
    public static final BlockPhysicsProps DIRT =
            new BlockPhysicsProps(1.5, 0.8, 0.0, 1.0, 0.0);

    /** 玻璃/冰：密度约 2.5，极低摩擦。 */
    public static final BlockPhysicsProps GLASS =
            new BlockPhysicsProps(2.5, 0.05, 0.1, 1.0, 0.0);

    /** 粘液块：高弹性（弹跳板，原版 bouncy restitution 0.5 级别）。 */
    public static final BlockPhysicsProps SLIME =
            new BlockPhysicsProps(1.0, 0.2, 0.8, 1.0, 0.0);

    /** 海绵/轻质：低质量，体积大（易受浮力）。 */
    public static final BlockPhysicsProps SPONGE =
            new BlockPhysicsProps(0.3, 0.6, 0.0, 1.0, 0.0);

    /** 升力方块（气球/气垫）：低质量 + 强升力。 */
    public static final BlockPhysicsProps LIFT =
            new BlockPhysicsProps(0.2, 0.4, 0.1, 1.0, 8.0);

    /** 浮力方块（船体/软木）：低密度 + 大排水体积。 */
    public static final BlockPhysicsProps FLOAT =
            new BlockPhysicsProps(0.5, 0.4, 0.0, 4.0, 0.0);

    /** 是否升力提供方块（liftStrength > 0）。 */
    public boolean isLiftProvider() {
        return this.liftStrength > 0.0;
    }

    /** 是否参与浮力（volume > 0）。 */
    public boolean isBuoyant() {
        return this.volume > 0.0;
    }

    /** 结构级综合摩擦（体积加权用——调用方传 mass 权重即可；此处仅展示语义）。 */
    public double frictionScaledByMass() {
        return this.friction * this.mass;
    }
}
