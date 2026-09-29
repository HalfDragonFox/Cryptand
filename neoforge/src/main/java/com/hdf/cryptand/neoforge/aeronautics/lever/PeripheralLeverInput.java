/**
 * ===== 外设拉杆 · 客户端输入（2026-09-13） =====
 *
 * 复用【外设船舵的客户端全局设备池】（{@link com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput}）：
 * 设备共享 + 引用计数（同一套方向盘/踏板可被船舵与拉杆同时使用，最后一个使用者释放才关闭设备）。
 *
 * 交互（2026-09-13 用户定稿）：<b>取消官方手抓</b>，右键直接打开绑定界面（与外设船舵一致）；
 * 档位完全由外部设备驱动。
 *
 * 输入模式（用户定稿）：
 * <ul>
 *   <li><b>自动</b>：轴给基础档位，按键可在其之上加减（设备只有按钮时也能用）；</li>
 *   <li><b>仅轴</b>：只看踏板轴；</li>
 *   <li><b>仅按键</b>：只看"加档/减档"两个按钮（边沿触发，点动式）。</li>
 * </ul>
 * 轴 → 档位是线性映射（{@link PeripheralLeverBinding#stateOfAxis}），档位变化才发包。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PeripheralLeverInput {

    /** 每个拉杆的客户端状态（按键边沿 + 累积档位） */
    private static final Map<Long, ClientState> STATES = new ConcurrentHashMap<>();

    private PeripheralLeverInput() {
    }

    /** 使用者的 key（与外设船舵的区别开：方向盘用 block:，拉杆用 lever:） */
    public static String userKey(BlockPos pos) {
        return "lever:" + pos.asLong();
    }

    /** 方块被移除/区块卸载时调用：忘掉它的客户端状态（按钮边沿、已登记设备）。 */
    public static void forget(BlockPos pos) {
        if (pos != null) {
            STATES.remove(pos.asLong());
        }
    }

    /** 退出世界：整表清空（新世界的方块坐标可能复用旧状态 ⇒ 档位凭空跳变）。 */
    public static void reset() {
        STATES.clear();
    }

    /** 客户端每 tick：采样设备 → 算档位 → 变化才写本地 BE 并发包 */
    public static void tickClient(PeripheralLeverBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || be.getLevel() == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        String key = userKey(pos);
        PeripheralLeverBinding binding = be.getBinding();

        // 未绑定设备 / 不是归属玩家 → 释放引用（引用计数 -1；最后一个使用者释放后设备会被关闭）
        if (!binding.bound()
                || (be.owner() != null && !be.owner().equals(mc.player.getUUID()))) {
            PeripheralHelmInput.releaseUser(key);
            STATES.remove(pos.asLong());
            return;
        }
        ClientState st = STATES.computeIfAbsent(pos.asLong(), k -> new ClientState());
        // ⚠ 只在设备变化时登记（bind 会被本方法每 tick 调用；每 tick 调用会放大设备池的抖动）。
        // ★ 但"设备 id 没变"不等于"引用还在池里"：退出世界时设备池会清空全部使用者
        //   （PeripheralHelmInput.shutdown），而本类的 STATES 是静态的、会跨世界残留。
        //   只比对 id 就会得出"已登记过"的结论 ⇒ 永远不再 bind ⇒ sample 返回 EMPTY
        //   ⇒ **重进世界后输出彻底失效**（用户实测）。所以必须用池里的真实引用复核。
        if (!binding.deviceId().equals(st.boundDevice)
                || !PeripheralHelmInput.isBound(binding.deviceId(), key)) {
            st.boundDevice = binding.deviceId();
            PeripheralHelmInput.bind(binding.deviceId(), key);
        }
        // 强制设备类型（0 = 自动 → null = 按后端判定）
        PeripheralHelmInput.setForcedKind(binding.deviceId(), key,
                com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds.kindOf(binding.forceKind()));
        PeripheralHelmInput.Sample sample = PeripheralHelmInput.sample(binding.deviceId(), key);

        int state = st.buttonState;
        if (binding.axisEnabled()) {
            state = binding.stateOfAxis(sample.axis(binding.axisIndex()));
            st.buttonState = state;
        }
        if (binding.buttonEnabled()) {
            boolean up = sample.button(binding.buttonUp());
            boolean down = sample.button(binding.buttonDown());
            if (up && !st.prevUp) {
                state = Mth.clamp(state + 1, 0, 15);
            }
            if (down && !st.prevDown) {
                state = Mth.clamp(state - 1, 0, 15);
            }
            st.prevUp = up;
            st.prevDown = down;
            st.buttonState = state;
        }

        boolean changed = state != be.getState();
        if (changed) {
            be.setSignalFromDevice(state);            // 本地立即响应（无延迟）
        }
        // ★ 会话式上行（用户定稿）：按配置频率定时发；档位真的变化时【立刻发】——
        //   这是服务端红石跟手的直接来源（不必再等最长一个发送间隔）。
        com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionClient.tickSend(
                pos, com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionPayload.KIND_LEVER,
                state, binding.bound(), changed);
    }

    private static final class ClientState {
        int buttonState;
        boolean prevUp;
        boolean prevDown;
        /** 已登记到设备池的 deviceId（bind 只在此值变化时调用） */
        String boundDevice = "";
    }
}
