/**
 * ===== 接地棒（PowerGrid electricity.grounding） =====
 * 接地：闭合（SwitchedWire 通）→ 该端子节点作为参考地（V=0）。
 * 实际接地由 PhasorNetworkBuilder 构建时检测并设置 groundNode（见
 * PhasorNetworkBuilder.applyGrounding），本类不做元件装配。
 */

package com.hdf.cryptand.neoforge.powergrid.device.grounding;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceWire;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class GroundingRodAssembler implements Assembler {

    public static final GroundingRodAssembler INSTANCE = new GroundingRodAssembler();

    private GroundingRodAssembler() {}

    /** 接地棒是否闭合（连接到地） */
    public static boolean isGrounded(BlockEntity be) {
        try {
            DeviceWire dw = DeviceWire.of(DeviceWire.field(be, "wire"));
            return dw.enabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void stamp(BlockEntity be, int a, int b, Network net) {
        // 接地节点由 builder 设为 groundNode（V=0），无需元件
    }
}
