/**
 * ===== 绘图仪（PowerGrid kinetics.plotter） =====
 * 采样电阻设备：buildCircuit 用 SamplingWire(20e3f)（20 kΩ 采样阻抗，两端口）。
 * 建模为固定 20 kΩ 电阻（魔法数字，与 PowerGrid 一致）——电压采样正常，
 * 不再因未建模而孤立（悬空端残留电压）。
 */

package com.hdf.cryptand.neoforge.powergrid.device.plotter;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class PlotterAssembler implements Assembler {

    public static final PlotterAssembler INSTANCE = new PlotterAssembler();

    /** 采样阻抗（Ω）——与 PowerGrid PlotterBlockEntity buildCircuit 的
     *  SamplingWire(20e3f) 一致（魔法数字）。 */
    public static final double SAMPLING_RESISTANCE = 20e3;

    private PlotterAssembler() {}

    @Override
    public void stamp(BlockEntity be, int a, int b, Network net) {
        if (a == b) return;
        net.addElement(new Resistor(a, b, SAMPLING_RESISTANCE));
    }
}
