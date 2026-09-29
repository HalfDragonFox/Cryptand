/**
 * ===== 航空学（Sable）物理化装配坐标迁移（2026-08-23 用户） =====
 *
 * 问题：物理化（SubLevelAssemblyHelper.assembleBlocks → moveBlocks）把世界方块
 * 移入亚层 plot 大坐标（chunk >= 1280_000，即块坐标 >= 20,480,000），世界原
 * 坐标变空气。Cryptand 自管图端点 key（"BBlockPos{...}#t"/"JBlockPos{...}"）
 * 仍为【旧世界坐标】→ 物理化后 getBlockState 读不到端子 → 网络/导线消失。
 *
 * 方案（参考 CEE SubLevelAssemblyHelperMixin，2026-08-23 用户确认）：mixin
 * Sable 的 SubLevelAssemblyHelper.moveBlocks（装配时每块 world → plot 坐标；
 * 拆解方向同方法复用），【HEAD】注入（与 CEE 一致：方块/BE 移除前迁移，
 * 避免与 Sable 的 Clearable/removeBlockEntity 时序竞争）：
 *   newPos = transform.apply(block)（public，反射调用，零 Sable 编译期依赖）
 *   → WireNetworkManager.remapBlockPos(old, new)（图端点 key 批量迁移）
 *   → graphVersion++ → 下 tick PowerGridWireConverter 全量同步客户端
 *   → 客户端 refreshPositions 现算真实世界坐标（亚层注入 + logicalPose）→
 *     导线【不消失】并跟随装置（与 CEE WireRenderer 同原理）。
 *   DeviceParamCache/TerminalRegistry 由下轮 sync/核心重建以新坐标自动重建
 *   （亚层 plot 坐标经 Sable Server/ClientChunkCacheMixin 注入，父 Level 的
 *   getBlockState/getBlockEntity 可直接读亚层方块）。
 *
 * 门控：CryptandMixinPlugin 按 sable mod 加载条件注入（未装跳过）。
 * 目标类不可见（runtimeOnly）→ @Mixin(targets=...) 字符串 + handler 参数
 * 用 @Coerce Object（AssemblyTransform 超类型）。
 */
package com.hdf.cryptand.neoforge.sable.mixin;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.device.terminal.WireTerminals;
import com.hdf.cryptand.neoforge.cee.SableSubLevelObserver;
import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper")
public class SableAssemblyMoveMixin {

    /** moveBlocks(ServerLevel, AssemblyTransform, Iterable<BlockPos>) —— HEAD
     *  （对齐 CEE：迁移在方块移除前完成，旧数据仍可读）。
     *  AssemblyTransform 编译期不可见（sable runtimeOnly）→ @Coerce Object。 */
    @Inject(method = "moveBlocks", at = @At("HEAD"), remap = false)
    private static void cryptand$onMoveBlocks(ServerLevel level,
                                              @Coerce Object transform,
                                              Iterable<BlockPos> blocks,
                                              CallbackInfo ci) {
        try {
            // ★ 2026-09-07 【运行时 enableSableSupport 门控】mixin 注入判定发生在 config 加载前
            //   （官方类于 mod 构造期加载）→ 注入不可避免；方法体执行时（官方装配，config 已
            //   加载）按真实开关判定：enableSableSupport=false → 完全放行官方（零联动行为）
            try {
                // ⚠ 2026-08-30 跨子包依赖经加载器查询（开关归 sable 子包）
                if (!com.hdf.cryptand.neoforge.core.module.SubpackageRegistry.isEnabled("sable")) {
                    return;
                }
            } catch (Throwable ignored) {
            }
            if (level == null || transform == null || blocks == null) return;
            if (!WireTerminals.coreActive()) return;
            // 取消物理化兜底：确保亚层移除观察者已注册（回迁端点）
            try {
                SableSubLevelObserver.ensure(level);
            } catch (Throwable ignored) {
            }
            var mgr = WireNetworkManager.get();
            java.lang.reflect.Method apply =
                    transform.getClass().getMethod("apply", BlockPos.class);
            // 2026-08-23 用户：物理化【最终执行前】主线程提交 BE 更新列表——
            // HEAD 收集全部 old→new 映射，一次性 preMoveUpdate（图 remap +
            // 各注册表迁移，读旧数据写新坐标），CEE HEAD 迁移同款。
            java.util.Map<BlockPos, BlockPos> moves = new java.util.LinkedHashMap<>();
            for (BlockPos block : blocks) {
                if (block == null) continue;
                try {
                    Object r = apply.invoke(transform, block);
                    BlockPos newPos = r instanceof BlockPos bp ? bp : null;
                    if (newPos != null && !newPos.equals(block)) {
                        moves.put(block.immutable(), newPos.immutable());
                    }
                } catch (Throwable ignored) {
                    // 单块失败继续（不中断装配）
                }
            }
            if (!moves.isEmpty()) {
                mgr.preMoveUpdate(level, moves);
            }
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[Sable] moveBlocks hook failed", t);
        }
    }
}
