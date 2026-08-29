/**
 * ===== CEE 原版受电弓【算法接管】Mixin（2026-08-22 用户："原版仅接管算法"） =====
 *
 * CEE 原版 PantographBlockEntity 在自管模式下（PowerGrid 接管 + CEE 支持开启）
 * 只接管【滑触头贴合/取电算法】，渲染仍用 CEE 原版渲染器：
 *   - handleOnServer：替换为自管贴合（PantographTapLogic）→ 接触网取自管网、
 *     target/current 同步动画、写 PantographTapCache 供相量构建 1:1 互感桥接；
 *     跳过 CEE 原版（CEE InfrastructureSavedData 线网）取电，避免双系统。
 *   - handleOnClient：自管模式直接取消（CEE 客户端贴合基于 CEE 线网不可信）；
 *     动画 target 由服务端 NBT 同步（TargetExtensionState 走 CEE 原版 write/read）。
 *
 * ⚠ 依赖 CEE 类（compileOnly）→ 仅 CEE mod 加载时由 CryptandMixinPlugin 放行
 * （ceeEnabled && ceeModLoaded）；CEE 未装 → 跳过本 mixin（目标类不存在安全）。
 */
package com.hdf.cryptand.neoforge.cee.mixin;

import com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographBlockEntity;
import com.hdf.cryptand.neoforge.cee.PantographTapLogic;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.UnaryOperator;

@Mixin(value = PantographBlockEntity.class, remap = false)
public abstract class CeePantographBlockEntityMixin {

    /** 服务端：自管模式 → 自管贴合 + 写 PantographTapCache，跳过 CEE 原版取电 */
    @Inject(method = "handleOnServer", at = @At("HEAD"), cancellable = true, remap = false)
    private void cryptand$selfManagedServer(UnaryOperator<Vec3> positionTransform,
                                            UnaryOperator<Vec3> inversePositionTransform,
                                            CallbackInfo ci) {
        try {
            PantographBlockEntity self = (PantographBlockEntity) (Object) this;
            if (self.getLevel() == null || self.getLevel().isClientSide) return;
            if (!PantographTapLogic.isSelfManaged(self.getLevel())) return;
            // 原版仅接管算法：服务端贴合/取电 → 自管网（渲染仍是 CEE 原版）
            PantographTapLogic.serverHandle(self.getLevel(), self.getBlockPos(),
                    self.getBlockState(), self.extended,
                    v -> self.targetExtensionState = v);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }

    /** 客户端：自管模式 → 取消 CEE 原版贴合（target 已由服务端 NBT 同步） */
    @Inject(method = "handleOnClient", at = @At("HEAD"), cancellable = true, remap = false)
    private void cryptand$selfManagedClient(UnaryOperator<Vec3> positionTransform,
                                            UnaryOperator<Vec3> inversePositionTransform,
                                            CallbackInfo ci) {
        try {
            PantographBlockEntity self = (PantographBlockEntity) (Object) this;
            if (self.getLevel() == null || !self.getLevel().isClientSide) return;
            if (!PantographTapLogic.isSelfManaged(self.getLevel())) return;
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
