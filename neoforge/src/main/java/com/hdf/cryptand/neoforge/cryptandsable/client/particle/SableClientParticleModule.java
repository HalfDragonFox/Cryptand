package com.hdf.cryptand.neoforge.cryptandsable.client.particle;

import com.hdf.cryptand.neoforge.cryptandsable.core.destruction.DestructionEvent;

/**
 * 客户端粒子模块（SableClientParticleModule）。
 *
 * <p>消费破坏事件 → 生成粒子特效（碎片/烟雾）。MVP 只登记（不直接绑
 * ClientLevel.spawnParticles，避免跨 dist 类加载），后续 v0 接入。
 */
public final class SableClientParticleModule {

    public void onDestruction(DestructionEvent de) {
        // TODO(v0): 绑定 ClientLevel.spawnParticles（破坏特效）。当前仅占位。
    }

    public void clear() {
        // no-op
    }
}