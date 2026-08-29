/**
 * ===== 虚拟设备参数快照生命周期（超长线路输电，2026-08-13 修复）=====
 *
 * 目标：未加载区块的电气元件仍能参与网络求解（已加载导线持有其网络节点，
 * 位置在 blockTerminals → 构建时 getBlockEntity null → 用快照建虚拟元件）。
 *
 * ⚠ 2026-08-13 修复：原版注入 BlockEntity.onLoad/onChunkUnloaded，但 1.21.1
 * 的 BlockEntity 基类【没有这两个方法】（javap 验证；那是 NeoForge
 * IBlockEntityExtension 的 default 方法）→ 注入必失败崩溃。之前被 mixin
 * 插件 SKIPPED 恰好掩盖（快照从未被填充 → 虚拟设备从未建模）。
 *
 * 修复架构：
 *   - 【快照保存】移到构建时（PhasorNetworkBuilder.addElement 建模成功 →
 *     VirtualDeviceStore.put）——快照恒最新，区块卸载后天然可用，不再依赖
 *     卸载事件。
 *   - 【快照删除·破坏】本 mixin 的 setRemoved（1.21.1 存在，无参）→ 设备被
 *     破坏/替换时删除快照，避免虚拟错误建模已破坏的设备。
 *   - 【快照删除·加载】DeviceBinding.notifyCompositeRemoved（真移除）也会删。
 *
 * 注入 BlockEntity 基类，仅对 ElectricBlockEntity 生效（instanceof 过滤，
 * 低频事件非每 tick）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.VirtualDeviceStore;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class VirtualDeviceSnapshotMixin {

    /** 设备被破坏/移除 → 删除快照：该位置已无设备，避免虚拟错误建模 */
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void cryptand$setRemoved(CallbackInfo ci) {
        if ((Object) this instanceof ElectricBlockEntity) {
            try {
                VirtualDeviceStore.remove(((BlockEntity) (Object) this).getBlockPos());
            } catch (Throwable ignored) {
            }
        }
    }
}
