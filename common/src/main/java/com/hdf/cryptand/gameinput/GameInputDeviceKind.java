package com.hdf.cryptand.gameinput;

/** 游戏输入设备类型（后端枚举时判定；用户 2026-09-14："需要支持比如键盘、手柄、方向盘等常见类型"） */
public enum GameInputDeviceKind {
    /** 方向盘（罗技 G29/G920/G27、莱仕达等） */
    WHEEL,
    /** 手柄（Xbox/PS 等） */
    GAMEPAD,
    /** 摇杆/飞行杆/排挡器等单杆设备 */
    JOYSTICK,
    /** 键盘（GLFW 后端；可绑定字母/数字/功能键） */
    KEYBOARD,
    /** 其他游戏控制器 */
    OTHER
}
