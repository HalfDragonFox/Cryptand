package com.hdf.cryptand.circuitsimulation.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPICE 子电路定义（{@code .subckt name pin1 pin2 ...} ... {@code .ends}）。
 * <p>
 * 引脚顺序即端口顺序：实例化时端口 i 对应第 i 个引脚的电气节点。
 * 支持默认参数（{@code .param}）与内部元件（{@link SpiceElement}），
 * 可由 {@link SpiceLibrary#expand} 展开为 Cryptand 求解器元件。
 */
public final class SpiceSubcircuit {

    /** 子电路名（大写，查找 key） */
    public final String name;
    /** 引脚（端口）名，按声明顺序（已小写） */
    public final List<String> pins;
    /** 内部元件（按声明顺序） */
    public final List<SpiceElement> elements;
    /** 默认参数（.param 声明；实例化时被外部参数覆盖） */
    public final Map<String, Double> defaultParams;

    public SpiceSubcircuit(String name) {
        this.name = name;
        this.pins = new ArrayList<>();
        this.elements = new ArrayList<>();
        this.defaultParams = new LinkedHashMap<>();
    }

    public int portCount() { return pins.size(); }

    public String describe() {
        return "SpiceSubcircuit{" + name + ", pins=" + pins
                + ", elements=" + elements.size() + ", params=" + defaultParams + "}";
    }
}
