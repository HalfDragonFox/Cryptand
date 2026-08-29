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
 *     世界网络）→ netVer++ → PhasorWriteback CACHES 校验 miss → 强制重建该网络
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
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceBinding;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class ElectricBlockEntityRemoveMixin {

    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void cryptand$onElectricRemoved(CallbackInfo ci) {
        try {
            if (!ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()) return;
            // ⚠ 2026-08-23 修复"电机打掉后网络不消失（渲染网格还在）"：
            // ConstantSpeedMotor/ElectricMotor 等是 IElectricEntity【非
            // ElectricBlockEntity 子类】→ 原守卫只放行 ElectricBlockEntity →
            // 电机拆除不触发破坏检测 → 图端子/导线不删除 → 客户端网格残留。
            // 双判：IElectricEntity（电机/电磁铁等）或 ElectricBlockEntity（设备）。
            boolean electric = (Object) this instanceof org.patryk3211.powergrid
                    .electricity.base.IElectricEntity
                    || (Object) this instanceof ElectricBlockEntity;
            if (!electric) return;
            BlockEntity be = (BlockEntity) (Object) this;
            Level level = be.getLevel();
            if (level == null || level.isClientSide) return;
            BlockPos pos = be.getBlockPos();

            // ===== 区块卸载防护（2026-08-15 修复“导线被删 → 无输出”） =====
            // MC 1.21.1 区块卸载时 LevelChunk.invalidateAllBlockEntities 会对
            // 区块内【所有】BE 调 setRemoved()——此时 ServerChunkCache 已把该
            // 区块移出内存（level.isLoaded(pos)=false）。若把区块卸载误判为
            // “设备拆除”→ 删除该方块全部自管导线边 + 端子注册表清理 → 导线
            // 消失、电压清零、重载后电路断裂（日志实证：保存世界时
            // [WireStore] onWorldSave edges=9 → 0，图被清空）。
            // 卸载场景必须跳过清理：边/注册表保留，区块重载后一切照旧；
            // 真实拆除（破坏/替换/爆炸）时区块仍在内存（isLoaded=true）→
            // 正常清理。
            if (!level.isLoaded(pos)) return;

            // ⚠ 2026-08-22 修复"图被清空 / 拆除不生效 / 导线无法放置"（根因）：
            // 区块【加载/重载】时 LevelChunk.rebuildBlockEntities() 会对区块内
            // 所有 BE 调 setRemoved()——此时 isLoaded(pos) 已为 true（上面的卸载
            // 防护只防 isLoaded=false 的【卸载】，防不住【重载】）→ 原逻辑立即
            // 删除图中该方块全部设备点/边 → 玩家登录后周围区块加载时图被清空
            // （日志实证：世界加载恢复 3 节点 1 边，4 秒后 nodes=0；此后每次
            // addDevice 后 deferred 检查 nodes=0 → 放置/拆除/接线全部失效）。
            // 改为【延迟确认删除】：排到下一 tick 检查该 pos 是否仍有电气 BE——
            //   - 区块重载/替换成电气方块：新 BE 存在 → 保留图点（同方块同端子
            //     key，addDevice 幂等不重复）→ 图不丢；
            //   - 真拆除/替换成非电气方块：无电气 BE → 执行完整清理（缓存失效/
            //     端子注销/删边/删点/客户端同步）。
            if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                final boolean overheatBurning =
                        com.hdf.cryptand.neoforge.powergrid.adapter.DestructionQueue
                                .isOverheatBurning(pos);
                // ⚠ 2026-08-22 v3 用户架构：主线程破坏检测类——延迟确认（区块
                // 重载过滤：getBlockState 非 air → 保留）+ 检测悬空导线 + 整合
                // 破坏计划（悬空端子 + 相关 BE）→ 发异步核心执行删除（不直接删图）。
                com.hdf.cryptand.neoforge.powergrid.adapter.NetworkDestructionDetector
                        .get().onBeRemoved(sl, pos, overheatBurning);
            } else {
                // 非 ServerLevel（异常路径）→ 立即清理兜底（原逻辑，极罕见）
                DeviceBinding db = DeviceBinding.forPos(pos);
                if (db != null) {
                    db.notifyModelDestroyed();
                } else {
                    CryptandTopologyManager.get().markTopologyChanged();
                }
                com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry.unregister(pos);
                boolean overheatBurning2 =
                        com.hdf.cryptand.neoforge.powergrid.adapter.DestructionQueue
                                .isOverheatBurning(pos);
                try {
                    if (com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter
                            .isEnabled() && !overheatBurning2) {
                        var mgr = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get();
                        if (mgr != null && mgr.nodeCount() > 0) {
                            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge> toRemove =
                                    new java.util.ArrayList<>();
                            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : mgr.pointList()) {
                                BlockPos pp = com.hdf.cryptand.neoforge.powergrid.adapter
                                        .PhasorNetworkBuilder.pointPosOfPublic(p.key);
                                if (pos.equals(pp)) toRemove.addAll(mgr.adjacent(p));
                            }
                            for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge e : toRemove) {
                                mgr.removeEdge(e.a, e.b);
                            }
                            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> toDrop =
                                    new java.util.ArrayList<>();
                            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : mgr.pointList()) {
                                BlockPos pp = com.hdf.cryptand.neoforge.powergrid.adapter
                                        .PhasorNetworkBuilder.pointPosOfPublic(p.key);
                                if (pos.equals(pp)) toDrop.add(p);
                            }
                            for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : toDrop) {
                                mgr.removePoint(p);
                            }
                            if (!toRemove.isEmpty() || !toDrop.isEmpty()) {
                                com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter
                                        .syncGraphToClientsNow();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ===== 区块重载过滤 + 破坏计划已整合进 NetworkDestructionDetector（2026-08-22 v3）=====
    // 原 confirmRemoval/performRemoval 已迁移到 NetworkDestructionDetector：
    //   - 延迟确认（getBlockState 非 air → 保留；持续缺失 → 真拆除）
    //   - 构建 NetworkDestructionPlan（悬空端子 + 相关 BE + 悬空导线）
    //   - 发 MainThreadInteractionManager.postDestroy(plan) 给异步核心删除
}
