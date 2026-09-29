/**
 * ===== 配置层 · 核心门面（ConfigLoad，2026-09-06 库化：core 完全不知道子包内容） =====
 *
 * <p>【库语义】本类只属于 core 核心，【不 import / 不引用任何子包配置类】——
 * 子包配置（ConfigCircuit / ConfigPowerGrid / ConfigCryptandSable ...）各自
 * 定义在【子包目录的 config 类】中，由子包自己 {@code register()} 到
 * {@link com.hdf.cryptand.neoforge.core.registry.CryptandRegistries}（core 注册器）。
 * 子包字段经 {@code ConfigXxx.XXX} 直接读取，不再经本类转发。
 *
 * <p>本类仅保留（core 自己的核心配置 + 通用门面）：
 * <ol>
 *   <li>core 自己的核心配置（{@link ConfigCore}：CONFIG_VERSIONS 等）；</li>
 *   <li>配置域文件映射工具（CONFIG_DIR_REL / domainFileName / domainPath）。</li>
 * </ol>
 *
 * <p>★ 2026-09-06 【官方配置模式】全部配置值统一经各子包 Config 类的
 * {@code ModConfigSpec} 常量读取（NeoForge 官方加载/管理，spec.isLoaded() 守卫
 * 类加载期语义）；原手工 TOML 直读（readBool/readInt/readDouble/readString）已删除。
 */

package com.hdf.cryptand.neoforge.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ConfigLoad {
    // ===== core 自己的核心配置（仅 ConfigCore；不引用任何子包配置类） =====
    public static final ModConfigSpec.IntValue CONFIG_VERSIONS = ConfigCore.CONFIG_VERSIONS;

    // ===== 配置域分发：文件路径总管理（纯工具，与子包无关） =====
    /** 配置目录（相对游戏运行目录）。 */
    public static final String CONFIG_DIR_REL = "config/cryptand/";

    /** 配置域 → config/cryptand/ 下文件名。 */
    public static String domainFileName(final String domain) {
        return switch (domain == null ? "" : domain) {
            case "sable" -> "sable.toml";
            case "cryptandsable" -> "cryptand-sable.toml";
            case "cryptandsable-compat" -> "cryptand-sable-compat.toml";
            case "circuit" -> "circuit-simulation.toml";
            case "powergrid" -> "powergrid.toml";
            case "cee" -> "cee.toml";
            case "aero" -> "aeronautics.toml";
            case "pipez" -> "pipez.toml";
            case "simserver" -> "simserver.toml";
            case "gameinput" -> "gameinput.toml";
            case "inc" -> "inc.toml";
            case "waterphysics" -> "waterphysics.toml";
            case "threading" -> "threading.toml";
            case "soc" -> "soc.toml"; // 游戏内 SoC/芯片（2026-09-15）
            case "core" -> "common.toml"; // 核心/通用
            default -> "common.toml";
        };
    }

    // ===== 构造期配置预读（2026-08-30） =====

    /** 预读缓存（域 → 文件内容；一次读盘）。 */
    private static final java.util.Map<String, String> PRELOAD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * ⚠ 2026-08-30 构造期配置预读：子包内容注册（init）发生在【ModConfigEvent 之前】
     * （RegisterRenderers/RegisterEvent 等 MOD 总线事件早于 config 加载）——此时
     * {@code ModConfigSpec.isLoaded()==false}，直接读 spec 会抛/回退默认值 →
     * 配置开关失效（"false 仍加载内容"，且把 init 延迟到 ModConfigEvent 会因
     * 方块/BE 未注册导致客户端 RegisterRenderers 崩溃）。
     * <p>因此构造期的 enabled 判定改读【配置文件文本】（config/cryptand/&lt;file&gt;.toml）：
     * 仅解析顶层 {@code key = true/false}（本项目开关均为顶层布尔）。文件不存在/
     * 键缺失/解析失败 → 返回 {@code def}（默认值）。
     */
    public static boolean preloadBoolean(final String domain, final String key,
                                         final boolean def) {
        try {
            final String content = PRELOAD_CACHE.computeIfAbsent(domain, d -> {
                try {
                    final java.nio.file.Path p = domainPath(d);
                    return java.nio.file.Files.exists(p)
                            ? java.nio.file.Files.readString(p) : "";
                } catch (final Throwable t) {
                    return "";
                }
            });
            if (content.isEmpty()) {
                return def;
            }
            for (final String raw : content.split("\r?\n")) {
                final String s = raw.trim();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("[")) {
                    continue;
                }
                final int eq = s.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                if (!s.substring(0, eq).trim().equals(key)) {
                    continue;
                }
                final String v = s.substring(eq + 1).trim();
                if (v.startsWith("true")) {
                    return true;
                }
                if (v.startsWith("false")) {
                    return false;
                }
                break;
            }
        } catch (final Throwable ignored) {
        }
        return def;
    }

    /** 清除预读缓存（配置重载/测试用）。 */
    public static void clearPreloadCache() {
        PRELOAD_CACHE.clear();
    }

    /** 域对应配置文件路径（相对游戏运行目录）。 */
    public static java.nio.file.Path domainPath(final String domain) {
        return java.nio.file.Path.of(CONFIG_DIR_REL, domainFileName(domain));
    }
}
