package com.hdf.cryptand.gameinput;

import java.io.IOException;
import java.util.List;

/**
 * 游戏输入后端 SPI（通用输入后端可替换性/可组合性）：枚举设备 + 打开设备。
 * <p>
 * common 侧【不主动加载任何原生库】——后端实例由平台层按配置显式创建并注册到
 * {@link GameInputProvider}（懒加载：dll 在后端首次使用时才加载）。
 * 当前唯一后端：{@link com.hdf.cryptand.gameinput.sdl.SdlBackend}（SDL2 跨平台：
 * Linux evdev / macOS IOKit HID / Windows DirectInput+XInput，含 haptic 力反馈）。
 */
public interface GameInputBackend {

    /** 后端前缀（设备 id 前缀，如 "sdl"；open 时按前缀路由） */
    String prefix();

    /** 后端是否可用（dll 可加载；懒加载失败返回 false，原因见 {@link #lastError()}） */
    boolean available();

    /** 最近一次初始化失败原因（available()==false 诊断用） */
    String lastError();

    /** 枚举已连接的游戏输入设备（无设备/后端不可用返回空列表，不抛异常） */
    List<GameInputDeviceInfo> listDevices();

    /** 按设备描述打开（打开失败抛 {@link IOException}） */
    GameInputController open(GameInputDeviceInfo info) throws IOException;
}
