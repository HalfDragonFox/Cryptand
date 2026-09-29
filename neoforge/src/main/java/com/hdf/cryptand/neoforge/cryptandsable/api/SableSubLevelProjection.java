/**
 * ===== 亚层投射器（SableSubLevelProjection，2026-09-01） =====
 *
 * 虚拟物理核心算的是【虚拟位姿】（PoseSnapshot: px/py/pz + qx/qy/qz/qw）；
 * 渲染与实体交互（碰撞/射线/破坏）都必须按该位姿把亚层方块【投射】到实际世界——否则
 * 玩家/射线按静态世界位置交互，永远对不上（无法交互/穿透）。
 *
 * 本类是【单一投射事实】：
 *  - 渲染（CryptandChunkedSubLevelRenderData / 客户端）按 pose 生成变换矩阵；
 *  - 碰撞（feedCollectShapes / 服务端）按 pose 的逆变换把实体查询范围转进亚层局部，
 *    或把方块 shape 按平移投影到世界；
 *  - 由于亚层方块在 OFFSET 区域（渲染用），【世界交互用】的投影总是把"初始世界位置"
 *    变换为"物理位姿下的世界位置"（基准 = anchor：初始结构包围盒几何中心）。
 *
 * 数学：
 *   blockWorldPos = pose.position + rot.transform(blockInitPos - anchorInitPos)
 *   其中 pose.position = 物理体当前位置（原子中心）；anchorInitPos = 初始结构包围盒中心
 *   （与 buildImport 初始位姿/渲染 anchor 同基准）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.api;

import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import net.minecraft.core.BlockPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;

public final class SableSubLevelProjection {

    private SableSubLevelProjection() {
    }

    /**
     * 把方块初始世界位置投射为物理位姿下的世界位置。
     *
     * @param pose         亚层最新物理位姿（null = 静止在初始位置）
     * @param anchorInit   初始结构包围盒【浮点几何中心】（[cx,cy,cz]，与 buildImport
     *                     初始位姿 (min+max+1)*0.5 同基准；整数 >>1 会差 0.5 → 错位 1 格）
     * @param blockInitPos 方块的初始世界位置（PlacedBlockSnapshot.worldPos）
     * @return 投射后的世界位置（坐标=方块格，用于 shape.move）
     */
    public static BlockPos projectBlock(final SableMessages.PoseSnapshot pose,
                                        final double[] anchorInit,
                                        final BlockPos blockInitPos) {
        if (pose == null || anchorInit == null || anchorInit.length < 3 || blockInitPos == null) {
            return blockInitPos;
        }
        final Vector3d rel = new Vector3d(
                blockInitPos.getX() + 0.5 - anchorInit[0],
                blockInitPos.getY() + 0.5 - anchorInit[1],
                blockInitPos.getZ() + 0.5 - anchorInit[2]);
        final Quaterniond rot = new Quaterniond(pose.qx(), pose.qy(), pose.qz(), pose.qw()).normalize();
        rot.transform(rel);
        return new BlockPos(
                (int) Math.floor(pose.px() + rel.x - 0.5),
                (int) Math.floor(pose.py() + rel.y - 0.5),
                (int) Math.floor(pose.pz() + rel.z - 0.5));
    }

    /** 兼容重载：整数锚（历史调用方；内部转浮点 +0.5）。 */
    public static BlockPos projectBlock(final SableMessages.PoseSnapshot pose,
                                        final BlockPos anchorInit,
                                        final BlockPos blockInitPos) {
        if (anchorInit == null) return blockInitPos;
        return projectBlock(pose, new double[]{
                anchorInit.getX() + 0.5, anchorInit.getY() + 0.5, anchorInit.getZ() + 0.5
        }, blockInitPos);
    }

    /**
     * 方块 shape 平移量（from 初始位置 → 投射后位置），供 VoxelShape.move(dx,dy,dz)。
     */
    public static double[] projectOffset(final SableMessages.PoseSnapshot pose,
                                         final double[] anchorInit,
                                         final BlockPos blockInitPos) {
        if (pose == null || anchorInit == null || anchorInit.length < 3 || blockInitPos == null) {
            return new double[]{0, 0, 0};
        }
        final BlockPos projected = projectBlock(pose, anchorInit, blockInitPos);
        return new double[]{
                projected.getX() - blockInitPos.getX(),
                projected.getY() - blockInitPos.getY(),
                projected.getZ() - blockInitPos.getZ()
        };
    }

    /** 兼容重载：整数锚（历史调用方）。 */
    public static double[] projectOffset(final SableMessages.PoseSnapshot pose,
                                         final BlockPos anchorInit,
                                         final BlockPos blockInitPos) {
        if (anchorInit == null) return new double[]{0, 0, 0};
        return projectOffset(pose, new double[]{
                anchorInit.getX() + 0.5, anchorInit.getY() + 0.5, anchorInit.getZ() + 0.5
        }, blockInitPos);
    }
}
