package com.hdf.cryptand.neoforge.railway.pantograph;

/**
 * 受电弓几何参数（移植自 CEE PantographType）。
 * reach：臂展；backOffset/topOffset：滑触板相对底座偏移；sidewaysReach：侧向触达。
 */
public final class PantographType {
    public final float reach;
    public final float backOffset;
    public final float topOffset;
    public final float sidewaysReach;

    public PantographType(float reach, float backOffset, float topOffset, float sidewaysReach) {
        this.reach = reach;
        this.backOffset = backOffset;
        this.topOffset = topOffset;
        this.sidewaysReach = sidewaysReach;
    }

    /** 标准单受电弓（CEE 参数） */
    public static final PantographType STANDARD = new PantographType(3.25f, 0.25f, 0.5f, 2f);
    /** 双受电弓（并排，CEE 参数） */
    public static final PantographType DOUBLE = new PantographType(3.25f, 0.5f, 0.5f, 2f);
}