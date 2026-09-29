/**
 * ===== 外设船舵 · 客户端全局设备池（2026-09-13） =====
 *
 * 用户定稿的模型：
 * <ul>
 *   <li><b>每个客户端一个全局设备池</b>：设备一旦被打开就留在池里，读取（采样）直接从池取快照
 *       —— 不再每次绑定都重新枚举/打开（此前放置方块卡顿、绑定后"设备未连接"都源于此）；</li>
 *   <li><b>只有点"刷新设备"才会扫描一次</b>（{@link #requestScan()}）；界面打开、放置方块、
 *       采样都不触发 SDL 枚举；</li>
 *   <li><b>设备共享（引用计数，类似 shared_ptr）</b>：一套设备可被多个外设船舵同时绑定 ——
 *       所有使用者读<b>同一份</b>采样快照（方向盘只有一个物理位置），力反馈按"逐通道取最强"合成；
 *       只有<b>最后一个使用者释放</b>、引用计数归零时，采样线程才关闭设备
 *       （停掉全部力反馈 + 关闭句柄 = 断开方向盘连接）。</li>
 * </ul>
 * 线程模型：所有 SDL 调用（扫描、打开/关闭、采样、力反馈）都在单个常驻 daemon 线程上；
 * 主线程只做 <b>O(1) 的池表读写</b>（ConcurrentHashMap + volatile），零阻塞。
 *
 * 使用者 key：方块用 `block:&lt;BlockPos.asLong&gt;`，界面预览等其它用途可自定义。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.hdf.cryptand.gameinput.ForceEffect;
import com.hdf.cryptand.gameinput.ForceParams;
import com.hdf.cryptand.gameinput.GameInputBackend;
import com.hdf.cryptand.gameinput.GameInputController;
import com.hdf.cryptand.gameinput.GameInputDeviceInfo;
import com.hdf.cryptand.gameinput.GameInputDeviceKind;
import com.hdf.cryptand.gameinput.GameInputProvider;
import com.hdf.cryptand.gameinput.GameInputState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PeripheralHelmInput {

    private static final String THREAD_NAME = "Cryptand-PeripheralHelm-Pool";
    /** 有设备打开时的采样周期（ms） */
    private static final long POLL_INTERVAL_MS = 4L;
    /** 池空闲时的休眠（降低唤醒频率） */
    private static final long IDLE_SLEEP_MS = 50L;
    /** 打开失败后的重试间隔（ms） */
    private static final long OPEN_RETRY_MS = 2000L;
    /**
     * 打开超时（ms）：SDL 的 native 调用（尤其 {@code SDL_OpenJoystick}）在设备被其它程序
     * 独占时可能长期不返回。超时后池线程如实报错并继续服务其它设备，绝不陪它一起等。
     */
    private static final long OPEN_TIMEOUT_MS = 3000L;
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Cryptand-PeripheralHelm");
    /** 力反馈最小重下发间隔（ms）与强度死区 */
    private static final long FORCE_REAPPLY_MS = 120L;
    private static final int FORCE_DEADBAND = 400;
    /** 连接成功后的力反馈自检：持续时间与强度（恒定力，静止也能明显感觉到） */
    private static final long SELF_TEST_MS = 1200L;
    private static final int SELF_TEST_STRENGTH = 6000;
    /**
     * 设备打开后"把方向盘拉到 0 度位"的回中力持续时间（ms）。
     * <p>不这么做的话，方向盘停在非中位（电位器漂移 / 上次没回正 / 驱动刚打开轴值未就绪读到 ±1）时，
     * 绝对轴值会立刻被当成满舵指令 —— 现象就是"一连接舵轮就甩到 -180°"。
     */
    private static final long RECENTER_FORCE_MS = 4000L;
    /** 手动【回中方向盘】的回中力持续时间（设备已热，短一点就够） */
    private static final long RECENTER_MANUAL_MS = 3000L;   // 回中 + 到 0 后保持 1 秒，窗口必须够长
    /** 回中判定"已到中心"的轴值阈值（|轴值| ≤ 它就收工） */
    private static final float RECENTER_ARRIVED = 0.05f;
    /** 回中力上限（0..10000；别用满力，免得猛甩方向盘） */
    private static final int RECENTER_MAX_FORCE = 5500;
    /** 到 0 后保持（确认稳定）的时长：满力推到位后继续按住这么久才退出回中 */
    private static final long RECENTER_HOLD_MS = 1000L;
    /** 0 附近的方向滞回：只有偏离超过它才翻转力的方向，否则满力会在中位来回撞 */
    private static final float RECENTER_HYSTERESIS = 0.06f;
    /** 力反馈自检的"左右抖动"半周期（ms）：交替正负恒力，方向盘在当前角度附近快速摇摆 */
    private static final long SELF_TEST_SWING_MS = 90L;
    /** 回中阻尼系数（∝ 轴值每帧变化量）：没有它，纯比例的恒定力会把方向盘推过中点来回弹 */
    private static final float RECENTER_DAMPING = 3000f;

    /** 一帧采样快照（不可变；axes 已克隆） */
    public record Sample(boolean connected, float steer, float throttle, float brake, float[] axes,
                        long buttons, int pov, int[] keys) {
        public static final Sample EMPTY =
                new Sample(false, 0f, 0f, 0f, new float[GameInputState.AXIS_COUNT], 0L, -1,
                        GameInputState.EMPTY_KEYS);

        /** 方向帽（POV）在【统一按键索引空间】里的编号起点：64..71 = 上/右上/右/右下/下/左下/左/左上 */
        public static final int HAT_BASE = 64;
        /**
         * 键盘在【统一按键索引空间】里的起点：{@code KEY_BASE + GLFW key code}。
         * 于是"按键"这一个概念同时覆盖 SDL 按钮、方向帽、键盘 —— 绑定表、录制、显示全部一条路径。
         */
        public static final int KEY_BASE = 72;
        /** 统一按键索引空间的大小（0..63 = SDL 按钮；64..71 = 方向帽；72.. = 键盘 GLFW 码） */
        public static final int BUTTON_COUNT = KEY_BASE + 349;

        public float axis(int index) {
            if (!connected || axes == null || index < 0 || index >= axes.length) {
                return 0f;
            }
            return axes[index];
        }

        /**
         * 按键是否按下（统一索引空间）。
         * <ul>
         *   <li>{@code 0..63} = SDL 按钮索引（⚠ 旧实现把 {@code long} 强转成 {@code int}，
         *       32 号以后的按键会被静默丢弃 —— 排挡器/换挡器的按键经常落在那里）；</li>
         *   <li>{@code 64..71} = 方向帽（POV）的 8 个方向。很多换挡器/排挡器用<b>方向帽</b>报告档位，
         *       而不是按钮 —— 只读按钮的话这类设备一个键都绑不上。</li>
         * </ul>
         */
        public boolean button(int index) {
            if (!connected || index < 0) {
                return false;
            }
            if (index < HAT_BASE) {
                return (buttons & (1L << index)) != 0;
            }
            if (index < KEY_BASE) {
                int dir = index - HAT_BASE;
                return dir < 8 && pov == dir * 4500;
            }
            int glfw = index - KEY_BASE;
            if (keys != null) {
                for (int k : keys) {
                    if (k == glfw) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 当前按下的全部按键（统一索引空间；界面"录制/监视"用） */
        public int[] pressedButtons() {
            if (!connected) {
                return new int[0];
            }
            int[] tmp = new int[HAT_BASE + 8 + (keys == null ? 0 : keys.length)];
            int n = 0;
            for (int i = 0; i < HAT_BASE; i++) {
                if ((buttons & (1L << i)) != 0L) {
                    tmp[n++] = i;
                }
            }
            for (int d = 0; d < 8; d++) {
                if (pov == d * 4500) {
                    tmp[n++] = HAT_BASE + d;
                }
            }
            if (keys != null) {
                for (int k : keys) {
                    tmp[n++] = KEY_BASE + k;
                }
            }
            return java.util.Arrays.copyOf(tmp, n);
        }
    }

    /**
     * 力反馈<b>多通道</b>请求（模拟赛车风格）：SDL haptic 允许每类效果各一个实例，
     * 我们的 {@code SdlController} 也已按 {@link com.hdf.cryptand.gameinput.ForceEffect} 分别持有 effect id
     * ⇒ 可以同时下发"回中弹簧 + 阻尼 + 侧向恒定力 + 路面振动"，这正是真实赛车 FFB 的合成方式。
     *
     * @param constant    恒定力（-10000..10000；符号 = 方向。用于侧向 G / 对齐力矩）
     * @param spring      回中弹簧刚度（0..10000；∝ 舵角）
     * @param damper      阻尼（0..10000；∝ 转向角速度）
     * @param sineMagnitude 正弦振动幅度（0..10000；路面纹理）
     * @param sinePeriodMs  正弦周期（ms；车速越高周期越短 = 频率越高）
     * @param inertia       惯性力系数（0..10000；∝ 垂向加速度突变 —— 颠簸/落地/上下坡冲击）
     */
    public record ForceChannels(int constant, int spring, int damper,
                                int sineMagnitude, int sinePeriodMs, int inertia) {
        public static final ForceChannels NONE = new ForceChannels(0, 0, 0, 0, 60, 0);
    }

    /** 池条目：设备句柄 + 快照 + 绑定者 */
    private static final class DeviceHandle {
        /**
         * 稳定设备 id（绑定持久化用）。⚠ SDL3 的 instance id 在设备重连/程序重启后会变
         * （文档："never reused... gets a new ID"），拿它存绑定会出现
         * `SDL_OpenJoystick(46) 失败: Joystick 46 not found` —— 故绑定存稳定 id。
         */
        final String stableId;
        /** 本次运行扫描到的设备描述（{@code info.id()} = SDL3 instance id，仅用于打开句柄） */
        volatile GameInputDeviceInfo info;
        volatile GameInputController controller;
        volatile Sample sample = Sample.EMPTY;
        /**
         * 回中窗口结束时刻（ms）：设备刚打开（或用户点【回中方向盘】）后的这段时间内，
         * 用【恒定力 PD 闭环】把<b>物理方向盘推回 0 度位置</b>（位置项 + 阻尼项），其它力反馈通道暂停。
         * <p>⚠ 舵角一律取轴的<b>绝对值</b>（不做软件零点偏移）—— "中位对齐"由这里的力反馈负责。
         */
        volatile long recenterForceUntil;
        /** 回中闭环用的轴映射（由方块每 tick 通过 {@link #setSteerAxis} 传入；池与绑定解耦） */
        volatile int recenterAxis = 0;
        volatile boolean recenterInvert;
        /** 回中 PD 用的上一帧轴值（算阻尼项，抑制过冲） */
        volatile float recenterLastPos;
        /** 回中推力方向（±1；0 = 未定）——满力回中时靠它 + 滞回避免 0 附近抖动 */
        volatile int recenterDir;
        /** 到达 0 位的时刻（ms；0 = 尚未到位）——用于"保持 1 秒再退出" */
        volatile long recenterArrivedAt;

        /** 一键停力：抑制下发到该时刻（ms），期间任何通道都不下发 —— 否则下一帧又被自动力覆盖 */
        volatile long suppressForceUntil;
        /** 待执行的"停掉设备上所有力"请求（由采样线程执行，避免主线程碰 SDL） */
        volatile boolean stopRequested;
        /**
         * 使用者集合 = <b>引用计数</b>：设备不再独占，一套设备可被多个外设船舵同时绑定；
         * 集合为空 ⇒ 无人使用 ⇒ 采样线程关闭设备（并停掉全部力反馈，等于断开方向盘连接）。
         */
        final Set<String> users = ConcurrentHashMap.newKeySet();
        /** 每个使用者各自请求的力反馈通道（共享时逐通道取最强合成，见 {@link #mergeChannels}） */
        final Map<String, ForceChannels> channelsByUser = new ConcurrentHashMap<>();
        /** 打开失败原因（界面诊断用） */
        volatile String error;
        /** 力反馈请求（0..10000；兼容旧的单通道 API，等价于 damper） */
        volatile int forceRequest;
        /** 多通道请求（赛车风格合成） */
        volatile ForceChannels channels = ForceChannels.NONE;
        /** 各通道上次实际下发的强度（分离节流用） */
        final Map<com.hdf.cryptand.gameinput.ForceEffect, Integer> appliedChannels =
                new java.util.EnumMap<>(com.hdf.cryptand.gameinput.ForceEffect.class);
        int appliedForce = -1;
        long appliedForceAt;
        long lastOpenAttempt;
        /** 最近一次"请求打开"的投递时刻（ms）；SDL 线程成功交出句柄后清 0 —— 用于打开超时诊断 */
        volatile long openRequestedAt;
        /** 力反馈自检截止时刻（连接成功后短暂给一段恒定力，用于验证设备能不能感觉到） */
        volatile long selfTestUntil;
        /** 自检强度（0..10000；手动测试用最大值） */
        volatile int selfTestStrength = SELF_TEST_STRENGTH;
        /** 力反馈可用性（懒探测：null = 未探测；false = 无 haptic 也无马达） */
        volatile Boolean ffbSupported;
        /**
         * 强制设备类型（null = 自动，用 {@link #info} 的后端判定）。
         * 用户："…都可以和船舵一样设置设备强制，防止识别有问题" + "设置为自动则按照自动的进行设置"。
         */
        volatile GameInputDeviceKind forcedKind;

        DeviceHandle(String stableId, GameInputDeviceInfo info) {
            this.stableId = stableId;
            this.info = info;
        }
    }

    /** 设备池（key =【稳定 id】） */
    private static final Map<String, DeviceHandle> POOL = new ConcurrentHashMap<>();

    /** 待绑定：绑定时池里还没有该设备（未扫描/刚重进游戏）→ 扫描后自动补上使用者（可多个） */
    private static final Map<String, Set<String>> PENDING_BIND = new ConcurrentHashMap<>();

    // 主线程 → worker
    private static volatile boolean scanRequested;
    // worker → 主线程
    private static volatile boolean scanning;
    private static volatile String backendStatus = "未扫描（点「刷新设备」）";
    private static volatile Thread worker;

    private PeripheralHelmInput() {
    }

    // ==================== 主线程 API（零阻塞） ====================

    /**
     * 池内设备列表（零阻塞；{@code id()} 是【稳定 id】，可直接写进绑定）。
     * <p>⚠ <b>按稳定 id 排序后返回</b>：池是 {@code ConcurrentHashMap}，遍历顺序不稳定 ——
     * 界面用列表指纹来判断"要不要重建列表"，只要顺序一变指纹就变，于是界面会<b>每 tick
     * 重建整个列表</b>，LDLib2 疯狂重绘把渲染线程 CPU 打满（实测"添加新设备后点刷新卡死，
     * 要很久才恢复"）。排序后指纹稳定，问题从根上消失。
     */
    public static List<GameInputDeviceInfo> devices() {
        List<GameInputDeviceInfo> out = new ArrayList<>(POOL.size());
        for (DeviceHandle h : POOL.values()) {
            GameInputDeviceInfo i = h.info;
            out.add(new GameInputDeviceInfo(h.stableId, i.name(), i.kind(), i.forceFeedback()));
        }
        out.sort(java.util.Comparator.comparing(GameInputDeviceInfo::id));
        return out;
    }

    /** 池内设备数 */
    public static int deviceCount() {
        return POOL.size();
    }

    /** 该设备当前的<b>使用者数量</b>（引用计数；0 = 空闲，设备会被关闭） */
    public static int userCount(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        return h == null ? 0 : h.users.size();
    }

    /** 该设备是否有任何使用者（引用计数 &gt; 0） */
    public static boolean isInUse(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        return h != null && !h.users.isEmpty();
    }

    /** 本使用者是否已登记使用该设备 */
    public static boolean isBound(String deviceId, String userKey) {
        DeviceHandle h = handleOf(deviceId);
        return h != null && h.users.contains(userKey == null ? "" : userKey);
    }

    /** 设备打开错误（null = 无）；池中不存在但处于待绑定 → 提示刷新 */
    public static String deviceError(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        if (h != null) {
            return h.error;
        }
        if (PENDING_BIND.containsKey(deviceId)) {
            return "设备不在池中（点【刷新设备】扫描；旧格式 id 需重新选择）";
        }
        return null;
    }

    /**
     * 申请绑定（<b>共享，非独占</b>）：把 {@code userKey} 登记为该设备的使用者（引用计数 +1，幂等）。
     * <p>一套设备可被多个外设船舵同时使用；只有最后一个使用者 {@link #releaseUser 释放}、
     * 计数归零时，采样线程才关闭设备 —— 也就是"没人用了就断开方向盘连接"。
     */
    public static void bind(String deviceId, String userKey) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;   // 未指定设备 → 不占用任何设备
        }
        String key = userKey == null ? "" : userKey;

        // ★ 同一使用者只允许绑定一个设备：换绑时先把【旧设备】上的引用释放掉。
        //   用户定稿："每次连接新的需要把旧的绑定退出即可"。
        //   不这么做，旧设备的引用计数永远不归零 ⇒ 句柄不关、力反馈残留
        //   （现象就是"回正后方向盘仍被一股力顶着 / 停在 ±180°"）。
        //   放在 bind() 内部，所以船舵、拉杆、以及以后任何调用方都自动获得这个行为。
        // 目标句柄（精确命中优先，其次按名称 —— "进入世界按照名称的第一个设备进行连接"）
        DeviceHandle target = handleOf(deviceId);
        for (DeviceHandle other : POOL.values()) {
            if (other != target && other.users.remove(key)) {
                other.channelsByUser.remove(key);
            }
        }
        for (Map.Entry<String, Set<String>> pending : PENDING_BIND.entrySet()) {
            if (!pending.getKey().equals(deviceId)
                    && (target == null || !pending.getKey().equals(target.stableId))) {
                pending.getValue().remove(key);
            }
        }

        DeviceHandle h = target;
        if (h == null) {
            // 池里还没有（本次没扫描过 / 存档里的旧绑定）→ 只记下"待绑定"。
            // ⚠ 绝不自动扫描：扫描只能由用户点【刷新设备】触发 —— bind() 会被方块每 tick 调用，
            //   一旦在这里触发扫描就会变成"每 tick 全池重扫"的死循环（实测"一直在重新扫描"）。
            PENDING_BIND.computeIfAbsent(deviceId, k -> ConcurrentHashMap.newKeySet()).add(key);
            ensureWorker();
            return;
        }
        PENDING_BIND.remove(deviceId);
        h.users.add(key);
        ensureWorker();
    }

    /**
     * 释放该使用者的引用（方块移除/退出世界/停止驱动）——引用计数 -1。
     * <p>计数归零后，采样线程下一轮会 {@link #closeDevice 关闭设备}（停掉全部力反馈 + 关闭句柄），
     * 即"最后一个人走了才断开方向盘连接"。
     */
    public static void releaseUser(String userKey) {
        if (userKey == null) {
            return;
        }
        for (DeviceHandle h : POOL.values()) {
            if (h.users.remove(userKey)) {
                h.channelsByUser.remove(userKey);
                if (h.users.isEmpty()) {
                    // ★ 最后一个使用者离开：立刻标记"停掉设备上一切力"（用户要求"设备被破坏时强制关闭"）。
                    //   worker 下一轮（≤4ms）会 closeDevice：SDL_StopHapticEffects 全停 +
                    //   purgeHapticEffects 遍历销毁残留 + 关句柄。若还有别的使用者则不动它（共享语义）。
                    h.stopRequested = true;
                    h.selfTestUntil = 0L;
                    h.recenterForceUntil = 0L;
                    h.suppressForceUntil = 0L;
                }
            }
        }
        for (Set<String> pending : PENDING_BIND.values()) {
            pending.remove(userKey);
        }
    }

    /**
     * 读取该设备的当前快照（零阻塞；未登记的使用者返回 {@link Sample#EMPTY}）。
     * <p>共享设备时所有使用者读的是<b>同一份</b>快照 —— 方向盘只有一个物理位置，这才是应有的语义。
     */
    public static Sample sample(String deviceId, String userKey) {
        DeviceHandle h = handleOf(deviceId);
        if (h == null || !h.users.contains(userKey == null ? "" : userKey)) {
            return Sample.EMPTY;
        }
        return h.sample;
    }

    // ==================== 设备标识解析（"按名称的第一个设备连接"）====================

    /**
     * 把绑定里保存的设备标识解析成池中的句柄。
     *
     * <p>用户 2026-09-14 定稿："外设的方块退出时保存名称，然后进入世界是按照名称的第一个设备进行连接。"
     *
     * <p>为什么不能只精确查：绑定里存的是稳定 id（设备名的 slug），而同名设备的序号后缀
     * （{@code _2}）取决于"这次扫描到几个同名设备" —— 拔掉一个再进世界，稳定 id 就从
     * {@code xxx_2} 变回 {@code xxx}，精确查会判定"设备不在池里" ⇒ 莫名其妙的绑定失效。
     * 因此：先精确命中（保持不变），再按【名称基准】取第一个同名设备。
     */
    private static DeviceHandle handleOf(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return null;
        }
        DeviceHandle exact = POOL.get(deviceId);
        if (exact != null) {
            return exact;
        }
        String base = baseName(deviceId);
        if (base.isEmpty()) {
            return null;
        }
        DeviceHandle first = null;
        for (DeviceHandle h : POOL.values()) {
            if (baseName(h.stableId).equals(base)
                    && (first == null || h.stableId.compareTo(first.stableId) < 0)) {
                first = h;   // 取名称相同的第一个（按 stableId 排序 ⇒ 结果确定，不随遍历顺序变）
            }
        }
        return first;
    }

    /** 名称基准：去掉"同名序号"后缀（{@code xxx_2} → {@code xxx}） */
    private static String baseName(String stableId) {
        return stableId == null ? "" : stableId.replaceAll("_\\d+$", "");
    }

    /**
     * 设备名（池中该设备的原始名称；用于界面显示 / 说明绑定到底连的是谁）。
     * 设备不在池中返回 {@code null}。
     */
    public static String deviceName(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        return h == null || h.info == null ? null : h.info.name();
    }

    /**
     * 设置"强制设备类型"（{@code null} = 自动 = 按后端检测结果）。
     * <p>由外设方块每周期传入（与 {@link #setSteerAxis} 同一模式）。语义（用户定稿）：
     * <b>自动则完全按自动的走</b>，只有玩家显式选了具体类型才覆盖 —— 用于纠正
     * "国产方向盘被 SDL 认成通用控制器"这类识别问题。
     */
    public static void setForcedKind(String deviceId, String userKey, GameInputDeviceKind kind) {
        DeviceHandle h = handleOf(deviceId);
        if (h != null && h.users.contains(userKey == null ? "" : userKey)) {
            h.forcedKind = kind;
        }
    }

    /** 该设备当前生效的类型：强制优先；未强制（自动）则用后端检测结果。设备不在池中返回 null。 */
    public static GameInputDeviceKind kindOf(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        if (h == null) {
            return null;
        }
        if (h.forcedKind != null) {
            return h.forcedKind;
        }
        return h.info == null ? null : h.info.kind();
    }

    /**
     * 手动把物理方向盘拉回 0 度位（界面【回中方向盘】按钮）：
     * 接下来 {@link #RECENTER_MANUAL_MS} 内下发满力 SPRING（中心 0）。
     * <p>方向盘中位会随开机/断电/回中弹簧漂移，玩家也可能在非中位时点【连接】——
     * 这时绝对轴值会立刻被当成满舵指令（"一连接就转到 -180°"就是这么来的），点一下重新拉正即可。
     *
     * @return 是否已排队（false = 设备不在池中，或不是本使用者持有）
     */
    public static boolean requestRecenter(String deviceId, String userKey) {
        DeviceHandle h = handleOf(deviceId);
        if (h == null || !h.users.contains(userKey == null ? "" : userKey)) {
            return false;
        }
        scheduleRecenterForce(h, RECENTER_MANUAL_MS);
        return true;
    }

    /**
     * 一键停掉设备上的<b>所有</b>力反馈（界面【停止力反馈】）。
     * <p>用途：效果一旦泄漏在设备上（我们的 effect id 记录丢失、力停不掉），方向盘会被一直推着走
     * —— 实测"顶在 -180°、只有插拔 USB 才恢复"。此方法走 {@code SDL_StopHapticEffects}（一次停全部），
     * 并在此后 {@code suppressMs} 内不下发任何通道，给你观察方向盘是否恢复。
     *
     * @return 是否已排队（false = 设备不在池中，或不是本使用者持有）
     */
    public static boolean requestStopForce(String deviceId, String userKey, long suppressMs) {
        DeviceHandle h = handleOf(deviceId);
        if (h == null || !h.users.contains(userKey == null ? "" : userKey)) {
            return false;
        }
        h.selfTestUntil = 0L;
        h.recenterForceUntil = 0L;
        h.forceRequest = 0;
        h.channels = ForceChannels.NONE;
        h.suppressForceUntil = System.currentTimeMillis() + Math.max(0L, suppressMs);
        h.stopRequested = true;   // 采样线程负责真正停力（SDL 只能在那个线程碰）
        return true;
    }

    // ==================== 指令接口（/cryptand peripheral …） ====================

    /**
     * 完全断开指定设备的所有使用者（{@code deviceId} 为空 = 全部设备）。
     * <p>做法是把引用计数清零并置"立即停力"：采样线程下一轮就会
     * {@link #closeDevice 关闭句柄}（停掉设备上一切力 + 销毁残留效果）。
     *
     * @return 被断开的使用者数量（0 = 该设备当前没人在用）
     */
    public static int disconnectDevice(String deviceId) {
        String target = deviceId == null ? "" : deviceId.trim();
        int removed = 0;
        for (DeviceHandle h : POOL.values()) {
            if (!target.isEmpty() && !h.stableId.equals(target)) {
                continue;
            }
            removed += h.users.size();
            h.users.clear();
            h.channelsByUser.clear();
            h.selfTestUntil = 0L;
            h.recenterForceUntil = 0L;
            h.suppressForceUntil = 0L;
            h.stopRequested = true;      // 立刻停掉设备上的所有力
        }
        if (target.isEmpty()) {
            PENDING_BIND.clear();
        } else {
            PENDING_BIND.remove(target);
        }
        return removed;
    }

    /** 停掉所有设备上的力反馈（保留绑定，只是不再输出力）。 */
    public static void stopAllForce() {
        for (DeviceHandle h : POOL.values()) {
            h.stopRequested = true;
        }
    }

    /** 设备快照（指令回显用；一行一台设备）。 */
    public static List<String> describeDevices() {
        List<String> out = new ArrayList<>();
        for (DeviceHandle h : POOL.values()) {
            out.add(h.stableId
                    + "  [" + (h.info == null ? "?" : h.info.name()) + "]"
                    + "  使用者=" + h.users.size()
                    + "  句柄=" + (h.controller != null ? "已打开" : "未打开")
                    + (h.error == null ? "" : "  错误=" + h.error));
        }
        return out;
    }

    /** 是否处于"一键停力"的抑制期（界面提示用） */
    public static boolean isForceSuppressed(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        return h != null && System.currentTimeMillis() < h.suppressForceUntil;
    }

    /** 回中力是否仍在生效（界面提示用） */
    public static boolean isRecentering(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        return h != null && System.currentTimeMillis() < h.recenterForceUntil;
    }

    /**
     * 由方块每 tick 同步"转向轴映射"（设备池与绑定解耦，故由此传入）：
     * 回中闭环需要知道读哪个轴、是否反向，才能算出"朝绝对 0 推"的力方向。
     */
    public static void setSteerAxis(String deviceId, String userKey, int axis, boolean invert) {
        DeviceHandle h = handleOf(deviceId);
        if (h == null || !h.users.contains(userKey == null ? "" : userKey)) {
            return;
        }
        h.recenterAxis = Math.max(0, axis);
        h.recenterInvert = invert;
    }

    /** 开一个回中窗口（恒定力闭环把方向盘推到绝对 0 位） */
    private static void scheduleRecenterForce(DeviceHandle h, long durationMs) {
        h.recenterForceUntil = System.currentTimeMillis() + durationMs;
        h.recenterDir = 0;
        h.recenterArrivedAt = 0L;
        h.recenterLastPos = 0f;
        h.appliedForce = -1;        // 让下一次下发不被节流挡住
    }

    /**
     * 请求一次力反馈自检（连接成功后调用）：接下来 {@link #SELF_TEST_MS} 内下发恒定力，
     * 无论船体是否在动都能明显感觉到 —— 用来快速区分"设备不支持/链路没通"和"静止所以按设计没反馈"。
     */
    public static void requestSelfTest(String deviceId, String userKey) {
        requestSelfTest(deviceId, userKey, SELF_TEST_STRENGTH, SELF_TEST_MS);
    }

    /**
     * 通用自检/测试：在接下来 {@code durationMs} 内下发 {@code strength}（0..10000）的恒定力。
     * 界面"测试力反馈"按钮用最大强度 + 5 秒，方便确认方向盘到底能不能动。
     */
    public static void requestSelfTest(String deviceId, String userKey, int strength, long durationMs) {
        DeviceHandle h = handleOf(deviceId);
        if (h != null && h.users.contains(userKey == null ? "" : userKey)) {
            h.selfTestStrength = Math.max(0, Math.min(10000, strength));
            h.selfTestUntil = System.currentTimeMillis() + Math.max(100L, durationMs);
        }
    }

    /** 该设备是否正在自检/测试（界面显示"测试中"） */
    public static boolean isSelfTesting(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        return h != null && System.currentTimeMillis() < h.selfTestUntil;
    }

    /** 力反馈状态一行（界面显示；null = 设备不在池中） */
    public static String ffbStatus(String deviceId) {
        DeviceHandle h = handleOf(deviceId);
        if (h == null) {
            return null;
        }
        Boolean supported = h.ffbSupported;
        if (supported == null) {
            return "力反馈：探测中";
        }
        return supported ? "力反馈：就绪" : "力反馈：不支持（无 haptic 也无马达）";
    }

    /** 请求力反馈强度（0..10000；非持有者忽略）——单通道旧 API，等价于阻尼通道 */
    public static void requestForce(String deviceId, String userKey, int magnitude) {
        DeviceHandle h = handleOf(deviceId);
        if (h != null && h.users.contains(userKey == null ? "" : userKey)) {
            h.forceRequest = Math.max(0, Math.min(10000, magnitude));
        }
    }

    /**
     * 请求多通道力反馈（赛车风格；未登记的使用者忽略）。
     * <p>共享设备时每个使用者各存一份请求，采样线程每轮按通道<b>取强度最大者</b>合成
     * （{@link #mergeChannels}）—— 谁的手感都不会被别人的请求吞掉，也不需要抢占。
     */
    public static void requestChannels(String deviceId, String userKey, ForceChannels channels) {
        DeviceHandle h = handleOf(deviceId);
        if (h != null && h.users.contains(userKey == null ? "" : userKey)) {
            h.channelsByUser.put(userKey == null ? "" : userKey,
                    channels == null ? ForceChannels.NONE : channels);
        }
    }

    /**
     * 合成多个使用者的力反馈请求：逐通道<b>取绝对值最大者</b>（确定、无优先级、无抢占、无兜底）。
     * 正弦通道的周期跟随"幅度最大的那一份请求"。
     */
    private static ForceChannels mergeChannels(java.util.Collection<ForceChannels> all) {
        int constant = 0;
        int spring = 0;
        int damper = 0;
        int sine = 0;
        int period = 60;
        int inertia = 0;
        for (ForceChannels c : all) {
            if (c == null) {
                continue;
            }
            if (Math.abs(c.constant()) > Math.abs(constant)) {
                constant = c.constant();
            }
            if (Math.abs(c.spring()) > Math.abs(spring)) {
                spring = c.spring();
            }
            if (Math.abs(c.damper()) > Math.abs(damper)) {
                damper = c.damper();
            }
            if (Math.abs(c.sineMagnitude()) > Math.abs(sine)) {
                sine = c.sineMagnitude();
                period = c.sinePeriodMs();
            }
            if (Math.abs(c.inertia()) > Math.abs(inertia)) {
                inertia = c.inertia();
            }
        }
        if (constant == 0 && spring == 0 && damper == 0 && sine == 0 && inertia == 0) {
            return ForceChannels.NONE;
        }
        return new ForceChannels(constant, spring, damper, sine, period, inertia);
    }

    /** 请求扫描设备（<b>仅"刷新设备"按钮调用</b>；结果稍后出现在 {@link #devices()}） */
    public static void requestScan() {
        scanRequested = true;
        ensureWorker();
    }

    public static boolean isScanning() {
        return scanning;
    }

    /** 诊断行（读缓存，零阻塞） */
    public static String diagnostic() {
        try {
            int bound = 0;
            for (DeviceHandle h : POOL.values()) {
                if (!h.users.isEmpty()) {
                    bound++;
                }
            }
            String suffix = scanning ? " · 扫描中…" : (" · 设备 " + POOL.size() + (bound > 0 ? "（占用 " + bound + "）" : ""));
            return backendStatus + suffix;
        } catch (Throwable t) {
            return "诊断失败：" + t.getClass().getSimpleName();
        }
    }

    /** 关闭全部设备（退出世界/退出游戏）：引用计数全部归零，worker 下一轮即关闭句柄并停掉一切力。 */
    public static void shutdown() {
        for (DeviceHandle h : POOL.values()) {
            h.users.clear();
            h.channelsByUser.clear();
            h.forceRequest = 0;
            // ★ 必须同时置 stopRequested：否则"退出世界时"残留的力反馈/回中窗口会继续下发，
            //   下次进世界又叠加一套新状态（用户实测过"方向盘被一股力顶着"）。
            h.stopRequested = true;
            h.selfTestUntil = 0L;
            h.recenterForceUntil = 0L;
            h.suppressForceUntil = 0L;
        }
        PENDING_BIND.clear();
    }

    // ==================== 后台线程 ====================
    //
    // 2026-09-14 用户定稿："SDL 可以单独一个线程，每次有消息时激活处理" + "通过消息交互，防止竞态"。
    //
    // 线程职责（严格分离，SDK 句柄只在 SDL 线程碰）：
    //   · 池线程 Cryptand-PeripheralHelm-Pool —— 扫描 / 池维护 / 绑定判定 / 超时诊断；
    //     它【不直接调用任何 SDL 接口】，只 postSdl(...) 投递请求，并读 volatile 快照(Sample)。
    //   · SDL 线程 Cryptand-PeripheralHelm-SDL —— 唯一碰 SDL 的地方：open / close / getState
    //     / 力反馈下发；有消息才跑，没有就 park（零轮询）。
    //
    // 为什么必须这样（实测事故）：SDL_OpenJoystick 会卡在 native 里永不返回（设备被其它程序
    // 独占/驱动异常时）。旧实现在池线程里同步调用它 ⇒ 整条池线程冻死 ⇒ <b>所有</b>设备都连不上、
    // 界面 5 秒后报"连接超时"。职责分离后：卡住的只是 SDL 线程，池线程照常做超时诊断与重试，
    // 其它路径（键盘等）不受影响；消息队列也让两个线程之间没有任何共享可变状态 —— 无竞态。

    // 队列与线程本身在 common 的 {@code SdlThread}（全局唯一一条 SDL 线程）：
    // 手柄/方向盘的打开与采样、SDL 侧键盘的事件泵，全部排在同一条线程上执行。


    /**
     * 采样消息的合并标志：SDL 线程卡住时不能让"每 4ms 一条采样"无限堆积
     * （已有未处理的采样就跳过本次投递 —— 这正是消息机制该有的背压方式）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean SAMPLE_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 投递一条 SDL 消息（任意线程可调；无锁、非阻塞）。
     * <p>实现委托给 common 的 {@code SdlThread}：它的周期任务由<b>项目线程分配器</b>以
     * {@code TaskMode.EXCLUSIVE} 排程 ⇒ 永远同一条常驻 Worker 线程串行执行（SDL 的线程亲和性）。
     */
    private static void postSdl(Runnable message) {
        if (message != null) {
            com.hdf.cryptand.gameinput.sdl.SdlThread.post(message);
        }
    }

    /** 确保全局 SDL 线程已就绪（幂等）。 */
    private static void ensureSdlThread() {
        com.hdf.cryptand.gameinput.sdl.SdlThread.ensureStarted();
    }

    /** 请求打开设备（只投递；真正的 SDL 调用在 SDL 线程里做） */
    private static void requestOpen(DeviceHandle h) {
        h.openRequestedAt = System.currentTimeMillis();
        postSdl(() -> openDeviceOnSdl(h));
    }

    /** 请求关闭设备（只投递） */
    private static void requestClose(DeviceHandle h) {
        postSdl(() -> closeDeviceOnSdl(h));
    }

    /**
     * 请求采样（遍历所有已打开设备 + 力反馈）——<b>合并投递</b>：
     * 已有未处理的采样时直接返回，避免 SDL 线程卡住时队列堆积。
     */
    private static void requestSample() {
        if (!SAMPLE_PENDING.compareAndSet(false, true)) {
            return;
        }
        postSdl(() -> {
            SAMPLE_PENDING.set(false);
            sampleAllOnSdl();
        });
    }

    /**
     * 在 SDL 线程上采样全部已打开设备并下发力反馈（原来的 loop 第 3 段，逻辑未变）。
     * <p>只有本方法所在的线程碰 {@code h.controller} 与 SDL 接口。
     */
    private static void sampleAllOnSdl() {
        for (DeviceHandle h : POOL.values()) {
            GameInputController c = h.controller;
            if (c == null) {
                h.sample = Sample.EMPTY;
                continue;
            }
            try {
                // 共享设备：所有使用者的请求在此合成（逐通道取最强）
                h.channels = mergeChannels(h.channelsByUser.values());
                GameInputState state = c.getState();
                float[] axes = state.axes();
                // 绝对轴值 → 舵角（不做零点偏移；中位对齐靠打开设备时的满力回中）
                h.sample = new Sample(state.connected(),
                        state.steering(), state.throttle(), state.brake(),
                        axes == null ? new float[GameInputState.AXIS_COUNT] : axes.clone(),
                        state.buttons(), state.pov(),
                        state.keys() == null ? GameInputState.EMPTY_KEYS : state.keys());
                if (state.connected()) {
                    h.error = null;   // 恢复正常 → 清掉历史错误提示
                }
                applyForce(h, c);
            } catch (Throwable t) {
                // 采样失败也要可诊断（设备断开/驱动异常）：只在 error 为空时写，避免刷屏
                h.sample = Sample.EMPTY;
                if (h.error == null) {
                    h.error = "读取失败：" + shortError(String.valueOf(t.getMessage()));
                }
            }
            // 打开成功但 SDL 报告未连接（拔了/被独占）也要说明白
            if (h.controller != null && !h.sample.connected() && h.error == null) {
                h.error = "设备已打开，但 SDL 报告未连接（可能被其它程序独占或已拔出）";
            }
        }
    }

    private static void ensureWorker() {
        ensureSdlThread();
        Thread current = worker;
        if (current != null && current.isAlive()) {
            return;
        }
        synchronized (PeripheralHelmInput.class) {
            current = worker;
            if (current != null && current.isAlive()) {
                return;
            }
            Thread thread = new Thread(PeripheralHelmInput::loop, THREAD_NAME);
            thread.setDaemon(true);
            thread.start();
            worker = thread;
            ensureSdlThread();
        }
    }

    private static void loop() {
        while (true) {
            try {
                // -------- 1) 扫描（唯一入口：进入世界自动一次 + 用户点"刷新设备"） --------
                // ★ 扫描也必须【投递到 SDL 线程】执行：它内部要调 SDL 枚举设备
                //   （GameInputProvider.listDevices / backend.available）。
                //   实测事故：扫描留在池线程 ⇒ SDL 的 joystick 子系统与它的消息窗口在池线程
                //   初始化，而打开/采样在 SDL 线程 ⇒ SDL_OpenJoystick 报
                //   "IDirectInputDevice8::SetCooperativeLevel() DirectX error 0x80070006 (E_HANDLE)"。
                //   规则统一为：**任何**碰 SDL 的调用都只在 SDL 线程上发生。
                if (scanRequested) {
                    scanRequested = false;
                    scanning = true;
                    postSdl(() -> {
                        try {
                            doScanOnSdl();
                        } finally {
                            scanning = false;
                        }
                    });
                }

                // -------- 2) 池维护：只做判定与消息投递，【绝不直接调用 SDL】（防竞态） --------
                boolean anyOpen = false;
                long now = System.currentTimeMillis();
                for (DeviceHandle h : POOL.values()) {
                    boolean wanted = !h.users.isEmpty();   // 引用计数 > 0 才需要打开；归零即关闭 = 断开方向盘
                    if (wanted && h.controller == null) {
                        if (now - h.lastOpenAttempt >= OPEN_RETRY_MS) {
                            h.lastOpenAttempt = now;
                            requestOpen(h);
                        }
                        // SDL 线程迟迟没交出句柄 ⇒ native 侧卡住（设备被其它程序独占/驱动异常）。
                        // 池线程必须照常活着，把真实状态报出来，而不是陪着一起冻死。
                        if (h.error == null && h.openRequestedAt > 0
                                && now - h.openRequestedAt >= OPEN_TIMEOUT_MS) {
                            h.error = "打开超时（SDL 线程未响应：设备可能已被其它程序独占）";
                        }
                    } else if (!wanted && h.controller != null) {
                        requestClose(h);
                    }
                    if (h.controller != null) {
                        anyOpen = true;
                    }
                }

                // -------- 3) 采样 + 力反馈：投递给 SDL 线程（合并投递，SDK 卡住时不堆积） --------
                if (anyOpen) {
                    requestSample();
                }

                Thread.sleep(anyOpen ? POLL_INTERVAL_MS : IDLE_SLEEP_MS);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /**
     * 扫描一次（进入世界自动一次 + "刷新设备"触发）：SDL 枚举 → 更新池
     * （保留已打开的设备，移除已拔出的空闲设备）。
     *
     * <p>⚠ <b>只在 SDL 线程执行</b>：方法体内会调 {@code GameInputProvider.listDevices()} /
     * {@code backend.available()} —— 全是 SDL 调用。放到别的线程会让 SDL 子系统与它的
     * joystick 消息窗口在"错误的线程"上初始化，之后 {@code SDL_OpenJoystick} 就会以
     * {@code E_HANDLE} 失败（实测）。
     *
     * <p>池是 {@code ConcurrentHashMap}，池线程同时读写安全；{@code scanning} 是 volatile，
     * 界面照常能读进度。
     */
    private static void doScanOnSdl() {
        try {
            GameInputProvider.load();
            List<GameInputBackend> backends = GameInputProvider.backends();
            if (backends.isEmpty()) {
                backendStatus = "输入后端未注册（gameinput.toml#enableGameInput=false？）";
                return;
            }
            // ★ 多后端（用户："常见设备最好都上"）：游戏控制器走 SDL3，键盘走 MC 自带的 GLFW。
            //   只要【任一后端可用】就能扫描 —— 旧实现只看 backends.get(0)，SDL 一挂键盘也跟着没了。
            boolean anyAvailable = false;
            StringBuilder unavailable = new StringBuilder();
            for (GameInputBackend backend : backends) {
                if (backend.available()) {
                    anyAvailable = true;
                } else {
                    if (unavailable.length() > 0) {
                        unavailable.append(" / ");
                    }
                    unavailable.append(backend.prefix()).append('：')
                            .append(shortError(backend.lastError()));
                }
            }
            if (!anyAvailable) {
                backendStatus = "输入后端不可用：" + unavailable;
                return;
            }
            backendStatus = "就绪 · 后端 " + backends.size() + " 个";

            // ★ 扫描 = 【增量刷新】，绝不影响已绑定的设备（用户定稿："已绑定设备不会被扫描影响"）：
            //   ① 只新增条目 / 更新描述，**不关闭任何已打开的句柄**；
            //   ② 有使用者的条目一律保留（即使这次没枚举到 —— 设备可能只是暂时不可见，绑定还在）；
            //   ③ 只清理"没人在用 且 本次没枚举到"的条目；
            //   ④ 只有当某条目的 SDL instance id 真的变了（设备重连 ⇒ 旧句柄必然失效）才关掉重开。
            //   旧实现是"关闭全部 + 清空池 + 重建 + 重新打开"，重开一旦失败就会把正在用的设备彻底搞坏
            //   （实测：界面反复显示"设备句柄已失效，正在重新扫描…"）。
            List<GameInputDeviceInfo> found = GameInputProvider.listDevices();
            Set<String> seen = new HashSet<>();
            Map<String, Integer> slugCount = new HashMap<>();
            for (GameInputDeviceInfo info : found) {
                String stable = stableId(info, slugCount);
                seen.add(stable);
                DeviceHandle existing = POOL.get(stable);
                if (existing == null) {
                    POOL.put(stable, new DeviceHandle(stable, info));
                    continue;
                }
                boolean idChanged = existing.info != null
                        && !Objects.equals(existing.info.id(), info.id());
                existing.info = info;
                if (idChanged && existing.controller != null) {
                    // 设备重连后 instance id 变了 ⇒ 旧句柄已失效，投递关闭消息让 SDL 线程关掉，
                    // 池线程下一轮会用新 id 重开（本方法在池线程，绝不直接碰 SDL）
                    requestClose(existing);
                }
            }
            // 只清理"没人用 且 已不在"的条目
            List<DeviceHandle> dropped = new ArrayList<>();
            POOL.entrySet().removeIf(e -> {
                DeviceHandle h = e.getValue();
                if (h.users.isEmpty() && !seen.contains(e.getKey())) {
                    dropped.add(h);
                    return true;
                }
                return false;
            });
            for (DeviceHandle h : dropped) {
                requestClose(h);
            }
            // 待绑定补齐（只挂使用者，不触发扫描）
            for (Map.Entry<String, Set<String>> pending : PENDING_BIND.entrySet()) {
                DeviceHandle h = handleOf(pending.getKey());
                if (h != null) {
                    h.users.addAll(pending.getValue());
                }
            }
            PENDING_BIND.keySet().removeIf(id -> handleOf(id) != null);
            // 消息机制（无锁）：扫描完成 → 通知外设核心"设备表已更新"，由它在自己的周期里处理。
            // 刻意不用"核心去查 isScanning 状态"那种耦合写法。
            com.hdf.cryptand.neoforge.aeronautics.PeripheralCore.postMessage(
                    com.hdf.cryptand.neoforge.aeronautics.PeripheralCore::pruneStaleSessions);
        } catch (Throwable t) {
            backendStatus = "扫描失败：" + shortError(String.valueOf(t.getMessage()));
        }
    }

    /**
     * 打开池中设备（<b>只在 SDL 线程执行</b>；失败记录 error 并按 {@link #OPEN_RETRY_MS} 重试）。
     *
     * <p>⚠ 刻意**不用** {@code GameInputManager}：它的 {@code shutdown()} 是<b>终态</b>
     * （{@code InputLifecycle} 在 {@code ServerStoppingEvent} 调用 —— 退出世界回主菜单就会触发），
     * 之后 {@code acquire()} 永久抛 "GameInputManager 已 shutdown，不能继续申请" ⇒ 重进世界后
     * 设备再也打不开（实测"还是连不上"的根因）。设备池自管句柄：open/close 成对，退出世界由
     * {@code PeripheralHelmGameEvents} 清绑定后由本线程关闭。
     */
    private static void openDeviceOnSdl(DeviceHandle h) {
        try {
            GameInputController c = GameInputProvider.open(h.info);
            if (c == null) {
                h.error = "设备未打开（后端未返回句柄）";
                return;
            }
            h.error = null;
            h.openRequestedAt = 0L;      // 句柄已到手 → 池线程不再判定"打开超时"
            h.controller = c;
            h.appliedForce = -1;
            h.appliedChannels.clear();   // 新会话：清掉上会话的下发记录，避免"值没变就不下发"
            h.ffbSupported = null;       // 重新探测力反馈能力
            // 设备刚打开：先下满力回中，把物理方向盘拉到 0 度位（轴值回中 → 舵角自然是 0）
            scheduleRecenterForce(h, RECENTER_FORCE_MS);
        } catch (Throwable t) {
            h.controller = null;
            String message = String.valueOf(t.getMessage());
            // 真实原因必须可见：以前这里只留一句笼统提示，导致"到底为什么打不开"无从查起
            LOGGER.warn("[PeripheralHelm] 打开设备失败 [{} / {}]: {}", h.stableId,
                    h.info == null ? "?" : h.info.id(), message);
            // 句柄失效（SDL 子系统重启导致 instance id 重排 / 设备被拔插）→ 触发重扫，
            // 下一轮用刷新后的 id 重试；重扫会重建池但稳定 id 不变，绑定会自动恢复。
            // ⚠ 但必须有冷却：打开一直失败时，"失败→全池重扫→再失败"会变成无限循环。
            // ⚠ 绝不自动重扫（用户定稿：扫描必须手动）：以前这里会设 scanRequested，
            //   在"打开一直失败"时形成"失败→全池重扫→再失败"的无限循环。
            //   现在只把真实原因显示出来 + 提示用户点【刷新设备】。
            h.error = shortError(message) + "（可点【刷新设备】重新枚举后再连接）";
        }
    }

    /**
     * 关闭设备句柄，并把<b>会话级状态全部归零</b>。
     * <p>⚠ 必须清干净：否则"断开 → 连别的设备 → 再连回来"时，{@code appliedChannels} 里
     * 还留着上次下发的通道值，{@code applyChannel} 会认为"值没变、无需下发" ⇒ <b>力反馈整个失效</b>
     * （用户实测："连接到其他设备连回来会出现，是不是没有断开干净"）；
     * {@code ffbSupported} 也会错误沿用上一个设备的探测结论。
     */
    private static void closeDeviceOnSdl(DeviceHandle h) {
        GameInputController c = h.controller;
        h.controller = null;
        h.sample = Sample.EMPTY;
        h.appliedForce = -1;
        h.appliedChannels.clear();
        h.ffbSupported = null;          // 重新探测（不同设备力反馈能力不同）
        h.selfTestUntil = 0L;
        h.recenterForceUntil = 0L;
        h.suppressForceUntil = 0L;
        h.stopRequested = false;
        h.recenterLastPos = 0f;
        h.recenterDir = 0;
        h.recenterArrivedAt = 0L;
        h.forceRequest = 0;
        if (c == null) {
            return;
        }
        try {
            if (c.hasForceFeedback()) {
                c.stopForce();
            }
        } catch (Throwable ignored) {
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** 力反馈下发（后台线程；节流） */
    private static void applyForce(DeviceHandle h, GameInputController c) {
        try {
            if (h.ffbSupported == null) {
                // 懒探测一次（结果同时给界面显示：就绪 / 不支持）
                h.ffbSupported = c.hasForceFeedback();
            }
            if (!h.ffbSupported) {
                return;
            }
            long now = System.currentTimeMillis();
            // 界面【停止力反馈】：先真正停掉设备上的一切力（含失去追踪的残留效果），再进入抑制期
            if (h.stopRequested) {
                h.stopRequested = false;
                c.stopForce();
                h.appliedChannels.clear();
                h.appliedForce = -1;
            }
            if (now < h.suppressForceUntil) {
                return;   // 抑制期内一律不下发（否则下一帧又被自动力覆盖）
            }
            // 自检/测试优先（用户定稿："力反馈测试改成在当前角度基础上快速左右抖动 5s"）：
            //   按固定半周期交替下发正/负恒力 ⇒ 方向盘在【当前角度附近】快速左右摇摆，
            //   比原来的恒定单向力更容易看出力反馈到底通没通。
            if (now < h.selfTestUntil) {
                boolean positive = ((now / SELF_TEST_SWING_MS) & 1L) == 0L;
                int level = positive ? h.selfTestStrength : -h.selfTestStrength;
                if (h.appliedForce != level) {
                    c.setForce(ForceParams.constant(level));
                    h.appliedForce = level;
                    h.appliedForceAt = now;
                }
                return;
            }
            // 回中窗口次优先：用【恒定力闭环】把物理方向盘推到绝对 0 位（轴值 0 = 中心）。
            //   ⚠ 刻意不用 SPRING 条件效果：部分设备的 center=0 语义与规范不符（会推向一侧而不是拉向中位），
            //     一旦方向相反就变成正反馈、把方向盘顶在极限上。恒定力的方向完全由我们算，并带自适应。
            if (now < h.recenterForceUntil) {
                float pos = h.sample.axis(h.recenterAxis);
                if (h.recenterInvert) {
                    pos = -pos;
                }
                float abs = Math.abs(pos);
                int level;
                if (abs <= RECENTER_ARRIVED) {
                    // ② 已到 0：保持 1 秒确认稳定，再退出回中（用户："保持 0 度静止 1s 左右退出回正"）
                    if (h.recenterArrivedAt == 0L) {
                        h.recenterArrivedAt = now;
                    } else if (now - h.recenterArrivedAt >= RECENTER_HOLD_MS) {
                        h.recenterForceUntil = 0L;
                        h.recenterArrivedAt = 0L;
                        h.recenterDir = 0;
                        h.appliedForce = -1;
                        return;
                    }
                    // 维持用 ∝ 偏差 + 阻尼的锁位力：满力在这个区间只会把方向盘来回撞
                    float holdDelta = pos - h.recenterLastPos;
                    h.recenterLastPos = pos;
                    level = (int) Math.max(-RECENTER_MAX_FORCE, Math.min(RECENTER_MAX_FORCE,
                            -pos * RECENTER_MAX_FORCE - holdDelta * RECENTER_DAMPING));
                } else {
                    // ① 未到 0：**固定最大力**朝 0 推（等速回中；力不随偏差衰减，有阻力的盘也推得动）
                    h.recenterArrivedAt = 0L;
                    h.recenterLastPos = pos;
                    if (abs > RECENTER_HYSTERESIS || h.recenterDir == 0) {
                        h.recenterDir = pos > 0f ? -1 : 1;
                    }
                    level = h.recenterDir * RECENTER_MAX_FORCE;
                }
                // 回中期间其它通道静音（各下一次 0 值即可）
                applyChannel(h, c, ForceEffect.SPRING, 0, now, v -> ForceParams.spring(0, v));
                applyChannel(h, c, ForceEffect.DAMPER, 0, now, ForceParams::damper);
                applyChannel(h, c, ForceEffect.SINE, 0, now, v -> ForceParams.sine(0, 60));
                applyChannel(h, c, ForceEffect.INERTIA, 0, now, ForceParams::inertia);
                applyChannel(h, c, ForceEffect.CONSTANT, level, now, ForceParams::constant);
                h.appliedForceAt = now;
                return;
            }
            if (h.appliedForce != 0) {
                // 自检/回中刚结束：清掉那些力，恢复正常合成（下一次 applyChannel 会覆盖成当前值）
                h.appliedForce = 0;
                h.appliedForceAt = 0L;
                h.appliedChannels.clear();
            }
            ForceChannels requested = h.channels;
            if (requested == ForceChannels.NONE && h.forceRequest > 0) {
                // 兼容旧单通道 API：映射到阻尼
                requested = new ForceChannels(0, 0, h.forceRequest, 0, 60, 0);
            }
            final ForceChannels want = requested;
            // ---- 多通道合成：每类效果一个实例，各自独立节流 ----
            applyChannel(h, c, ForceEffect.SPRING, want.spring(), now,
                    v -> ForceParams.spring(0, v));
            applyChannel(h, c, ForceEffect.DAMPER, want.damper(), now,
                    ForceParams::damper);
            applyChannel(h, c, ForceEffect.CONSTANT, want.constant(), now,
                    ForceParams::constant);
            applyChannel(h, c, ForceEffect.SINE, want.sineMagnitude(), now,
                    v -> ForceParams.sine(v, want.sinePeriodMs()));
            // 垂向冲击（颠簸/落地/上下坡）→ 惯性力通道
            applyChannel(h, c, ForceEffect.INERTIA, want.inertia(), now,
                    ForceParams::inertia);
            h.appliedForceAt = now;
        } catch (Throwable t) {
            h.ffbSupported = false;
            if (h.error == null) {
                h.error = "力反馈下发失败：" + shortError(String.valueOf(t.getMessage()));
            }
        }
    }

    /**
     * 计算稳定设备 id：名称 slug（+ 同名序号）。SDL3 的 instance id 会随重连/重启变化，
     * 绑定必须存稳定 id；打开设备时再用池里那份"当前 instance id"的 {@link GameInputDeviceInfo}。
     */
    private static String stableId(GameInputDeviceInfo info, Map<String, Integer> slugCount) {
        String name = info.name() == null ? "device" : info.name();
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (slug.isEmpty()) {
            slug = "device";
        }
        int n = slugCount.merge(slug, 1, Integer::sum);
        return "sdl:" + slug + (n > 1 ? ("#" + n) : "");
    }

    /**
     * 单通道下发（独立死区/节流）：强度为 0 时下发"零强度同类型效果"而不是 stopForce()
     * —— 因为 {@code GameInputController} 只有"停全部"的 stopForce()，零强度既能静音该通道，
     * 又不影响其它通道（弹簧/阻尼/振动可同时存在）。
     */
    private static void applyChannel(DeviceHandle h, GameInputController c,
                                     com.hdf.cryptand.gameinput.ForceEffect effect,
                                     int want, long now,
                                     java.util.function.IntFunction<ForceParams> factory) {
        int applied = h.appliedChannels.getOrDefault(effect, 0);
        boolean changed = Math.abs(want - applied) >= FORCE_DEADBAND
                || (want == 0) != (applied == 0);
        if (!changed) {
            return;
        }
        try {
            c.setForce(factory.apply(want));
            h.appliedChannels.put(effect, want);
        } catch (Throwable ignored) {
        }
    }

    private static String shortError(String message) {
        if (message == null || message.isBlank()) {
            return "未知原因";
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    /** 采样组 id（GameInputManager 记账用） */
    public static final String GROUP = "cryptand-peripheral-helm";
}
