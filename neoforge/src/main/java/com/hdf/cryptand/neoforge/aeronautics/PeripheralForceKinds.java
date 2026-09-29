/**
 * ===== 外设"强制设备类型"共享定义（2026-09-14）=====
 *
 * 用户要求（原话）："外设拉杆按钮与非按钮版本都可以和船舵一样设置设备强制，防止识别有问题"
 * + "需要支持比如键盘、手柄、方向盘等常见类型" + "常见设备最好都上"
 * + "设置为自动则按照自动的进行设置" + "键盘强制部分添加 GLFW 和 SDL 强制两个部分，默认走 SDL"。
 *
 * <p>索引即绑定里的 {@code forceKind} 字段（持久化）：
 * <pre>
 *   0 = 自动      → 完全按后端检测结果（"设置为自动则按照自动的进行设置"）
 *   1 = 方向盘    ┐
 *   2 = 手柄      │ 覆盖后端判定：国产方向盘常被 SDL 认成"通用控制器"，
 *   3 = 摇杆      ┘ 这里手动纠正，力反馈与显示都按纠正后的类型走
 *   4 = 键盘(GLFW) ← 默认：用 MC 自己的窗口（GLFW），不受焦点归属限制
 *   5 = 键盘(SDL)   用 SDL 自己创建的窗口 —— 需要那个窗口拿到键盘焦点
 *   6 = 通用
 * </pre>
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.gameinput.GameInputDeviceKind;
import net.minecraft.util.Mth;

import java.util.List;

public final class PeripheralForceKinds {

    /** 三个外设界面共用的 lang 前缀（沿用船舵既有键名，栏杆/拉杆直接复用） */
    public static final String LANG_PREFIX = "cryptand.peripheral_helm.force_kind_";

    /** 候选表（索引 = forceKind） */
    public static final List<String> KEYS = List.of(
            "auto", "wheel", "gamepad", "joystick", "keyboard_glfw", "keyboard_sdl", "other");

    /** 自动（不覆盖后端判定） */
    public static final int AUTO = 0;

    private PeripheralForceKinds() {
    }

    public static int clamp(int value) {
        return Mth.clamp(value, 0, KEYS.size() - 1);
    }

    public static String langKey(int index) {
        return LANG_PREFIX + KEYS.get(clamp(index));
    }

    /** 强制类型对应的设备类型；自动 / 通用 → null（表示"用后端检测结果"） */
    public static GameInputDeviceKind kindOf(int index) {
        return switch (clamp(index)) {
            case 1 -> GameInputDeviceKind.WHEEL;
            case 2 -> GameInputDeviceKind.GAMEPAD;
            case 3 -> GameInputDeviceKind.JOYSTICK;
            case 4, 5 -> GameInputDeviceKind.KEYBOARD;
            default -> null;
        };
    }

    /** 是否为"键盘"类强制（SDL / GLFW 两种读取方式） */
    public static boolean isKeyboard(int index) {
        int i = clamp(index);
        return i == 4 || i == 5;
    }

    /** 该强制类型是否要求走 SDL 键盘窗口（只有索引 5；其余一律 GLFW —— "默认使用 GLFW 方式"） */
    public static boolean wantsSdlKeyboard(int index) {
        return clamp(index) == 5;
    }

    /** 键盘读取方式名（"glfw" / "sdl"；非键盘返回 "glfw"） */
    public static String keyboardBackend(int index) {
        return wantsSdlKeyboard(index) ? "sdl" : "glfw";
    }
}
