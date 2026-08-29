package com.hdf.cryptand.circuitsimulation.model;

/**
 * 元件类型编码（序列化到 C++/集群时使用 ordinal）。
 * <p>
 * 注意：只标识【基础元件】类型——网络分解后参与求解/序列化的都是基础元件
 * （复合元件 {@code decompose()} 后进网络）。复合元件统一用 {@link #COMPOSITE}
 * 标识（诊断用），不再为每种复合元件加枚举（种类多了会导致枚举爆炸）。
 */
public enum ElementType {
    RESISTOR,
    DC_VOLTAGE_SOURCE,
    CURRENT_SOURCE,
    CAPACITOR,
    INDUCTOR,
    AC_VOLTAGE_SOURCE,
    /** 时域波形源（方波/三角波/正弦/锯齿/直流），波形类型见 WaveformType */
    WAVEFORM_SOURCE,
    /** 理想变压器（4 端口 + 内部约束节点），ratio = 副边匝数/原边匝数 */
    IDEAL_TRANSFORMER,
    /** 互感（耦合电感，2 绕组对称，L1/L2/M）——变压器两侧独立建模用 */
    MUTUAL_INDUCTOR,
    /** 受控电流源（值由外部同步写入——互感双半模型迭代耦合用） */
    CONTROLLED_CURRENT_SOURCE,
    /** 受控电压源（值由外部同步写入；诺顿形式：V 串内阻 → 电流源 ∥ 电导） */
    CONTROLLED_VOLTAGE_SOURCE,
    /** 复合元件通用类型（CompositeElement/CompositeModel 默认；具体物理种类由
     *  子类类名/toString 标识，不占用枚举 —— 求解/序列化不依赖此类型） */
    COMPOSITE
}
