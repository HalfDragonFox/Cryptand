/**
 * ===== 外设栏杆（按钮）· 客户端输入（2026-09-13） =====
 *
 * 在客户端异步核心（{@link com.hdf.cryptand.neoforge.aeronautics.PeripheralCore}）里执行：
 * 采样外部设备 → 按绑定表匹配按键 → 得到输出信号（0..15）→ 写本地 BE + 排队上行。
 * 一切与设备池的交互都是无锁消息/不可变快照（见 PeripheralCore 的说明）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionClient;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionPayload;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PeripheralRailingInput {

    /** 已登记到设备池的 deviceId（bind 只在变化时调用，避免每周期重复登记） */
    private static final Map<Long, String> BOUND = new ConcurrentHashMap<>();

    private PeripheralRailingInput() {
    }

    /** 使用者的 key（与外设船舵/拉杆区分开） */
    public static String userKey(BlockPos pos) {
        return "railing:" + pos.asLong();
    }

    /** 核心线程：一个计算周期 */
    public static void tickClient(PeripheralRailingBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || be.getLevel() == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        String key = userKey(pos);
        PeripheralRailingBinding binding = be.getBinding();

        if (!binding.bound()
                || (be.owner() != null && !be.owner().equals(mc.player.getUUID()))) {
            if (BOUND.remove(pos.asLong()) != null) {
                PeripheralHelmInput.releaseUser(key);
            }
            be.setClientDeviceConnected(false);
            return;
        }

        // ★ 设备 id 未变 ≠ 引用还在池里：退出世界时设备池清空全部使用者，而本表是静态的。
        //   只比对 id 会让重进世界后永远不再 bind（sample 恒为 EMPTY ⇒ 输出彻底失效）。
        if (!binding.deviceId().equals(BOUND.get(pos.asLong()))
                || !PeripheralHelmInput.isBound(binding.deviceId(), key)) {
            BOUND.put(pos.asLong(), binding.deviceId());
            PeripheralHelmInput.bind(binding.deviceId(), key);
        }

        // 强制设备类型（0 = 自动 → null = 按后端判定）
        PeripheralHelmInput.setForcedKind(binding.deviceId(), key,
                com.hdf.cryptand.neoforge.aeronautics.PeripheralForceKinds.kindOf(binding.forceKind()));
        PeripheralHelmInput.Sample sample = PeripheralHelmInput.sample(binding.deviceId(), key);
        be.setClientDeviceConnected(sample.connected());
        int signal = binding.signalOf(sample);

        boolean changed = signal != be.getState();
        if (changed) {
            be.setSignalFromDevice(signal);      // 本地立即响应（含变化检测，不刷音效）
        }
        // 信号变化 ⇒ 立刻上行（不必等最长一个发送间隔）
        PeripheralSessionClient.tickSend(pos, PeripheralSessionPayload.KIND_LEVER, signal,
                binding.bound(), changed);
    }

    /** 方块被移除 / 卸载 */
    public static void forget(BlockPos pos) {
        if (pos != null) {
            BOUND.remove(pos.asLong());
        }
    }

    /** 退出世界：整表清空 */
    public static void reset() {
        BOUND.clear();
    }
}
