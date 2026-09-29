/**
 * ===== 外设异步核心的可计算对象（2026-09-13）=====
 *
 * 外设方块（船舵 / 拉杆）实现本接口并注册到 {@link PeripheralCore}，
 * 由客户端异步核心线程<b>按固定频率串行遍历</b>调用：
 * <pre>
 *   接收处理 → 计算 → 发送     （用户定稿的流水线）
 * </pre>
 *
 * <p>⚠ 实现约束（与项目"主线程=纯同步、异步核心=全部计算"铁律一致）：
 * <ul>
 *   <li>本方法运行在<b>核心线程</b>：禁止读写 Level / 方块实体世界状态、禁止渲染、禁止发网络包；</li>
 *   <li>读设备池（{@code PeripheralHelmInput.sample}）是零阻塞的，可以放心调用；</li>
 *   <li>需要跨线程共享的字段必须 {@code volatile}（核心写 → 主线程渲染/UI 读）；</li>
 *   <li>上行数据通过 {@code PeripheralSessionClient.tickSend(...)} 排队，由核心统一投递主线程发送。</li>
 * </ul>
 */

package com.hdf.cryptand.neoforge.aeronautics;

public interface PeripheralTickable {

    /** 核心线程的一次计算周期（固定频率，见 {@code aeronautics.toml#peripheralClientCoreHz}）。 */
    void coreTick(long nowMs);
}
