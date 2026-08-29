package com.hdf.cryptand.circuitsimulation.model;

/**
 * 元件参数绑定源（2026-08-12 用户要求）：
 * <p>
 * 复合/基础元件可绑定一个【独立参数数据对象】——计算时全局可调整（无论实际
 * MC 模型/BE 是否更新）。虚拟设备（未加载区块的元件参数快照）就是典型绑定源：
 * 元件构造时 {@code composite.bind(virtual)}，之后任何外部逻辑（配置/命令/其他
 * 系统）调整绑定源参数（{@code setResistance} 等 → 置变动标记），元件在下一轮
 * 求解时自动应用新参数 → 重解，无需重建网络、无需 MC 模型参与。
 * <p>
 * 约定：
 *   - {@link #bindingDirty()} 为 true 表示参数已变动（需应用到元件 → 重解）
 *   - 应用后调用 {@link #bindingClearDirty()} 清除标记
 *   - 数值读取方法在参数未变动时返回当前值（供构造/诊断）
 */
public interface ElementBinding {

    /** 主线电阻（Ω）；<=0 表示不提供/无电阻 */
    double boundResistance();

    /** 电感（H） */
    double boundInductance();

    /** 开关/使能状态 */
    boolean boundEnabled();

    /** 源电压（V）；仅电压源有意义 */
    double boundVoltage();

    /** 源内阻（Ω） */
    double boundSourceResistance();

    /** 是否电压源 */
    boolean boundIsVoltageSource();

    /** 参数是否已变动（需应用到元件 → 重解） */
    boolean bindingDirty();

    /** 应用后清除变动标记 */
    void bindingClearDirty();
}
