/**
 * ===== 客户端导线阻挡高亮缓存（2026-08-14） =====
 *
 * 服务端 WireBlockedPayload → show() 记录阻挡方块列表 + 时间戳；
 * BlockedHighlightRenderer 每帧读取（3 秒过期自动消失）。
 * 线程：客户端主线程（包 handle enqueueWork + 渲染帧）。
 */

package com.hdf.cryptand.neoforge.core.client;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class ClientBlockedStore {

    /** 高亮持续时间（毫秒） */
    private static final long LIFETIME_MS = 3000;

    private static volatile List<BlockPos> blocks = List.of();
    private static volatile long shownAt = 0;

    private ClientBlockedStore() {
    }

    /** 服务端阻挡通知 → 记录（替换旧列表，重置计时） */
    public static void show(List<BlockPos> newBlocks) {
        blocks = newBlocks == null ? List.of() : List.copyOf(newBlocks);
        shownAt = System.currentTimeMillis();
    }

    /** 当前需高亮的阻挡方块（已过期返回空） */
    public static List<BlockPos> blocks() {
        if (System.currentTimeMillis() - shownAt > LIFETIME_MS) return List.of();
        return blocks;
    }

    /** 手动清空（世界卸载等） */
    public static void clear() {
        blocks = List.of();
        shownAt = 0;
    }
}
