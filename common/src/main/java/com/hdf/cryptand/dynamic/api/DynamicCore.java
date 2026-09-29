package com.hdf.cryptand.dynamic.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个具体核心（注解处理器据此生成）：
 * <ul>
 *   <li>{@code META-INF/services/com.hdf.cryptand.dynamic.api.DynamicCoreSpi}（ServiceLoader 定位）；</li>
 *   <li>{@code META-INF/cryptand-dynamic/core.properties}（免类加载读取元数据）。</li>
 * </ul>
 * <b>约束</b>：注解处理器只能生成文件，打包仍由构建工具完成；不能复用 LDLib2 的
 * {@code @LDLRegister}（它扫启动期 ModFileScanData，运行期插件不参与）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DynamicCore {

    String id();

    String[] suffixes() default {".jar"};

    String folder() default "";

    int apiVersion() default 1;
}
