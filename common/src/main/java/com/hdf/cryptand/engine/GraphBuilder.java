package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;

/**
 * ===== 引擎图构建器（2026-08-30 组装器 build 用） =====
 * 组装器向网络图添加元件/模型的引擎侧抽象（纯 Java——平台提供图数据实现）。
 */
public interface GraphBuilder {

    /** 添加基础元件（R/C/L/源…）到图 */
    void addElement(Element e);

    /** 添加复合模型（CompositeElement——含温度/能量模型的组合）到图 */
    void addModel(CompositeElement m);

    /** 分配新节点（组装器内部节点——如 R-L 串联中间节点） */
    int addNode();

    /** 设备端子节点（按端子索引——工厂创建设备时分配；组装器 build 用它取端子在
     *  图中的节点 id，然后 addElement/addModel 接该节点） */
    int terminal(int index);
}
