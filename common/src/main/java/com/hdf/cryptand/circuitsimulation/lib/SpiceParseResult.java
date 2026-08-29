package com.hdf.cryptand.circuitsimulation.lib;

import java.util.ArrayList;
import java.util.List;

/**
 * SPICE 库文件解析结果：子电路定义 + 元件模型（.model）。
 */
public final class SpiceParseResult {

    /** 子电路定义（按声明顺序） */
    public final List<SpiceSubcircuit> subcircuits = new ArrayList<>();
    /** 元件模型（.model，按声明顺序） */
    public final List<SpiceModel> models = new ArrayList<>();
}
