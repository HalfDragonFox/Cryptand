package com.hdf.cryptand.dynamic.api;

import java.util.concurrent.Callable;

/**
 * 宿主给核心的授权与能力（统一资源登记 ⇒ 卸载可回收 + 越权可检测）。
 */
public interface CoreHost {

    /** 登记本插件占用的一项资源与释放动作（宿主在 detach 后统一回收）。 */
    void own(String resourceId, Runnable release);

    /** 需要主线程时调用（离线实现 = 直接执行；MC 侧 = mc.execute）。 */
    <R> R mainThread(Callable<R> task);

    /** 就地接管：登记"本次装配替换了同 id 的旧资源"，失败时框架逆序还原。 */
    void takeOver(String resourceId, Object previous);

    /** 查询某资源被接管前的旧值（无则 null）。 */
    Object previousOf(String resourceId);

    void warn(String msg);
}
