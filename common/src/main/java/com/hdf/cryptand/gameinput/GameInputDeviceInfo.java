package com.hdf.cryptand.gameinput;

/**
 * 游戏输入设备描述（后端枚举结果；{@code id} 带后端前缀，供
 * {@link GameInputProvider#open} 按后端路由打开）。
 *
 * @param id             后端分配的设备唯一 id（格式 "&lt;prefix&gt;:&lt;设备标识&gt;"，如 "sdl:0"）
 * @param name           设备名（如 "Logitech G29 Racing Wheel USB"）
 * @param kind           设备类型（方向盘/手柄/摇杆…）
 * @param forceFeedback  是否支持力反馈（方向盘为转向力反馈；部分手柄可映射震动）
 */
public record GameInputDeviceInfo(String id, String name, GameInputDeviceKind kind, boolean forceFeedback) {
}
