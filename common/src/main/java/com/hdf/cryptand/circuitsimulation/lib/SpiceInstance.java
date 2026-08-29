package com.hdf.cryptand.circuitsimulation.lib;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;

import java.util.ArrayList;
import java.util.List;

/**
 * SPICE 子电路展开结果：Cryptand 求解器元件列表 + 诊断信息。
 * <p>
 * 由 {@link SpiceLibrary#expand} 生成；调用方把 {@link #elements()} 直接加入
 * {@link com.hdf.cryptand.circuitsimulation.model.Network}（已分配好内部节点）。
 */
public final class SpiceInstance {

    /** 展开目标网络（分配内部节点用；null 时退化为相对序号） */
    public Network net;
    private final List<Element> elements = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    /** 本次展开消耗的引擎节点数（内部节点） */
    public int internalNodes = 0;

    public List<Element> elements() {
        return elements;
    }

    public void add(Element e) {
        if (e != null) elements.add(e);
    }

    public void note(String s) {
        notes.add(s);
    }

    public List<String> notes() {
        return notes;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("SpiceInstance{elements=").append(elements.size())
          .append(", internalNodes=").append(internalNodes);
        if (!notes.isEmpty()) {
            sb.append(", notes=").append(notes);
        }
        return sb.append("}").toString();
    }
}
