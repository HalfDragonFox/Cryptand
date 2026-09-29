package com.hdf.cryptand.neoforge.soc.ui;

import com.hdf.cryptand.neoforge.soc.net.SocStatusPayload;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * ===== 客户端芯片状态缓存（2026-09-15）=====
 *
 * <p>服务端推来的 {@link SocStatusPayload} 按方块位置缓存；LDLib2 面板注册监听者，
 * 收到新状态即刷新显示。</p>
 */
public final class SocClientState {

    private static final Map<BlockPos, SocStatusPayload> STATUS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Consumer<SocStatusPayload>> LISTENERS = new ConcurrentHashMap<>();

    private SocClientState() {
    }

    /** 收到服务端状态（payload handler 在客户端主线程调用） */
    public static void update(SocStatusPayload payload) {
        STATUS.put(payload.pos(), payload);
        final Consumer<SocStatusPayload> listener = LISTENERS.get(payload.pos());
        if (listener != null) {
            try {
                listener.accept(payload);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 面板注册监听（立即回放当前缓存，若有） */
    public static void listen(BlockPos pos, Consumer<SocStatusPayload> listener) {
        LISTENERS.put(pos, listener);
        final SocStatusPayload current = STATUS.get(pos);
        if (current != null) {
            try {
                listener.accept(current);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 面板关闭时移除监听 */
    public static void unlisten(BlockPos pos) {
        LISTENERS.remove(pos);
    }

    public static SocStatusPayload get(BlockPos pos) {
        return STATUS.get(pos);
    }

    // ==================== 组装台（面板数据源） ====================

    private static final Map<BlockPos, java.util.List<String>> ASSEMBLER_LINES = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Runnable> ASSEMBLER_LISTENERS = new ConcurrentHashMap<>();

    /** 服务端回报组装台状态 */
    public static void updateAssembler(BlockPos pos, java.util.List<String> lines) {
        ASSEMBLER_LINES.put(pos, lines == null ? java.util.List.of() : lines);
        final Runnable listener = ASSEMBLER_LISTENERS.get(pos);
        if (listener != null) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 面板注册（立即回放缓存） */
    public static void listenAssembler(BlockPos pos, Runnable listener) {
        ASSEMBLER_LISTENERS.put(pos, listener);
        try {
            listener.run();
        } catch (Throwable ignored) {
        }
    }

    public static java.util.List<String> assemblerLines(BlockPos pos) {
        return ASSEMBLER_LINES.getOrDefault(pos, java.util.List.of());
    }
}
