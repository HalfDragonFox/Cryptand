/**
 * ===== SableServerBridge 调试访问接口（2026-09-02） =====
 *
 * 供 SableServerBridgeHighlightMixin（mixin 包）安全访问桥的 private 字段。
 * ⚠ Mixin 规则：mixin 包内的类不能被外部直接引用（IllegalClassLoadError）——
 * 必须把接口放在【非 mixin 包】。本接口位于 server 包，双方均可引用。
 */
package com.hdf.cryptand.neoforge.cryptandsable.server;

import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;

public interface SableServerBridgeAccess {
    CryptandSable bridgeCore();

    net.minecraft.server.level.ServerLevel bridgeLevel();
}
