/**
 * ===== @CryptandSubpackage（common core.api · 2026-08-30 迁入 common） =====
 *
 * ⚠ 用户架构：能放到 common 的通用注册机制放到 common——本注解为纯 Java
 * （零 MC 依赖），供任意平台复用（neoforge/fabric/独立仿真器）。
 *
 * 【子包主类标注】——类似 NeoForge 的 {@code @Mod} 主类约定：
 * 每个子包（powergrid / railway / cee / create / aeronautics / cryptandsable /
 * cryptandsable_compat / sable / simserver ...）在其入口类（实现
 * {@link com.hdf.cryptand.neoforge.core.module.SubpackageEntry}、提供
 * {@code public static final X INSTANCE}）上标注本注解。
 *
 * core 在构造期【扫描自身 mod 文件的类注解】发现全部子包（FML ModFileScanData，
 * 与 NeoForge 发现 {@code @Mod}/{@code @EventBusSubscriber} 同一机制）——
 * <b>core 不持有任何子包类名/包名字符串</b>：删除某子包目录 → 其入口类消失 →
 * 扫描结果无该类 → 自动跳过（其余子包与 core 照常编译运行）。
 *
 * 其他 mod 亦可用本注解向 Cryptand core 注册自己的子包级扩展
 * （把实现类放入任何包并在类上标注；core 扫描的是 Cryptand mod 文件，
 * 第三方类位于其自身 mod 文件——如需第三方支持另行扩展注册入口）。
 *
 * 初始化/注册消息面：入口类经 {@code SubpackageEntry} 生命周期钩子
 * （registerConfigs / init / tick / commonSetup / commands）与
 * {@code @EventBusSubscriber}（NeoForge 注解扫描）接收 core 的调度消息。
 */
package com.hdf.cryptand.core.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 子包主类标注（core 构造期注解扫描发现；入口类见 SubpackageEntry）。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CryptandSubpackage {

    /** 子包唯一 id（诊断打印/日志；如 "powergrid"）。 */
    String id();

    /** 启用条件的人类可读说明（诊断打印；可空）。 */
    String conditionDesc() default "";

    /** 加载顺序（越小越先；默认 1000；同名 order 按类名稳定排序）。 */
    int order() default 1000;
}