/**
 * ===== 亚层事件通知（消息驱动回调，2026-08-31） =====
 *
 * 用户要求：所有 API 消息驱动通知。事件 = 亚层生命周期/状态变化的通知，
 * 由 {@link SubLevelCommandBus} 执行命令时发出（异步/同步按场景）。
 *
 * 事件：
 *  - ASSEMBLED      装配完成（物理化成功）
 *  - DISASSEMBLED   拆卸完成（还原方块+移除体）
 *  - POSE_UPDATED   位姿更新（核心物理运动后写回）
 *  - REMOVED        亚层移除（容器删除）
 *
 * 监听器注册：{@link #register(Listener)}（线程安全，CopyOnWriteArrayList）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.api;

import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevel;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SubLevelEvents {

    /** 事件类型。 */
    public enum Kind { ASSEMBLED, DISASSEMBLED, POSE_UPDATED, REMOVED }

    /** 事件负载（纯数据，不可变）。 */
    public record Event(Kind kind, UUID subLevelId, int runtimeId, CryptandSubLevel subLevel) {
    }

    /** 事件监听器（实现者线程安全契约：回调可能发生于命令总线线程）。 */
    @FunctionalInterface
    public interface Listener {
        void onEvent(Event event);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void register(final Listener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void unregister(final Listener listener) {
        LISTENERS.remove(listener);
    }

    /** 广播事件（所有监听器；监听器异常不影响其他）。 */
    public static void emit(final Kind kind, final UUID subLevelId, final int runtimeId,
                            final CryptandSubLevel subLevel) {
        final Event ev = new Event(kind, subLevelId, runtimeId, subLevel);
        for (final Listener l : LISTENERS) {
            try {
                l.onEvent(ev);
            } catch (Throwable ignored) {
                // 单监听器异常不阻断
            }
        }
    }

    private SubLevelEvents() {
    }
}
