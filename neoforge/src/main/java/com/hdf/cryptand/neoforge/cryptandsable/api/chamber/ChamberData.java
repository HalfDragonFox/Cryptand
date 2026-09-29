package com.hdf.cryptand.neoforge.cryptandsable.api.chamber;

/**
 * 气室虚拟容器快照（ChamberData）。
 *
 * <p>气室 = 一个<b>虚拟容积容器</b>（原版 Sable 无此概念，CryptandSable 自实现）。
 * 按 "空气室=虚拟容积空间" 的物理语义建模：
 * <ul>
 *   <li><b>虚拟容积</b>：外壳包围的总体积（排开体积）→ 决定浮力/升力；</li>
 *   <li><b>内部内容</b>：实心方块(降低可容气空间) + 流体 + 气体（经
 *       {@code virtualVolume - solidVolume} 得到 freeSpace）；</li>
 *   <li><b>密度</b>：由内部内容 + 气体量/类型决定（换算进刚体总质量）；</li>
 *   <li><b>气压</b>：P = nRT/V（n=gasAmount mol），加压/通入气体/压缩改变；</li>
 *   <li><b>失压</b>：缺口 → sealLevel 下降 → gasAmount 泄漏 → 气压/浮力下降；</li>
 *   <li><b>计算只针对虚拟容积</b>，模拟现实情况（升力/压载舱/太空舱）。</li>
 * </ul>
 *
 * <p>为<b>省内存/CPU</b>：用标量参数（gasMolarMass）区分气体类型，不做混合气体注册表；
 * 每个刚体默认一个聚合气室（单 entry）。
 *
 * @param chamberId     气室 id
 * @param bodyId        所属刚体
 * @param virtualVolume 总虚拟容积（外壳体积）[m³]
 * @param solidVolume   内部实心方块体积 [m³]
 * @param gasAmount     气体摩尔数 [mol]（加压/通入时增加；泄漏时下降）
 * @param gasMolarMass  混合气体摩尔质量 [g/mol]（空气≈29 氢≈2 氦≈4 用于区分气体类型）
 * @param sealLevel     气密性 [0..1]（1=完全密封；缺口→下降→失压）
 * @param pressure      内部气压 [Pa] = nRT/V
 * @param temperature   内部温度 [K]（默认环境）
 * @param liftForce     净浮力/升力 [N]（正向=抬升）
 */
public record ChamberData(
        int chamberId,
        int bodyId,
        double virtualVolume,
        double solidVolume,
        double gasAmount,
        double gasMolarMass,
        double sealLevel,
        double pressure,
        double temperature,
        double liftForce
) {
    public static final int GAS_AIR = 0;
    public static final int GAS_HYDROGEN = 1;
    public static final int GAS_HELIUM = 2;

    /** 标准空气摩尔质量 [g/mol]。 */
    public static final double MOLAR_AIR = 28.97;
    /** 氢气 [g/mol]。 */
    public static final double MOLAR_HYDROGEN = 2.016;
    /** 氦气 [g/mol]。 */
    public static final double MOLAR_HELIUM = 4.003;
    /** 理想气体常数 [J/(mol·K)]。 */
    public static final double GAS_R = 8.314;

    /** 可容气空间 [m³]（虚拟容积 - 实心占用；最小 0）。 */
    public double freeSpace() {
        return Math.max(0.0, virtualVolume - solidVolume);
    }

    /** 内部气体总体密度 [kg/m³]（gasAmount × 摩尔质量 / 自由空间）。 */
    public double gasDensity() {
        double fs = freeSpace();
        if (fs <= 1e-9) return 0.0;
        return gasAmount * (gasMolarMass / 1000.0) / fs;
    }

    /** 内部气体总质量 [kg]。 */
    public double gasMass() {
        return gasAmount * (gasMolarMass / 1000.0);
    }

    /** 是否仍能产生至少部分气压/浮力（有气且未完全破封）。 */
    public boolean isActive() {
        return gasAmount > 1e-6 && sealLevel > 0.0001;
    }
}