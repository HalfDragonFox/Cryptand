package com.hdf.cryptand.neoforge.cryptandsable.client.sound;

import com.hdf.cryptand.neoforge.cryptandsable.core.destruction.DestructionEvent;

/**
 * 客户端声音模块（SableClientSoundModule）。
 *
 * <p>消费破坏/碰撞事件 → 播放音效。MVP 只登记/忽略（不直接绑 MC 音效系统，
 * 避免在纯逻辑层依赖客户端 SoundManager 造成类加载跨 dist 问题）。
 */
public final class SableClientSoundModule {

    public void onDestruction(DestructionEvent de) {
        // TODO(v0): 绑定 MC SoundManager（播放碰撞/破裂音效）。当前仅占位。
    }

    public void clear() {
        // no-op
    }
}