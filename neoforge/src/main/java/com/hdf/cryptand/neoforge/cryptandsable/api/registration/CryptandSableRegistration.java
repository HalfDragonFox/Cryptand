package com.hdf.cryptand.neoforge.cryptandsable.api.registration;

import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.api.physics.BodyParams;

/**
 * 核心专用注册 API（方块刚体等）。
 *
 * <p>供其他 mod（CEE/Aeronautics/Create/第三方）把世界中的方块结构注册进 CryptandSable 物理核心。
 * 只依赖接口不依赖实现（对齐 C11）：调用方只需引用本类与消息负载，不触碰 core 内部。
 *
 * <p>典型用法：
 * <pre>{@code
 * int id = CryptandSableRegistration.importBlockRigidBody(
 *     cx, cy, cz, localBounds, density, BodyParams.rigid(0)
 * );
 * // density 即每个方块的密度[kg/m³]（核心算出质量/质心/惯量）
 * }</pre>
 */
public final class CryptandSableRegistration {
    private CryptandSableRegistration() {}

    /**
     * 注册一块"方块刚体"（整块结构作为一个刚体）。
     *
     * <p>allDensity 按 (x + z*sx + y*(sx*sz)) 展开；为 null 时按每个体素 1000 kg/m³ 假设计算。
     * 质心/质量/惯量由核心自算（对齐 C5）。
     *
     * @return 分配到的 runtimeId（0 表示核心未启动/注册失败）
     */
    public static int importBlockRigidBody(
            double px, double py, double pz,
            double qx, double qy, double qz, double qw,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int[] voxelDensity,
            BodyParams params
    ) {
        CryptandSable core = CryptandSable.instance();
        if (!core.isStarted()) return 0;
        // 核心分配 runtimeId（同步约定：主线程在 worker 外申请）
        int runtimeId = core.allocateRuntimeId();
        core.importBody(new SableMessages.BodyImport(
                runtimeId, 0,
                px, py, pz, qx, qy, qz, qw,
                minX, minY, minZ, maxX, maxY, maxZ,
                voxelDensity,
                params.isSoft() ? 1 : 0,
                params // 真实参数透传（刚/柔统一：粒子/约束/刚度）
        ));
        return runtimeId;
    }

    /** 便捷重载：默认 1000kg/m³ 密度、单位朝向。 */
    public static int importBlockRigidBody(
            double px, double py, double pz,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            BodyParams params
    ) {
        return importBlockRigidBody(px, py, pz, 0, 0, 0, 1,
                minX, minY, minZ, maxX, maxY, maxZ, null, params);
    }

    /** 导入一个柔体（粒子+约束参数经 BodyParams.soft 给出）。 */
    public static int importSoftBody(
            double px, double py, double pz, BodyParams params
    ) {
        CryptandSable core = CryptandSable.instance();
        if (!core.isStarted()) return 0;
        int runtimeId = core.allocateRuntimeId();
        // MVP：柔体先以空 voxel 导入，粒子布局由 setParams 在核心侧创建
        core.importBody(new SableMessages.BodyImport(
                runtimeId, 0,
                px, py, pz, 0, 0, 0, 1,
                0, 0, 0, 0, 0, 0, null,
                1,
                params // 柔体真实参数（粒子/约束/刚度）
        ));
        return runtimeId;
    }
}