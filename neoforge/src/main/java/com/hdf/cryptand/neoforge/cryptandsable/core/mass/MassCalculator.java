package com.hdf.cryptand.neoforge.cryptandsable.core.mass;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * 质量/质心/惯量计算（核心承担，主线程不预构建）。
 *
 * <p>给定结构的块密度分布（局部 voxel 网格）计算总质量、质心（局部）与惯量张量。
 * 这些结果全部由核心在导入结构时算出并维护（对齐 C5），主线程只发"方块构成"。
 *
 * <p>本类为纯函数式无状态工具：输入像素密度体素数组，输出 MassResult。
 * 采用体素累加（每个方块贡献 dm=ρ*V0，质心加权平均，惯量按平行轴定理累加）。
 */
public final class MassCalculator {
    private MassCalculator() {}

    /** 单个体素（一立方米方块）的基准体积。 */
    public static final double VOXEL_VOLUME = 1.0;

    /**
     * 质量+质心+惯量计算结果（全部局部坐标）。
     *
     * @param totalMass       总质量 [kg]
     * @param centerOfMass    质心 [m]（局部）
     * @param inertiaTensor   绕质心的惯性张量 [kg·m²]（局部）
     */
    public record MassResult(double totalMass, Vector3d centerOfMass, Matrix3d inertiaTensor) {
    }

    /**
     * 计算体素网格的质量属性。
     *
     * @param density 个方块密度 [kg/m³]；负数/零视为空穴（不计质量）。
     * @param sizeX/sizeY/sizeZ 网格尺寸（各维度体素数）。
     */
    public static MassResult calculate(double[] density, int sizeX, int sizeY, int sizeZ) {
        int n = sizeX * sizeY * sizeZ;
        double totalMass = 0.0;
        Vector3d com = new Vector3d();

        // 第一遍：总质量 + 质心
        for (int i = 0; i < n; i++) {
            double dm = density[i] * VOXEL_VOLUME;
            if (dm <= 0.0 || Double.isNaN(dm)) continue;
            int x = i % sizeX;
            int z = (i / sizeX) % sizeZ;
            int y = i / (sizeX * sizeZ);
            // 体素中心坐标（半个偏移）
            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            totalMass += dm;
            com.add(cx * dm, cy * dm, cz * dm);
        }
        if (totalMass <= 0.0) {
            return new MassResult(0.0, new Vector3d(), new Matrix3d());
        }
        com.mul(1.0 / totalMass);

        // 第二遍：绕质心的惯量（平行轴定理）
        Matrix3d inertia = new Matrix3d();
        for (int i = 0; i < n; i++) {
            double dm = density[i] * VOXEL_VOLUME;
            if (dm <= 0.0 || Double.isNaN(dm)) continue;
            int x = i % sizeX;
            int z = (i / sizeX) % sizeZ;
            int y = i / (sizeX * sizeZ);
            double lx = (x + 0.5) - com.x;
            double ly = (y + 0.5) - com.y;
            double lz = (z + 0.5) - com.z;
            double lx2 = lx * lx, ly2 = ly * ly, lz2 = lz * lz;
            // 单位立方体绕自身质心的惯量 = dm/6 各轴（边长1，I=dm*(1²/6)）
            double diag = dm / 6.0;
            inertia.m00 += diag + dm * (ly2 + lz2);
            inertia.m11 += diag + dm * (lx2 + lz2);
            inertia.m22 += diag + dm * (lx2 + ly2);
            inertia.m01 += -dm * lx * ly;
            inertia.m10 += -dm * lx * ly;
            inertia.m02 += -dm * lx * lz;
            inertia.m20 += -dm * lx * lz;
            inertia.m12 += -dm * ly * lz;
            inertia.m21 += -dm * ly * lz;
        }

        return new MassResult(totalMass, com, inertia);
    }
}