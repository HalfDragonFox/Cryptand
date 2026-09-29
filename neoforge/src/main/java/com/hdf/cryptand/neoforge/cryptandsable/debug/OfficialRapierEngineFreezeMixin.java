/**
 * ===== 冻结物理 Mixin（2026-09-02，freezePhysics 调试开关） =====
 *
 * 注入 {@code OfficialRapierEngine.step()} HEAD：freeze 模式下替换为
 * debugFreezeTick()（只发收集查询不积分 → 结构保持不动；高亮收集照常）。
 * 仅 freezePhysics=true 时该 mixin 被 SableDebugMixinPlugin 应用 → 关闭时零开销。
 */
package com.hdf.cryptand.neoforge.cryptandsable.debug;

import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.OfficialRapierEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OfficialRapierEngine.class, remap = false)
public class OfficialRapierEngineFreezeMixin {

    /**
     * step() HEAD 冻结：不积分（结构保持 createBody 位姿），仍发收集查询
     * （debugFreezeTick → emitCollectQueries 冻结分支 allActiveBodies 持续发）。
     */
    @Inject(method = "step", at = @At("HEAD"), cancellable = true)
    private void cryptand$freezeStep(final CallbackInfo ci) {
        ((OfficialRapierEngine) (Object) this).debugFreezeTick();
        ci.cancel();
    }
}
