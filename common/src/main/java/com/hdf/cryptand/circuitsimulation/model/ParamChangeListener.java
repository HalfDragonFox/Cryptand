package com.hdf.cryptand.circuitsimulation.model;

/**
 * 参数变化监听：电路元件参数变化（电阻/电容/电感/源值）时收到消息。
 * <p>
 * 参数变化【不改变电路结构】→ 接收方应更新求解结果，而非重建电路网络。
 * 典型用法：电路上下文把本监听挂到元件上，收到消息后递增参数版本号，
 * 求解器据此"参数变才重解、结构复用"。
 */
@FunctionalInterface
public interface ParamChangeListener {
    /** 参数变化回调（source = 发生参数变化的元件） */
    void onParamChanged(Element source);
}
