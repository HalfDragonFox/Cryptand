/**
 * ===== 真实放置 / 移除事件（2026-09-11）=====
 *
 * 用户要求：接线柱与相关网络检测【只对真实的放置与移除】触发。区块加载（NBT 恢复 →
 * addAndRegisterBlockEntity）、区块卸载（invalidateAllBlockEntities）、区块重载都是
 * 虚拟内容，不得触发——BE 的 setLevel / setRemoved 无法区分这些场景（加载也会走）。
 *
 * 本 mixin 以区块级【真实方块变化】路径为事件源：
 *   - setBlockState（TAIL）：方块被真实设置（玩家放置、指令 /setblock、世界生成、
 *     其他 mod、爆炸后重建）→ 新状态带方块实体 → 检测该位置相关接线柱；
 *   - removeBlockEntity（HEAD）：方块实体被真实移除（破坏/替换）→ 检测并清理引用。
 * 加载/卸载/重载都不经过这两个方法 → 天然排除全部虚拟场景（无需启发式判断）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.interaction;

import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.network.NetworkDestructionDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class ChunkBlockChangeMixin {

    @Shadow @Final private Level level;

    @Unique
    private Level cryptand$level() {
        return this.level;
    }

    /**
     * 真实放置 / 移除：方块状态被真实设置（TAIL = 新 BE 已创建并注册，自身可见）。
     * <p>判定用【新旧状态】：
     *   - 新状态带 BE → 放置（玩家放置、指令 /setblock、其他 mod、世界生成）；
     *   - 旧状态带 BE 而新状态不带 → 移除（玩家破坏 / 爆炸 / 替换成普通方块）。
     * <p>区块加载走 addAndRegisterBlockEntity、卸载走 invalidateAllBlockEntities，
     * 都不经过本方法 → 虚拟场景天然不触发；区块卸载时电路与自管图数据完整保留
     *（另见 NetworkDestructionDetector.confirmAndDestroy：区块未加载 → 不消耗重试
     * 计数、继续等待，绝不误删）。
     */
    @Inject(method = "setBlockState", at = @At("TAIL"))
    private void cryptand$onBlockStateSet(BlockPos pos, BlockState state, boolean isMoving,
            CallbackInfoReturnable<BlockState> cir) {
        try {
            BlockState old = cir.getReturnValue(); // 旧状态（被替换掉的方块）
            boolean placed = state.hasBlockEntity();
            boolean removed = !placed && old != null && old.hasBlockEntity();
            if (!placed && !removed) return;      // 普通方块变化 → 与接线柱无关
            // 创建（真实放置）→ 绑定默认「已加载」（用户：创建 BE 电气设备时默认为加载）
            if (placed) {
                com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding b =
                        com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding.forPos(pos);
                if (b != null) b.setLoaded(true);
            }
            Level lv = cryptand$level();
            if (lv == null || lv.isClientSide) return;
            if (!CryptandTopologyManager.simulationEnabled()) return;
            com.hdf.cryptand.neoforge.powergrid.device.connector
                    .ProxyConnectorAssembler.detectNear(lv, pos);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 真实移除：方块实体被移除（破坏/替换）。区块卸载不经过此路径。
     * <p>TAIL：移除【之后】——自身已不在 BE 表 → detectAt 清理注册条目；
     * 若该位置紧接着被新 BE 替换，则按新 BE 正常检测。
     */
    @Inject(method = "removeBlockEntity", at = @At("TAIL"))
    private void cryptand$onBlockEntityRemoved(BlockPos pos, CallbackInfo ci) {
        try {
            Level lv = cryptand$level();
            if (lv == null || lv.isClientSide) return;
            if (!CryptandTopologyManager.simulationEnabled()) return;
            // ⚠ 2026-09-11 用户要求（不要兜底、只允许一套操作）：本方法 = 【唯一删除入口】
            // —— LevelChunk.removeBlockEntity 只在真实移除时调用（区块加载走
            // addAndRegisterBlockEntity、卸载走 invalidateAllBlockEntities，都不经过）
            // → 事件发生即真拆除，直接执行（无延迟确认/重试/存在性判定）。
            if (lv instanceof net.minecraft.server.level.ServerLevel sl) {
                try {
                    com.hdf.cryptand.neoforge.powergrid.network.NetworkDestructionDetector
                            .get().onBeRemoved(sl, pos, false);
                } catch (Throwable ignored) {
                }
            }
            com.hdf.cryptand.neoforge.powergrid.device.connector
                    .ProxyConnectorAssembler.detectNear(lv, pos);
        } catch (Throwable ignored) {
        }
    }
}
