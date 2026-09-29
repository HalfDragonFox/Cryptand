/**
 * ===== 客户端声音自检 tick =====
 *
 * 每客户端 tick 调用 SynthHumSound.tickAll()：
 *   - 来源失效（方块爆炸/卸载）→ 释放 OpenAL source（不再残留声音）
 *   - 游戏暂停（ESC）→ 静音（MC 实体 tick 暂停，tickAudio 不再更新）
 *
 * 同时清理变压器声音参数缓存（TransformerSoundState.tick()）：
 * 3 秒未收到服务端同步包（变压器被拆/区块卸载/断网）→ 条目失效。
 * 注意：此清理【不依赖电机存在】——原先放在 RotorSoundMixin（电机）
 * 中，若世界没有电机则变压器声音状态不会被定期清理。
 */

package com.hdf.cryptand.neoforge.powergrid.client;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.core.sound.TransformerSoundState;
import com.hdf.cryptand.neoforge.powergrid.client.CryptandWindingScreen;
import com.hdf.cryptand.neoforge.powergrid.client.wire.WireLookPicker;
import com.hdf.cryptand.neoforge.powergrid.client.wire.WireRenderManager;
import com.hdf.cryptand.neoforge.powergrid.sound.SoundModel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class ClientSoundTicker {

    private ClientSoundTicker() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        SoundModel.tickAll();
        // 变压器声音参数缓存定期清理（不依赖电机存在）
        TransformerSoundState.tick();
        // 导线视线射线检测→WireLookStore（万用表抓线/剪线钳拆除的依据；
        // ⚠ 2026-08-26 恢复：外部写回丢失过一次，缺失会导致两侧全部失效）
        WireLookPicker.tick();
        // 自管导线渲染同步（CEE 方式 Flywheel）：每 tick 检查 WireGraph 版本
        WireRenderManager.get().tick();
        // 匝数设置屏交互驱动（按住右键 3 tick 后开屏）
        CryptandWindingScreen.clientTick();
        // ★ 2026-09-07 亚层渲染 payload 批处理驱动移至 cryptandsable 子包自持
        //   （SableClientTickDriver）——core 零引用子包（子包隔离）。
    }
}