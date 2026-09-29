package com.hdf.cryptand.gameinput.sdl;

import com.hdf.cryptand.gameinput.ForceEffect;
import com.hdf.cryptand.gameinput.ForceParams;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;

import java.io.IOException;
import java.util.List;

/**
 * ===== SDL3（libSDL3）JNA 封装：joystick + haptic 力反馈（2026-09-13 由 SDL2 迁移） =====
 *
 * 用户定稿："SDL2 是旧版本，换成 SDL3"。本类只做 SDL3 绑定：
 * <ul>
 *   <li>设备枚举/打开按 <b>instance id</b>（SDL3 的 {@code SDL_JoystickID}，不再是 SDL2 的 device index）；
 *   <li>状态刷新用 {@code SDL_UpdateJoysticks()}（SDL3 无 {@code SDL_JoystickUpdate}）；
 *   <li>力反馈用 {@code SDL_OpenHapticFromJoystick / SDL_CreateHapticEffect / SDL_RunHapticEffect}；
 *   <li><b>SDL3 的 {@code SDL_HapticEffect} 是 union</b>（不是 SDL2 的 struct{type,direction,union}），
 *       结构体全部改用 JNA {@link Structure}/{@link Union} 映射——由 JNA 按 C ABI 计算偏移，
 *       彻底取代旧版手工字节偏移（旧偏移仅在 SDL2 布局下勉强对上，SDL3 下必然错位）。</li>
 * </ul>
 * SDL3 返回 C99 {@code bool}（1 字节）——一律用 {@code invoke(Byte.class, ...)} 读取返回值。
 */
final class SdlLib {

    // ==================== SDL3 常量（include/SDL3/*.h @3.4.16） ====================

    static final int SDL_INIT_JOYSTICK = 0x00000200;
    static final int SDL_INIT_HAPTIC = 0x00001000;

    /** SDL_HapticRunEffect 的无限迭代（Uint32 0xFFFFFFFF，JNA 传 int -1） */
    static final int SDL_HAPTIC_INFINITY = -1;

    /** SDL_JoystickType（SDL3：WHEEL=2 / THROTTLE=9，与 SDL2 的 5/10 不同！） */
    static final int SDL_JOYSTICK_TYPE_GAMEPAD = 1;
    static final int SDL_JOYSTICK_TYPE_WHEEL = 2;
    static final int SDL_JOYSTICK_TYPE_ARCADE_STICK = 3;
    static final int SDL_JOYSTICK_TYPE_FLIGHT_STICK = 4;
    static final int SDL_JOYSTICK_TYPE_THROTTLE = 9;

    /** SDL_HapticEffectType（SDL3 位偏移：SPRING=1<<7 / DAMPER=1<<8 / INERTIA=1<<9 / FRICTION=1<<10） */
    static final int SDL_HAPTIC_CONSTANT = 1 << 0;
    static final int SDL_HAPTIC_SINE = 1 << 1;
    static final int SDL_HAPTIC_SPRING = 1 << 7;
    static final int SDL_HAPTIC_DAMPER = 1 << 8;
    static final int SDL_HAPTIC_INERTIA = 1 << 9;
    static final int SDL_HAPTIC_FRICTION = 1 << 10;

    /** SDL_HapticDirectionType */
    static final int SDL_HAPTIC_POLAR = 0;
    static final int SDL_HAPTIC_CARTESIAN = 1;

    /** SDL_HAT_* 位掩码（SDL3 与 SDL2 同值） */
    static final int SDL_HAT_UP = 0x01;
    static final int SDL_HAT_RIGHT = 0x02;
    static final int SDL_HAT_DOWN = 0x04;
    static final int SDL_HAT_LEFT = 0x08;

    // ==================== JNA 结构体映射（SDL3 头文件逐字段对照） ====================

    /** SDL_HapticDirection：Uint8 type; Sint32 dir[3]; */
    public static class HapticDirection extends Structure {
        public byte type;
        public int[] dir = new int[3];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("type", "dir");
        }
    }

    /** SDL_HapticEffectType 是 Uint16；SDL_HapticConstant（SDL3 定义） */
    public static class HapticConstant extends Structure {
        public short type;
        public HapticDirection direction = new HapticDirection();
        public int length;
        public short delay;
        public short button;
        public short interval;
        public short level;
        public short attack_length;
        public short attack_level;
        public short fade_length;
        public short fade_level;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("type", "direction", "length", "delay", "button", "interval",
                    "level", "attack_length", "attack_level", "fade_length", "fade_level");
        }
    }

    /** SDL_HapticPeriodic（SINE / SQUARE / TRIANGLE / SAWTOOTH*） */
    public static class HapticPeriodic extends Structure {
        public short type;
        public HapticDirection direction = new HapticDirection();
        public int length;
        public short delay;
        public short button;
        public short interval;
        public short period;
        public short magnitude;
        public short offset;
        public short phase;
        public short attack_length;
        public short attack_level;
        public short fade_length;
        public short fade_level;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("type", "direction", "length", "delay", "button", "interval",
                    "period", "magnitude", "offset", "phase",
                    "attack_length", "attack_level", "fade_length", "fade_level");
        }
    }

    /** SDL_HapticCondition（SPRING / DAMPER / INERTIA / FRICTION） */
    public static class HapticCondition extends Structure {
        public short type;
        public HapticDirection direction = new HapticDirection();
        public int length;
        public short delay;
        public short button;
        public short interval;
        public short[] right_sat = new short[3];
        public short[] left_sat = new short[3];
        public short[] right_coeff = new short[3];
        public short[] left_coeff = new short[3];
        public short[] deadband = new short[3];
        public short[] center = new short[3];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("type", "direction", "length", "delay", "button", "interval",
                    "right_sat", "left_sat", "right_coeff", "left_coeff", "deadband", "center");
        }
    }

    /** SDL_HapticEffect —— ⚠ SDL3 是【union】（SDL2 是 struct；这是本次迁移的关键差异） */
    public static class HapticEffect extends Union {
        public short type;
        public HapticConstant constant = new HapticConstant();
        public HapticPeriodic periodic = new HapticPeriodic();
        public HapticCondition condition = new HapticCondition();

        public HapticEffect() {
            super();
        }
    }

    // ==================== 加载 ====================

    private final NativeLibrary lib;

    private SdlLib(NativeLibrary lib) {
        this.lib = lib;
    }

    /** 加载 SDL3（指定库名/路径或自动探测常见名称）；失败抛 IOException（附原因） */
    static SdlLib load(String libName) throws IOException {
        try {
            NativeLibrary l = (libName != null && !libName.isBlank())
                    ? NativeLibrary.getInstance(libName)
                    : detect();
            return new SdlLib(l);
        } catch (Throwable t) {
            throw new IOException("无法加载 SDL3: " + t.getMessage(), t);
        }
    }

    /** SDL3 库候选名（不再包含 SDL2 —— 用户要求彻底弃用旧版本） */
    private static NativeLibrary detect() {
        String[] candidates = {"SDL3", "SDL3.dll", "libSDL3.so.0", "libSDL3.so", "libSDL3.dylib"};
        Throwable last = null;
        for (String c : candidates) {
            try {
                return NativeLibrary.getInstance(c);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new UnsatisfiedLinkError("未找到 SDL3 本地库（候选: "
                + String.join(", ", candidates) + "）；原因: " + last);
    }

    private Function fn(String name) {
        return lib.getFunction(name);
    }

    /** SDL3 的 bool 是 C99 _Bool（1 字节）——按 Byte 读返回寄存器，避免高位垃圾 */
    private boolean callBool(String name, Object... args) {
        Object r = fn(name).invoke(Byte.class, args);
        return r instanceof Byte b ? b != 0 : false;
    }

    // ==================== 生命周期 ====================

    /** {@code SDL_HINT_OVERRIDE}（SDL_HintPriority）：最高优先级，能盖掉环境变量或先前设置 */
    static final int SDL_HINT_OVERRIDE = 2;

    /**
     * {@code SDL_SetHintWithPriority(name, value, priority)}。
     * <p>⚠ 影响 joystick 后端的 hint 必须在 {@link #sdlInit} <b>之前</b>设置 ——
     * 各后端在自己的 Init 里读这些 hint，Init 之后再设已经来不及。</p>
     */
    boolean sdlSetHint(String name, String value, int priority) {
        try {
            return callBool("SDL_SetHintWithPriority", name, value, priority);
        } catch (Throwable t) {
            return false;
        }
    }

    boolean sdlInit(int flags) {
        try {
            return callBool("SDL_Init", flags);
        } catch (Throwable t) {
            return false;
        }
    }

    void sdlQuitSubSystem(int flags) {
        try {
            fn("SDL_QuitSubSystem").invoke(Void.class, new Object[]{flags});
        } catch (Throwable ignored) {
        }
    }

    // ==================== 键盘 / 窗口（SDL 侧键盘必须有焦点窗口才读得到）====================

    /**
     * SDL_InitSubSystem：键盘状态由 SDL 的事件泵驱动，必须先起 {@code SDL_INIT_VIDEO}
     * （并隐式带上 {@code SDL_INIT_EVENTS}）。
     */
    boolean sdlInitSubSystem(int flags) {
        try {
            return callBool("SDL_InitSubSystem", flags);
        } catch (Throwable t) {
            return false;
        }
    }

    /** SDL_WasInit（判断子系统是否已起） */
    boolean sdlWasInit(int flags) {
        try {
            Object r = fn("SDL_WasInit").invoke(Integer.class, new Object[]{flags});
            return r instanceof Integer i && (i & flags) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** SDL_PumpEvents：把系统消息泵进 SDL 事件队列 —— 键盘状态在这里更新（必须在 SDL 窗口所在线程）。 */
    void sdlPumpEvents() {
        try {
            fn("SDL_PumpEvents").invoke(Void.class, new Object[]{});
        } catch (Throwable ignored) {
        }
    }

    /**
     * SDL_GetKeyboardState(int *numkeys)：返回按 {@code SDL_Scancode} 索引的
     * {@code const bool *}（每键 1 字节）。无窗口/未泵事件时全为 0。
     */
    byte[] sdlKeyboardState() {
        try {
            com.sun.jna.ptr.IntByReference count = new com.sun.jna.ptr.IntByReference();
            Pointer p = fn("SDL_GetKeyboardState").invokePointer(new Object[]{count});
            int n = count.getValue();
            if (p == null || n <= 0) {
                return new byte[0];
            }
            return p.getByteArray(0, n);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /** SDL_HasKeyboard（SDL 侧是否认到键盘设备） */
    boolean sdlHasKeyboard() {
        try {
            return callBool("SDL_HasKeyboard");
        } catch (Throwable t) {
            return false;
        }
    }

    /** SDL_CreateWindow(title, w, h, flags) */
    Pointer sdlCreateWindow(String title, int width, int height, long flags) {
        try {
            return fn("SDL_CreateWindow").invokePointer(new Object[]{title, width, height, flags});
        } catch (Throwable t) {
            return null;
        }
    }

    void sdlShowWindow(Pointer window) {
        try {
            fn("SDL_ShowWindow").invoke(Void.class, new Object[]{window});
        } catch (Throwable ignored) {
        }
    }

    void sdlHideWindow(Pointer window) {
        try {
            fn("SDL_HideWindow").invoke(Void.class, new Object[]{window});
        } catch (Throwable ignored) {
        }
    }

    void sdlRaiseWindow(Pointer window) {
        try {
            fn("SDL_RaiseWindow").invoke(Void.class, new Object[]{window});
        } catch (Throwable ignored) {
        }
    }

    void sdlDestroyWindow(Pointer window) {
        try {
            fn("SDL_DestroyWindow").invoke(Void.class, new Object[]{window});
        } catch (Throwable ignored) {
        }
    }

    /** 当前拥有键盘焦点的 SDL 窗口（null = 没有）——判断"焦点窗口"特性是否生效 */
    Pointer sdlKeyboardFocus() {
        try {
            return fn("SDL_GetKeyboardFocus").invokePointer(new Object[]{});
        } catch (Throwable t) {
            return null;
        }
    }

    /** SDL_GetError（诊断用；可能为 null） */
    String lastError() {
        try {
            Pointer p = fn("SDL_GetError").invokePointer(new Object[]{});
            return p == null ? null : p.getString(0);
        } catch (Throwable t) {
            return null;
        }
    }

    void sdlFree(Pointer p) {
        try {
            fn("SDL_free").invoke(Void.class, new Object[]{p});
        } catch (Throwable ignored) {
        }
    }

    // ==================== Joystick（SDL3 按 instance id） ====================

    /** SDL_GetJoysticks(int *count) → 需 SDL_free 的 SDL_JoystickID 数组（0 结尾） */
    int[] joystickIds() {
        try {
            com.sun.jna.ptr.IntByReference count = new com.sun.jna.ptr.IntByReference();
            Pointer arr = fn("SDL_GetJoysticks").invokePointer(new Object[]{count});
            if (arr == null) {
                return new int[0];
            }
            int n = count.getValue();
            int[] ids = new int[Math.max(0, n)];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = arr.getInt((long) i * 4);
            }
            sdlFree(arr);
            return ids;
        } catch (Throwable t) {
            return new int[0];
        }
    }

    String joystickNameForId(int instanceId) {
        try {
            Pointer p = fn("SDL_GetJoystickNameForID").invokePointer(new Object[]{instanceId});
            return p == null ? null : p.getString(0);
        } catch (Throwable t) {
            return null;
        }
    }

    int joystickTypeForId(int instanceId) {
        try {
            Object r = fn("SDL_GetJoystickTypeForID").invoke(Integer.class, new Object[]{instanceId});
            return r instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    int joystickVendorForId(int instanceId) {
        try {
            Object r = fn("SDL_GetJoystickVendorForID").invoke(Short.class, new Object[]{instanceId});
            return r instanceof Short s ? (s & 0xFFFF) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    int joystickProductForId(int instanceId) {
        try {
            Object r = fn("SDL_GetJoystickProductForID").invoke(Short.class, new Object[]{instanceId});
            return r instanceof Short s ? (s & 0xFFFF) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    Pointer joystickOpen(int instanceId) {
        try {
            return fn("SDL_OpenJoystick").invokePointer(new Object[]{instanceId});
        } catch (Throwable t) {
            return null;
        }
    }

    void joystickClose(Pointer joystick) {
        try {
            fn("SDL_CloseJoystick").invoke(Void.class, new Object[]{joystick});
        } catch (Throwable ignored) {
        }
    }

    /** SDL_JoystickConnected（SDL3，bool） */
    boolean joystickConnected(Pointer joystick) {
        return callBool("SDL_JoystickConnected", joystick);
    }

    int joystickInstanceId(Pointer joystick) {
        try {
            Object r = fn("SDL_GetJoystickID").invoke(Integer.class, new Object[]{joystick});
            return r instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    int joystickNumAxes(Pointer joystick) {
        try {
            Object r = fn("SDL_GetNumJoystickAxes").invoke(Integer.class, new Object[]{joystick});
            return r instanceof Integer i ? Math.max(0, i) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    int joystickNumButtons(Pointer joystick) {
        try {
            Object r = fn("SDL_GetNumJoystickButtons").invoke(Integer.class, new Object[]{joystick});
            return r instanceof Integer i ? Math.max(0, i) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    int joystickNumHats(Pointer joystick) {
        try {
            Object r = fn("SDL_GetNumJoystickHats").invoke(Integer.class, new Object[]{joystick});
            return r instanceof Integer i ? Math.max(0, i) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    int joystickGetAxis(Pointer joystick, int axis) {
        try {
            Object r = fn("SDL_GetJoystickAxis").invoke(Short.class, new Object[]{joystick, axis});
            return r instanceof Short s ? s : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    boolean joystickGetButton(Pointer joystick, int button) {
        return callBool("SDL_GetJoystickButton", joystick, button);
    }

    /** SDL_GetJoystickHat → Uint8 位掩码（SDL_HAT_*） */
    int joystickGetHat(Pointer joystick, int hat) {
        try {
            Object r = fn("SDL_GetJoystickHat").invoke(Byte.class, new Object[]{joystick, hat});
            return r instanceof Byte b ? (b & 0xFF) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** SDL3：状态由事件系统维护，轮询前显式刷新 */
    void updateJoysticks() {
        try {
            callBool("SDL_UpdateJoysticks");
        } catch (Throwable ignored) {
        }
    }

    // ==================== Haptic（力反馈） ====================

    Pointer hapticOpenFromJoystick(Pointer joystick) {
        try {
            return fn("SDL_OpenHapticFromJoystick").invokePointer(new Object[]{joystick});
        } catch (Throwable t) {
            return null;
        }
    }

    void hapticClose(Pointer haptic) {
        try {
            fn("SDL_CloseHaptic").invoke(Void.class, new Object[]{haptic});
        } catch (Throwable ignored) {
        }
    }

    int hapticMaxEffects(Pointer haptic) {
        try {
            Object r = fn("SDL_GetMaxHapticEffects").invoke(Integer.class, new Object[]{haptic});
            return r instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** SDL_CreateHapticEffect → SDL_HapticEffectID（int；<0 = 失败） */
    int hapticCreateEffect(Pointer haptic, HapticEffect effect) {
        try {
            effect.write();
            Object r = fn("SDL_CreateHapticEffect").invoke(Integer.class, new Object[]{haptic, effect});
            return r instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    boolean hapticRunEffect(Pointer haptic, int effectId, int iterations) {
        return callBool("SDL_RunHapticEffect", haptic, effectId, iterations);
    }

    boolean hapticStopEffect(Pointer haptic, int effectId) {
        return callBool("SDL_StopHapticEffect", haptic, effectId);
    }

    void hapticDestroyEffect(Pointer haptic, int effectId) {
        try {
            fn("SDL_DestroyHapticEffect").invoke(Void.class, new Object[]{haptic, effectId});
        } catch (Throwable ignored) {
        }
    }

    /**
     * SDL_StopHapticEffects —— 停掉该 haptic 上【所有】效果（SDL3 新增）。
     * <p>关键自救手段：一旦我们的 effect id 记录丢了（destroy / create 失败），那个残留在设备上的效果
     * 就再也停不掉，方向盘会被一直推着走（用户实测"顶在 -180°、只有插拔 USB 才恢复"就是这么来的）。
     * 这个调用能把设备上的一切力一次停掉。
     */
    boolean hapticStopEffects(Pointer haptic) {
        try {
            return callBool("SDL_StopHapticEffects", haptic);
        } catch (Throwable t) {
            return false;   // 旧版/裁剪版 SDL 可能没有该符号 → 调用方逐个 stop 兜底
        }
    }

    /**
     * SDL_GetHapticEffects —— 取该 haptic 上当前<b>所有</b>效果的 id（SDL3 新增）。
     * <p>用来彻底清掉"失去追踪的残留效果"：逐个销毁、释放效果槽位。否则槽位被残留占满后
     * {@code SDL_CreateHapticEffect} 会持续失败，泄漏越滚越多。
     */
    int[] hapticGetEffects(Pointer haptic) {
        try {
            com.sun.jna.ptr.IntByReference count = new com.sun.jna.ptr.IntByReference();
            Object r = fn("SDL_GetHapticEffects").invoke(Pointer.class, new Object[]{haptic, count});
            Pointer arr = r instanceof Pointer p ? p : null;
            int n = count.getValue();
            if (arr == null || n <= 0) {
                return new int[0];
            }
            int[] ids = arr.getIntArray(0, n);
            try {
                fn("SDL_free").invoke(Void.class, new Object[]{arr});
            } catch (Throwable ignored) {
                // 拿不到 SDL_free 也无所谓（一次几十字节的临时数组）
            }
            return ids;
        } catch (Throwable t) {
            return new int[0];   // 老版本 SDL3 可能没有该符号
        }
    }

    /**
     * SDL_UpdateHapticEffect —— 原地更新已存在效果的参数（SDL3 新增）。
     * <p>用来替代"destroy + create"：每秒几十次重建既慢，又会在失败时泄漏效果
     * （id 记录已被移除、效果却还在设备上跑）。
     */
    boolean hapticUpdateEffect(Pointer haptic, int effectId, HapticEffect effect) {
        try {
            effect.write();
            return callBool("SDL_UpdateHapticEffect", haptic, effectId, effect);
        } catch (Throwable t) {
            return false;   // 个别驱动不支持 → 调用方回退到重建
        }
    }

    /** 全局增益（0..100）；SDL3 语义为百分比 */
    boolean hapticSetGain(Pointer haptic, int gain) {
        return callBool("SDL_SetHapticGain", haptic, gain);
    }

    /**
     * SDL_RumbleJoystick（手柄马达震动；low/high 0..0xFFFF，durationMs）。
     * <p>手柄一般没有 DirectInput haptic 效果，但都有马达 ⇒ 力反馈的兜底通道。
     * 返回 false = 设备不支持（用 0 力度 1ms 试探即可判定）。
     */
    boolean rumbleJoystick(Pointer joystick, int low, int high, int durationMs) {
        return callBool("SDL_RumbleJoystick", joystick, low, high, durationMs);
    }

    // ==================== 效果构造（ForceParams → SDL_HapticEffect） ====================

    /** 把通用 {@link ForceParams} 填进 SDL3 的 HapticEffect union（调用方再 write/Create） */
    static HapticEffect buildEffect(ForceParams p) {
        int mag = clamp(p.magnitude(), -10000, 10000);
        int coef = clamp(p.positiveCoef(), 0, 10000);
        int off = clamp(p.offset(), 0, 10000);
        int gain = clamp(p.gain(), 0, 10000);
        int dirX = gain > 0 ? 1 : 0;
        HapticEffect effect = new HapticEffect();
        ForceEffect type = p.effect();
        if (type == ForceEffect.SPRING || type == ForceEffect.DAMPER
                || type == ForceEffect.FRICTION || type == ForceEffect.INERTIA) {
            effect.setType(HapticCondition.class);
            HapticCondition c = effect.condition;
            c.type = (short) switch (type) {
                case SPRING -> SDL_HAPTIC_SPRING;
                case FRICTION -> SDL_HAPTIC_FRICTION;
                case INERTIA -> SDL_HAPTIC_INERTIA;
                default -> SDL_HAPTIC_DAMPER;
            };
            c.direction.type = (byte) SDL_HAPTIC_CARTESIAN;
            c.direction.dir[0] = dirX;
            c.length = 0;                       // 0 = 持续到停止
            for (int i = 0; i < 3; i++) {
                c.right_sat[i] = (short) 10000;
                c.left_sat[i] = (short) 10000;
                c.right_coeff[i] = (short) coef;
                c.left_coeff[i] = (short) coef;
                c.deadband[i] = 0;
                c.center[i] = (short) off;
            }
        } else if (type == ForceEffect.SINE) {
            effect.setType(HapticPeriodic.class);
            HapticPeriodic per = effect.periodic;
            per.type = (short) SDL_HAPTIC_SINE;
            per.direction.type = (byte) SDL_HAPTIC_CARTESIAN;
            per.direction.dir[0] = dirX;
            per.length = 0;
            per.period = (short) Math.max(1, Math.min(65535, p.periodMs()));
            per.magnitude = (short) mag;
            per.offset = 0;
            per.phase = (short) clamp(p.phaseDeg() * 100, 0, 35999);
        } else {
            // CONSTANT（默认）
            effect.setType(HapticConstant.class);
            HapticConstant con = effect.constant;
            con.type = (short) SDL_HAPTIC_CONSTANT;
            con.direction.type = (byte) SDL_HAPTIC_CARTESIAN;
            con.direction.dir[0] = 1;
            con.direction.dir[1] = 0;
            con.direction.dir[2] = 0;
            con.length = 0;
            // ⚠⚠ 关键：level 必须【带符号】。
            //   SDL_HapticConstant.level 是 Sint16，SDL 内部直接把它当作 DirectInput 的
            //   DICONSTANTFORCE.lMagnitude（有符号，符号即方向）；而 direction 对"恒定力"效果
            //   在大量方向盘驱动上是被忽略的。
            //   旧实现写的是 level = |mag| + direction.dir[0] = ±1 ⇒ **力永远朝同一侧推**，
            //   方向盘被单向顶到极限（实测"回正后停在 +180° 或 -180°"就是这么来的）。
            con.level = (short) mag;
        }
        return effect;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
