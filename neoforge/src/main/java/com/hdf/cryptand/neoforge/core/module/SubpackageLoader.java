/**
 * ===== 子包发现加载器（SubpackageLoader，2026-09-07 v2 注解扫描） =====
 *
 * core 唯一接触子包的入口。v1 曾持【子包入口类全限定名常量表】（字符串）；
 * ★ 2026-09-07 用户定稿："core 不要有任何引用其他子包的行为" + "子包可以定义
 * 一个主类（类似 mod 定义主类），通过注解或消息实现初始化与注册"——
 * 改为【注解扫描发现】：
 *
 * <pre>
 *   子包入口类（实现 SubpackageEntry + public static final INSTANCE）标注
 *   {@code @CryptandSubpackage(id=..., order=...)}（core.api 注解，NeoForge 风格）；
 *   core 构造期扫描【自身 mod 文件的类注解】（ModFileScanData——与 NeoForge
 *   发现 @Mod/@EventBusSubscriber 同一机制）→ 反射实例化 → registerConfigs +
 *   注册进 SubpackageRegistry。core 不持有任何子包名字符串。
 *
 *   删除某子包源码目录 → 其入口类不存在 → 扫描结果无该类 → 自动跳过
 *   → 其余子包与 core 照常编译运行。
 * </pre>
 *
 * 加载时序：构造期（core 注册自身 config 后、consumeConfigs 前）调用；
 * 扫描失败/空 → 警告后跳过（不阻断 core 加载）。
 */
package com.hdf.cryptand.neoforge.core.module;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.core.api.CryptandSubpackage;
import net.neoforged.bus.api.IEventBus;

import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 子包发现加载器（注解扫描；core 编译/运行零子包类引用）。 */
public final class SubpackageLoader {

    private SubpackageLoader() {
    }

    /** 已成功加载的子包 id（注册顺序；仅诊断用）。 */
    private static final List<String> LOADED_IDS = new ArrayList<>();

    /** ⚠ 2026-08-30 子包声明的 Mixin 配置（id → mixin 配置名列表）。
     *  用户："子包全部采用注册方式，类似独立 mod 加载（非真独立）——参考 mixin：
     *  子包主类注册，通过一个函数加载并返回可加载的 mixin 包"。
     *  loadEntry 时收集（{@link SubpackageEntry#mixinConfigs()}）；
     *  {@link #loadableMixinConfigs()} 按 enabled 统一判定并返回。 */
    private static final Map<String, List<String>> MIXIN_CONFIGS = new LinkedHashMap<>();

    /** 已成功加载的子包 id 集合（诊断打印）。 */
    public static synchronized java.util.Set<String> loadedIds() {
        return java.util.Set.copyOf(LOADED_IDS);
    }

    /**
     * 构造期调用（core 注册完自身 config 后、consumeConfigs 前）：
     * 注解扫描全部存在的子包入口 → registerConfigs + 注册进 SubpackageRegistry。
     */
    public static void loadAll() {
        final List<String> fqcns = scanAnnotatedEntryClasses();
        if (fqcns.isEmpty()) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[Subpackage] 注解扫描未发现任何 @CryptandSubpackage 入口（cryptand）");
            return;
        }
        // 按注解 order + 类名稳定排序（加载顺序 = 注册/调度顺序）
        final Map<String, Integer> orderOf = new LinkedHashMap<>();
        final List<String> sorted = new ArrayList<>(fqcns);
        for (final String fqcn : fqcns) {
            try {
                final CryptandSubpackage ann = Class.forName(fqcn)
                        .getAnnotation(CryptandSubpackage.class);
                orderOf.put(fqcn, ann != null ? ann.order() : 1000);
            } catch (final Throwable t) {
                orderOf.put(fqcn, 1000);
            }
        }
        sorted.sort(Comparator
                .comparingInt((String f) -> orderOf.getOrDefault(f, 1000))
                .thenComparing(f -> f));
        for (final String fqcn : sorted) {
            loadEntry(fqcn);
        }
    }

    /** 注解扫描 Cryptand 自身 mod 文件内所有 @CryptandSubpackage(TYPE) 类。 */
    private static List<String> scanAnnotatedEntryClasses() {
        final List<String> out = new ArrayList<>();
        try {
            final net.neoforged.fml.ModList modList = net.neoforged.fml.ModList.get();
            if (modList == null) return out;
            final var info = modList.getModFileById(Cryptand.MOD_ID);
            if (info == null || info.getFile() == null) return out;
            info.getFile().getScanResult()
                    .getAnnotatedBy(CryptandSubpackage.class, ElementType.TYPE)
                    .forEach(ad -> out.add(ad.clazz().getClassName()));
        } catch (final Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[Subpackage] 注解扫描失败（回退：无子包加载）: {}", t.toString());
        }
        return out;
    }

    /**
     * ⚠ 2026-08-30 统一加载函数（用户："通过一个函数进行加载包括 mixin 可加载的包
     * 返回等，类似独立 mod 创建，但不是真独立"）：
     * 返回【当前可加载的 Mixin 配置列表】——仅包含 enabled（启用条件为真）的
     * 子包所声明的 mixin 配置（子包主类 {@link SubpackageEntry#mixinConfigs()}）。
     * 供 mixin 框架 / 诊断 / 未来按子包动态装配使用。
     */
    public static List<String> loadableMixinConfigs() {
        // ⚠ 2026-08-30 统一由 common ModuleRegistry 收集/判定（依赖+启用）
        return SubpackageRegistry.loadableMixinConfigs();
    }

    /** 已加载子包声明的全部 Mixin 配置（id → 配置列表；诊断用，不判定 enabled）。 */
    public static Map<String, List<String>> declaredMixinConfigs() {
        return SubpackageRegistry.declaredMixinConfigs();
    }

    /** 加载单个入口类（注册 config + 注册进 SubpackageRegistry）。 */
    private static void loadEntry(final String fqcn) {
        try {
            final Class<?> clazz = Class.forName(fqcn);
            final CryptandSubpackage ann = clazz.getAnnotation(CryptandSubpackage.class);
            final java.lang.reflect.Field inst = clazz.getField("INSTANCE");
            final SubpackageEntry entry = (SubpackageEntry) inst.get(null);
            if (entry == null) {
                return;
            }
            final String id = ann != null && !ann.id().isBlank()
                    ? ann.id() : fqcn.substring(fqcn.lastIndexOf('.') + 1);
            try {
                entry.registerConfigs();
            } catch (final Throwable t) {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                        "[Subpackage] {} registerConfigs failed: {}", id, t.toString());
            }
            // ⚠ 2026-08-30 类 mod 注册：依赖表（dependencies）+ Mixin 配置（mixinConfigs）
            // 一并交给 common 通用加载器（ModuleRegistry）——由它做拓扑排序/循环依赖
            // 检测/传递禁用，并统一返回可加载 mixin 配置。
            SubpackageRegistry.register(id, entry.conditionDesc(), entry::enabled,
                    entry.dependencies(), entry::init, entry::tick,
                    entry::commonSetup, entry::commands, entry.mixinConfigs());
            synchronized (LOADED_IDS) {
                LOADED_IDS.add(id);
            }
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[Subpackage] loaded entry {} -> {}", id, fqcn);
        } catch (final Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[Subpackage] entry load failed {}: {}", fqcn, t.toString());
        }
    }
}
