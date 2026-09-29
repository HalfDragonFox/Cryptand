package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

import com.hdf.cryptand.neoforge.CryptandNeoForge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 引擎注册表（EngineRegistry）—— 引擎 id → 引擎规格/门面 的可插拔注册中心（2026-09-02）。
 *
 * <p>每个引擎 = EngineSpec(id, precision, jniClassName)：
 * <ul>
 *   <li>id：如 rapier-f64 / rapier-f32 / box3d（用户提到的其他引擎，可自行注册）；</li>
 *   <li>precision：F64 / F32（{@link EngineApi.Precision}）；</li>
 *   <li>jniClassName：该引擎 native 门面的 Java 类全名。JVM 按「类名.方法名」在已
 *       System.load 的库中解析 native —— <b>同 jniClassName 的两引擎不能同时 native
 *       激活</b>（第二次 load 同名符号不重新绑定）。构建 dispatcher 时会检测并降级。</li>
 * </ul>
 *
 * <p>内置 rapier-f64 / rapier-f32（都走官方 Rapier3D 门面）。后续新增独立门面引擎
 * （如 Rust 侧导出 Rapier3DF64 / Box3D 等独立 JNI 符号 + 独立 Java 门面类）直接
 * {@link #register(String, EngineApi.Precision, String)}，即可同进程真正同时加载。
 */
public final class EngineRegistry {

    /** CryptandSable 独立 JNI 门面类（f32/f64 共享同一套导出符号；2026-09-06 符号重编后）。 */
    public static final String RAPIER_JNI_CLASS =
            "CryptandRapierNative";

    private static final EngineRegistry INSTANCE = new EngineRegistry();

    private final Map<String, EngineSpec> specs = new LinkedHashMap<>();

    /** 引擎规格（不可变）。 */
    public record EngineSpec(String id, EngineApi.Precision precision, String jniClassName) {
        @Override
        public String toString() {
            return "engine[" + this.id + "|" + this.precision + "|jni " + this.jniClassName + "]";
        }
    }

    private EngineRegistry() {
    }

    public static EngineRegistry instance() {
        return INSTANCE;
    }

    /** 注册引擎规格（幂等：同 id 覆盖）。 */
    public synchronized EngineRegistry register(String id, EngineApi.Precision precision,
                                                String jniClassName) {
        this.specs.put(id, new EngineSpec(id, precision, jniClassName));
        return this;
    }

    /** 查引擎规格；未知 id → null。 */
    public synchronized EngineSpec spec(String id) {
        return this.specs.get(id);
    }

    /** 已注册 id 集合。 */
    public synchronized Set<String> ids() {
        return Set.copyOf(this.specs.keySet());
    }

    /**
     * 判断两引擎是否共享同一 native 门面类。
     * 同 jniClassName（且非同一 id）→ 不能同时 native 激活。
     */
    public boolean sharesJniClass(String idA, String idB) {
        EngineSpec a = spec(idA);
        EngineSpec b = spec(idB);
        if (a == null || b == null) return false;
        return a.id().equals(idA) && b.id().equals(idB)
                && a.jniClassName().equals(b.jniClassName())
                && !a.id().equals(b.id());
    }

    /**
     * 创建引擎后端实例（自动加载对应 DLL）。
     *
     * @return 引擎实例；加载失败/未知 id → null（调用方回退）
     */
    public EngineApi createBackend(String id) {
        EngineSpec spec = spec(id);
        if (spec == null) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] unknown engine id: {} (registered: {})", id, ids());
            return null;
        }
        return switch (spec.id()) {
            case "rapier-f64" -> {
                boolean ok = SableNativeLoader.load(true);
                if (!ok) yield null;
                yield new RapierF64Backend();
            }
            case "rapier-f32" -> {
                boolean ok = SableNativeLoader.load(false);
                if (!ok) yield null;
                yield new RapierF32Backend();
            }
            default -> {
                // 自定义引擎：只有规格没有对应后端工厂 → 无法创建（日志提示）
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] engine {} has no backend factory yet (spec={})",
                        spec.id(), spec);
                yield null;
            }
        };
    }

    // 内置规格：f64 / f32（共享官方 Rapier3D 门面）
    static {
        INSTANCE.register("rapier-f64", EngineApi.Precision.F64, RAPIER_JNI_CLASS);
        INSTANCE.register("rapier-f32", EngineApi.Precision.F32, RAPIER_JNI_CLASS);
    }
}