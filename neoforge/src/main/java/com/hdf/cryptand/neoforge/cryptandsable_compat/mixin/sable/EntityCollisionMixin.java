/**
 * ===== 亚层方块实体碰撞（服务端，2026-08-31 修复） =====
 *
 * 【背景】之前 LevelCollisionMixin 用 @Mixin(Level.class) + @Inject(getBlockCollisions)，
 * 但 getBlockCollisions 是 CollisionGetter 接口的 default 方法，不在 Level.class 方法表内
 * → 注入器找不到目标 → mixin 静默失败。改为 @Mixin(CollisionGetter.class) 又报
 * "target type mismatch: interface"（Mixin 标准子类型不支持接口目标）。
 *
 * 【官方接入点】官方 SubLevelEntityCollision 的实现机制：在 Entity.collide(Vec3)（private）
 * 处 @Redirect，用自写的 OBB-SAT 碰撞器替换整个运动求解。我们不搬 OBB-SAT（依赖太重），
 * 但用官方同一条调用链：Entity.collide → collideWithShapes → collectColliders。
 *
 * 【本 mixin】注入 Entity.collectColliders（private static，收集实体碰撞 shape 到列表），
 * 在列表构造后把"亚层方块（MOVED 表）+ 实体 AABB 相交"的 VoxelShape 喂进去——
 * 这样实体的 collide/collideWithShapes 会把亚层方块挡下（碰撞箱生效）。
 *
 * 数据来源：CryptandSubLevelApi.feedCollisionShapes（服务端 MOVED 表）；
 * 客户端无 MOVED（无亚层数据）→ 无操作，不影响原版。
 *
 * 注：collectColliders 是 private static，@Inject 可行（Mixin 能注入 private 方法）。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Entity.class)
public class EntityCollisionMixin {

    /**
     * 注入 collectColliders 返回前（TAIL）：收集到的 List 已构建，把亚层方块 shape 加入。
     *
     * <p>2026-09-02 字节码实证（21.1.231）：主体碰撞链 =
     * Entity#collide → Vec3.collideBoundingBox → collectColliders（返回 ImmutableList）
     * → collideWithShapes；block 碰撞源 = level.getBlockCollisions。亚层方块已从世界搬走，
     * 故主路径无方块 → 实体穿透；本注入把 MOVED/渲染镜像投影 shape 补进该列表。
     *
     * <p>★ 2026-09-06 【运行期 core 门控】mixin apply 早于 config 加载（apply 期无法可靠
     * 按 enableCryptandSableCore 决策——core=false 时仍可能注入），故注入本身无害化：
     * 方法体执行时 config 必已加载 → core=false（模式 C）直接 return 走原版碰撞，
     * 不喂 shape、不打印任何 [CryptandSable] 内容（"完全关闭"语义）。
     *
     * @param entity   实体（arg0）
     * @param level    世界（arg1）
     * @param existing 已有碰撞列表（arg2）
     * @param box      实体包围盒（arg3）
     * @param cir      返回值（List&lt;VoxelShape&gt;）——TAIL 时返回值已可用，需可变列表
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "collectColliders", at = @At("TAIL"), remap = false)
    private static void cryptand$feedSubLevelCollisions(final Entity entity, final Level level,
                                                        final List<VoxelShape> existing,
                                                        final AABB box,
                                                        final CallbackInfoReturnable<List<VoxelShape>> cir) {
        try {
            // ★ 2026-09-06 运行期总闸：核心关闭 → 完全走原版碰撞（零 CryptandSable 行为/日志）
            if (!ConfigCryptandSable
                    .ENABLE_CRYPTAND_SABLE_CORE.get()) {
                return;
            }
            if (box == null) return;
            // 收集亚层方块 shape（与 box 相交；服务端 MOVED / 客户端渲染镜像）
            final java.util.List<VoxelShape> shapes = new java.util.ArrayList<>();
            CryptandSubLevelApi.feedCollectShapes(level, box, shapes);
            if (!shapes.isEmpty()) {
                // 返回值是 ImmutableList（不可变）——不能直接 add；合成新可变列表
                final java.util.List<VoxelShape> combined = new java.util.ArrayList<>(cir.getReturnValue());
                combined.addAll(shapes);
                cir.setReturnValue(combined);
                // ★ 方案A：站在亚层结构上 = 视为着地（参考官方 SubLevelEntityCollision
                //   ServerPlayer 分支：setOnGround + 抑制下落，防"每 tick 重新陷落"）。
                if (entity instanceof net.minecraft.server.level.ServerPlayer sp
                        && CryptandSubLevelApi.subLevelSupportsFeet(level, sp)) {
                    sp.setOnGround(true);
                    if (sp.getDeltaMovement().y < 0.0) {
                        sp.setDeltaMovement(sp.getDeltaMovement().multiply(1.0, 0.0, 1.0));
                    }
                }
            }
        } catch (final Throwable ignored) {
            // 亚层碰撞补充失败不影响原版碰撞
        }
    }
}
