/**
 * ===== 引擎实现子包（cryptandsable_engines，2026-09-03） =====
 *
 * <p><b>定位</b>：与兼容子包 {@code com.hdf.cryptand.neoforge.cryptandsable_compat}
 * 平级的兄弟包，专用于存放【物理引擎实现】——即放入引擎 jar/DLL 等实现代码
 * （自研引擎 / 第三方引擎适配 / GPU 引擎等），与核心逻辑（cryptandsable.core.*）
 * 和兼容转接层（cryptandsable_compat）物理隔离。
 *
 * <p><b>接入方式</b>：引擎实现类须实现
 * {@code com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineApi}（或
 * {@code PhysicsBackend}），并通过
 * {@code com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineRegistry}
 * 的 {@code register(id, precision, jniClassName)} 注册，即可加入动态多引擎分发
 * （{@code EngineDispatcher} / {@code EngineManager.loadDynamic}）。
 *
 * <p><b>装载约定</b>：引擎原生二进制（DLL/JAR）默认按配置
 * {@code com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable.SABLE_ENGINE_DIR}（config/cryptand/engines/）查找，加载逻辑
 * 由引擎实现自行完成（参考 {@code SableNativeLoader}）。
 */
package com.hdf.cryptand.neoforge.cryptandsable_engines;
