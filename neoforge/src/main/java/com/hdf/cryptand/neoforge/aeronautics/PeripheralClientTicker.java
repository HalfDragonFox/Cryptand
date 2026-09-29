/**
 * ===== 外设客户端公共事件（2026-09-14）=====
 *
 * 用户报障："外设拉杆退出重进世界后会输出失效，要求进入时全部归位到0格的情况，强制刷0"。
 * 排查结论（两条独立的失效链，都要堵）：
 * <ol>
 *   <li><b>跨世界残留</b>：{@code PeripheralLeverInput}/{@code PeripheralRailingInput} 的登记表是
 *       静态的，而退出世界时设备池会清空全部使用者 —— 只比对"设备 id 没变"就永远不再 bind，
 *       采样恒为空 ⇒ 输出彻底失效。<b>治本已在各自 tickClient 里用池的真实引用复核</b>，
 *       这里再在卸载时整表清空（新世界方块坐标可能复用旧状态）；</li>
 *   <li><b>核心周期静默停摆</b>：调度句柄失效而 {@code scheduled} 仍为 true ⇒ 外设从此不再计算，
 *       无异常、无日志。由 {@link PeripheralCore#watchdog()} 在主线程每 tick 自愈。</li>
 * </ol>
 *
 * ⚠ 本类监听【游戏总线】事件（ClientTickEvent 属游戏总线），必须用 {@code NeoForge.EVENT_BUS} 注册。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverInput;
import com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class PeripheralClientTicker {

    /** 主线程每 tick：驱动核心看门狗（自愈静默停摆的调度）。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        PeripheralCore.watchdog();
    }

    /** 退出世界/断线：清空跨世界残留（注册表、会话计时、输入登记表）。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PeripheralCore.onLevelUnload();
        PeripheralLeverInput.reset();
        PeripheralRailingInput.reset();
    }

    private PeripheralClientTicker() {
    }
}
