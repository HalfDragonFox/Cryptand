/**
 * ===== 官方 Rapier 场景句柄（OfficialRapierScene，2026-09-01） =====
 *
 * 官方 RapierPhysicsScene 的等价体（仅封装 long 原生句柄；无官方依赖）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

import org.jetbrains.annotations.ApiStatus;

/**
 * Rapier 场景句柄包装（官方 RapierPhysicsScene 等价体，无官方依赖）。
 */
@ApiStatus.Internal
public final class OfficialRapierScene {
    private final long handle;

    OfficialRapierScene(final long handle) {
        if (handle == 0L) {
            throw new IllegalArgumentException("invalid rapier scene handle");
        }
        this.handle = handle;
    }

    /** 原生场景句柄（仅供 Rapier3D 静态 native 调用）。 */
    long handle() {
        return this.handle;
    }

    /** 释放场景（native 侧资源）。 */
    public void dispose() {
        CryptandRapierNative.dispose(this.handle);
    }
}
