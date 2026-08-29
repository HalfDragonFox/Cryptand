package com.hdf.cryptand.circuitsimulation.lib;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPICE 元件模型（{@code .model NAME TYPE(p1=v1 p2=v2)}）——内部电路实现模型。
 * <p>
 * 元件行引用模型名（如 {@code D1 a b 1N4148}、{@code Q1 c b e NPN_MODEL}、
 * {@code M1 d g s NMOS_MODEL}），展开时用模型参数替换元件内部电气参数
 * （二极管 Vf/Ron/Roff、BJT Bf/Vbe/Rbe/VceSat、MOS Vto/Ron/Roff 等）。
 * <p>
 * 同名模型覆盖（后加载的替换先加载的）——实现"替换内部电路实现模型"。
 */
public final class SpiceModel {

    /** 模型名（大写，查找 key） */
    public final String name;
    /** 模型类型（D/NPN/PNP/NMOS/PMOS/R/C/L 等，大写） */
    public final String type;
    /** 模型参数（k=v，v 已求值） */
    public final Map<String, Double> params;

    public SpiceModel(String name, String type, Map<String, Double> params) {
        this.name = name;
        this.type = type == null ? "" : type.toUpperCase();
        this.params = params == null ? new LinkedHashMap<>() : params;
    }

    public String describe() {
        return "SpiceModel{" + name + " type=" + type + " params=" + params + "}";
    }
}
