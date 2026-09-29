package com.hdf.cryptand.lod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ===== LOD 后端注册表（common，纯 Java 零 MC）=====
 *
 * <p>2026-09-29 用户定案：</p>
 * <ul>
 *   <li>「lod 后端有<b>自实现兜底</b>」⇒ 内置后端 {@link LodBackendBuiltin}（id {@code builtin}）
 *       在构造时自动登记，且永远是链条最后一项；</li>
 *   <li>「配置可以配置<b>后端链条</b>，<b>优先使用第一有效</b>」⇒ {@link #setChain(List)} 给出有序链，
 *       按链顺序挑选第一个「已登记 + available + 支持该方向 + 返回非 null」的后端；
 *       链里写了但没登记 / 不可用 / 不支持 / 返回 null ⇒ <b>跳过并记日志</b>（回退必须可见）。</li>
 * </ul>
 *
 * <p>日志出口是 {@link Consumer}：common 不许依赖 MC/日志框架。</p>
 */
public final class LodBackendRegistry {

    /** 内置兜底后端 id（链条里永远垫底）。 */
    public static final String BUILTIN = "builtin";

    private final Map<String, LodBackend> backends = new LinkedHashMap<>();
    private List<String> chain = List.of(BUILTIN);
    private Consumer<String> log = message -> {
    };

    public LodBackendRegistry() {
        // 自实现兜底：构造即登记，外部不需要（也不应该）提供。
        register(new LodBackendBuiltin());
    }

    /** 日志出口（MC 侧接自己的 Logger；不设就丢弃 —— 但回退语义仍然成立）。 */
    public void setLogger(Consumer<String> logger) {
        if (logger != null) {
            this.log = logger;
        }
    }

    /**
     * 配置后端链条（有序）。允许写"尚未登记"的名字（例如来自可选依赖的后端）：
     * 运行期遇到时会跳过并记日志，不影响后面的项 —— 这就是"优先使用第一有效"。
     * 空/全空 ⇒ 只有内置兜底。
     */
    public void setChain(List<String> names) {
        if (names == null || names.isEmpty()) {
            log.accept("[lod] 后端链为空，只用内置兜底：" + BUILTIN);
            this.chain = List.of(BUILTIN);
            return;
        }
        final List<String> cleaned = new ArrayList<>();
        for (final String raw : names) {
            final String name = raw == null ? "" : raw.trim();
            if (!name.isEmpty() && !cleaned.contains(name)) {
                cleaned.add(name);
            }
        }
        if (!cleaned.contains(BUILTIN)) {
            // 兜底永远垫底：链条尾部一定有可用实现。
            cleaned.add(BUILTIN);
        }
        this.chain = List.copyOf(cleaned);
        log.accept("[lod] 后端链：" + String.join(" -> ", this.chain));
    }

    /** 配置写法（逗号分隔）：flywheel,voxy,builtin。 */
    public void setChainCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            setChain(List.of());
            return;
        }
        setChain(List.of(csv.split(",")));
    }

    /** 当前链条（含兜底，配置顺序）。 */
    public List<String> chain() {
        return chain;
    }

    /** 登记（同 id 覆盖为最新，与像素通道表的同名覆盖语义一致）。 */
    public void register(LodBackend backend) {
        if (backend == null || backend.name() == null) {
            return;
        }
        backends.put(backend.name(), backend);
    }

    public LodBackend get(String name) {
        return backends.get(name);
    }

    /** 已登记的后端（登记顺序）。 */
    public List<LodBackend> all() {
        return new ArrayList<>(backends.values());
    }

    /** 当前可用的后端名（日志/观测用）。 */
    public List<String> availableNames() {
        final List<String> names = new ArrayList<>();
        for (final LodBackend b : backends.values()) {
            if (b.available()) {
                names.add(b.name());
            } else {
                log.accept("[lod] 后端不可用，跳过：" + b.name() + " " + b.capabilities());
            }
        }
        return names;
    }

    /**
     * 2D 步长覆盖：按链条顺序找第一个"愿意覆盖"的后端（返回 &gt; 0）；都不覆盖 ⇒ 返回 -1（用阶梯表）。
     *
     * <p>"优先使用第一有效"在这里同样成立：链首的外部后端先问，它不覆盖才轮到后面的；
     * 内置兜底不会覆盖，所以"外部后端全无效"时行为与只用阶梯表完全一致。</p>
     */
    public int overrideStep2D(int stairsStep, int pixelsW, int pixelsH, int blocksW, int blocksH,
                              double distanceBlocks) {
        for (final String name : chain) {
            final LodBackend b = backends.get(name);
            if (b == null) {
                log.accept("[lod] 2D 步长覆盖：链中后端未登记，跳过：" + name);
                continue;
            }
            if (!b.available()) {
                log.accept("[lod] 2D 步长覆盖：链中后端不可用，跳过：" + name);
                continue;
            }
            if (!b.capabilities().supports2D()) {
                continue;
            }
            final int step = b.overrideStep2D(stairsStep, pixelsW, pixelsH, blocksW, blocksH, distanceBlocks);
            if (step > 0) {
                log.accept("[lod] 2D 步长由后端覆盖：" + name + " 阶梯=" + stairsStep + " ⇒ " + step);
                return step;
            }
        }
        return -1;
    }

    /** 2D 档位：按链条顺序取第一个有效后端；链走完 ⇒ 内置兜底 + 日志。 */
    public LodLevel level2D(Lod2DRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null（没有请求就没有档位）");
        }
        for (final String name : chain) {
            final LodBackend b = backends.get(name);
            if (b == null) {
                log.accept("[lod] 2D 链中后端未登记，跳过：" + name);
                continue;
            }
            if (!b.available()) {
                log.accept("[lod] 2D 链中后端不可用，跳过：" + name);
                continue;
            }
            if (!b.capabilities().supports2D()) {
                log.accept("[lod] 2D 链中后端不支持 2D，跳过：" + name);
                continue;
            }
            final LodLevel level = b.level2D(request);
            if (level == null) {
                log.accept("[lod] 2D 链中后端自称支持却返回 null，跳过：" + name);
                continue;
            }
            if (BUILTIN.equals(name) && chain.size() > 1) {
                // 回退必须可见：链条里还有外部后端却轮到兜底 ⇒ 汇总一条（链路诊断的关键线索）。
                log.accept("[lod] 2D 使用内置兜底（链中外部后端均无效）");
            }
            return level;
        }
        log.accept("[lod] 2D 后端链全部无效，用内置兜底（Lod2D.level）");
        return Lod2D.level(request);
    }

    /** 3D 档位：按链条顺序取第一个有效后端；链走完 ⇒ 内置兜底 + 日志。 */
    public LodLevel level3D(Lod3DRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null（没有请求就没有档位）");
        }
        for (final String name : chain) {
            final LodBackend b = backends.get(name);
            if (b == null) {
                log.accept("[lod] 3D 链中后端未登记，跳过：" + name);
                continue;
            }
            if (!b.available()) {
                log.accept("[lod] 3D 链中后端不可用，跳过：" + name);
                continue;
            }
            if (!b.capabilities().supports3D()) {
                log.accept("[lod] 3D 链中后端不支持 3D，跳过：" + name);
                continue;
            }
            final LodLevel level = b.level3D(request);
            if (level == null) {
                log.accept("[lod] 3D 链中后端自称支持却返回 null，跳过：" + name);
                continue;
            }
            if (BUILTIN.equals(name) && chain.size() > 1) {
                // 回退必须可见（同 2D 口径）。
                log.accept("[lod] 3D 使用内置兜底（链中外部后端均无效）");
            }
            return level;
        }
        log.accept("[lod] 3D 后端链全部无效，用内置兜底（Lod3D.level）");
        return Lod3D.level(request);
    }
}