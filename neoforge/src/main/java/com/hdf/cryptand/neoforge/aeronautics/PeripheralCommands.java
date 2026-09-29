/**
 * ===== 外设（船舵/拉杆）指令（2026-09-13）=====
 *
 * 按项目命令框架定义：子包经 {@code CryptandRegistries.registerSubcommand} 把子树挂到
 * {@code /cryptand} 根下（删掉本子包 → 指令整体消失，core 零编译引用）。
 *
 * <pre>
 *   /cryptand peripheral scan                重新扫描设备（等价界面【刷新设备】）
 *   /cryptand peripheral disconnect [device] 完全断开指定设备；省略则断开全部
 *   /cryptand peripheral stop                停掉所有设备上的力反馈
 *   /cryptand peripheral list                列出设备池现状
 * </pre>
 *
 * ⚠ 设备池只存在于【客户端】（每客户端自己的 SDL 设备池），所以这些指令在服务端执行时
 * 只做一件事：把动作转发给**执行者自己的客户端**（{@link PeripheralCommandPayload}），
 * 由那边真正操作设备。多人服务器上因此也只有发起者受影响。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PeripheralCommands {

    private PeripheralCommands() {
    }

    /** 挂载 /cryptand peripheral 子树（AeronauticsModule 在任一外设开启时调用）。 */
    public static void register() {
        CryptandRegistries.registerSubcommand("peripheral", peripheralCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> peripheralCommand() {
        return Commands.literal("peripheral")
                // 外设是玩家自己的硬件，不需要权限等级（多人下也只影响自己）
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal("scan")
                        .executes(ctx -> forward(ctx.getSource(),
                                PeripheralCommandPayload.ACTION_SCAN, "")))
                .then(Commands.literal("disconnect")
                        .executes(ctx -> forward(ctx.getSource(),
                                PeripheralCommandPayload.ACTION_DISCONNECT, ""))
                        .then(Commands.argument("device", StringArgumentType.greedyString())
                                .executes(ctx -> forward(ctx.getSource(),
                                        PeripheralCommandPayload.ACTION_DISCONNECT,
                                        StringArgumentType.getString(ctx, "device")))))
                .then(Commands.literal("stop")
                        .executes(ctx -> forward(ctx.getSource(),
                                PeripheralCommandPayload.ACTION_STOP_FORCE, "")))
                .then(Commands.literal("list")
                        .executes(ctx -> forward(ctx.getSource(),
                                PeripheralCommandPayload.ACTION_LIST, "")));
    }

    /** 把动作发给执行者自己的客户端（设备池在那边）。 */
    private static int forward(CommandSourceStack source, int action, String arg) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("外设指令需要在游戏内执行（设备池在客户端）"));
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new PeripheralCommandPayload(action, arg));
        return 1;
    }
}
