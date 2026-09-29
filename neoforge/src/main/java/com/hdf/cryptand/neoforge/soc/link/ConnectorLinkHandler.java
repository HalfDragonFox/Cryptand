package com.hdf.cryptand.neoforge.soc.link;

import com.hdf.cryptand.neoforge.soc.content.ConnectorItem;
import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * ===== 硬件连接器：右键机箱打开硬件配置页面（2026-09-29 用户定案）=====
 *
 * <p>用户原话：「硬件连接器还是没改掉，取消绑定功能，仅右键机箱打开硬件配置页面」。</p>
 *
 * <p>所以本类<b>不再</b>做「选端点 / 建链路 / 潜行断开」——只做一件事：判定右键的是不是一台机器
 * （方块实体实现了 OC 的 {@code li.cil.oc.api.machine.Machine}），是就把该机器周边的硬件枚举进
 * 连接器物品的 NBT，然后打开配置页面（{@code HeldItemUIMenuType}：数据随物品栈发到客户端，
 * 两端读同一份，面板侧不另算）。</p>
 *
 * <p>⚠ {@code soc} 子包纪律：不许在编译期引用 OC 的类型（OC 是软依赖）⇒ 用反射按类名判定。</p>
 */
public final class ConnectorLinkHandler {

    private static final String OC_MACHINE = "li.cil.oc.api.machine.Machine";

    private ConnectorLinkHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        final ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof ConnectorItem)) {
            return;
        }
        final Level level = event.getLevel();
        final Player player = event.getEntity();
        if (level == null || player == null) {
            return;
        }
        // 连接器吃掉这次交互（否则右键机箱会开机箱界面）
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level.isClientSide) {
            return;
        }
        final BlockPos pos = event.getPos();
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        if (!isMachine(level, pos)) {
            say(sp, "右键 OC 机箱（机器方块）打开硬件配置页面");
            return;
        }
        // 服务端枚举一次：机器 + 周边硬件 + 各自的接口列表 ⇒ 写进物品 NBT ⇒ 开页面
        final ItemStack inHand = stack;
        ConnectorItem.setPanel(inHand,
                pos.getX() + "|" + pos.getY() + "|" + pos.getZ(),
                SocPairingData.write(SocPairingData.collect(level, pos), level.registryAccess()),
                cpuArch(level, pos));
        inHand.set(DataComponents.CUSTOM_DATA,
                inHand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
        HeldItemUIMenuType.openUI(sp, event.getHand() == null ? InteractionHand.MAIN_HAND : event.getHand());
    }

    /**
     * 机箱里那颗 CPU 的架构（决定接线页是否有效）。
     *
     * <p>用户定案：「如果是 lua 架构的 cpu 则直接无效」——所以这里如实判定：
     * 我们的架构类（{@code com.hdf.cryptand.*}）= {@code cryptand}；OC 原版的 Lua 架构 = {@code lua}；
     * 其它/取不到 = {@code none}。反射调用（OC 是软依赖，不能编译期引用）。</p>
     */
    /**
     * 机箱里那颗 CPU 是什么架构（决定接线页有效与否）。
     *
     * <p>顺序（越靠前越权威）：</p>
     * <ol>
     *   <li><b>看机箱物品槽里插的是谁的 CPU</b> —— 芯片信息在 CPU 里：{@code cryptand:chip_*} = 我们的 RV32；
     *       {@code opencomputers:cpu*} = OC 原版 Lua（用户定案："如果是 lua 架构的 cpu 则直接无效"）。
     *       这条不依赖机器是否开机（{@code architecture()} 只有在运行时才存在 ⇒ 关机时查架构会得到 null，
     *       那会把"关机"误报成"没芯片"）；</li>
     *   <li>退回运行期架构判定：方块实体 → {@code machine()} → {@code architecture()}（{@link OcMachineReflect}）。</li>
     * </ol>
     */
    private static String cpuArch(Level level, BlockPos pos) {
        // OC 的机箱方块实体实现的是原版 Container（{@code li.cil.oc.api.internal.Case extends Container}），
        // 不走 NeoForge 的 item handler capability —— 所以这里按 Container 读槽位（真机实测：
        // capability 对 OC 机箱恒为 null，这正是"机箱里的扩展卡/CPU 一直读不到"的原因）。
        final net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof net.minecraft.world.Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                final ItemStack s = container.getItem(i);
                if (s.isEmpty()) {
                    continue;
                }
                if (s.getItem() instanceof com.hdf.cryptand.neoforge.soc.content.SocAssembledItem) {
                    return "cryptand";
                }
                final String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(s.getItem()).toString();
                if (id.startsWith("opencomputers:cpu")) {
                    return "lua";
                }
            }
        }
        return OcMachineReflect.cpuArch(level.getBlockEntity(pos));
    }

    /** 方块实体是不是一台机器（2026-09-29 修：改用 {@link OcMachineReflect} 的正确判据） */
    private static boolean isMachine(Level level, BlockPos pos) {
        return OcMachineReflect.isMachineHost(level.getBlockEntity(pos));
    }

    private static void say(Player player, String text) {
        player.displayClientMessage(Component.literal("[硬件配置] " + text), false);
    }
}
