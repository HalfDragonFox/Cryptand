/**
 * ===== 变压器参数模型（魔法数字参数化 + 基类接口更新） =====
 *
 * <b>设计原则：基础元件已承载电气功能，参数模型只定义物理损耗 + 结构规格。</b>
 *   - 漏感：由基础互感元件 {@code MutualInductor} 按耦合系数自动产生
 *     （漏感 = (1-cf)·L），【不定义漏感参数】
 *   - 铜阻：基础 {@code Resistor} 元件的电阻功能，值由导线规格推导
 *   - 自感/互感：基础电感元件的电气值，由铁心规格推导
 *
 * 参数模型只定义：
 *   - 【物理损耗参数】铁损因子、温度（散热值/热容/环境/最高温）
 *   - 【结构规格】铁心 coreAl、最大线圈匝数、导线型号（Ω/m）
 * 模型套用后调用基类接口 {@link #applyModel()} 按结构规格推导基础元件值
 * （自感上限/铜阻），不手写配置电气参数。
 * 支持 NBT 持久化（命令/界面固化到方块侧表）。
 */

package com.hdf.cryptand.neoforge.powergrid.state;

import net.minecraft.nbt.CompoundTag;
import org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity;

public class TransformerParameters extends DeviceParameters {

    @Override
    public String deviceType() {
        return "transformer";
    }

    // ==================== 物理损耗参数（float，不需要 double） ====================
    // 基础元件（Resistor/MutualInductor/IdealTransformer）已承载电气功能，
    // 参数模型只定义【物理损耗】与【结构规格】——物理参数用 float。

    /** 铁损因子（×互感 → 铁损电阻 rCore，基础电阻承载）；&lt;0 用 PowerGrid 默认 */
    public float coreLossMultiplier = -1;
    /** 散热值（W/K，越大散热越快） */
    public float heatDissipation = 4.0f;
    /** 热容（J/K，越大升温越慢） */
    public float heatCapacity = 200.0f;
    /** 环境温度（K，默认 20°C） */
    public float ambientK = 298.15f; // 25°C（2026-08-19 统一室温）
    /** 最高安全温度（K，默认 200°C） */
    public float maxTempK = 473.15f;

    // ==================== 结构规格（float，模型套用后推导基础元件值） ====================

    /** 铁心磁导系数（H/匝²；&lt;0 用方块默认）→ 推导自感 L = coreAl × n² */
    public float coreAl = -1;
    /** 最大线圈匝数（结构上限）→ 推导自感/铜阻 */
    public int maxTurns = 0;
    /** 原边导线单位长度电阻（Ω/m；&lt;0 未设置）→ 推导铜阻（基础 Resistor 承载） */
    public float primaryWireRPerMeter = -1;
    /** 副边导线单位长度电阻（Ω/m；&lt;0 未设置）→ 推导铜阻 */
    public float secondaryWireRPerMeter = -1;

    // ==================== 魔法数字（double，计算后入矩阵转 float） ====================
    // 漏感【不定义参数】：基础互感元件 MutualInductor 按耦合系数自动产生漏感。

    /** 耦合系数上限（内部数值保护 clamp；漏感 = (1-cf)·L 由基础互感元件计算） */
    public double maxCoupling = 0.999999;
    /** 自感上限（H；由铁心 coreAl × n² 推导，基础 Inductor/互感承载） */
    public double maxSelfInductance = 1000.0;
    /** 原边铜阻（Ω；由导线规格推导，基础 Resistor 承载） */
    public double primaryResistance = 0.5;
    /** 副边铜阻（Ω；由导线规格推导，基础 Resistor 承载） */
    public double secondaryResistance = 0.5;

    public static TransformerParameters defaults() {
        return new TransformerParameters();
    }

    /** 铁损因子：配置值或 PowerGrid 默认。 */
    public double effectiveCoreLossMultiplier() {
        return coreLossMultiplier >= 0
                ? coreLossMultiplier : TransformerBlockEntity.mutualMultiplier();
    }

    /**
     * 【基类接口】模型套用后按结构规格推导基础元件值（不手写电气参数）：
     *   - 铁心 + 最大匝数 → 自感上限 = coreAl × n²（基础互感/电感承载）
     *   - 导线单位电阻 × 匝数 × 平均匝长（近似 0.5m/匝）→ 铜阻（基础 Resistor 承载）
     * 子类/模型可覆盖此方法实现自定义换算。
     */
    @Override
    public void applyModel() {
        if (coreAl > 0 && maxTurns > 0) {
            // ⚠ 2026-08-30 审计 C10 根因：自感上限与默认路径【统一】——原硬编码
            // Math.min(coreAl×n², 1e6) 使参数模型下上限 1e6，而默认路径
            // （PhasorNetworkBuilder）上限 = maxSelfInductance（默认 1000）→
            // 同一变压器在有无参数模型下自感差 1000 倍 → 漏感/励磁/副边电压
            // 行为显著变化。统一取【既有上限与推导值较小者】（推导值更大时
            // 保持 1000；更小时收紧——两路径一致）。
            maxSelfInductance = Math.min(coreAl * maxTurns * maxTurns, maxSelfInductance);
        }
        if (maxTurns > 0) {
            double len = maxTurns * 0.5; // 近似每匝平均周长 0.5m
            if (primaryWireRPerMeter > 0) {
                primaryResistance = Math.max(primaryWireRPerMeter * len, 1e-3);
            }
            if (secondaryWireRPerMeter > 0) {
                secondaryResistance = Math.max(secondaryWireRPerMeter * len, 1e-3);
            }
        }
    }

    // ==================== NBT 持久化 ====================

    @Override
    public void write(CompoundTag tag) {
        tag.putDouble("MaxCoupling", maxCoupling);
        tag.putDouble("MaxSelfInductance", maxSelfInductance);
        tag.putDouble("PrimaryResistance", primaryResistance);
        tag.putDouble("SecondaryResistance", secondaryResistance);
        tag.putFloat("CoreLossMultiplier", coreLossMultiplier);
        tag.putFloat("HeatDissipation", heatDissipation);
        tag.putFloat("HeatCapacity", heatCapacity);
        tag.putFloat("AmbientK", ambientK);
        tag.putFloat("MaxTempK", maxTempK);
        tag.putFloat("CoreAl", coreAl);
        tag.putInt("MaxTurns", maxTurns);
        tag.putFloat("PrimaryWireRPerMeter", primaryWireRPerMeter);
        tag.putFloat("SecondaryWireRPerMeter", secondaryWireRPerMeter);
    }

    @Override
    public void read(CompoundTag tag) {
        maxCoupling = tag.getDouble("MaxCoupling");
        maxSelfInductance = tag.getDouble("MaxSelfInductance");
        primaryResistance = tag.getDouble("PrimaryResistance");
        secondaryResistance = tag.getDouble("SecondaryResistance");
        coreLossMultiplier = tag.getFloat("CoreLossMultiplier");
        heatDissipation = tag.getFloat("HeatDissipation");
        heatCapacity = tag.getFloat("HeatCapacity");
        ambientK = tag.getFloat("AmbientK");
        maxTempK = tag.getFloat("MaxTempK");
        coreAl = tag.getFloat("CoreAl");
        maxTurns = tag.getInt("MaxTurns");
        primaryWireRPerMeter = tag.getFloat("PrimaryWireRPerMeter");
        secondaryWireRPerMeter = tag.getFloat("SecondaryWireRPerMeter");
    }

    /** 诊断/命令展示（只列物理损耗 + 结构规格；电气值由基础元件承载/推导）。 */
    @Override
    public String describe() {
        return "变压器[铁损因子=" + effectiveCoreLossMultiplier()
                + " 散热=" + heatDissipation + "W/K 热容=" + heatCapacity + "J/K"
                + " 铁心Al=" + (coreAl < 0 ? "默认" : coreAl)
                + " 最大匝数=" + maxTurns
                + " 导线P/S=" + (primaryWireRPerMeter < 0 ? "默认" : primaryWireRPerMeter + "Ω/m")
                + "/" + (secondaryWireRPerMeter < 0 ? "默认" : secondaryWireRPerMeter + "Ω/m")
                + "（漏感/铜阻由基础元件按耦合与规格推导）]";
    }
}
