/**
 * ===== 设备参数模型基类（魔法数字参数化 + 基类接口更新） =====
 *
 * 变压器/电机/发电机等设备都有硬编码魔法数字（铁损/漏感/绕组/温度等）。
 * 本基类把这些参数统一提升为可配置模型：
 *   - 【正常求解】用默认魔法数字（defaults()，= 当前硬编码值，行为不变）
 *   - 【模型套用】对固定参数更改后调用基类接口 {@link #applyModel()} 更新
 *     魔法数字（子类覆盖实现物理换算）
 *   - 支持 NBT 持久化（命令/界面固化到方块侧表）
 *
 * 通过配置 ENABLE_DEVICE_PARAMETER_MODELS 全局开关控制（默认关闭）。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import net.minecraft.nbt.CompoundTag;

public abstract class DeviceParameters {

    /** 设备类型标识（transformer/motor/generator/...）。 */
    public abstract String deviceType();

    /**
     * 【基类接口】模型套用后更新魔法数字。
     * 子类覆盖实现物理换算（铁心+导线→自感/铜阻、磁通+极对→转速/EMF 等）；
     * 默认实现为空（保持现有魔法数字）。
     */
    public void applyModel() {
        // 默认无操作
    }

    /** 序列化到 NBT（子类实现）。 */
    public abstract void write(CompoundTag tag);

    /** 从 NBT 读取（子类实现）。 */
    public abstract void read(CompoundTag tag);

    /** 诊断/命令展示。 */
    public abstract String describe();
}
