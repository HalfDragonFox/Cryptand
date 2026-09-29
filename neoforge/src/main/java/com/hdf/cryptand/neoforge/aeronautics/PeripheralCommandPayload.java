/**
 * ===== 外设指令包（S2C，2026-09-13）=====
 *
 * 命令树挂在 /cryptand 根下（{@code CryptandRegistries.registerSubcommand}，服务端执行），
 * 但【设备池只存在于客户端】（每客户端自己的 SDL 设备池），所以服务端命令只负责
 * "把动作转发给执行者自己的客户端" —— 这样多人服务器上也只有发起者受影响。
 *
 * 动作：
 * <ul>
 *   <li>{@link #ACTION_SCAN}       —— 重新扫描设备（等价界面【刷新设备】）；</li>
 *   <li>{@link #ACTION_DISCONNECT} —— 完全断开指定设备（{@code arg} 为空 = 全部）；</li>
 *   <li>{@link #ACTION_STOP_FORCE} —— 停掉所有设备上的力反馈；</li>
 *   <li>{@link #ACTION_LIST}       —— 把设备池现状回显到聊天框。</li>
 * </ul>
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.Cryptand;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PeripheralCommandPayload(int action, String arg) implements CustomPacketPayload {

    public static final int ACTION_SCAN = 0;
    public static final int ACTION_DISCONNECT = 1;
    public static final int ACTION_STOP_FORCE = 2;
    public static final int ACTION_LIST = 3;

    public static final Type<PeripheralCommandPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "peripheral_command"));

    public static final StreamCodec<ByteBuf, PeripheralCommandPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PeripheralCommandPayload::action,
                    ByteBufCodecs.STRING_UTF8, PeripheralCommandPayload::arg,
                    PeripheralCommandPayload::new);

    @Override
    public Type<PeripheralCommandPayload> type() {
        return TYPE;
    }

    /**
     * 在【客户端】执行（设备池所在侧）。
     * <p>⚠ 本类会被<b>双端加载</b>（payload 注册在两端都会发生），所以这里<b>不能出现任何
     * 客户端专属类的直接引用</b>（如 `Minecraft`）—— 回显统一走 {@code context.player()}
     * （NeoForge 的通用 API，客户端上下文即本地玩家），避免服务端解析到客户端类。
     */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.world.entity.player.Player player = context.player();
            try {
                switch (action) {
                    case ACTION_SCAN -> {
                        com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput.requestScan();
                        reply(player, "外设：已请求重新扫描设备");
                    }
                    case ACTION_DISCONNECT -> {
                        int n = com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput
                                .disconnectDevice(arg);
                        reply(player, "外设：断开 "
                                + (arg == null || arg.isEmpty() ? "全部设备" : arg)
                                + "，释放使用者 " + n + " 个");
                    }
                    case ACTION_STOP_FORCE -> {
                        com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput.stopAllForce();
                        reply(player, "外设：已停掉所有力反馈");
                    }
                    case ACTION_LIST -> {
                        var lines = com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmInput
                                .describeDevices();
                        reply(player, "外设设备池（" + lines.size() + "）：");
                        for (String line : lines) {
                            reply(player, "  " + line);
                        }
                    }
                    default -> reply(player, "外设：未知动作 " + action);
                }
            } catch (Throwable t) {
                reply(player, "外设指令失败：" + t);
            }
        });
    }

    private static void reply(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.displayClientMessage(Component.literal(text), false);
        }
    }
}
