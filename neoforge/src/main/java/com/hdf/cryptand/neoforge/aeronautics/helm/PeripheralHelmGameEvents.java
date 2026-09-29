/**
 * ===== 外设船舵游戏总线事件（2026-09-13） =====
 *
 * 退出世界时释放外部设备（采样线程 + SDL 实例）——避免手柄/方向盘被长期占用。
 * ⚠ 本类监听【游戏总线】事件，必须用 NeoForge.EVENT_BUS.register 注册。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

public final class PeripheralHelmGameEvents {

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 退出世界：清空设备池绑定（后台线程随后关闭设备）。
        // 绑定本身已经落盘（存储的是设备名派生的稳定 id）—— 重进世界按名称重连，见下。
        PeripheralHelmInput.shutdown();
    }

    /**
     * 进入世界：<b>默认刷新一次设备池</b>（用户 2026-09-14 定稿）。
     *
     * <p>为什么必须有这一次：绑定表按【设备名】保存，重进世界后要拿它去池里找设备 ——
     * 池是空的就什么都找不到，界面会显示"未连接"，玩家必须手点一次【刷新设备】。
     * 这一次自动扫描只发生在<b>进入世界</b>这一个时刻，之后仍严格手动
     * （界面【刷新设备】或 {@code /cryptand peripheral scan}），不做任何周期性重扫。
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        PeripheralHelmInput.requestScan();
    }

    private PeripheralHelmGameEvents() {
    }
}
