/**
 * 温度扩散模型类型（2026-08-18 用户需求）。
 *
 * 只有实现温度扩散模型的组装器才能向周围元件传递温度。扩散分三种：
 *   1. 仅输出（OUTPUT_ONLY）：只向周围传热，不接受外部输入
 *      ——发热设备（加热器）向周围散热，但不会被周围高温反向加热
 *   2. 仅输入（INPUT_ONLY）：只接受外部传热，不向周围输出
 *      ——被加热的目标（如烘烤对象）接收热量，但不会把热传回热源
 *   3. 双向（BIDIRECTIONAL）：可双向交互（热 → 冷，物理热传导）
 *      ——谁更热谁向对方传热
 */
package com.hdf.cryptand.neoforge.powergrid.device.thermal;

public enum ThermalDiffusionType {
    /** 仅输出扩散模型：只向周围传热，不接受外部输入 */
    OUTPUT_ONLY,
    /** 仅输入扩散模型：只接受外部传热，不向周围输出 */
    INPUT_ONLY,
    /** 双向扩散模型：可双向交互（热 → 冷） */
    BIDIRECTIONAL
}
