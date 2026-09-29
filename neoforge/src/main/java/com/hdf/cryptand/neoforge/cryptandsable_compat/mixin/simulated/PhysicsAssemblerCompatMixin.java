package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.simulated;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevel;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable_compat.CryptandSubLevelLocator;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * simulated 的物理组装器兼容 mixin（cryptandsable_compat 接管点）。
 *
 * <p>目标：{@code dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity}
 * （simulated 的"物理装配器"，字节码确认 {@code getSubLevel()} 与 {@code assembleOrDisassemble()}
 * 均以 {@code Sable.HELPER.getContaining(BlockEntity)} 判定当前区块是否已组装为物理亚层）。
 *
 * <p><b>接管策略（sable 空壳 → mixin 拦截目标 mod）</b>：官方 sable 已被 CryptandSable 完全替代、
 * {@code Sable.HELPER}（{@link ActiveSableCompanion}）为纯空壳（不触发、不委托）。为避免装配器
 * 走进空壳拿 null 而判定"未组装"（→ 无物理化），本 mixin 把这处调用重定向为直接查询 CryptandSable
 * 兼容容器（{@link CryptandSubLevelLocator}），使装配器正确感知 assembleBlocks 登记的物理亚层。
 *
 * <p>该方法在类内所有 {@code getContaining(BlockEntity)} 调用点生效（getSubLevel / assembleOrDisassemble），
 * 一处接管、全路径覆盖；空壳 sable 完全不参与。
 */
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity")
public abstract class PhysicsAssemblerCompatMixin {

    /**
     * 拦截对 {@code ActiveSableCompanion.getContaining(BlockEntity)} 的调用，
     * 改查 CryptandSable 兼容容器（按 plot 范围命中的真实物理亚层；无 → null）。
     *
     * <p>2026-08-31 用户"组装器独立发送消息"：优先按【装配器位置关联表】精确命中
     * （每个装配器只对应自己物理化的亚层；不混用广域 plot 命中），miss 才退回 locator。
     */
    private static final long THROTTLE = 5000L;
    private static long lastLog = 0L;

    @SuppressWarnings("unused")
    @Redirect(
            method = {
                    "getSubLevel",
                    "assembleOrDisassemble"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getContaining" +
                            "(Lnet/minecraft/world/level/block/entity/BlockEntity;)" +
                            "Ldev/ryanhcode/sable/sublevel/SubLevel;"
            ),
            remap = false
    )
    private static SubLevel cryptandsable$getContaining(
            final ActiveSableCompanion instance, final BlockEntity blockEntity) {
        // ★ 2026-09-06 【运行期 core 门控】mixin apply 早于 config 加载（core=false 仍可能
        //   注入）→ 方法体执行时判 enableCryptandSableCore：关闭（模式 C）→ 放行官方原实现
        //   （模拟装配走官方 sable，不劫持）；启用才转接 CryptandSable。
        if (!ConfigCryptandSable
                .ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return instance.getContaining(blockEntity);
        }
        java.util.UUID hit = null;
        // 档①：装配器关联表精确命中（独立消息——每个装配器只对应自己物理化的亚层）
        if (blockEntity != null) {
            final java.util.UUID assoc =
                    CryptandSubLevelApi
                            .associatedSubLevel(blockEntity.getBlockPos());
            if (assoc != null && blockEntity.getLevel() instanceof final ServerLevel sl) {
                if (CryptandSubLevelApi
                        .isRegistered(sl, assoc)) {
                    hit = assoc;
                }
            }
        }
        // 档②：仅【结构精确包围盒】命中（不含广域 plot 兜底——避免"别处装配器命中同一亚层"串扰）
        if (hit == null && blockEntity != null) {
            final CryptandSubLevel own =
                    CryptandSubLevelLocator.containingExactOwn(blockEntity);
            if (own != null) {
                hit = own.getUniqueId();
            }
        }
        // 仅做节流确认（mixin 是否进入此 handler），避免热路径刷屏。
        // ⚠ 官方容器不挂载（官方 sable 纯类库、不运行）：命中核心亚层后仍返回 null——
        //   simulated 侧视为"未命中"但不崩溃（用户：官方 mixin 不起作用但不至于崩溃）。
        final long now = System.currentTimeMillis();
        if (now - lastLog >= THROTTLE) {
            lastLog = now;
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] PhysicsAssemblerCompatMixin.redirect be={} -> coreHit={} (official-null by design)",
                    blockEntity != null ? blockEntity.getBlockPos() : null, hit);
        }
        return null;
    }

    /**
     * 拦截模拟的拆卸开始流程：CryptandSable 无"约束对齐到目标姿态"语义（约束为空桩，会令
     * simulated 拆卸对齐永远失败报"无法对准物理结构进行拆卸"）。因此拆卸直接走专属 API：
     * 还原被搬走的方块 + 移出容器 + 移除核心物理体，一次性完成。
     *
     * <p>注意：本方法取消 startDisassembling 后，模拟的 tickDisassembling 仍会被 tick
     * 状态机驱动（disassembling 态），其内部会用 null 的 disassemblyOrientation 做四元数
     * 差分（Quaterniond.div(null) → NPE 崩溃）。因此【必须同时在 tickDisassembling 处
     * 拦截取消】——整个拆卸流程由本 mixin 全接管（仅保留模拟的"已开始"状态标记）。
     */
    @SuppressWarnings("unused")
    @Inject(
            method = "startDisassembling",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cryptandsable$startDisassembling(
            final ServerSubLevel serverSubLevel, final ServerLevel level, final SubLevel subLevel,
            final CallbackInfo ci) {
        try {
            // ★ 2026-09-06 运行期 core 门控：关闭（模式 C）→ 放行模拟官方拆卸流程
            if (!ConfigCryptandSable
                    .ENABLE_CRYPTAND_SABLE_CORE.get()) {
                return;
            }
            // 幂等：该亚层已移出容器（已拆卸过）→ 只取消模拟继续（防每 tick 重复拆卸）
            if (serverSubLevel != null && level != null) {
                final java.util.UUID subId = serverSubLevel.getUniqueId();
                final boolean stillRegistered =
                        CryptandSubLevelApi
                                .isRegistered(level, subId);
                if (!stillRegistered) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[CryptandSable] PhysicsAssemblerCompatMixin skip repeated disassemble sub={}",
                            subId);
                    ci.cancel();
                    return;
                }
                // 核心自有亚层：按 uuid 取核心容器对象 → 专属拆卸 API（还原方块+移除体+清持久化）
                final CryptandSubLevel coreSub =
                        CryptandSubLevelApi
                                .getSubLevel(level, subId);
                final int restored = coreSub != null
                        ? CryptandSubLevelApi
                                .disassembleSubLevel(level, coreSub)
                        : 0;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] PhysicsAssemblerCompatMixin disassemble via API restoredBlocks={} sub={}",
                        restored, subId);
            }
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] disassemble via API failed: {}", t);
        }
        ci.cancel();
    }

    /**
     * 拦截模拟的拆卸 tick 推进：startDisassembling 已被本 mixin 取消（实际拆卸走 API），
     * tickDisassembling 继续跑会对 null 的 disassemblyOrientation 做 Quaterniond.div → NPE。
     * 取消整个 tick 主体：拆卸状态由模拟自身标记为已开始即止，方块/物理体由 API 还原。
     */
    @SuppressWarnings("unused")
    @Inject(
            method = "tickDisassembling",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cryptandsable$tickDisassembling(final CallbackInfo ci) {
        // ★ 2026-09-06 运行期 core 门控：关闭（模式 C）→ 放行模拟官方 tick（官方流程完整）
        if (!ConfigCryptandSable
                .ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }
        // 防护：拆卸 API 已还原；tick 主体（姿态对齐）无意义且会 NPE → 直接不执行。
        ci.cancel();
    }
}