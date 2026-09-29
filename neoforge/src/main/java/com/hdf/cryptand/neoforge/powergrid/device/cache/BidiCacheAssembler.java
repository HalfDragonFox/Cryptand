/**
 * ===== 组装器缓存化分支 3：双向（BE ↔ 组装器） =====
 *
 * 方向：双向。同时使用两个槽：
 *   - 输入槽（SLOT_IN）：BE → 组装器（主线程写，后台组装器读）
 *   - 输出槽（SLOT_OUT）：组装器 → BE（后台组装器写，主线程读应用）
 * 适用：既消费 BE 参数、又回写结果的设备（电机：读 R/L + 回写转速；
 * 电池：读 SOC + 回写电压/电流等）。
 */
package com.hdf.cryptand.neoforge.powergrid.device.cache;

public interface BidiCacheAssembler extends SourceCacheAssembler, SinkCacheAssembler {
}
