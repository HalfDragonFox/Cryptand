package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

import com.hdf.cryptand.neoforge.CryptandNeoForge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引擎分发器（EngineDispatcher）—— 多引擎同时装载 + 按规则把参数路由到不同引擎/JNI（2026-09-02）。
 *
 * <p>动态引擎分发：本类替换「单活引擎」的底层获取接口（config 开启时由
 * {@link EngineManager#loadDynamic} 构建并作为 active 使用；config 关闭 → 完全不介入
 * 原有单活逻辑）。
 *
 * <p>能力：
 * <ul>
 *   <li><b>同时装载多引擎</b>：多台 {@link EngineApi}（f32/f64/自定义）全部 initialize，
 *       各自持独立 native scene；</li>
 *   <li><b>门面互斥检测</b>：两引擎共享同一 JNI 门面类（当前 f32/f64 都走 Rapier3D）时，
 *       JVM 只会把 native 符号绑定到先加载的库 → 后者标记 unavailable，路由回退（默认 id）；</li>
 *   <li><b>按坐标路由</b>：{@link #bindScene} 或用 pose 坐标（{@link #uploadPoseBatch}）
 *       把刚体分配到目标引擎（小坐标→F32，大坐标→F64；规则可插拔）；</li>
 *   <li><b>对接多 JNI 接口</b>：每个引擎 = 一个 id + 一个 jniClassName 门面——独立门面的
 *       引擎（未来 Box3D 等）可真正同进程同时 native。</li>
 * </ul>
 *
 * <p>线程安全：路由映射为并发容器；引擎实例生命周期由上层（EngineManager）写锁保证。
 */
public final class EngineDispatcher implements EngineApi {

    private final EngineDispatchRule rule;
    private final String defaultEngineId;
    private final String idLabel;
    /** 引擎 id → 已装载引擎（只含成功创建的）。 */
    private final Map<String, EngineApi> engines = new LinkedHashMap<>();
    /** 因 JNI 门面互斥/加载失败而未 native 激活的 id（路由回退到 default）。 */
    private final java.util.Set<String> unavailable = ConcurrentHashMap.newKeySet();
    /** scene 绑定：核心 sceneId → 引擎 id（结构在导入/注册时按坐标绑定）。 */
    private final ConcurrentHashMap<Integer, String> sceneBindings = new ConcurrentHashMap<>();

    private EngineDispatcher(EngineDispatchRule rule, String defaultEngineId, String idLabel,
                             Map<String, EngineApi> engines, java.util.Set<String> unavailable) {
        this.rule = rule;
        this.defaultEngineId = defaultEngineId;
        this.idLabel = idLabel;
        this.engines.putAll(engines);
        this.unavailable.addAll(unavailable);
    }

    /**
     * 构建分发器并同时装载给定引擎 id 列表。
     * <p>同列表内共享同一 JNI 门面的引擎只装载第一个（其余 unavailable + 日志）；
     * 全部装载失败 → {@link #engineCount()} 为 0（调用方据此回退单活/纯 Java）。
     *
     * @param engineIds 本次要装载的引擎 id 列表（来自 config；顺序有意义——首个可用者为默认）
     * @param rule      路由规则（坐标阈值等）
     */
    public static EngineDispatcher create(List<String> engineIds, EngineDispatchRule rule) {
        Map<String, EngineApi> engines = new LinkedHashMap<>();
        java.util.Set<String> unavailable = ConcurrentHashMap.newKeySet();
        EngineRegistry reg = EngineRegistry.instance();
        String firstId = null;

        if (engineIds != null) {
            for (String id : engineIds) {
                if (id == null || id.isBlank()) continue;
                if (engines.containsKey(id) || unavailable.contains(id)) continue;
                // 门面互斥：与先前已装载引擎共享 JNI 门面 → 不能同时 native
                boolean exclusive = false;
                for (String loaded : engines.keySet()) {
                    if (reg.sharesJniClass(loaded, id)) {
                        exclusive = true;
                        break;
                    }
                }
                if (exclusive) {
                    unavailable.add(id);
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[CryptandSable] engine {} shares JNI facade with an already loaded "
                                    + "engine -> native unavailable this session (needs independent "
                                    + "JNI facade class); routing falls back to {}", id, firstId);
                    continue;
                }
                EngineApi engine = reg.createBackend(id);
                if (engine != null) {
                    engines.put(id, engine);
                    if (firstId == null) firstId = id;
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[CryptandSable] dispatch engine loaded: {}", engine.info());
                } else {
                    unavailable.add(id);
                }
            }
        }
        String defaultId = firstId != null ? firstId : "none";
        String label = "dispatcher[" + String.join(",", engineIds) + "]";
        return new EngineDispatcher(rule, defaultId, label, engines, unavailable);
    }

    /** 已成功装载的引擎数。 */
    public int engineCount() {
        return this.engines.size();
    }

    /** 全部已装载引擎。 */
    public Collection<EngineApi> engines() {
        return List.copyOf(this.engines.values());
    }

    /** 按 id 取引擎（未知/不可用 → 默认引擎）。 */
    public EngineApi engine(String id) {
        return this.engines.get(resolveId(id));
    }

    /** 指定 id 是否 native 激活（false=被门面互斥/加载失败，路由回退）。 */
    public boolean isNativeActive(String id) {
        return this.engines.containsKey(id);
    }

    /**
     * 显式把 scene 绑定到目标引擎（结构导入/注册时按坐标调用）。
     * 未显式绑定的 scene 使用默认引擎。
     */
    public void bindScene(int sceneId, double x, double y, double z) {
        if (sceneId <= 0) return;
        String id = resolveId(this.rule.selectEngineId(x, y, z));
        this.sceneBindings.put(sceneId, id);
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] dispatch bind scene={} @({},{},{}) -> {}", sceneId, x, y, z, id);
    }

    /** scene → 引擎 id（未绑定/0 → 默认）。 */
    private String engineIdForScene(long sceneHandle) {
        if (sceneHandle == 0L) return this.defaultEngineId;
        String id = this.sceneBindings.get((int) sceneHandle);
        return id != null ? id : this.defaultEngineId;
    }

    /** 规则产出 id → 真实装载引擎 id（unknown/unavailable → 默认引擎）。 */
    private String resolveId(String raw) {
        if (raw == null) return this.defaultEngineId;
        if (this.engines.containsKey(raw)) return raw;
        return this.defaultEngineId;
    }

    @Override
    public EngineInfo info() {
        EngineApi def = this.engines.get(this.defaultEngineId);
        EngineApi.Precision p = def != null ? def.info().precision() : EngineApi.Precision.F64;
        StringBuilder sb = new StringBuilder(this.idLabel);
        for (Map.Entry<String, EngineApi> e : this.engines.entrySet()) {
            sb.append("; ").append(e.getKey()).append("=").append(e.getValue().info());
        }
        if (!this.unavailable.isEmpty()) {
            sb.append("; unavailable=").append(this.unavailable);
        }
        return new EngineInfo(this.idLabel, p, sb.toString());
    }

    @Override
    public void initialize(double gravityX, double gravityY, double gravityZ, double universalDrag) {
        for (EngineApi e : this.engines.values()) {
            try {
                e.initialize(gravityX, gravityY, gravityZ, universalDrag);
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] dispatcher init {} failed: {}", e.info(), t.toString());
            }
        }
    }

    @Override
    public void stepBatch(long sceneHandle, int substeps, double dt) {
        EngineApi e = this.engines.get(engineIdForScene(sceneHandle));
        if (e != null) e.stepBatch(sceneHandle, substeps, dt);
    }

    /** 批量上传位姿/速度：按每个刚体的 pose 坐标路由到目标引擎 → 各自批量上传（JNI 值数组不变）。 */
    @Override
    public void uploadPoseBatch(long sceneHandle, int[] runtimeIds, double[] pose, double[] velocities) {
        if (runtimeIds == null || runtimeIds.length == 0) return;
        Map<String, List<Integer>> byEngine = new LinkedHashMap<>();
        for (int i = 0; i < runtimeIds.length; i++) {
            double x = pose != null ? pose[i * 7] : 0;
            double y = pose != null ? pose[i * 7 + 1] : 0;
            double z = pose != null ? pose[i * 7 + 2] : 0;
            String id = resolveId(this.rule.selectEngineId(x, y, z));
            byEngine.computeIfAbsent(id, k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<Integer>> en : byEngine.entrySet()) {
            EngineApi e = this.engines.get(en.getKey());
            if (e == null) continue;
            List<Integer> idxs = en.getValue();
            int n = idxs.size();
            int[] subIds = new int[n];
            double[] subPose = new double[n * 7];
            double[] subVel = new double[n * 6];
            for (int k = 0; k < n; k++) {
                int i = idxs.get(k);
                subIds[k] = runtimeIds[i];
                if (pose != null) System.arraycopy(pose, i * 7, subPose, k * 7, 7);
                if (velocities != null) System.arraycopy(velocities, i * 6, subVel, k * 6, 6);
            }
            e.uploadPoseBatch(0L, subIds, subPose, subVel);
        }
    }

    @Override
    public void bakeChunkBatch(long sceneHandle, int[] sectionPositions, int[] data) {
        EngineApi e = this.engines.get(engineIdForScene(sceneHandle));
        if (e != null) e.bakeChunkBatch(sceneHandle, sectionPositions, data);
    }

    /** 拉回状态：默认引擎优先；无内容则取其他引擎（MVP 合并；核心后续可接入多引擎聚合）。 */
    @Override
    public double[] fetchState(long sceneHandle) {
        EngineApi def = this.engines.get(this.defaultEngineId);
        if (def != null) {
            double[] s = def.fetchState(sceneHandle);
            if (s != null && s.length > 0) return s;
        }
        for (EngineApi e : this.engines.values()) {
            if (e == def) continue;
            double[] s = e.fetchState(sceneHandle);
            if (s != null && s.length > 0) return s;
        }
        return new double[0];
    }

    /** 释放全部子引擎。 */
    @Override
    public void dispose() {
        for (EngineApi e : this.engines.values()) {
            try {
                e.dispose();
            } catch (Throwable ignored) {
            }
        }
        this.engines.clear();
        this.sceneBindings.clear();
        this.unavailable.clear();
    }
}