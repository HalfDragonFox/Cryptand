/**
 * ★ 子包配置接入接口（2026-09-06，用户模块化配置）
 *
 * 【用户需求】"子包带 config 文件夹和定义子配置，初始化时注册到 core 核心部分的
 * config 注册器"。子包配置类实现本接口（域 id + 文件名 + ModConfigSpec 三要素），
 * core 的 {@link CryptandRegistries#registerConfig} 消费后统一在模组构造期注册
 * 到 ModContainer（config/cryptand/&lt;fileName&gt;）。
 *
 * <p>示例：
 * <pre>{@code
 * public final class ConfigCryptandSable implements CryptandConfigSpec {
 *     public static final String DOMAIN = "cryptandsable";
 *     private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();
 *     public static final ModConfigSpec.BooleanValue X = CK.define(...);   // ⚠ 全部字段后 build
 *     public static final ModConfigSpec SPEC = CK.build();
 *
 *     @Override public String domain() { return DOMAIN; }
 *     @Override public String fileName() { return "cryptand-sable.toml"; }
 *     @Override public ModConfigSpec spec() { return SPEC; }
 * }
 * }</pre>
 */
package com.hdf.cryptand.neoforge.core.registry;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 子包配置（三要素：域 id / 文件名 / ModConfigSpec）。 */
public interface CryptandConfigSpec {

    /** 配置域 id（唯一；注册到 CryptandRegistries 的 key）。 */
    String domain();

    /** 配置文件相对路径（config/cryptand/ 下；如 "sable.toml"）。 */
    String fileName();

    /** 子包构建完成的 ModConfigSpec（build 必须在全部字段之后）。 */
    ModConfigSpec spec();
}
