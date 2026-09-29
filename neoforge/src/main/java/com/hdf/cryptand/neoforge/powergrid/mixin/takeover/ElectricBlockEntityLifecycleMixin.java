/**
 * ===== 电气方块移除 → 立即拓扑重建（2026-08-13） =====
 *
 * 背景：打掉负载（风扇/设备等）后，PowerGrid 的 removeWire hook 不一定触发
 * （导线可能仍留在网络 wires，只是一端端子悬空）→ Cryptand 不感知 → 旧 ctx
 * 缓存复用（含已移除设备模型/旧节点映射）→ 悬空端电压未按新拓扑回写 → 0V
 * vs 有电压端压差 → 烧线 / 开路误转。
 *
 * 本 mixin 在【电气方块实体被移除】时（玩家破坏/命令/爆炸等 → setRemoved）
 * 主动触发拓扑失效——通过【双向绑定】精准定位（2026-08-13 用户要求）：
 *   - 构建时 DeviceBinding 按 pos 注册并 attach 了该设备复合元件所属网络。
 *   - 此处经 {@code DeviceBinding.forPos(pos).notifyModelDestroyed()} → 用记录
 *     的 ElectricalNetwork 精准 {@code markNetworkChanged(net)}（O(1)，不遍历
 *     世界网络）→ netVer++ → PhasorPipeline CACHES 校验 miss → 强制重建该网络
 *     ctx + 稳定检测重置（netVer 变）→ 稳定前清零电压（导线两端 0 → 无压差 →
 *     不烧线）。⚠ 必须网络级：markTopologyChanged() 只递增全局 topoVersion，而
 *     CACHES 缓存校验【只看网络级 netVer + 节点/导线签名】——打掉负载通常不改
 *     wires/节点集合 → 签名不变 → 缓存命中旧 ctx（含已移除设备模型）→ 悬空端
 *     电压错误 → 烧线！网络级失效是治本。
 *   - 重建后已移除设备不再建模，悬空端经 WORLD_WIRES 连通性扩展进求解图 →
 *     引擎 MNA 自然等电位（底层自实现：无闭合回路 → 无电流 → 两端等电位）。
 *   - 元件被移除（重建后 ctx 不再含该 pos）→ 复合元件 notifyRemoved() →
 *     通知实际模型清理（温度/虚拟快照，DeviceBinding.cleanupRemoved 节流检测）。
 *
 * 注：MC 1.21.1 的 BlockEntity.setRemoved() 是【无参】方法（javap 已验证，
 * 1.20.2 曾引入的 RemovalReason 枚举已回退），且只在方块破坏/替换时调用；
 * 区块卸载走 onChunkUnloaded（VirtualDeviceSnapshotMixin 已分别处理），
 * 不会误触发重建。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.device.thermal.FanCoolingRegistry;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ===== 电气 BE 破坏事件（2026-09-11）=====
 *
 * 本 mixin 只负责【破坏】侧的电气拓扑清理（清图点/边、通知绑定、延迟确认真拆除）。
 * <p>注意：{@code setRemoved} 也会被区块卸载/重载调用（虚拟内容）→ 这里的判定只是
 * 粗筛（isLoaded），真拆除判定由 {@code NetworkDestructionDetector} 的延迟确认完成
 *（连续确认方块与 BE 都缺失才判真拆除）。
 * <p>【真实放置 / 移除】的接线柱检测事件源见 {@link ChunkBlockChangeMixin}
 *（{@code LevelChunk.setBlockState} / {@code removeBlockEntity} —— 只有真实方块变化
 * 才经过这两条路径；区块加载走 addAndRegisterBlockEntity、卸载走
 * invalidateAllBlockEntities，天然不经过）。
 */
@Mixin(BlockEntity.class)
public abstract class ElectricBlockEntityLifecycleMixin {
    /**
     * 【参数变更上报（变化即上报）】{@code BlockEntity.setChanged} 是所有 BE 数据变化的
     * 标准入口（参数 setter / UI 更新都会调用）→ 电气设备标记该位置需重读参数
     *（{@code DeviceParamCache.markDirty}）：消费端只处理脏位置，不遍历元器件。
     */
    @Inject(method = "setChanged", at = @At("TAIL"))
    private void cryptand$onChanged(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof org.patryk3211.powergrid.electricity.base
                    .IElectricEntity)
                    && !((Object) this instanceof ElectricBlockEntity)
                    && !((Object) this instanceof org.patryk3211.powergrid.electricity
                            .deviceconnector.DeviceConnectorBlockEntity)) {
                return;
            }
            BlockEntity be = (BlockEntity) (Object) this;
            if (be.getLevel() == null || be.getLevel().isClientSide) return;
            com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache
                    .markDirty(be.getBlockPos());
            // 组装器缓存槽同步同样走增量（参数变化 → 只需刷新该位置的槽）
            com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry
                    .markDirty(be.getBlockPos());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【风扇被破坏】{@code BlockEntity.setRemoved}（方块破坏/替换时调用；区块卸载走
     * {@code onChunkUnloaded}，不经过这里——见类注释的 1.21.1 实测结论）→
     * 只撤销【这一台风扇】附加的额外散热，其余风扇的贡献原样保留（多风扇叠加）。
     *
     * <p>2026-09-12 用户设计：放下风扇时登记它冷却的位置与额外散热值，风扇破坏时
     * 精确减掉这一份——而不是"清空所有风扇的冷却再重建"。
     */
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void cryptand$onFanRemoved(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof IAirCurrentSource)) return; // 只处理风扇
            net.minecraft.world.level.block.entity.BlockEntity be =
                    (net.minecraft.world.level.block.entity.BlockEntity) (Object) this;
            com.hdf.cryptand.neoforge.powergrid.device.thermal.FanCoolingRegistry
                    .removeFan(be.getBlockPos());
        } catch (Throwable ignored) {
        }
    }

}
