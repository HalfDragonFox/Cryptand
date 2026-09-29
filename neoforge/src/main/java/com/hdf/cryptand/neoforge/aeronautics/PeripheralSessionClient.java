/**
 * ===== 外设会话 · 客户端上行（主动端，2026-09-13）=====
 *
 * 由 {@link PeripheralCore} 在核心线程里调用：把"当前数据或空包"排进核心的<b>统一出站队列</b>
 * （{@link PeripheralCore#postSend}）—— 与界面连接/断开/保存共用同一条上行通道，
 * 出站只有一处可管理（用户："每次UI更新数据到服务器也是走这个异步核心，方便管理"）。
 *
 * <p><b>自适应上行速率</b>（2026-09-14 用户定稿）："按照每方块来设置，然后添加普通速率，
 * 比如 20hz 下，正常按照 4hz 传输一次，然后一旦数据有更新就立马加速到最快（如果更新很快的话）
 * 就按照 20tick，保证一有数据立马传输，保证实时性。"
 * <pre>
 *   数据变化            ⇒ 【立刻发一包】，不等任何间隔（实时性的直接来源）
 *   变化后 burstHoldMs  ⇒ 加速档：按 peripheralClientSendHz（默认 20Hz = 每 tick）发
 *   连续快速变化        ⇒ 每次都续期加速窗口 ⇒ 一直满速，不会掉回普通速率
 *   稳态（无变化）      ⇒ 普通速率：peripheralClientIdleHz（默认 2Hz，可配，1 秒 2 次）
 *   普通速率 = 0        ⇒ 纯事件驱动：稳态一个包都不发，只在参数更新时上传
 * </pre>
 *
 * <p>每个方块<b>各自独立</b>计时与本地方案；服务端的限流同样按"每方块"独立配额，两边语义对齐。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PeripheralSessionClient {

    /** 每方块的上行节奏（只在核心线程访问；pruneExcept/forget 会整条移除） */
    private static final class Rhythm {
        /** 上次真正把包排进出站队列的时刻（ms） */
        long lastSentAt;
        /** 加速窗口截止时刻（ms）：窗口内走最高速率；有变化就续期 */
        long burstUntil;
        /** 会话序号 */
        int seq;
    }

    private static final Map<Long, Rhythm> SEND = new ConcurrentHashMap<>();

    private PeripheralSessionClient() {
    }

    /**
     * 核心线程：按自适应速率把上行包投进核心出站队列。
     *
     * @param hasData false ⇒ 发空包（{@code KIND_PING}）保活会话窗口
     * @param changed 本周期值是否真的变了 —— true 时<b>立刻发</b>并开启/续期加速窗口
     */
    public static void tickSend(BlockPos pos, int kind, float value, boolean hasData,
                               boolean changed) {
        int maxHz = Math.max(1, ConfigAero.peripheralClientSendHz());
        // 普通速率允许为 0（0 = 纯事件驱动：只在参数更新时上传）
        int idleHz = Math.max(0, Math.min(ConfigAero.peripheralClientIdleHz(), maxHz));
        long burstHoldMs = Math.max(0L, ConfigAero.peripheralClientBurstHoldMs());
        long now = System.currentTimeMillis();

        Rhythm r = SEND.computeIfAbsent(pos.asLong(), key -> new Rhythm());

        if (changed) {
            // ★ 有更新：续期加速窗口 + 立刻发，保证"一有数据立马传输"
            r.burstUntil = now + burstHoldMs;
            send(r, pos, kind, value, hasData, now);
            return;
        }

        if (idleHz <= 0) {
            // ★ 普通速率 0 = 纯事件驱动：参数没更新就一个包都不发
            //   （连加速窗口也不用发 —— 窗口期内本来就没有新的更新）
            return;
        }
        // 加速窗口内走最高速率（连续变化时等于每 tick 一包）；否则回落到普通速率保活
        int hz = now < r.burstUntil ? maxHz : idleHz;
        long intervalMs = Math.max(1L, 1000L / hz);
        if (now - r.lastSentAt < intervalMs) {
            return;   // 还没到发送时刻
        }
        send(r, pos, kind, value, hasData, now);
    }

    /** 真正排一包进核心出站队列（网络包只能在主线程发，故经核心统一投递）。 */
    private static void send(Rhythm r, BlockPos pos, int kind, float value,
                             boolean hasData, long now) {
        r.lastSentAt = now;
        int seq = ++r.seq;
        int outKind = hasData ? kind : PeripheralSessionPayload.KIND_PING;
        float outValue = hasData ? value : 0f;
        PeripheralCore.postSend(() -> PeripheralSessionPayload.sendToServer(pos, outKind, outValue, seq));
    }

    /**
     * 只保留仍然存在的方块条目（由核心在收到"设备表已更新"消息时调用）。
     * <p>消息机制的收尾：设备池扫描/注销后，这里不会残留已经消失的方块计时记录。
     */
    public static void pruneExcept(java.util.Set<Long> keep) {
        if (keep == null) {
            return;
        }
        SEND.keySet().removeIf(key -> !keep.contains(key));
    }

    /** 方块被移除 / 区块卸载：忘掉计时。 */
    public static void forget(BlockPos pos) {
        if (pos != null) {
            SEND.remove(pos.asLong());
        }
    }

    /** 世界卸载（退出世界）：整表清空 —— 新世界的方块坐标可能复用，旧计时/序号必须丢弃。 */
    public static void clear() {
        SEND.clear();
    }
}
