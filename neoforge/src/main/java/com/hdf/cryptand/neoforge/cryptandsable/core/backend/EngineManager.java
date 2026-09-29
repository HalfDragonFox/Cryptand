package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

import com.hdf.cryptand.neoforge.CryptandNeoForge;

import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 引擎管理器（EngineManager）—— 引擎动态加载/卸载/切换（2026-09-01 V2 架构）。
 *
 * <p>计算核心通过 {@link #active()} 拿到当前引擎；引擎切换 = 卸载旧 DLL → 加载新 DLL
 * （不同引擎/精度）。换引擎不换上游（结构/消费层无感——数据结构统一为数组打包）。
 *
 * <p>默认：f64·Rapier（{@link #DEFAULT_ENGINE_ID}）。
 * 支持后端：f64（默认）/ f32（性能）/ 其他（后续扩展）。
 *
 * <p>线程安全：切换期间核心 worker 查询阻塞（读写锁），保证引擎生命周期一致。
 */
public final class EngineManager {

    /** 默认引擎 id（f64）。 */
    public static final String DEFAULT_ENGINE_ID = "rapier-f64";

    private static final EngineManager INSTANCE = new EngineManager();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private EngineApi active;
    private EngineApi fallback; // 纯 Java 仿真兜底（native 缺失时）

    private EngineManager() {
    }

    public static EngineManager instance() {
        return INSTANCE;
    }

    /**
     * 启动时加载默认引擎（f64·Rapier；失败回退 f32 → 纯 Java 仿真）。
     *
     * @return 加载的引擎（绝不返回 null —— 兜底纯 Java 仿真）
     */
    public EngineApi loadDefault() {
        return this.switchTo(DEFAULT_ENGINE_ID);
    }

    /**
     * 切换到指定引擎（卸载旧 → 加载新）。
     * 若新引擎加载失败：保持旧引擎不动（若旧引擎存在）；否则回退默认。
     *
     * @param engineId 引擎标识（"rapier-f64"/"rapier-f32"/其他扩展）
     * @return 当前激活引擎（失败时可能仍是旧/兜底）
     */
    public EngineApi switchTo(final String engineId) {
        this.lock.writeLock().lock();
        try {
            // 若是当前引擎 → 直接返回
            EngineApi cur = this.active;
            if (cur != null && cur.info() != null && engineId.equals(cur.info().id())) {
                return cur;
            }
            // 构建新引擎（DLL 加载失败 → null）
            EngineApi next = create(engineId);
            if (next == null) {
                if (cur == null && this.fallback == null) {
                    this.fallback = new SimulationEngineFallback();
                    next = this.fallback;
                } else {
                    next = cur != null ? cur : this.fallback;
                }
            } else {
                // 加载成功：卸载旧引擎（若存在且不同）
                if (cur != null && cur != next) {
                    try {
                        cur.dispose();
                    } catch (Throwable ignored) {
                        // 卸载失败不阻塞切换
                    }
                }
            }
            this.active = next;
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] engine switched to {}", next != null ? next.info() : "none");
            return next;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * 当前激活引擎（核心 worker 查询；读锁）。
     */
    public EngineApi active() {
        this.lock.readLock().lock();
        try {
            return this.active;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
     * 构造指定引擎（DLL 自动加载）。
     *
     * @return 引擎；加载失败返回 null
     */
    private EngineApi create(final String engineId) {
        switch (engineId) {
            case "rapier-f64" -> {
                // 先加载 f64 DLL（资源路径约定见 SableNativeLoader）
                boolean ok = SableNativeLoader.load(true);
                if (!ok) {
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[CryptandSable] f64 DLL load failed -> try f32");
                    return create("rapier-f32");
                }
                return new RapierF64Backend();
            }
            case "rapier-f32" -> {
                boolean ok = SableNativeLoader.load(false);
                if (!ok) return null;
                return new RapierF32Backend();
            }
            default -> {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] unknown engine id: {} (fallback default)", engineId);
                return create(DEFAULT_ENGINE_ID);
            }
        }
    }

    /**
     * 动态多引擎分发模式：同时装载 {@code engineIds} 全部引擎并构建分发器作为 active。
     *
     * <p>2026-09-02：替换单活引擎的底层获取接口（config 开启时由 CoreEngine.startDynamic
     * 调用）。关闭时单活路径（loadDefault/switchTo/active）完全不触碰。全部分发装载失败
     * → 回退单活默认引擎；部分失败 → 分发器只含成功的引擎，路由回退其默认引擎。
     *
     * @param engineIds 本次要装载的引擎 id 列表（非空；首个可用者为默认）
     * @param rule      分发路由规则（坐标阈值等）
     * @return 当前激活引擎（分发器；失败回退单活默认）
     */
    public EngineApi loadDynamic(List<String> engineIds, EngineDispatchRule rule) {
        if (engineIds == null || engineIds.isEmpty()) return loadDefault();
        this.lock.writeLock().lock();
        try {
            EngineApi cur = this.active;
            EngineDispatcher d = EngineDispatcher.create(engineIds, rule);
            if (d.engineCount() == 0) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] no engine loaded for dispatch -> fallback single");
                return loadDefault();
            }
            if (cur != null && cur != d) {
                try {
                    cur.dispose();
                } catch (Throwable ignored) {
                    // 卸载失败不阻塞切换
                }
            }
            this.active = d;
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] dynamic engine dispatch active: {}", d.info());
            return this.active;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * 若当前激活引擎是分发器 → 返回它；否则 null（用于结构级按坐标绑定）。
     */
    public EngineDispatcher activeDispatcher() {
        EngineApi a = this.active();
        return a instanceof EngineDispatcher d ? d : null;
    }

    /**
     * 通知引擎 DLL 卸载（世界卸载时调用；当前引擎置 null）。
     */
    public void unloadAll() {
        this.lock.writeLock().lock();
        try {
            EngineApi cur = this.active;
            if (cur != null) {
                try {
                    cur.dispose();
                } catch (Throwable ignored) {
                }
            }
            this.active = null;
            this.fallback = null;
        } finally {
            this.lock.writeLock().unlock();
        }
    }
}
