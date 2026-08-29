package com.hdf.cryptand.circuitsimulation.model;

/**
 * 参数变化消息源：理想元件实现本接口，参数变化时发送消息（notifyParamChanged）。
 * <p>
 * 设计原则【结构 vs 参数】：
 *   - 电路【结构】变化（接线/拆线/方块添加移除）→ 重建电路网络（拓扑版本失效）。
 *   - 元件【参数】变化（电阻值/电容/电感/源幅值相位）→ 只发消息，不重建网络，
 *     接收方（电路上下文/求解器）更新求解结果即可。
 */
public interface ParamChangeSource {
    /** 注册参数变化监听 */
    void addParamChangeListener(ParamChangeListener listener);

    /** 移除参数变化监听 */
    void removeParamChangeListener(ParamChangeListener listener);

    /** 参数变化 → 发送消息给所有监听者 */
    void notifyParamChanged();
}
