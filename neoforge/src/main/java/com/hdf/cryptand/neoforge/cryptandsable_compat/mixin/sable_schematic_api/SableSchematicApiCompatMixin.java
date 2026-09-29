package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable_schematic_api;

/**
 * Sable Schematic API 兼容 mixin 包（cryptandsable_compat.mixin.sable_schematic_api）。
 *
 * <p>承载针对 sable_schematic_api 的 sable 兼容（mixin/拦截）。
 * sable_schematic_api（mod id: {@code sable_schematic_api}）会调用
 * {@code SubLevelContainer.getContainer(ClientLevel)} 等 sable 静态面；
 * 兼容实现按需随轮次填充。本类为包锚。
 */
public final class SableSchematicApiCompatMixin {
    private SableSchematicApiCompatMixin() {
    }
}