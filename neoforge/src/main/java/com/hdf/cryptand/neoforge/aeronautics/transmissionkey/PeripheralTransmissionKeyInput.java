/**
 * ===== 外设模拟传动器（按键）· 客户端输入（2026-09-16）=====
 *
 * 在客户端异步核心（{@link com.hdf.cryptand.neoforge.aeronautics.PeripheralCore}）里执行两条通道：
 *
 * <pre>
 *   ① 轴通道（沿用）：采样设备轴 → 输入范围归一化 → 曲线 → 输出比例
 *   ② 按键通道（新增）：读按键 → 命中某点绑定的键 ⇒ "激活该点" ⇒
 *      按 (Δ值 ÷ 沿途时间之和) 算出每周期推进量 → 每周期推进滑块 → 滑块位置即输出比例
 * </pre>
 *
 * <h3>用户定义的运行语义（2026-09-16 两轮澄清后定稿）</h3>
 * <pre>
 *   点 = (曲线位置 x, 值 y, 时间 ms)，其中 **y 就是输出百分比**（0..1）。
 *   例：点1=(x0, 0值, 3s)、点2=(x1, 50值, 5s)、点3=(x2, 100值, 3s)
 *   · 滑块当前停在点1，按下"点2的键" ⇒ 滑块用 5s 从 0 走到 50（速度 = Δ值 ÷ 5s）；
 *   · 中途"点1的键"又按下 ⇒ **立即重算**，以点1的时间(3s)为准退回（速度可正可负）；
 *   · 从点1 直接激活点3 ⇒ 沿途时间相加 = 5s + 3s = 8s ⇒ 用 8s 走完 0→100。
 *   · 每到一次目标就停住，等待下一次按键。
 * </pre>
 *
 * **滑块进度 = 输出百分比 = 同一个值**（用户明确）：该值写进 BE 的 {@code targetRatio}
 * （即方块缓存），UI 只读这个进度值渲染滑块 —— 不额外维护第二套状态，也无需新增网络包
 * （上行继续走既有的比例通道 {@link PeripheralSessionClient}）。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmissionkey;

import com.hdf.cryptand.algorithm.curve.CurvePoint;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionClient;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionPayload;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PeripheralTransmissionKeyInput {

    /** 已登记到设备池的 deviceId（bind 只在变化时调用） */
    private static final Map<Long, String> BOUND = new ConcurrentHashMap<>();

    /** 滑块推进状态（按方块位置），运行期临时值 */
    private static final Map<Long, Slider> SLIDERS = new ConcurrentHashMap<>();

    /** MC 一个游戏刻的毫秒数（20 tps）—— 把"毫秒数"换算成推进周期数 */
    private static final double MS_PER_TICK = 50.0;

    private PeripheralTransmissionKeyInput() {
    }

    /** 使用者 key（与船舵/拉杆/栏杆区分开） */
    public static String userKey(BlockPos pos) {
        return "transmission:" + pos.asLong();
    }

    public static void forget(BlockPos pos) {
        if (pos != null) {
            BOUND.remove(pos.asLong());
            SLIDERS.remove(pos.asLong());
        }
    }

    /** 退出世界：整表清空 */
    public static void reset() {
        BOUND.clear();
        SLIDERS.clear();
    }

    // ==================== 供界面读取的滑块视图 ====================
    // 用户口径：滑块进度就是"输入输出映射的百分比值"，与 BE 的 ratio 同源。
    // 界面**首选**直接读方块缓存（be.getTargetRatio()），下列方法作为没有 BE 引用时的兜底。

    /** 滑块当前进度 0..1（= 输出百分比；无状态 ⇒ 0） */
    public static double sliderProgress(BlockPos pos) {
        final Slider s = pos == null ? null : SLIDERS.get(pos.asLong());
        return s == null ? 0.0 : s.progress;
    }

    /** 滑块是否正在推进（界面提示"运行中"用） */
    public static boolean sliderRunning(BlockPos pos) {
        final Slider s = pos == null ? null : SLIDERS.get(pos.asLong());
        return s != null && s.running();
    }

    /** 当前目标点索引（-1 = 无目标） */
    public static int sliderTarget(BlockPos pos) {
        final Slider s = pos == null ? null : SLIDERS.get(pos.asLong());
        return s == null ? -1 : s.targetPoint;
    }

    /** 界面手动把滑块拖到位（编辑时预览；不参与推进） */
    public static void setSliderProgress(BlockPos pos, double value) {
        if (pos == null) {
            return;
        }
        final Slider s = SLIDERS.computeIfAbsent(pos.asLong(), k -> new Slider());
        s.progress = Math.max(0.0, Math.min(1.0, value));
        s.ticksLeft = 0;
        s.targetPoint = -1;
    }

    // ==================== 核心线程：一个计算周期 ====================

    public static void tickClient(PeripheralTransmissionKeyBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || be.getLevel() == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        String key = userKey(pos);
        PeripheralTransmissionKeyBinding binding = be.getBinding();

        // 未绑定 / 非归属玩家 → 释放引用（引用计数 -1）
        if (!binding.bound()
                || (be.owner() != null && !be.owner().equals(mc.player.getUUID()))) {
            if (BOUND.remove(pos.asLong()) != null) {
                PeripheralHelmInput.releaseUser(key);
            }
            SLIDERS.remove(pos.asLong());
            be.setClientSample(false, 0f, 0f);
            return;
        }

        // 设备 id 未变 ≠ 引用还在池里（退出世界时池会清空）⇒ 必须用池里的真实引用复核
        if (!binding.deviceId().equals(BOUND.get(pos.asLong()))
                || !PeripheralHelmInput.isBound(binding.deviceId(), key)) {
            BOUND.put(pos.asLong(), binding.deviceId());
            PeripheralHelmInput.bind(binding.deviceId(), key);
        }
        PeripheralHelmInput.setForcedKind(binding.deviceId(), key,
                com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds.kindOf(binding.forceKind()));

        PeripheralHelmInput.Sample sample = PeripheralHelmInput.sample(binding.deviceId(), key);
        boolean connected = sample.connected();
        float raw = connected ? sample.axis(binding.axisIndex()) : 0f;

        // ---------- ② 按键通道：命中 ⇒ 激活该点；随后推进滑块 ----------
        final Slider slider = SLIDERS.computeIfAbsent(pos.asLong(), k -> new Slider());
        final long nowButtons = connected ? sample.buttons() : 0L;
        final long newlyPressed = nowButtons & ~slider.lastButtons;   // 边沿：本次新按下
        slider.lastButtons = nowButtons;

        if (newlyPressed != 0L) {
            final int hit = matchPoint(binding, newlyPressed);
            if (hit >= 0) {
                slider.activate(binding, hit);
            }
        }
        slider.advance();

        // ---------- 输出比例 ----------
        // 按键通道"启动过" ⇒ 滑块进度本身就是输出百分比；否则退回轴通道（手动摇杆/踏板）
        float ratio;
        if (binding.hasAnyKey() && slider.everActivated) {
            ratio = (float) slider.progress;
        } else {
            ratio = (float) binding.ratioOf(raw);   // ★ 求值链：轴 → 归一化 → 曲线 → 比例
        }
        be.setClientSample(connected, raw, ratio);

        // 上行：比例真的变化时立刻发（红石/传动跟手的关键，逻辑在 SessionClient 里）
        boolean changed = Math.abs(ratio - be.getTargetRatio()) > 1.0E-4f;
        PeripheralSessionClient.tickSend(pos, PeripheralSessionPayload.KIND_LEVER,
                ratio, true, changed);
    }

    /** 找到第一个"绑定的键位与本次新按下有交集"的点；没有返回 -1（掩码为 long，避免高位键被截断） */
    private static int matchPoint(PeripheralTransmissionKeyBinding binding, long newlyPressed) {
        final int n = binding.pointCount();
        for (int i = 0; i < n; i++) {
            final PeripheralTransmissionKeyBinding.PointKey pk = binding.keyAt(i);
            if (pk.hasKey() && (newlyPressed & pk.keyMask()) != 0L) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 滑块推进状态机 ====================

    /**
     * 一个方块的滑块状态（运行期临时值，不落盘）。
     *
     * <p>{@link #progress} 既是滑块位置，也是输出百分比 —— 用户口径的"同一个值"。</p>
     */
    private static final class Slider {

        /** 当前进度 0..1（= 输出百分比） */
        double progress = 0.0;
        /** 目标点索引（-1 = 没有目标） */
        int targetPoint = -1;
        /** 每周期推进量（带符号：负 = 退回） */
        double stepPerTick = 0.0;
        /** 剩余推进周期数 */
        int ticksLeft = 0;
        /** 上一周期按键位（边沿检测用；long 与 gameinput 掩码对齐） */
        long lastButtons = 0L;
        /** 是否曾被按键激活过（决定用按键通道还是轴通道） */
        boolean everActivated = false;

        boolean running() {
            return ticksLeft > 0;
        }

        /**
         * 激活第 index 个点：**先算速度再出发**。
         *
         * <p>速度 = (目标值 y − 当前进度) ÷ 沿途时间之和；沿途时间按点序列累加
         * （跨多点即相加），时间取该点设置的 ms（未设置的用默认值）。
         * 中途再次激活 ⇒ 覆盖目标与速度 ⇒ 天然实现"退回时重新计算"。</p>
         */
        void activate(PeripheralTransmissionKeyBinding binding, int index) {
            final List<CurvePoint> pts = binding.curve().points();
            if (pts.isEmpty()) {
                return;
            }
            final int n = pts.size();
            final int target = Math.max(0, Math.min(index, n - 1));
            final double from = progress;
            final double to = pts.get(target).y();      // ★ 目标是"点的值"，不是曲线位置

            final int fromSeg = segmentOf(pts, from);
            int totalMs = 0;
            if (target > fromSeg) {
                for (int i = fromSeg + 1; i <= target; i++) {
                    totalMs += binding.keyAt(i).effectiveTimeMs();
                }
            } else if (target < fromSeg) {
                for (int i = target + 1; i <= fromSeg; i++) {
                    totalMs += binding.keyAt(i).effectiveTimeMs();
                }
            } else {
                totalMs = binding.keyAt(target).effectiveTimeMs();
            }
            if (totalMs <= 0) {
                totalMs = PeripheralTransmissionKeyBinding.DEFAULT_TIME_MS;
            }

            final int ticks = Math.max(1, (int) Math.round(totalMs / MS_PER_TICK));
            this.stepPerTick = (to - from) / ticks;
            this.ticksLeft = ticks;
            this.targetPoint = target;
            this.everActivated = true;
        }

        /** 推进一个周期（到达目标即停住） */
        void advance() {
            if (ticksLeft <= 0) {
                return;
            }
            progress += stepPerTick;
            ticksLeft--;
            if (ticksLeft <= 0) {
                progress = Math.max(0.0, Math.min(1.0, progress));
                targetPoint = -1;
            }
        }

        /** 当前进度落在哪一段（按点的"值 y"比较：最后一个 y ≤ progress 的点下标） */
        private static int segmentOf(List<CurvePoint> pts, double progress) {
            for (int i = pts.size() - 1; i >= 0; i--) {
                if (progress >= pts.get(i).y() - 1.0E-9) {
                    return i;
                }
            }
            return 0;
        }
    }
}
