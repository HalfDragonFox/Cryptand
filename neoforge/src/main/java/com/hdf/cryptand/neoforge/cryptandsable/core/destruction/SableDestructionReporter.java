package com.hdf.cryptand.neoforge.cryptandsable.core.destruction;

import com.hdf.cryptand.neoforge.cryptandsable.core.collision.Contact;
import com.hdf.cryptand.neoforge.cryptandsable.core.worker.SableSimulationContext;

import java.util.List;

/**
 * 破坏列表聚合器（SableDestructionReporter）。
 *
 * <p>把检测到的 {@link DestructionEvent} 打包成"破坏列表"（增量下行），
 * 交给门面发往主线程。对齐 C8：核心被破坏 → 向主线程发送破坏列表，增量通知。
 */
public final class SableDestructionReporter {

    private SableDestructionReporter() {
    }

    /**
     * 从碰撞接触生成破坏事件列表（每个物理体至多一条聚合，避免 sub-step 粒度过碎）。
     *
     * @return 破坏事件列表；空表示无破坏
     */
    public static List<DestructionEvent> buildList(SableSimulationContext ctx, List<Contact> contacts) {
        return SableDestructionDetector.detect(ctx, contacts);
    }
}