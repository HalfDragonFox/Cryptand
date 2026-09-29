package com.hdf.cryptand.neoforge.cryptandsable.api.physics;

/**
 * 统一物理体参数（BodyParams）。
 *
 * <p>刚体/柔体【同一个物理体】，仅通过本参数对象区分模拟模式（对齐 C8 + 用户需求：
 * "刚体柔体是否能同一个，通过不同参数实现分隔" → 能）。参数差异：
 * <ul>
 *   <li>{@link BodyKind#RIGID}：只解释 mass/inertia；粒子数为 0。</li>
 *   <li>{@link BodyKind#SOFT}：解释 particleCount/constraintType/constraintStiffness/restLength；
 *       积分走粒子-约束路径。</li>
 * </ul>
 */
public final class BodyParams {
    public final BodyKind kind;
    public final double mass;

    // 柔体参数（kind==SOFT 时有效）
    public final int particleCount;
    public final int constraintType;          // 见下方 CONSTRAINT_*
    public final double constraintStiffness;  // [0..1] 越高越"刚"
    public final double restLength;           // 初始静止长度 [m]
    public final boolean weldToNeighbors;     // 是否把端点焊接（悬挂点）

    public static final int CONSTRAINT_ROPE = 0;   // 绳/链（只拉不推）
    public static final int CONSTRAINT_SPRING = 1; // 弹簧（双向）
    public static final int CONSTRAINT_RIGID_LINK = 2; // 近刚连杆

    private BodyParams(BodyKind kind, double mass, int particleCount, int constraintType,
                       double constraintStiffness, double restLength, boolean weldToNeighbors) {
        this.kind = kind;
        this.mass = mass;
        this.particleCount = particleCount;
        this.constraintType = constraintType;
        this.constraintStiffness = constraintStiffness;
        this.restLength = restLength;
        this.weldToNeighbors = weldToNeighbors;
    }

    /** 刚体参数。 */
    public static BodyParams rigid(double mass) {
        return new BodyParams(BodyKind.RIGID, mass, 0, CONSTRAINT_ROPE, 0, 0, false);
    }

    /** 柔体参数。 */
    public static BodyParams soft(int particleCount, int constraintType, double stiffness, double restLength) {
        return new BodyParams(BodyKind.SOFT, 0, particleCount, constraintType, stiffness, restLength, false);
    }

    public boolean isRigid() {
        return kind == BodyKind.RIGID;
    }

    public boolean isSoft() {
        return kind == BodyKind.SOFT;
    }
}