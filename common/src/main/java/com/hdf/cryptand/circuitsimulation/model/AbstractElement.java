package com.hdf.cryptand.circuitsimulation.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 元件基类：持有两个连接节点 + 参数变化消息机制。
 * 理想元件（电阻/电容/电感/源）参数变化时调用 {@link #notifyParamChanged()}
 * 发送消息 → 接收方更新求解结果，不重建电路网络（结构未变）。
 */
public abstract class AbstractElement implements Element, ParamChangeSource {
    protected final int nodeA;
    protected final int nodeB;

    /** 参数变化监听器（参数变化时发送消息，接收方更新求解而非重建网络） */
    private final List<ParamChangeListener> paramListeners = new ArrayList<>();

    protected AbstractElement(int nodeA, int nodeB) {
        this.nodeA = nodeA;
        this.nodeB = nodeB;
    }

    @Override
    public int nodeA() { return nodeA; }

    @Override
    public int nodeB() { return nodeB; }

    @Override
    public void addParamChangeListener(ParamChangeListener listener) {
        if (listener != null) paramListeners.add(listener);
    }

    @Override
    public void removeParamChangeListener(ParamChangeListener listener) {
        paramListeners.remove(listener);
    }

    @Override
    public void notifyParamChanged() {
        for (ParamChangeListener l : paramListeners) {
            try {
                l.onParamChanged(this);
            } catch (Throwable ignored) {
            }
        }
    }
}
