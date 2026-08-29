package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.Node;
import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import com.hdf.cryptand.circuitsimulation.model.WaveformGroup;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.composite.InductorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络完整结构编解码（2026-08-19 用户需求：缓存网络内所有数据含电路结构，
 * 支持跨区块传输）。比 {@link NetworkSnapshot} 更进一步：
 * <ul>
 *   <li>基础元件（elements，与 NetworkSnapshot 相同编码）</li>
 *   <li>复合元件（composites：WireComposite / ResistorModel / CapacitorModel /
 *       InductorModel——设备模型有外部依赖不在此编码，恢复后由组装器重建）</li>
 *   <li>端子（terminals：deviceKey + terminalIndex + engineNode）</li>
 *   <li>波形组（waveforms：多频叠加）</li>
 *   <li>接地 / 频率 / dt / time</li>
 * </ul>
 * 纯数据（零 MC 依赖），二进制格式（DataStream），Base64 由调用方负责。
 */
public final class NetworkStructureCodec {

    private NetworkStructureCodec() {}

    /** 从 Network 编码完整结构（基础元件 + 复合元件 + 端子 + 波形）。 */
    public static byte[] encode(Network net) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bos);
            o.writeInt(0x43545259); // magic "CTRY"
            o.writeInt(net.nodeCount());
            o.writeInt(net.groundNode);
            o.writeDouble(net.frequency);
            o.writeDouble(net.dt);
            o.writeDouble(net.time);
            // 基础元件
            List<Element> els = net.elements();
            o.writeInt(els.size());
            for (Element e : els) {
                writeElement(o, e);
            }
            // 复合元件（仅可纯数据重建的类型；其余跳过——恢复后由组装器重建）
            List<CompositeElement> comps = net.composites();
            int cw = 0;
            List<CompositeElement> keep = new ArrayList<>();
            for (CompositeElement c : comps) {
                if (c instanceof WireComposite || c instanceof ResistorModel
                        || c instanceof CapacitorModel || c instanceof InductorModel) {
                    keep.add(c);
                }
            }
            o.writeInt(keep.size());
            for (CompositeElement c : keep) {
                writeComposite(o, c);
            }
            // 端子
            List<TerminalElement> terms = net.terminals();
            o.writeInt(terms.size());
            for (TerminalElement t : terms) {
                o.writeUTF(t.deviceKey);
                o.writeInt(t.terminalIndex);
                o.writeInt(t.engineNode);
            }
            // 波形组
            WaveformGroup wg = net.waveforms();
            if (wg == null) {
                o.writeInt(0);
            } else {
                List<WaveformGroup.Component> cs = wg.components();
                o.writeInt(cs.size());
                for (WaveformGroup.Component c : cs) {
                    o.writeDouble(c.frequency);
                    o.writeDouble(c.amplitude);
                    o.writeDouble(c.phaseDeg);
                    o.writeByte(c.type.ordinal());
                }
            }
            o.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("NetworkStructureCodec encode failed", e);
        }
    }

    /** 解码为完整 Network（基础元件 + 复合元件 + 端子 + 波形）。 */
    public static Network decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != 0x43545259) throw new IOException("bad magic");
            Network net = new Network();
            int nodeCount = in.readInt();
            net.groundNode = in.readInt();
            net.frequency = in.readDouble();
            net.dt = in.readDouble();
            net.time = in.readDouble();
            for (int i = 0; i < nodeCount; i++) net.addNode();
            int ne = in.readInt();
            for (int i = 0; i < ne; i++) net.addElement(readElement(in));
            int nc = in.readInt();
            for (int i = 0; i < nc; i++) net.addComposite(readComposite(in));
            int nt = in.readInt();
            for (int i = 0; i < nt; i++) {
                String key = in.readUTF();
                int ti = in.readInt();
                int en = in.readInt();
                net.addTerminal(new TerminalElement(key, ti, en));
            }
            int nw = in.readInt();
            if (nw > 0) {
                WaveformGroup wg = new WaveformGroup();
                for (int i = 0; i < nw; i++) {
                    double f = in.readDouble();
                    double a = in.readDouble();
                    double p = in.readDouble();
                    WaveformType t = WaveformType.values()[in.readUnsignedByte()];
                    wg.add(new WaveformGroup.Component(f, a, p, t));
                }
                net.setWaveforms(wg);
            }
            return net;
        } catch (IOException e) {
            throw new RuntimeException("NetworkStructureCodec decode failed", e);
        }
    }

    // ==================== 元件编解码 ====================

    private static void writeElement(DataOutputStream o, Element e) throws IOException {
        o.writeByte(e.type().ordinal());
        o.writeInt(e.nodeA());
        o.writeInt(e.nodeB());
        double[] p = e.params();
        o.writeInt(p.length);
        for (double v : p) o.writeDouble(v);
    }

    private static Element readElement(DataInputStream in) throws IOException {
        ElementType t = ElementType.values()[in.readUnsignedByte()];
        int a = in.readInt();
        int b = in.readInt();
        int plen = in.readInt();
        double[] p = new double[plen];
        for (int i = 0; i < plen; i++) p[i] = in.readDouble();
        return switch (t) {
            case RESISTOR -> new Resistor(a, b, p[0]);
            case DC_VOLTAGE_SOURCE -> new DcVoltageSource(a, b, p[0], p[1]);
            case CURRENT_SOURCE -> p.length > 1
                    ? new CurrentSource(a, b, p[0], p[1]) : new CurrentSource(a, b, p[0]);
            case CAPACITOR -> new Capacitor(a, b, p[0]);
            case INDUCTOR -> new Inductor(a, b, p[0]);
            case AC_VOLTAGE_SOURCE -> p.length > 2
                    ? new AcVoltageSource(a, b, p[0], p[1], p[2], p[3])
                    : new AcVoltageSource(a, b, p[0], p[1], p[2]);
            case WAVEFORM_SOURCE -> new WaveformSource(a, b, p[0] > 0, WaveformType.SINE,
                    p[1], p[2], p[3], p[4], p[5], p[6]);
            default -> throw new IOException("unsupported element " + t);
        };
    }

    // ==================== 复合元件编解码 ====================

    private static void writeComposite(DataOutputStream o, CompositeElement c) throws IOException {
        if (c instanceof WireComposite wc) {
            o.writeByte(1);
            o.writeInt(wc.a);
            o.writeInt(wc.b);
            o.writeDouble(wc.resistance);
            o.writeUTF(wc.compositeKey() == null ? "" : wc.compositeKey());
        } else if (c instanceof ResistorModel rm) {
            o.writeByte(2);
            o.writeInt(rm.a);
            o.writeInt(rm.b);
            // 电阻值：展开元件第 0 个 Resistor 的 resistance 字段
            double r = 0;
            try {
                Element[] de = rm.decompose();
                if (de.length > 0 && de[0] instanceof Resistor rr) r = rr.resistance;
            } catch (Throwable ignored) { }
            o.writeDouble(r);
            o.writeUTF(rm.compositeKey() == null ? "" : rm.compositeKey());
        } else if (c instanceof CapacitorModel cm) {
            o.writeByte(3);
            o.writeInt(cm.a);
            o.writeInt(cm.b);
            o.writeInt(cm.x);
            o.writeDouble(cm.energy.capacitance);
            // ESR：展开元件中第一个 Resistor 的 resistance
            double esr = 0;
            try {
                for (Element e : cm.decompose()) {
                    if (e instanceof Resistor rr) { esr = rr.resistance; break; }
                }
            } catch (Throwable ignored) { }
            o.writeDouble(esr);
            o.writeUTF(cm.compositeKey() == null ? "" : cm.compositeKey());
        } else if (c instanceof InductorModel im) {
            o.writeByte(4);
            o.writeInt(im.a);
            o.writeInt(im.b);
            o.writeInt(im.x);
            // 电感值：展开元件中最后一个 Inductor 的 inductance
            double l = 0;
            try {
                for (Element e : im.decompose()) {
                    if (e instanceof Inductor ii) { l = ii.inductance; }
                }
            } catch (Throwable ignored) { }
            o.writeDouble(l);
            double dcr = 0;
            try {
                for (Element e : im.decompose()) {
                    if (e instanceof Resistor rr) { dcr = rr.resistance; break; }
                }
            } catch (Throwable ignored) { }
            o.writeDouble(dcr);
            o.writeUTF(im.compositeKey() == null ? "" : im.compositeKey());
        } else {
            throw new IOException("unsupported composite " + c.getClass().getName());
        }
    }

    private static CompositeElement readComposite(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte();
        switch (kind) {
            case 1: {
                int a = in.readInt();
                int b = in.readInt();
                double r = in.readDouble();
                String key = in.readUTF();
                return new WireComposite(a, b, r, null, key.isEmpty() ? null : key);
            }
            case 2: {
                int a = in.readInt();
                int b = in.readInt();
                double r = in.readDouble();
                String key = in.readUTF();
                ResistorModel rm = new ResistorModel(a, b, r, null);
                if (!key.isEmpty()) rm.setCompositeKey(key);
                return rm;
            }
            case 3: {
                int a = in.readInt();
                int b = in.readInt();
                int x = in.readInt();
                double c = in.readDouble();
                double esr = in.readDouble();
                String key = in.readUTF();
                CapacitorModel cm = new CapacitorModel(a, b, x, c, esr, null, null);
                if (!key.isEmpty()) cm.setCompositeKey(key);
                return cm;
            }
            case 4: {
                int a = in.readInt();
                int b = in.readInt();
                int x = in.readInt();
                double l = in.readDouble();
                double dcr = in.readDouble();
                String key = in.readUTF();
                InductorModel im = new InductorModel(a, b, x, l, dcr, null, null);
                if (!key.isEmpty()) im.setCompositeKey(key);
                return im;
            }
            default:
                throw new IOException("unsupported composite kind " + kind);
        }
    }
}
