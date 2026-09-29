package com.hdf.cryptand.neoforge.truescreen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== 真彩屏 GRAPHICS 生产者的**查找表**（按节点地址，2026-09-27 任务 D）=====
 *
 * <p>移植件的屏幕组件在**节点接入/断开**时登记/注销它自己的 sink（Scala 侧完成，见
 * {@code common/TrueScreenGraphics.scala} 与 {@code component.TextBuffer.onConnect/onDisconnect}）。
 * 生产者（opencomputers 子包的 GPU 环境）只需按 guest 绑定的地址查一次。</p>
 *
 * <p>⚠ 唯一入口：不许再另建"地址 → 屏幕"的第二张表（组件索引已经是 OC 的 ComponentTracker，
 * 这里只是"地址 → 像素写入通道"的窄接口）。</p>
 */
public final class TrueScreenGraphicsApi {

    private static final Map<String, TrueScreenGraphicsSink> SINKS = new ConcurrentHashMap<>();

    private TrueScreenGraphicsApi() {
    }

    /**
     * 屏自己的像素通道 key —— {@code rc_screen@<维度>@<x>,<y>,<z>}。
     *
     * <p>不再依赖 OC 节点地址：字符驱动退役后，屏仍然要在表里被 GPU 找到，
     * 而方块位置是**与 OC 无关的稳定标识**。</p>
     */
    public static String keyFor(String dimension, net.minecraft.core.BlockPos pos) {
        return "rc_screen@" + dimension + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 屏幕节点接入时登记（重复登记覆盖为最新，避免旧对象泄漏）。 */
    public static void register(String address, TrueScreenGraphicsSink sink) {
        if (address != null && sink != null) {
            SINKS.put(address, sink);
        }
    }

    /** 屏幕节点断开/区块卸载时注销（只注销自己那一份，防止误删新实例）。 */
    public static void unregister(String address, TrueScreenGraphicsSink sink) {
        if (address != null) {
            SINKS.remove(address, sink);
        }
    }

    /** 静默探测（"这地址有没有像素通道"，用于判定屏的类型；没有就是没有，不记日志）。 */
    public static TrueScreenGraphicsSink sink(String address) {
        return address == null ? null : SINKS.get(address);
    }

    /**
     * 生产者专用：**确认对方应当有像素通道**（调用方已判定那是我方移植屏）却查不到时，
     * 明确记一条 warn —— 真机上"像素没接上"必须在日志里看得见，不许静默什么都不做。
     *
     * <p>判定与释义：移植屏的像素通道在**组件接入网络**时登记（origin 才登记）。
     * 查不到只有三种可能：屏还没接入网络、它不是 origin（拼合里的从块）、或者刚刚卸载。
     * 三者都会让 GRAPHICS 像素写入落空，因此必须点名。</p>
     *
     * @param address 节点地址
     * @param where   调用点（日志里用来定位是哪个生产者路径）
     * @return 找到的通道；没找到 ⇒ null（调用方按"该屏只能走字符路径"继续，不抛异常）
     */
    public static TrueScreenGraphicsSink sinkRequired(String address, String where) {
        final TrueScreenGraphicsSink found = sink(address);
        if (found == null) {
            LOG.warn("[TrueScreen] 像素通道缺失：addr={} 调用点={} 已登记通道数={} —— "
                            + "该屏没登记像素通道（未接入网络 / 不是拼合 origin / 刚卸载），"
                            + "本次 GRAPHICS 像素写入不会生效；请对照日志里的 gpu.bind 类型与屏幕节点接入记录",
                    address, where, SINKS.size());
        }
        return found;
    }

    /**
     * 按**方块坐标**查（无人化工具按坐标操作屏幕）。
     *
     * <p>⚠ 这不是第二张登记表：登记/注销仍然只有 {@link #register}/{@link #unregister} 一处，
     * 这里只是对**同一张表**做一次扫描（屏的数量是"几十"量级；扫描比维护第二张表更不容易出错 ——
     * 不会出现"坐标表里还留着已断开的屏"这种状态）。</p>
     */
    public static TrueScreenGraphicsSink sinkAt(net.minecraft.core.BlockPos pos) {
        if (pos == null) {
            return null;
        }
        for (final TrueScreenGraphicsSink s : SINKS.values()) {
            if (s != null && pos.equals(s.blockPos())) {
                return s;
            }
        }
        return null;
    }

    /** 按坐标查 + 缺了**响亮报错**（无人化工具专用；语义同 {@link #sinkRequired}）。 */
    public static TrueScreenGraphicsSink sinkAtRequired(net.minecraft.core.BlockPos pos, String where) {
        final TrueScreenGraphicsSink found = sinkAt(pos);
        if (found == null) {
            LOG.warn("[TrueScreen] 像素通道缺失：blockPos={} 调用点={} 已登记通道数={} —— "
                            + "该坐标上没有登记像素通道（不是移植屏 / 没接入网络 / 不是拼合 origin / 刚卸载），"
                            + "本次像素操作不会生效",
                    pos, where, SINKS.size());
        }
        return found;
    }

    /** 诊断用：当前登记的通道数。 */
    public static int size() {
        return SINKS.size();
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/truescreen");
}
