package com.hdf.cryptand.neoforge.cryptandsable.core.engine;

import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.core.allocator.SableBatchScheduler;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineApi;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineDispatchRule;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineManager;
import com.hdf.cryptand.neoforge.cryptandsable.core.entity.PhysicalStructure;
import com.hdf.cryptand.neoforge.cryptandsable.core.entity.StructureRegistry;

/**
 * 核心引擎（CoreEngine）—— 计算核心批处理流程整合（2026-09-01 V2 架构）。
 *
 * <p><b>职责：</b>批处理推送实现地。
 * <ul>
 *   <li>结构注册表 + 引擎当前实例交互；</li>
 *   <li><b>批处理推送</b>：结构组切分 chunk → 并行 submit → 每 chunk 打包数据 → 引擎 step；</li>
 *   <li>结果解包（fetchState）→ 回传（下游消息下行）。</li>
 * </ul>
 *
 * <p><b>JNI 边界</b>：引擎只暴露交互接口（uploadPoseBatch/stepBatch/fetchState）——
 * 批处理全部在本类实现。
 */
public final class CoreEngine {

    private final StructureRegistry structures;
    private final SableBatchScheduler scheduler;
    private final EngineManager engineManager;

    public CoreEngine(StructureRegistry structures, SableBatchScheduler scheduler, EngineManager engineManager) {
        this.structures = structures != null ? structures : new StructureRegistry();
        this.scheduler = scheduler != null ? scheduler : new SableBatchScheduler();
        this.engineManager = engineManager != null ? engineManager : EngineManager.instance();
    }

    public StructureRegistry structures() { return this.structures; }
    public SableBatchScheduler scheduler() { return this.scheduler; }
    public EngineManager engineManager() { return this.engineManager; }

    /**
     * 启动（加载默认 f64 引擎并初始化）。
     */
    public void start(double gravityX, double gravityY, double gravityZ, double universalDrag) {
        EngineApi engine = this.engineManager.loadDefault();
        if (engine != null) {
            engine.initialize(gravityX, gravityY, gravityZ, universalDrag);
        }
    }

    /**
     * 动态多引擎分发启动（config 开启时；替换单活 loadDefault 的底层引擎获取）。
     * 同时装载全部子引擎并逐个 initialize；结构侧路由由 EngineManager.activeDispatcher()
     * 的 bindScene 完成（见 CryptandSable.importBody）。
     */
    public void startDynamic(double gravityX, double gravityY, double gravityZ, double universalDrag) {
        EngineApi engine = this.engineManager.loadDynamic(dispatchEngineIds(), dispatchRule());
        if (engine != null) {
            engine.initialize(gravityX, gravityY, gravityZ, universalDrag);
        }
    }

    /** 本次要同时装载的引擎 id 列表（来自 config；逗号分隔；空 → 默认 f64）。 */
    private java.util.List<String> dispatchEngineIds() {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        String raw = ConfigCryptandSable.SABLE_ENGINE_LIST.get();
        if (raw != null) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) ids.add(t);
            }
        }
        if (ids.isEmpty()) ids.add("rapier-f64");
        return ids;
    }

    /** 分发路由规则：按坐标阈值（小坐标→低精度，大坐标→高精度）或全部走默认引擎。 */
    private EngineDispatchRule dispatchRule() {
        if (ConfigCryptandSable.SABLE_ENGINE_COORD_DISPATCH.get()) {
            return new EngineDispatchRule.CoordThresholdRule(
                    ConfigCryptandSable.SABLE_ENGINE_LOW_PRECISION_ID.get(),
                    ConfigCryptandSable.SABLE_ENGINE_HIGH_PRECISION_ID.get(),
                    ConfigCryptandSable.SABLE_ENGINE_COORD_THRESHOLD.get());
        }
        return new EngineDispatchRule.SingleIdRule(dispatchEngineIds().get(0));
    }

    /**
     * 停止（卸载引擎）。
     */
    public void stop() {
        this.engineManager.unloadAll();
    }

    /**
     * 登记物理结构（structureId 入注册表 + 环境缓存刷新；纯数据）。
     */
    public void registerStructure(PhysicalStructure structure) {
        if (structure == null) return;
        structure.refreshCaches();
        this.structures.register(structure);
    }

    /**
     * 批量步进（批处理推送 → 引擎 step）：
     * 结构组按 scene 分区切 chunk → 并行处理 → 每 chunk 经引擎 stepBatch。
     *
     * @param substeps 子步数
     * @param dt       子步长
     */
    public void stepBatch(int substeps, double dt) {
        EngineApi engine = this.engineManager.active();
        if (engine == null || this.structures.size() == 0) return;
        final int[] sceneIds = this.structures.sceneIds();
        // 批处理：按 scene 分区 + 切 chunk → 并行 submit → 每 chunk 打包 step
        this.scheduler.dispatch(sceneIds, chunk -> {
            for (int idx : chunk) {
                int sceneId = sceneIds[idx];
                // 每 chunk 统一步进（打包 per scene）
                engine.stepBatch(sceneId, substeps, dt);
            }
        });
    }

    /**
     * 拉回状态（引擎 fetch → 结果数据）。MVP 简化：返回全部结构位姿快照数组
     * [idCount*7]（引擎 fetch 后续接入 batch 拉回）。
     */
    public double[] fetchState() {
        EngineApi engine = this.engineManager.active();
        if (engine == null) return new double[0];
        double[] state = engine.fetchState(0L);
        return state != null ? state : new double[0];
    }

    /**
     * 批量上传位姿（结构打包 → 引擎 uploadPoseBatch）。
     */
    public void uploadPoses() {
        EngineApi engine = this.engineManager.active();
        if (engine == null || this.structures.size() == 0) return;
        final java.util.List<PhysicalStructure> all = this.structures.all();
        final int n = all.size();
        int[] ids = new int[n];
        double[] pose = new double[n * 7];
        double[] vel = new double[n * 6];
        for (int i = 0; i < n; i++) {
            PhysicalStructure s = all.get(i);
            ids[i] = s.runtimeId();
            pose[i * 7] = s.position().x; pose[i * 7 + 1] = s.position().y; pose[i * 7 + 2] = s.position().z;
            pose[i * 7 + 3] = s.orientation().x; pose[i * 7 + 4] = s.orientation().y;
            pose[i * 7 + 5] = s.orientation().z; pose[i * 7 + 6] = s.orientation().w;
            vel[i * 6] = s.linearVelocity().x; vel[i * 6 + 1] = s.linearVelocity().y; vel[i * 6 + 2] = s.linearVelocity().z;
            vel[i * 6 + 3] = s.angularVelocity().x; vel[i * 6 + 4] = s.angularVelocity().y; vel[i * 6 + 5] = s.angularVelocity().z;
        }
        // 单次批量上传（核心已排好序）
        engine.uploadPoseBatch(0L, ids, pose, vel);
    }
}
