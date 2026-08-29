package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.MutualInductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;

/** 根据 ElementData 重建元件实例（本地求解用）。 */
final class ElementFactory {

    static Element create(NetworkSnapshot.ElementData d) {
        double[] p = d.params;
        switch (d.type) {
            case RESISTOR:          return new Resistor(d.nodeA, d.nodeB, p[0]);
            case DC_VOLTAGE_SOURCE: return new DcVoltageSource(d.nodeA, d.nodeB, p[0], p[1]);
            case CURRENT_SOURCE:    return new CurrentSource(d.nodeA, d.nodeB, p[0]);
            case CAPACITOR: {
                // 2026-08-21 快照状态保留：Capacitor.params()=[C, vPrev]，重建必须
                //  恢复 vPrev（否则每次快照重建 vPrev=0 → DC 充电恒第一拍大电流）
                Capacitor c = new Capacitor(d.nodeA, d.nodeB, p[0]);
                if (p.length > 1) c.vPrev = p[1];
                return c;
            }
            case INDUCTOR: {
                // 同上：params()=[L, iPrev] 恢复电流记忆
                Inductor ind = new Inductor(d.nodeA, d.nodeB, p[0]);
                if (p.length > 1) ind.iPrev = p[1];
                return ind;
            }
            case AC_VOLTAGE_SOURCE: return new AcVoltageSource(d.nodeA, d.nodeB, p[0], p[1], p[2]);
            case WAVEFORM_SOURCE:   return new WaveformSource(d.nodeA, d.nodeB, p[0] > 0, d.waveform,
                    p[1], p[2], p[3], p[4], p[5], p[6]);
            case IDEAL_TRANSFORMER: return new IdealTransformer((int) p[1], (int) p[2], (int) p[3],
                    (int) p[4], (int) p[5], p[0]);
            case MUTUAL_INDUCTOR: return new MutualInductor((int) p[4], (int) p[5], (int) p[6],
                    (int) p[7], p[0], p[1], p[2], p[3], (int) p[8], (int) p[9], (int) p[10]);
            default: throw new IllegalArgumentException("Unknown element type " + d.type);
        }
    }
}
