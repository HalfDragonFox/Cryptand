/**
 * ===== 键盘主线程泵（2026-09-14）=====
 *
 * 键盘有两条读取路径（用户："当前键盘走 SDL，虽然需要焦点运行，但是不做留空，还是实现代码，
 * 默认 GLFW 好了" + "SDL 不做留空，也实现对应代码保证对齐" + "为焦点窗口这个特性做支持"）：
 * <ul>
 *   <li><b>GLFW（默认）</b> —— {@link KeyboardInputBackend}，用 MC 自己的窗口；</li>
 *   <li><b>SDL</b> —— {@link SdlKeyboardBackend}，用 SDL 自己创建的窗口（需要它拿到键盘焦点）。</li>
 * </ul>
 * 两者都只能在<b>主线程</b>采样：GLFW 不是线程安全的；SDL 的视频/事件子系统有 main-thread 断言。
 * 本类把两条路径挂到同一个 {@code ClientTickEvent}（<b>游戏总线</b>）上。
 */

package com.hdf.cryptand.neoforge.gameinput;

import com.hdf.cryptand.gameinput.sdl.SdlKeyboardBackend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class KeyboardInputEvents {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 只有 GLFW 路径在主线程：GLFW 不是线程安全的，且它的窗口属于主线程。
        KeyboardInputBackend.pumpMainThread();
        // SDL 路径（焦点窗口）：把事件泵【投递】给 SDL 专用线程，与手柄/方向盘的 SDL 调用
        // 共用同一条常驻平台线程 —— 主线程绝不直接碰 SDL（那正是竞态来源）。
        if (SdlKeyboardBackend.isEnabled()) {
            com.hdf.cryptand.gameinput.sdl.SdlThread.post(SdlKeyboardBackend::pump);
        }
    }

    /** 退出世界：销毁 SDL 键盘窗口（下次绑定 SDL 键盘时会重新创建）。 */
    @SubscribeEvent
    public static void onLoggingOut(
            net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        SdlKeyboardBackend.shutdown();
    }

    private KeyboardInputEvents() {
    }
}
