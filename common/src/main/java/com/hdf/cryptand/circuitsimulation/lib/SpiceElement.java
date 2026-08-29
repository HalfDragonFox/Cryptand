package com.hdf.cryptand.circuitsimulation.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPICE 子电路内的一条元件语句（如 {@code R1 in out 100} / {@code Q1 c b e} /
 * {@code X1 a b 0 my_subckt}）。
 * <p>
 * 解析保持原始 token 语义（节点名/值表达式/附加参数），具体电气含义在
 * {@link SpiceLibrary#expand} 展开时决定——这样解析器最通用，可支持
 * R/C/L/V/I/D/Q/M/K/X 各类元件及参数化（{@code {Rval}}）。
 */
public final class SpiceElement {

    /** 类型首字符（大写）：R/C/L/V/I/D/Q/M/K/X */
    public final String type;
    /** 完整元件名（如 R1 / Q1 / X1） */
    public final String name;
    /** 节点引用（端口名或内部节点名，已小写；数字节点如 "0" 也保留） */
    public final List<String> nodes;
    /** 值 token 原始字符串（如 "100" / "{Rval}" / "SIN(0 10 50)" / "DC"，可 null） */
    public final String value;
    /** X 元件：引用的子电路名（大写；null 表示非 X） */
    public final String subcktRef;
    /** 附加参数 token（模型名 / 参数 k=v 等） */
    public final List<String> extra;

    public SpiceElement(String type, String name, List<String> nodes,
                        String value, String subcktRef, List<String> extra) {
        this.type = type;
        this.name = name;
        this.nodes = new ArrayList<>(nodes);
        this.value = value;
        this.subcktRef = subcktRef;
        this.extra = extra == null ? new ArrayList<>() : new ArrayList<>(extra);
    }

    /** 诊断描述。 */
    public String describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("name", name);
        m.put("nodes", nodes);
        m.put("value", value);
        if (subcktRef != null) m.put("subckt", subcktRef);
        if (!extra.isEmpty()) m.put("extra", extra);
        return m.toString();
    }
}
