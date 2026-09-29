package com.hdf.cryptand.neoforge.cryptandsable.core.destruction;

/**
 * 破坏事件（核心 → 主线程 破坏列表的一项）。
 *
 * <p>当结构中某个局部点承受的应力/冲击超过阈值，核心产出破坏事件增量下行，
 * 主线程据此应用破坏（把方块拆掉/半残）。对齐 C8："如果被破坏则向主线程发送破坏列表"。
 *
 * @param bodyId    所属物理体
 * @param sceneId   所属 scene
 * @param localX/Y/Z 破坏发生的【局部】坐标（相对质心；主线程换算世界+结构内方块）
 * @param severity  破坏强度 0..1（>0.6 拆方快；0.3~0.6 半残裂纹）
 * @param cause     原因分类
 */
public record DestructionEvent(
        int bodyId,
        int sceneId,
        double localX, double localY, double localZ,
        double severity,
        Cause cause
) {
    public enum Cause {
        IMPACT,          // 碰撞冲击
        OVERSTRESS,      // 应力（重力/承重超限）
        CHAMBER_BREACH,  // 气密失效（气室破损）
        FATIGUE          // 疲劳（长期）
    }

    /** 便捷：是否是同类事件的强破坏。 */
    public boolean isSevere() {
        return severity >= 0.6;
    }
}