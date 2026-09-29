/**
 * ===== SDL 键盘后端（2026-09-14）=====
 *
 * 用户定稿："当前键盘走 SDL，虽然需要焦点运行，但是不做留空，还是实现代码，默认 GLFW 好了"
 * + "SDL 不做留空，也实现对应代码保证对齐" + "为焦点窗口这个特性做支持"。
 *
 * <p><b>为什么 SDL 键盘需要"焦点窗口"</b>：SDL 的键盘状态表由 {@code SDL_PumpEvents} 从
 * <b>SDL 窗口</b>的键盘事件更新；MC 的窗口是 GLFW 建的，SDL 收不到它的任何键盘事件，
 * 而且 SDL3 没有 {@code SDL_GetGlobalKeyboardState}（只有鼠标有 {@code SDL_GetGlobalMouseState}，
 * 已用 {@code SDL3.dll} 导出符号核实）。
 *
 * <p><b>本后端刻意什么都不初始化、也不创建窗口</b>（用户 2026-09-14 定稿：
 * "SDL 需要指定聚焦位置，没有的话就不管，不要新建窗口，这个是为了配合其他内容使用的，
 * 一般不使用 SDL"）。它只做一件事：<b>跟随 SDL 当前已有的键盘焦点窗口</b>
 * （{@code SDL_GetKeyboardFocus()}）——
 * <ul>
 *   <li>别的内容（其他模组/子系统）已经起了 SDL 视频并且有聚焦窗口 ⇒ 这里能读到键盘；</li>
 *   <li>没有任何 SDL 窗口聚焦 ⇒ {@code SDL_GetKeyboardFocus()} 返回 null ⇒
 *       <b>直接不管</b>，上报"未连接"，绝不自行造一个窗口出来。</li>
 * </ul>
 *
 * <p>与 {@code KeyboardInputBackend}（GLFW，默认）实现同一个 {@link GameInputBackend} SPI，
 * 对上层（设备池 / 绑定 / 界面 / 力反馈）完全一致 —— 两条路径只是"键盘从哪读"的区别。
 *
 * <p><b>线程约束</b>：{@code SDL_PumpEvents} 必须在 SDL 窗口所属线程调用（主线程），
 * 故只在 {@link #pumpMainThread()}（主线程每 tick）里做；{@code open()} 只置一个启用标志。
 */

package com.hdf.cryptand.gameinput.sdl;

import com.hdf.cryptand.gameinput.GameInputBackend;
import com.hdf.cryptand.gameinput.GameInputController;
import com.hdf.cryptand.gameinput.GameInputDeviceInfo;
import com.hdf.cryptand.gameinput.GameInputDeviceKind;
import com.hdf.cryptand.gameinput.GameInputState;
import com.hdf.cryptand.gameinput.ForceParams;
import com.sun.jna.Pointer;

import java.util.Arrays;
import java.util.List;

public final class SdlKeyboardBackend implements GameInputBackend {

    public static final String PREFIX = "sdlkb";

    private static final GameInputDeviceInfo INFO = new GameInputDeviceInfo(
            PREFIX + ":main", "Keyboard (SDL)", GameInputDeviceKind.KEYBOARD, false);

    /** 单帧最多记录的按键数 */
    private static final int MAX_KEYS = 16;

    private static volatile SdlLib lib;
    private static volatile boolean libTried;
    /** 是否被设备池启用（open 过）—— 只有启用才泵事件，默认零开销 */
    private static volatile boolean enabled;
    private static volatile GameInputState snapshot = GameInputState.disconnected();

    private static final SdlKeyboardController CONTROLLER = new SdlKeyboardController();

    public SdlKeyboardBackend() {
    }

    // ==================== GameInputBackend ====================

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public boolean available() {
        return lib() != null;
    }

    @Override
    public String lastError() {
        SdlLib l = lib();
        return l == null ? "SDL3 不可用" : String.valueOf(l.lastError());
    }

    @Override
    public List<GameInputDeviceInfo> listDevices() {
        // 只要 SDL3 能加载就提供该设备（真正的视频子系统在首次泵事件时才起）
        return lib() == null ? List.of() : List.of(INFO);
    }

    @Override
    public GameInputController open(GameInputDeviceInfo info) {
        enabled = true;
        CONTROLLER.reopen();
        return CONTROLLER;
    }

    // ==================== 主线程泵（唯一的 SDL 视频/事件入口）====================

    /**
     * 由 {@link SdlThread} 在自己的常驻线程上周期调用（<b>绝不在主线程调</b>：SDL 的
     * {@code SDL_PumpEvents} 与 joystick 共用同一套内部状态，跨线程调用就是竞态来源）。
     * 仅在设备被启用后才做任何事；不初始化任何 SDL 子系统、不创建窗口 —— 只跟随已有聚焦窗口。
     */
    public static void pump() {
        if (!enabled) {
            return;
        }
        SdlLib l = lib();
        if (l == null) {
            snapshot = GameInputState.disconnected();
            return;
        }
        l.sdlPumpEvents();   // ★ 键盘状态在这里更新（若 SDL 事件子系统已由别处启动）

        Pointer focus = l.sdlKeyboardFocus();
        byte[] state = l.sdlKeyboardState();
        if (focus == null || state.length == 0) {
            // ★ "没有聚焦位置就不管"：不上报任何键位（也绝不为了读出键盘而自建窗口）
            snapshot = GameInputState.disconnected();
            return;
        }
        int[] keys = new int[MAX_KEYS];
        int n = 0;
        for (int sc = 0; sc < state.length && n < MAX_KEYS; sc++) {
            if (state[sc] == 0) {
                continue;
            }
            int glfw = SCANCODE_TO_GLFW.length > sc ? SCANCODE_TO_GLFW[sc] : 0;
            if (glfw != 0) {
                keys[n++] = glfw;
            }
        }
        snapshot = new GameInputState(true, new float[GameInputState.AXIS_COUNT], 0L, -1,
                n == 0 ? GameInputState.EMPTY_KEYS : Arrays.copyOf(keys, n), System.nanoTime());
    }

    /** 是否已被设备池启用（只有启用时才需要把事件泵投给 SDL 线程） */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 停用（退出世界/后端不再被绑定）：只清状态，没有窗口要销毁。 */
    public static void shutdown() {
        enabled = false;
        snapshot = GameInputState.disconnected();
    }

    private static SdlLib lib() {
        if (!libTried) {
            libTried = true;
            try {
                lib = SdlLib.load(null);
            } catch (Throwable t) {
                lib = null;
            }
        }
        return lib;
    }

    // ==================== 键码映射（统一到 GLFW key code）====================

    /**
     * {@code SDL_Scancode → GLFW key code}。两套键码必须归一到同一个空间，
     * 否则"绑定表"在 GLFW / SDL 两种键盘之间不通用。
     */
    private static final int[] SCANCODE_TO_GLFW = buildScancodeMap();

    private static int[] buildScancodeMap() {
        int[] m = new int[232];   // SDL_SCANCODE_ 最大 231（RGUI）
        for (int i = 0; i < 26; i++) {
            m[4 + i] = 65 + i;            // A..Z
        }
        for (int i = 0; i < 9; i++) {
            m[30 + i] = 49 + i;           // 1..9
        }
        m[39] = 48;    // 0
        m[40] = 257;   // RETURN
        m[41] = 256;   // ESCAPE
        m[42] = 259;   // BACKSPACE
        m[43] = 258;   // TAB
        m[44] = 32;    // SPACE
        m[45] = 45;    // MINUS
        m[46] = 61;    // EQUALS
        m[47] = 91;    // LEFT BRACKET
        m[48] = 93;    // RIGHT BRACKET
        m[49] = 92;    // BACKSLASH
        m[51] = 59;    // SEMICOLON
        m[52] = 39;    // APOSTROPHE
        m[53] = 96;    // GRAVE
        m[54] = 44;    // COMMA
        m[55] = 46;    // PERIOD
        m[56] = 47;    // SLASH
        m[57] = 280;   // CAPS LOCK
        for (int i = 0; i < 12; i++) {
            m[58 + i] = 290 + i;          // F1..F12
        }
        m[70] = 283;   // PRINT SCREEN
        m[71] = 281;   // SCROLL LOCK
        m[72] = 284;   // PAUSE
        m[73] = 260;   // INSERT
        m[74] = 268;   // HOME
        m[75] = 266;   // PAGE UP
        m[76] = 261;   // DELETE
        m[77] = 269;   // END
        m[78] = 267;   // PAGE DOWN
        m[79] = 262;   // RIGHT
        m[80] = 263;   // LEFT
        m[81] = 264;   // DOWN
        m[82] = 265;   // UP
        m[83] = 282;   // NUM LOCK
        m[84] = 331;   // KP /
        m[85] = 332;   // KP *
        m[86] = 333;   // KP -
        m[87] = 334;   // KP +
        m[88] = 335;   // KP ENTER
        for (int i = 0; i < 9; i++) {
            m[89 + i] = 321 + i;          // KP 1..9
        }
        m[98] = 320;   // KP 0
        m[99] = 330;   // KP .
        m[224] = 341;  // LCTRL
        m[225] = 340;  // LSHIFT
        m[226] = 342;  // LALT
        m[227] = 343;  // LGUI
        m[228] = 345;  // RCTRL
        m[229] = 344;  // RSHIFT
        m[230] = 346;  // RALT
        m[231] = 347;  // RGUI
        return m;
    }

    /** 键盘控制器：读 {@link #snapshot}（后台线程零阻塞）。 */
    private static final class SdlKeyboardController implements GameInputController {

        private volatile boolean open = true;

        void reopen() {
            open = true;
        }

        @Override
        public String id() {
            return PREFIX + ":main";
        }

        @Override
        public String name() {
            return "Keyboard (SDL)";
        }

        @Override
        public GameInputDeviceKind kind() {
            return GameInputDeviceKind.KEYBOARD;
        }

        @Override
        public boolean isConnected() {
            return open;
        }

        @Override
        public GameInputState getState() {
            return open ? snapshot : GameInputState.disconnected();
        }

        @Override
        public boolean hasForceFeedback() {
            return false;
        }

        @Override
        public void setForce(ForceParams params) {
            throw new UnsupportedOperationException("键盘不支持力反馈");
        }

        @Override
        public void setForceEnabled(boolean value) {
        }

        @Override
        public void stopForce() {
        }

        @Override
        public void close() {
            open = false;
            snapshot = GameInputState.disconnected();
        }
    }
}
