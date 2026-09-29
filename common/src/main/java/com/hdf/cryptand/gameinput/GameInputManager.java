package com.hdf.cryptand.gameinput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏输入实例管理类（2026-09-08 通用输入后端：生命周期记账管理）。
 * <p>
 * 向管理类【申请】实例（{@link #acquire}）→ 管理类打开设备 + 分配 {@link UUID} +
 * 归入组 id 登记；删除（{@link #release} / {@link #releaseGroup}）时【先关闭再移除】；
 * 退出游戏调用 {@link #shutdown()} 遍历列表全部关闭。
 * <pre>{@code
 * GameInputManager mgr = GameInputManager.DEFAULT;
 *
 * // 申请第一个设备（默认组；经 GameInputProvider 路由到对应后端）
 * GameInputController ctrl = mgr.acquireFirst();
 *
 * // 申请指定设备到组
 * GameInputController w2 = mgr.acquire("DRIVER", GameInputProvider.listDevices().get(0));
 *
 * ctrl.setForce(ForceParams.spring(0, 8000));   // 方向盘力反馈
 * GameInputState s = ctrl.getState();           // 读状态
 *
 * mgr.release(ctrl);          // 先关闭再移除
 * mgr.releaseGroup("DRIVER"); // 整组释放
 * mgr.shutdown();             // 退出游戏：遍历全部关闭（平台自动调用）
 * }</pre>
 * 线程安全：acquire/release/shutdown 内部同步；查询方法并发安全。
 */
public final class GameInputManager {

    /** 全局默认管理类（平台层退出游戏时调用 {@link #shutdown()}） */
    public static final GameInputManager DEFAULT = new GameInputManager();

    /** 默认组 id */
    public static final String DEFAULT_GROUP = "default";

    private final Object lock = new Object();
    private final Map<UUID, ManagedInput> inputs = new ConcurrentHashMap<>();

    public GameInputManager() {
    }

    // ==================== 申请（acquire） ====================

    /** 申请指定设备（默认组） */
    public GameInputController acquire(GameInputDeviceInfo info) throws IOException {
        return acquire(DEFAULT_GROUP, info);
    }

    /** 申请指定设备到组（子包未启用抛 {@link IllegalStateException}） */
    public GameInputController acquire(String groupId, GameInputDeviceInfo info) throws IOException {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId 不能为空");
        }
        if (info == null) throw new IllegalArgumentException("info 不能为空");
        if (!GameInputConfig.isInputEnabled()) {
            throw new IllegalStateException("游戏输入未启用（核心配置 enableGameInput=false，config/cryptand/common.toml）");
        }
        synchronized (lock) {
            if (shutdown) {
                throw new IllegalStateException("GameInputManager 已 shutdown，不能继续申请");
            }
            GameInputController c = GameInputProvider.open(info); // 经门面按前缀路由后端
            try {
                UUID id = UUID.randomUUID();
                inputs.put(id, new ManagedInput(id, groupId, c));
                return c;
            } catch (RuntimeException e) {
                try { c.close(); } catch (Throwable ignored) { }
                throw e;
            }
        }
    }

    /** 便捷：打开第一个可用设备（默认组；子包未启用/无设备抛 IOException） */
    public GameInputController acquireFirst() throws IOException {
        if (!GameInputConfig.isInputEnabled()) {
            throw new IOException("游戏输入未启用（核心配置 enableGameInput=false，config/cryptand/common.toml）");
        }
        return acquire(DEFAULT_GROUP, GameInputProvider.listDevices().get(0));
    }

    // ==================== 释放（release：先关闭再移除） ====================

    /** 释放实例：先 {@code close()} 再移出列表；未登记的实例返回 false */
    public boolean release(GameInputController controller) {
        if (controller == null) return false;
        synchronized (lock) {
            for (ManagedInput m : inputs.values()) {
                if (m.controller == controller) {
                    closeAndRemove(m);
                    return true;
                }
            }
            return false;
        }
    }

    /** 按 UUID 释放 */
    public boolean release(UUID id) {
        if (id == null) return false;
        synchronized (lock) {
            ManagedInput m = inputs.get(id);
            if (m == null) return false;
            closeAndRemove(m);
            return true;
        }
    }

    /** 整组释放（逐个先关闭再移除）；返回释放数量 */
    public int releaseGroup(String groupId) {
        if (groupId == null) return 0;
        synchronized (lock) {
            List<ManagedInput> list = new ArrayList<>();
            for (ManagedInput m : inputs.values()) {
                if (groupId.equals(m.groupId)) list.add(m);
            }
            for (ManagedInput m : list) closeAndRemove(m);
            return list.size();
        }
    }

    // ==================== 查询 ====================

    /** 当前登记实例数 */
    public int size() {
        return inputs.size();
    }

    public boolean isEmpty() {
        return inputs.isEmpty();
    }

    /** 是否已 shutdown（退出游戏后为 true，此后 acquire 被拒绝） */
    public boolean isShutdown() {
        return shutdown;
    }

    /** 全部登记实例 */
    public List<GameInputController> controllers() {
        List<GameInputController> r = new ArrayList<>(inputs.size());
        for (ManagedInput m : inputs.values()) r.add(m.controller);
        return r;
    }

    /** 指定组的全部实例 */
    public List<GameInputController> controllersOf(String groupId) {
        List<GameInputController> r = new ArrayList<>();
        if (groupId == null) return r;
        for (ManagedInput m : inputs.values()) {
            if (groupId.equals(m.groupId)) r.add(m.controller);
        }
        return r;
    }

    /** 实例的登记 UUID（未登记返回 null） */
    public UUID idOf(GameInputController controller) {
        if (controller == null) return null;
        for (ManagedInput m : inputs.values()) {
            if (m.controller == controller) return m.id;
        }
        return null;
    }

    /** 实例所属组 id（未登记返回 null） */
    public String groupOf(GameInputController controller) {
        if (controller == null) return null;
        for (ManagedInput m : inputs.values()) {
            if (m.controller == controller) return m.groupId;
        }
        return null;
    }

    // ==================== 退出游戏/卸载：遍历列表全部关闭 ====================

    /**
     * 关闭全部登记实例并清空列表（非终态——closeAll 后仍可继续 acquire）。
     * 子包 unload（懒加载释放资源）时调用。
     */
    public void closeAll() {
        synchronized (lock) {
            for (ManagedInput m : inputs.values()) {
                try {
                    m.controller.close();
                } catch (Throwable ignored) {
                }
            }
            inputs.clear();
        }
    }

    /** 关闭全部登记实例并清空；之后管理类进入终态（acquire 拒绝）。平台层退出游戏时调用。 */
    public void shutdown() {
        synchronized (lock) {
            if (shutdown) return;
            shutdown = true;
            closeAll();
        }
    }

    // ==================== 内部 ====================

    private volatile boolean shutdown;

    private void closeAndRemove(ManagedInput m) {
        try {
            m.controller.close(); // 先关闭
        } catch (Throwable ignored) {
        }
        inputs.remove(m.id, m); // 再移除记账
    }

    /** 登记条目：实例 + UUID + 组 id */
    private static final class ManagedInput {
        final UUID id;
        final String groupId;
        final GameInputController controller;

        ManagedInput(UUID id, String groupId, GameInputController controller) {
            this.id = id;
            this.groupId = groupId;
            this.controller = controller;
        }
    }
}
