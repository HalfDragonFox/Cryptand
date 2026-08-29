package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络快照 —— 纯数据、可序列化（用于发送给 C++/GPU/集群）。
 * 只包含求解所需的全部信息：节点数、接地、频率、dt、元件列表。
 */
public final class NetworkSnapshot {
    public final int nodeCount;
    public final int groundNode;
    public final double frequency;   // Hz, 0 = DC/时域
    public final double dt;
    /** 仿真时间（秒），波形源求瞬时值用；发送到 C++/集群端后据此重建同一波形 */
    public final double time;
    public final ElementData[] elements;

    public static final class ElementData {
        public final ElementType type;
        /** 波形类型（方波/三角波/正弦等），序列化到 C++/集群时识别用；非波形源为 DC */
        public final WaveformType waveform;
        public final int nodeA;
        public final int nodeB;
        public final double[] params;

        public ElementData(ElementType type, int nodeA, int nodeB, double[] params) {
            this(type, nodeA, nodeB, params, WaveformType.DC);
        }

        public ElementData(ElementType type, int nodeA, int nodeB, double[] params, WaveformType waveform) {
            this.type = type;
            this.waveform = waveform;
            this.nodeA = nodeA;
            this.nodeB = nodeB;
            this.params = params;
        }
    }

    public NetworkSnapshot(int nodeCount, int groundNode, double frequency, double dt, double time, ElementData[] elements) {
        this.nodeCount = nodeCount;
        this.groundNode = groundNode;
        this.frequency = frequency;
        this.dt = dt;
        this.time = time;
        this.elements = elements;
    }

    /** 从 Network 抓取快照（用于发送到远端执行） */
    public static NetworkSnapshot from(Network net) {
        ElementData[] eds = new ElementData[net.elements().size()];
        int i = 0;
        for (Element e : net.elements()) {
            eds[i++] = new ElementData(e.type(), e.nodeA(), e.nodeB(), e.params(), e.waveform());
        }
        return new NetworkSnapshot(net.nodeCount(), net.groundNode, net.frequency, net.dt, net.time, eds);
    }

    public SolveMode solveMode() {
        // 2026-08-14 完全禁用时域：恒相量（DC 由求解器内部用等效小频率近似）
        return SolveMode.COMPLEX_AC;
    }

    public int elementCount() { return elements.length; }

    // ---- 二进制序列化（发给 C++ 的格式） ----
    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bos);
            o.writeInt(nodeCount);
            o.writeInt(groundNode);
            o.writeDouble(frequency);
            o.writeDouble(dt);
            o.writeDouble(time);
            o.writeInt(elements.length);
            for (ElementData e : elements) {
                o.writeByte(e.type.ordinal());
                o.writeByte(e.waveform.ordinal());   // 波形类型（C++/集群据此识别方波/三角波/正弦等）
                o.writeInt(e.nodeA);
                o.writeInt(e.nodeB);
                o.writeInt(e.params.length);
                for (double p : e.params) o.writeDouble(p);
            }
            o.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("NetworkSnapshot serialize failed", e);
        }
    }

    public static NetworkSnapshot fromByteArray(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int nc = in.readInt();
            int g = in.readInt();
            double f = in.readDouble();
            double dt = in.readDouble();
            double tm = in.readDouble();
            int n = in.readInt();
            ElementData[] eds = new ElementData[n];
            for (int i = 0; i < n; i++) {
                ElementType t = ElementType.values()[in.readUnsignedByte()];
                WaveformType wf = WaveformType.values()[in.readUnsignedByte()];
                int a = in.readInt();
                int b = in.readInt();
                int plen = in.readInt();
                double[] ps = new double[plen];
                for (int j = 0; j < plen; j++) ps[j] = in.readDouble();
                eds[i] = new ElementData(t, a, b, ps, wf);
            }
            return new NetworkSnapshot(nc, g, f, dt, tm, eds);
        } catch (IOException e) {
            throw new RuntimeException("NetworkSnapshot deserialize failed", e);
        }
    }

    /** 重建为可求解的 Network（本地求解用） */
    public Network toNetwork() {
        Network net = new Network();
        net.frequency = frequency;
        net.dt = dt;
        net.time = time;
        net.groundNode = groundNode;
        List<Element> list = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) net.addNode();
        for (ElementData ed : elements) {
            net.addElement(ElementFactory.create(ed));
        }
        return net;
    }

    @Override
    public String toString() {
        return "NetworkSnapshot{nodes=" + nodeCount + ", elements=" + elements.length
                + ", f=" + frequency + ", bytes=" + toByteArray().length + "}";
    }
}
