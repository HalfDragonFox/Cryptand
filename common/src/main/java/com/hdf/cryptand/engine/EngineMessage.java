package com.hdf.cryptand.engine;

/**
 * ===== 引擎网络操作消息（2026-08-30 用户：引擎可以定义网络操作的消息） =====
 *
 * 引擎定义的消息（求解完成/网络重建/参数变化等）——求解完成一种后直接向
 * 绑定器发送消息即可（用户："求解完成一种后直接向绑定器发送消息"）。
 * 绑定器（{@link Binding}）必须有消息接口（{@link Binding#onMessage}）。
 */
public final class EngineMessage {

    /** 消息类型（网络操作） */
    public enum Type {
        /** 求解完成（网络已求解——结果就绪，绑定器可读取/应用） */
        SOLVE_DONE,
        /** 网络已重建（结构变化——图已重建，绑定器可更新绑定） */
        NETWORK_REBUILT,
        /** 参数已变化（元件参数变化——触发重解） */
        PARAM_CHANGED,
        /** 网络已失效（设备移除/网络销毁——绑定器清理） */
        NETWORK_INVALIDATED,
        /** 设备所在区块【已加载】→ 批量「开启」（绑定器恢复参与仿真） */
        DEVICE_LOADED,
        /** 设备所在区块【已卸载】→ 批量「关闭」（绑定器暂停；电路与模型保留，不删除） */
        DEVICE_UNLOADED
    }

    public final Type type;
    /** 消息数据（求解结果/元件等——按类型可选） */
    public final Object data;

    public EngineMessage(Type type, Object data) {
        this.type = type;
        this.data = data;
    }

    public static EngineMessage of(Type type, Object data) {
        return new EngineMessage(type, data);
    }
}
