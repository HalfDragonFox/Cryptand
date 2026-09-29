/**
 * ===== 电机/发电机参数模型（魔法数字参数化，骨架） =====
 *
 * <b>设计原则：基础元件已承载电气功能，参数模型只定义物理参数 + 结构规格。</b>
 *   - 绕组电阻/电感：基础 {@code Resistor}/{@code Inductor} 元件承载
 *     （默认 -1 = 用 PowerGrid 线圈实际值），不手写配置
 *   - 物理参数：发电机 EMF 常数（EMF_K 磁场简化恒磁）、额定电压/负载耦合、
 *     温度（散热值/热容/环境/最高温）
 *   - 结构规格：磁通（磁场强度）、极对数 → {@link #applyModel()} 推导 EMF 常数
 * 正常求解用默认魔法数字（-1 = 沿用现有行为）；模型套用后调基类接口
 * {@link #applyModel()} 更新。受配置 ENABLE_DEVICE_PARAMETER_MODELS 控制。
 */

package com.hdf.cryptand.neoforge.powergrid.state;

import net.minecraft.nbt.CompoundTag;

public class MotorParameters extends DeviceParameters {

    // ==================== 物理参数（float，不需要 double） ====================
    // 绕组 R/L 是基础 Resistor/Inductor 元件承载的电气值（不手写配置）。

    /** 发电机 EMF 常数（物理特性；EMF = K × 角速度；-1 用 MotorAssembler 现值） */
    public float emfK = -1;
    /** 额定电压（V；-1 用配置 motorRatedVoltage） */
    public float ratedVoltage = -1;
    /** 负载耦合系数（-1 用配置 motorLoadCoupling） */
    public float loadCoupling = -1;
    /** 散热值（W/K） */
    public float heatDissipation = 4.0f;
    /** 热容（J/K） */
    public float heatCapacity = 200.0f;
    /** 环境温度（K，默认 20°C） */
    public float ambientK = 298.15f; // 25°C（2026-08-19 统一室温）
    /** 最高安全温度（K，默认 200°C） */
    public float maxTempK = 473.15f;

    // ==================== 结构规格（float，模型套用后推导） ====================

    /** 磁通（磁场强度，Wb 量级；&lt;0 未设置）→ 推导 EMF 常数 */
    public float magnetFlux = -1;
    /** 极对数（同步转速 n = 60f/p） */
    public int polePairs = 1;

    // ==================== 魔法数字（double，计算后入矩阵转 float） ====================

    /** 绕组电阻（Ω；基础 Resistor 承载；-1 用 PowerGrid 线圈实际值） */
    public double windingResistance = -1;
    /** 绕组电感（H；基础 Inductor 承载；-1 用 PowerGrid 线圈实际值） */
    public double windingInductance = -1;

    @Override
    public String deviceType() {
        return "motor";
    }

    @Override
    public void applyModel() {
        // 模型套用后按结构规格推导（骨架：磁通/极对 → EMF 常数，后续丰富）
        if (magnetFlux > 0 && polePairs > 0) {
            // EMF 常数 ≈ 磁通 × 极对数（量纲近似，后续按实际算法细化）
            emfK = magnetFlux * polePairs;
        }
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putFloat("EmfK", emfK);
        tag.putDouble("WindingResistance", windingResistance);
        tag.putDouble("WindingInductance", windingInductance);
        tag.putFloat("RatedVoltage", ratedVoltage);
        tag.putFloat("LoadCoupling", loadCoupling);
        tag.putFloat("HeatDissipation", heatDissipation);
        tag.putFloat("HeatCapacity", heatCapacity);
        tag.putFloat("AmbientK", ambientK);
        tag.putFloat("MaxTempK", maxTempK);
        tag.putFloat("MagnetFlux", magnetFlux);
        tag.putInt("PolePairs", polePairs);
    }

    @Override
    public void read(CompoundTag tag) {
        emfK = tag.getFloat("EmfK");
        windingResistance = tag.getDouble("WindingResistance");
        windingInductance = tag.getDouble("WindingInductance");
        ratedVoltage = tag.getFloat("RatedVoltage");
        loadCoupling = tag.getFloat("LoadCoupling");
        heatDissipation = tag.getFloat("HeatDissipation");
        heatCapacity = tag.getFloat("HeatCapacity");
        ambientK = tag.getFloat("AmbientK");
        maxTempK = tag.getFloat("MaxTempK");
        magnetFlux = tag.getFloat("MagnetFlux");
        polePairs = tag.getInt("PolePairs");
    }

    @Override
    public String describe() {
        return "电机/发电机参数[EMF常数=" + (emfK < 0 ? "默认" : emfK)
                + " 绕组R=" + (windingResistance < 0 ? "线圈实际" : windingResistance + "Ω")
                + " 绕组L=" + (windingInductance < 0 ? "线圈实际" : windingInductance + "H")
                + " 额定电压=" + (ratedVoltage < 0 ? "配置" : ratedVoltage + "V")
                + " 负载耦合=" + (loadCoupling < 0 ? "配置" : loadCoupling)
                + " 磁通=" + (magnetFlux < 0 ? "未设" : magnetFlux)
                + " 极对数=" + polePairs + "]";
    }
}
