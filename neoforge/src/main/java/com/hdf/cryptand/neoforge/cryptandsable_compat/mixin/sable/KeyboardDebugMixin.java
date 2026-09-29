/**
 * ===== F3+B 亚层碰撞箱开关（客户端，2026-09-01） =====
 *
 * 官方 sable 有 DebugRendererMixin（@Overwrite renderFilledBox：当亚层块被
 * DebugRenderer 遍历时按位姿变换画框）。但我们移除了 ClientChunkCacheMixin
 * （黑块问题）→ 亚层方块在主世界区块为空气，DebugRenderer 永不遍历到 →
 * F3+B 永不显示亚层碰撞箱。
 *
 * 本 mixin：监听 KeyboardHandler.handleDebugKeys（F3+B → key=66），切换
 * SableSubLevelWorldRenderer 的亚层碰撞线框显示。这样 F3+B 按下时，
 * 【原版碰撞箱开 + 我们的亚层线框同步开】——视觉一致，无需动官方的
 * DebugRenderer 路径。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableSubLevelWorldRenderer;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardDebugMixin {

    /** 追踪 F3+B 按键：key=66 即 ASCII 'B'。handleDebugKeys 仅在 F3 组合下被调用。 */
    @Inject(method = "handleDebugKeys", at = @At("HEAD"), remap = false)
    private void cryptand$onDebugKey(final int key, final CallbackInfoReturnable<Boolean> cir) {
        try {
            // ★ 2026-09-06 【运行期 core 门控】mixin apply 早于 config 加载 → 方法体执行时判
            //   core：关闭（模式 C）→ 不切换亚层碰撞箱（零行为/零输出）
            if (!com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable
                    .ENABLE_CRYPTAND_SABLE_CORE.get()) {
                return;
            }
            if (key == 66) { // F3+B
                final boolean next = !SableSubLevelWorldRenderer.isShowCollisionBoxes();
                SableSubLevelWorldRenderer.setShowCollisionBoxes(next);
            }
        } catch (final Throwable ignored) {
            // 切换失败不影响原版
        }
    }
}