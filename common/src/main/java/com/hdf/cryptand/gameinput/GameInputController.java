package com.hdf.cryptand.gameinput;

import java.io.IOException;

/**
 * 游戏输入控制器对接接口（2026-09-08 通用输入后端：方向盘/手柄/摇杆抽象层）。
 * <p>
 * 经 {@link GameInputProvider#open} 或 {@link GameInputManager#acquire} 获取实例：
 * <ul>
 *   <li>读输入：{@link #getState()} 同步采样（内部 Poll + 读设备，返回归一化状态）；</li>
 *   <li>力反馈（方向盘专属扩展）：{@link #setForce} 下发效果（弹簧回中/阻尼/恒定力/正弦…），
 *       {@link #stopForce} 停止，{@link #setForceEnabled} 全局开关，
 *       {@link #setOperatingRange} 设转向范围度数；</li>
 *   <li>生命周期：{@link #close} 释放（管理器 release/shutdown 会自动调用）。</li>
 * </ul>
 * ⚠ {@link #getState()} 为同步设备访问（毫秒级），勿在 MC 主线程高频调用；
 * 建议由后台轮询线程采样后共享状态（本库不假设调用线程）。
 */
public interface GameInputController {

    /** 后端分配的设备 id（与 {@link GameInputDeviceInfo#id()} 一致，含后端前缀） */
    String id();

    /** 设备名（如 "Logitech G29 Racing Wheel USB"） */
    String name();

    /** 设备类型（方向盘/手柄/摇杆…） */
    GameInputDeviceKind kind();

    /** 设备当前是否连接/可用 */
    boolean isConnected();

    /** 同步采样一帧状态（设备断开时返回 connected=false 的状态，不抛异常） */
    GameInputState getState() throws IOException;

    /** 是否支持力反馈（方向盘转向力反馈；手柄可映射震动） */
    boolean hasForceFeedback();

    /** 下发力反馈效果（覆盖上一次效果；无力反馈设备抛 UnsupportedOperationException） */
    void setForce(ForceParams params) throws IOException;

    /** 力反馈全局开关（false 临时停用当前效果，true 恢复） */
    void setForceEnabled(boolean enabled) throws IOException;

    /** 停止当前力反馈效果 */
    void stopForce() throws IOException;

    /**
     * 方向盘专属：设置转向范围（度数，如 40..900）；不支持抛
     * {@link UnsupportedOperationException}（手柄等无转向范围概念）。
     */
    default void setOperatingRange(int degrees) throws IOException {
        throw new UnsupportedOperationException("设备不支持转向范围设置: " + name());
    }

    /** 释放设备（停止效果 + 放弃采集；幂等） */
    void close();
}
