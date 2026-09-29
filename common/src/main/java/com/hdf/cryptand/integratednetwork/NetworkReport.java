package com.hdf.cryptand.integratednetwork;

/**
 * 网络变化上报（2026-08-29 集成网络核心）：接口/容器及其参数变化 → 上报更新
 * 虚拟网络。主线程在速率/过滤/方向/升级等变化时提交，核心在纯虚拟图上更新。
 */
public final class NetworkReport {

    /** 上报类型 */
    public enum Kind {
        /** 新增接口 */
        ADD_INTERFACE,
        /** 移除接口 */
        REMOVE_INTERFACE,
        /** 新增容器信息 */
        ADD_CONTAINER,
        /** 移除容器信息 */
        REMOVE_CONTAINER
    }

    /** 上报类型 */
    public final Kind kind;
    /** 相关接口（ADD/REMOVE_INTERFACE） */
    public final NetworkInterface iface;
    /** 相关容器信息（ADD/REMOVE_CONTAINER） */
    public final ContainerInfo container;

    private NetworkReport(Kind kind, NetworkInterface iface, ContainerInfo container) {
        this.kind = kind;
        this.iface = iface;
        this.container = container;
    }

    public static NetworkReport addInterface(NetworkInterface i) {
        return new NetworkReport(Kind.ADD_INTERFACE, i, null);
    }

    public static NetworkReport removeInterface(Object id) {
        return new NetworkReport(Kind.REMOVE_INTERFACE,
                new NetworkInterface(id, TransferType.GENERIC, false, 0, 0, 0, 0, 0), null);
    }

    public static NetworkReport addContainer(ContainerInfo c) {
        return new NetworkReport(Kind.ADD_CONTAINER, null, c);
    }

    public static NetworkReport removeContainer(Object id) {
        return new NetworkReport(Kind.REMOVE_CONTAINER, null, new ContainerInfo(id));
    }

    @Override
    public String toString() {
        return "NetworkReport{" + kind + " " + (iface != null ? iface : "")
                + (container != null ? container : "") + "}";
    }
}