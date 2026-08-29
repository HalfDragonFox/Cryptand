package com.hdf.cryptand.circuitsimulation.comm;

import com.hdf.cryptand.circuitsimulation.cache.AppLink;
import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;

/**
 * 世界工厂（2026-08-22 通信组件：服务端创建实例用）。
 * <p>
 * 平台（MC / EDA / 任意）实现本接口，为【按名创建的实例】提供对话与执行器。
 * 远程客户端【不持有也不传递】 executor/对话对象（不可序列化）——只发请求，
 * 由服务端本地的 WorldFactory 提供。这是"实例完全隔离"的关键：
 * 通信层只把数据请求转发给具体实例，平台能力由服务端注入。
 */
public interface WorldFactory {

    /** 为空实例提供对话（可 null = 无对话回调） */
    AppLink linkFor(String worldName);

    /** 为空实例提供平台上网络操作执行器（不可为 null） */
    NetOpExecutor executorFor(String worldName);
}