/**
 * ===== 扫描方块缓存生命周期清空 Mixin（2026-09-03，debugScopeBox/debugHighlight 门控） =====
 *
 * 注入 {@code CryptandSubLevelApi} 的拆卸路径：
 *  - dephysicalize0 / disassemble0 HEAD → SableDebugScanStore.clearAll()
 *
 * 目的：物理化结构【拆卸/还原】时黄框（被扫描方块）若不清理会残留在旧位置。
 * 而物理化【装载】走 uploadAroundBox/uploadAroundAnchor/uploadAroundBounds 扫描入口
 * （WorldChunkUploaderHighlightMixin 的 @Inject HEAD 已清空），无需在此重复。
 *
 * ⚠ Mixin 规则：本类必须在 cryptandsable.debug 包（cryptand.sable.debug.mixins.json
 * 声明）；共享数据 store 放 server 包（非 debug 包），mixin 可引用、外部可引用。
 */
package com.hdf.cryptand.neoforge.cryptandsable.debug;

import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableDebugScanStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CryptandSubLevelApi.class, remap = false)
public class CryptandSubLevelApiClearScanMixin {

    // ★ 2026-09-05 修复：目标方法 dephysicalize0/disassemble0 是【static】，callback
    //   方法必须也是 static（Mixin 不支持非静态 callback 注入静态目标方法）。

    @Inject(method = "dephysicalize0",
            at = @At("HEAD"), remap = false)
    private static void cryptand$clearScanOnDephysicalize(final CallbackInfoReturnable<Boolean> cir) {
        SableDebugScanStore.clearAll();
    }

    @Inject(method = "disassemble0",
            at = @At("HEAD"), remap = false)
    private static void cryptand$clearScanOnDisassemble(final CallbackInfoReturnable<Integer> cir) {
        SableDebugScanStore.clearAll();
    }
}