/**
 * ===== 自定义测量仪表基类（2026-08-18） =====
 *
 * 所有 Cryptand 手持测量工具（万用表/温度计/示波器）都继承此类：
 *   - 继承原版 MultimeterItem（兼容 PowerGrid 的 ModeData/NBT 结构、
 *     getMode/setMode/getModeData/saveModeData/deleteModeData）
 *   - 统一探针线上限（maxProbeLines）——渲染器与服务端据此限制并发线数
 *   - 统一清除目标（clearTarget，shift+右键空气）
 *   - 子类覆写 grabWire 定义“右键看向导线时的抓取行为”
 *
 * ⚠ 原版 powergrid:multimeter（非自定义）不继承此类，走原版逻辑。
 */

package com.hdf.cryptand.neoforge.core.measurement;

import com.hdf.cryptand.neoforge.core.client.WireLookStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;

public abstract class CryptandMeterItem extends MultimeterItem {

    protected CryptandMeterItem(Properties properties) {
        super(properties);
    }

    /** 本仪表类型允许的最大探针线数量。渲染上限 + 服务端测量点上限。
     *  万用表(电压模式 Pos+Neg)=2，温度计/示波器/电流模式=1。 */
    public abstract int maxProbeLines();

    // ========== 统一清除 / 抓取（子类可覆写） ==========

    /** 统一清除当前测量目标（shift+右键空气时调用，清空 ModeData）。
     *  2026-08-20 修复"清除不彻底"：原 deleteModeData 只删 CUSTOM_DATA.ModeData，
     *  CUSTOM_DATA.Mode（电压/电流模式）保留 + getModeData 会自动重建空 ModeData
     *  → 清除后右键另一端子仍被当作第二点"接回"。改为【完全移除 CUSTOM_DATA】
     *  （与原版 MultimeterItem.use shift+右键 remove(CUSTOM_DATA) 行为一致）：
     *  Mode 与 ModeData 全清 → 下次右键端子全新开始。 */
    public static void clearTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        try {
            stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        } catch (Throwable t) {
            // 兜底：移除失败 → 至少删 ModeData + Mode
            try { deleteModeData(stack); } catch (Throwable ignored) { }
            try { stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA); } catch (Throwable ignored) { }
        }
    }

    /**
     * 右键“看向的导线”时的抓取行为。
     * <p>默认实现：电流模式 + 存单点电流点（端点 → NODE_CURRENT，万用表语义）。
     * 额外存储导线命中点坐标（HitX/Y/Z），渲染器直接用该点绘制探针线——
     * 导线非实体，连到曲线命中点比连到端子方块更直观。
     * 温度计覆写为导线段温度目标，示波器覆写为搭线端点。 */
    public void grabWire(ItemStack stack, WireLookStore.WireHit hit) {
        // 默认：电流模式 + 单点电流点（万用表）
        com.hdf.cryptand.neoforge.powergrid.adapter.SelfManagedMultimeter
                .grabWireAsSinglePoint(stack, hit.ax(), hit.ay(), hit.az(), hit.aTerm());
        // ⚠ 必须写原版 X/Y/Z（原版 MultimeterItemRenderer 电流模式读它渲染探针线）
        //   + 自管 HitX/Y/Z（测量链 resolveTargets 用）
        try {
            net.minecraft.nbt.CompoundTag md = org.patryk3211.powergrid.equipment.multimeter.MultimeterItem
                    .getModeData(stack);
            if (md != null && hit.hitPoint() != null) {
                md.putDouble("X", hit.hitPoint().x);
                md.putDouble("Y", hit.hitPoint().y);
                md.putDouble("Z", hit.hitPoint().z);
                md.putDouble("HitX", hit.hitPoint().x);
                md.putDouble("HitY", hit.hitPoint().y);
                md.putDouble("HitZ", hit.hitPoint().z);
                org.patryk3211.powergrid.equipment.multimeter.MultimeterItem
                        .saveModeData(stack, md);
            }
        } catch (Throwable ignored) {
        }
    }

    // ========== 目标解析（子类可覆写，供渲染器/测量链使用） ==========

    /** 从当前 ModeData 解析出测量目标列表（纯数据，可后台调用）。 */
    public java.util.List<MeasurementTarget> resolveTargets(
            net.minecraft.world.level.Level level, ItemStack stack) {
        return resolveDefaultTargets(level, stack);
    }

    /** 默认目标解析逻辑（从 MultimeterItem ModeData 解析）。 */
    protected static java.util.List<MeasurementTarget> resolveDefaultTargets(
            net.minecraft.world.level.Level level, ItemStack stack) {
        java.util.ArrayList<MeasurementTarget> out = new java.util.ArrayList<>();
        try {
            net.minecraft.nbt.CompoundTag md = MultimeterItem.getModeData(stack);
            if (md == null || md.isEmpty()) return out;
            int mode = stack.getItem() instanceof MultimeterItem m ? m.getMode(stack) : 0;

            if (mode == 0) {
                // 电压模式
                org.patryk3211.powergrid.electricity.wire.IWireEndpoint pos =
                        deserializeEp(md, "Pos");
                org.patryk3211.powergrid.electricity.wire.IWireEndpoint neg =
                        deserializeEp(md, "Neg");
                if (pos instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bp
                        && neg instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bn) {
                    double freq = freqOf(level, bp.getPos(), bp.getTerminal());
                    if (bp.getPos().equals(bn.getPos())) {
                        out.add(MeasurementTarget.sameBlockVoltage(
                                bp.getPos(), bp.getTerminal(), bn.getTerminal(), freq));
                    } else {
                        out.add(MeasurementTarget.betweenBlocksVoltage(
                                bp.getPos(), bp.getTerminal(), bn.getPos(), bn.getTerminal(), freq));
                    }
                } else if (pos instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bp) {
                    // 单点（电压模式只接了正极）→ 降级为单点电流
                    out.add(MeasurementTarget.nodeCurrent(
                            bp.getPos(), bp.getTerminal(),
                            freqOf(level, bp.getPos(), bp.getTerminal())));
                }
            } else {
                // 电流模式
                org.patryk3211.powergrid.electricity.wire.IWireEndpoint pos =
                        deserializeEp(md, "Pos");
                if (pos instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint bp) {
                    out.add(MeasurementTarget.nodeCurrent(
                            bp.getPos(), bp.getTerminal(),
                            freqOf(level, bp.getPos(), bp.getTerminal())));
                } else if (md.contains("X")) {
                    // 旧式 X/Y/Z 附着点（电流模式无 Pos → 回退 X/Y/Z）
                    // 只做视觉指示，无法精确到端子
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 从 ModeData 反序列化端点 */
    private static org.patryk3211.powergrid.electricity.wire.IWireEndpoint deserializeEp(
            net.minecraft.nbt.CompoundTag md, String key) {
        if (!md.contains(key)) return null;
        try {
            return org.patryk3211.powergrid.electricity.wire.WireEndpointType
                    .deserialize(md.getCompound(key));
        } catch (Throwable ignored) { return null; }
    }

    /** 频率（客户端 BFS/缓存） */
    private static double freqOf(net.minecraft.world.level.Level level,
                                 net.minecraft.core.BlockPos pos, int term) {
        try {
            org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint ep =
                    new org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint(pos, term);
            return com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug
                    .getNetworkFrequencyHz(level, ep);
        } catch (Throwable ignored) { return 0; }
    }
}