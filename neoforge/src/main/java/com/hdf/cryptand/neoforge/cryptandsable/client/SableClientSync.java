package com.hdf.cryptand.neoforge.cryptandsable.client;

import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.client.particle.SableClientParticleModule;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableClientRenderModule;
import com.hdf.cryptand.neoforge.cryptandsable.client.sound.SableClientSoundModule;
import com.hdf.cryptand.neoforge.cryptandsable.core.destruction.DestructionEvent;

/**
 * 客户端表现同步（SableClientSync）—— 把来自核心的表现事件拆包分发给各表现模块。
 *
 * <p>数据归属矩阵：Client 侧纯消费（只读镜像）。声音/粒子/渲染模块为独立分包，
 * 各自处理自己的事件；本类只是路由中枢 + 在客户端不存在物理计算。
 *
 * <p>线程：客户端（CLIENT dist）侧调用。物理守护与数据仍在 Server/核心（客户端只看到镜像）。
 */
public final class SableClientSync {
    private final SableClientRenderModule render = new SableClientRenderModule();
    private final SableClientSoundModule sound = new SableClientSoundModule();
    private final SableClientParticleModule particle = new SableClientParticleModule();

    /** 从核心出站消息中分解表现事件（主->客户端的镜像通道）。 */
    public void accept(Object message) {
        // 位姿快照：渲染模块收
        if (message instanceof SableMessages.PoseSnapshot ps) {
            render.onPoseSnapshot(ps);
        }
        // 破坏事件：粒子 + 声音表现
        else if (message instanceof DestructionEvent de) {
            particle.onDestruction(de);
            sound.onDestruction(de);
        }
    }

    public SableClientRenderModule render() {
        return render;
    }

    public SableClientSoundModule sound() {
        return sound;
    }

    public SableClientParticleModule particle() {
        return particle;
    }

    /** 世界卸载清理。 */
    public void clear() {
        render.clear();
        sound.clear();
        particle.clear();
    }
}