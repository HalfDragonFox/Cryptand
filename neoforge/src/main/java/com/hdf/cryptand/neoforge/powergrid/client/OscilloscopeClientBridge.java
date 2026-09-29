/**
 * ===== 示波器客户端桥（2026-08-14 服务端兼容修复） =====
 *
 * 问题：OscilloscopeItem 被 DeferredRegister 注册 → 服务端 RegisterEvent 分发时
 * 构造该物品 → 类 transform（RuntimeDistCleaner）扫描【方法体】引用的所有类型，
 * 加载并检查 @OnlyIn——use() 方法体直接引用 net.minecraft.client.Minecraft →
 * 服务端加载失败 → "Attempted to load class net/minecraft/client/gui/screens/Screen
 * for invalid dist DEDICATED_SERVER" → 整个 Cryptand mod 加载失败。
 *
 * 解法：所有客户端专属逻辑（Minecraft/ModularUIScreen/OscilloscopeStore 等）集中
 * 在本类。OscilloscopeItem 只通过【反射字符串】调用本类——类文件里不含任何
 * 客户端类型引用，服务端 transform 不扫描到客户端类。本类仅在客户端被加载
 * （服务端永不 loadClass），因此方法体引用客户端类完全安全。
 */
package com.hdf.cryptand.neoforge.powergrid.client;

import com.hdf.cryptand.neoforge.powergrid.client.OscilloscopeStore;
import com.hdf.cryptand.neoforge.powergrid.item.OscilloscopeItem;
import com.hdf.cryptand.neoforge.powergrid.ui.OscilloscopeUi;
import com.hdf.cryptand.neoforge.powergrid.measurement.SelfManagedMultimeter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class OscilloscopeClientBridge {

    private OscilloscopeClientBridge() {}

    /** 客户端：同步检测时间 + 打开 LDLib2 示波器放大界面（Micsig 风格；
     *  2026-08-21 改回 LDLib2；CC 终端原生 Screen 版已弃置，见
     *  根目录 弃置区/OscilloscopeScreen_vanilla_old.java）。 */
    public static void openScreen(Level level, Player player, InteractionHand hand, ItemStack stack) {
        // 同步检测时间（物品 NBT → Store）
        OscilloscopeStore
                .setDetectSeconds(OscilloscopeItem.getDetectSeconds(stack));
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen == null) {
            // LDLib2 全屏 UI（Micsig 示波器风格）
            com.lowdragmc.lowdraglib2.gui.ui.ModularUI modularUI =
                    new com.lowdragmc.lowdraglib2.gui.ui.ModularUI(
                            OscilloscopeUi.build());
            mc.setScreen(new com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen(
                    modularUI, net.minecraft.network.chat.Component.literal("示波器")));
        }
    }

    /** 客户端：手持示波器每 10 tick 发送波形请求（原 OscilloscopeItem.inventoryTick 逻辑）。
     *  频率复用上次响应回传的主导频率（OscilloscopeStore 缓存，5 秒内有效），
     *  避免每次请求前客户端沿导线 BFS 频率探测。 */
    public static void tickRequest(Level level, Player player, ItemStack stack) {
        double freq = OscilloscopeStore.lastFreq();
        if (System.currentTimeMillis()
                - OscilloscopeStore.lastUpdateMs() > 5000) {
            freq = 0; // 数据过期 → 回退 BFS 重新探测
        }
        SelfManagedMultimeter
                .sendOscilloscopeRequest(level, player, stack, freq);
    }
}
