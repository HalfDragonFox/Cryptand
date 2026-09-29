package com.hdf.cryptand.circuitsimulation.core.extensions;

import com.hdf.cryptand.circuitsimulation.core.SimulationCore;

/**
 * 核心扩展组件（2026-08-30 用户：类似 ECS——核心提供一系列工具，扩展组件
 * 其中一个是【具体实例类】（SimulationCore 实例），可把外部接口（TCP/UDP/
 * 消息等）接入实例以实现前端接入）。
 * <p>
 * 生命周期：{@link #onAttach}（接入实例）→ {@link #start}（启动：监听/注册）
 * → 运行（交互）→ {@link #stop} → {@link #onDetach}（断开）。
 * 每个实例（SimulationCore）可挂载多个扩展；扩展之间经实例交互（互不直接
 * 依赖）。ECS 映射：核心工具层 = System，扩展 = Component，实例 = Entity。
 */
public interface CoreExtension {

    /** 扩展标识（唯一；注册表/诊断用） */
    String id();

    /** 接入实例（实例侧注册扩展；start 前调用） */
    default void onAttach(SimulationCore core) {
    }

    /** 断开实例（stop 后调用） */
    default void onDetach(SimulationCore core) {
    }

    /** 启动（监听端口 / 注册消息 / 启动内部线程） */
    default void start() {
    }

    /** 停止（关闭监听 / 释放资源） */
    default void stop() {
    }
}
