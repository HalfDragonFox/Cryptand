package com.hdf.cryptand.circuitsimulation.lib;

import java.util.ArrayList;
import java.util.List;

/**
 * 固化电路：SPICE 子电路【选择后参数已求值、嵌套已内联】的不可变快照。
 * <p>
 * 由 {@link SpiceLibrary#resolve} 生成并持久化到方块 NBT——已放置的元件
 * 不依赖库文件（库删除/更新不影响），构建时由
 * {@link SpiceLibrary#expandResolved} 直接用固化参数生成 Cryptand 元件。
 * <p>
 * 纯数据类（不依赖 Minecraft/NBT），序列化由调用方（neoforge BE）完成。
 */
public final class ResolvedCircuit {

    /** 端口（引脚）名，顺序即端子顺序 */
    public final List<String> pins = new ArrayList<>();
    /** 固化元件（值已求值、嵌套已内联；节点名为端口名/内部名/"0" 地） */
    public final List<Element> elements = new ArrayList<>();

    /** 单个固化元件。 */
    public static final class Element {
        /** 类型首字符（R/C/L/V/I/D/Q/M/K；X 已内联不会出现） */
        public final String type;
        /** 节点引用（端口名/内部名/"0"） */
        public final List<String> nodes;
        /** 最终值文本：数字字符串，或 V 源关键字（DC/AC/SIN(...)） */
        public final String valueText;
        /** 附加参数（模型名 PNP/PMOS 等） */
        public final List<String> extra;

        public Element(String type, List<String> nodes, String valueText, List<String> extra) {
            this.type = type;
            this.nodes = nodes;
            this.valueText = valueText;
            this.extra = extra == null ? new ArrayList<>() : new ArrayList<>(extra);
        }

        public double value() {
            return SpiceValue.parse(valueText);
        }
    }
}
