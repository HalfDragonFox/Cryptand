/**
 * ===== 配置棒（木棍模型工具） =====
 *
 * 右键电气设备打开 Cryptand 配置界面：
 *   - 可编程元件方块 → 元件库选择界面（GUI）
 *   - 变压器 → 结构参数界面（铁心/线圈/导线型号，后续）
 *   - 其他电气设备 → 信息展示
 */

package com.hdf.cryptand.neoforge.powergrid.item;

import com.hdf.cryptand.neoforge.powergrid.block.ProgrammableComponentBlockEntity;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ConfigStickItem extends Item {

    public ConfigStickItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        net.minecraft.core.BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            player.sendSystemMessage(Component.literal("该位置没有方块实体"));
            return InteractionResult.CONSUME;
        }
        // 可编程元件 → 元件库选择界面（LDLib2 BlockUI）
        if (be instanceof ProgrammableComponentBlockEntity) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.openUI(sp, pos);
            }
            return InteractionResult.CONSUME;
        }
        // 变压器 → 结构参数界面（下一轮实现）
        if (be instanceof org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity) {
            player.sendSystemMessage(Component.literal("变压器结构参数界面开发中"));
            return InteractionResult.CONSUME;
        }
        // 电气设备 / 接线端子 → 按物理位置定位所属网络（BlockWireEndpoint +
        // JunctionWireEndpoint 都支持），不再依赖 getTerminal(0)
        org.patryk3211.powergrid.electricity.sim.ElectricalNetwork net =
                findNetworkAt(level, pos);
        String cls = com.hdf.cryptand.neoforge.powergrid.device.Assemblers.beKey(be); // 2026-09-13：自有 BE 子类 → 原版名
        if (net != null) {
            int nodes = net.getNodes().size();
            String freq = "DC";
            double f = MultimeterDebug
                    .getNetworkFrequencyHz(net);
            if (f > 0) freq = String.format("%.1fHz", f);
            player.sendSystemMessage(Component.literal(
                    cls + " → 所属网络 ✓（节点 " + nodes + " · " + freq + "）"));
            return InteractionResult.CONSUME;
        }
        // 兜底：ElectricBlockEntity 端子网络（未在 WORLD_NETS 收录时）
        if (be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity ebe) {
            try {
                org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh =
                        ebe.getElectricBehaviour();
                if (beh != null) {
                    var tn = beh.getTerminal(0);
                    String netInfo = (tn == null || tn.getNetwork() == null)
                            ? "无" : tn.getNetwork().getClass().getSimpleName();
                    player.sendSystemMessage(Component.literal(
                            "设备 " + cls + " · 网络: " + netInfo));
                    return InteractionResult.CONSUME;
                }
            } catch (Throwable ignored) {
            }
        }
        player.sendSystemMessage(Component.literal("该位置无电气连接: " + cls));
        return InteractionResult.CONSUME;
    }

    /**
     * 按物理位置定位所属电气网络（含接线端子）：
     *   - 方块端子（BlockWireEndpoint）→ 位置精确匹配
     *   - 接线端子（JunctionWireEndpoint，接线端子/连接器）→ 用真实坐标匹配
     * 遍历 PhasorPipeline.WORLD_NETS（物理连通分量）。
     */
    private static org.patryk3211.powergrid.electricity.sim.ElectricalNetwork findNetworkAt(
            Level level, net.minecraft.core.BlockPos pos) {
        if (level == null || pos == null) return null;
        for (org.patryk3211.powergrid.electricity.sim.ElectricalNetwork en
                : PhasorPipeline.WORLD_NETS) {
            if (en == null || en.isEmpty()) continue;
            for (org.patryk3211.powergrid.electricity.sim.node.INode in : en.getNodes()) {
                if (!(in instanceof org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode ofn)) {
                    continue;
                }
                org.patryk3211.powergrid.electricity.wire.IWireEndpoint ep = ofn.endpoint;
                if (ep instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bep) {
                    if (bep.getPos().equals(pos)) return en;
                } else if (ep instanceof org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint jep) {
                    try {
                        if (net.minecraft.core.BlockPos.containing(
                                jep.getExactPosition(level)).equals(pos)) {
                            return en;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }
}
