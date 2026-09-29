package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.SpeakerModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：扬声器（2026-08-30 引擎内置——交错电网抽象） =====
 * 扬声器 = SpeakerModel（音圈 R-L + 机械阻尼——电-声转换）。
 */
public final class SpeakerAssembler implements Assembler {

    private final double voiceCoilR, voiceCoilL, mechR;
    private final ThermalModel thermal;

    public SpeakerAssembler(double voiceCoilR, double voiceCoilL, double mechR, ThermalModel thermal) {
        this.voiceCoilR = voiceCoilR > 0 ? voiceCoilR : 8.0;
        this.voiceCoilL = Math.max(voiceCoilL, 0);
        this.mechR = mechR;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "speaker"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new SpeakerModel(a, b, x, voiceCoilR, voiceCoilL, mechR, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
