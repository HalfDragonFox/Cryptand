/**
 * ===== 外设模拟传动器 · 客户端输入（2026-09-14）=====
 *
 * 在客户端异步核心（{@link com.hdf.cryptand.neoforge.aeronautics.PeripheralCore}）里执行：
 * 采样设备轴 → 输入范围归一化 → 曲线 → 输出比例（0..1）→ 写本地 BE + 排队上行。
 * 复用外设全局设备池（{@link com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput}）。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionClient;
import com.hdf.cryptand.neoforge.aeronautics.PeripheralSessionPayload;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PeripheralTransmissionInput {

    /** 已登记到设备池的 deviceId（bind 只在变化时调用） */
    private static final Map<Long, String> BOUND = new ConcurrentHashMap<>();

    private PeripheralTransmissionInput() {
    }

    /** 使用者 key（与船舵/拉杆/栏杆区分开） */
    public static String userKey(BlockPos pos) {
        return "transmission:" + pos.asLong();
    }

    public static void forget(BlockPos pos) {
        if (pos != null) {
            BOUND.remove(pos.asLong());
        }
    }

    /** 退出世界：整表清空 */
    public static void reset() {
        BOUND.clear();
    }

    /** 核心线程：一个计算周期 */
    public static void tickClient(PeripheralTransmissionBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || be.getLevel() == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        String key = userKey(pos);
        PeripheralTransmissionBinding binding = be.getBinding();

        // 未绑定 / 非归属玩家 → 释放引用（引用计数 -1）
        if (!binding.bound()
                || (be.owner() != null && !be.owner().equals(mc.player.getUUID()))) {
            if (BOUND.remove(pos.asLong()) != null) {
                PeripheralHelmInput.releaseUser(key);
            }
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
        // ★ 求值链：轴原始值 → 输入最大/最小归一化 → 曲线 → 输出比例 0..1（0%..100%）
        float ratio = (float) binding.ratioOf(raw);
        be.setClientSample(connected, raw, ratio);

        // 上行：比例真的变化时立刻发（红石/传动跟手的关键，逻辑在 SessionClient 里）
        boolean changed = Math.abs(ratio - be.getTargetRatio()) > 1.0E-4f;
        PeripheralSessionClient.tickSend(pos, PeripheralSessionPayload.KIND_LEVER,
                ratio, true, changed);
    }
}
