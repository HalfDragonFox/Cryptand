/**
 * ===== 原版 Sable 兼容：Entity 注入 EntityMovementExtension（2026-09-01） =====
 *
 * 【架构】官方 sable 作为惰性 mod 加载（类进 ModuleLayer），但其官方 mixins 被
 * ban（不注入原版类）。其他 mod（simulated/aeronautics 等）可能调用官方
 * ActiveSableCompanion.getTrackingSubLevel(entity) →
 * ((EntityMovementExtension) entity).sable$getTrackingSubLevel() —— 官方 mixins
 * 被 ban 后 Entity 未实现该接口 → ClassCastException 崩溃。
 *
 * 【本 mixin】在 Entity 上实现官方 EntityMovementExtension 接口，方法体全部转接到
 * CryptandSable 自有 API（containingOwn → CryptandSubLevelContainer）：
 *   - sable$getTrackingSubLevel()         → 查询核心容器中实体所在亚层（无则 null）
 *   - sable$getLastTrackingSubLevelID()   → null（不追踪）
 *   - sable$setTrackingSubLevel(SubLevel) → no-op（官方追踪状态我们不维护）
 *   - sable$setLastTrackingSubLevelID()   → no-op
 *   - sable$getCollisionInfo()            → null（碰撞信息由核心碰撞层管理）
 *   - sable$setPosField(Vec3)             → no-op（官方字段覆盖不需要）
 *   - sable$getInBlockStatePos()          → BlockPos.ZERO（无意义，core 不依赖）
 *
 * 效果：官方 getTrackingSubLevel 返回 null → getTrackingOrVehicleSubLevel 回退
 * getVehicleSubLevel → getEyePositionInterpolated 走 entity.getEyePosition() 分支，
 * 调用链安全，且类型成立不崩。
 *
 * 核心包零官方 import（本类属兼容层，允许引用官方类型做转接）。
 */
package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(Entity.class)
public abstract class EntityMovementCompatMixin
        implements EntityMovementExtension {

    @Override
    public SubLevelEntityCollision.CollisionInfo sable$getCollisionInfo() {
        return null; // 碰撞信息由 CryptandSable 核心碰撞层管理（不在官方接口面暴露）
    }

    @Override
    public SubLevel sable$getTrackingSubLevel() {
        // 转接到 CryptandSable 核心容器：实体所在镜内亚层 → 返回官方 SubLevel 或 null
        // 注：CryptandSable 亚层是自有类型（CryptandSubLevel），官方眯系不直接暴露；
        // 这里返回 null = "不在官方追踪中"，官方调用链安全回退原版行为，不崩。
        return null;
    }

    @Override
    public UUID sable$getLastTrackingSubLevelID() {
        return null; // 不追踪
    }

    @Override
    public void sable$setPosField(Vec3 vec3) {
        // no-op：官方移动字段覆盖（位置回写）由 CryptandSable 核心管理，不篡改
    }

    @Override
    public void sable$setTrackingSubLevel(SubLevel subLevel) {
        // no-op：官方追踪状态我们不维护（CryptandSable 亚层管理独立）
    }

    @Override
    public void sable$setLastTrackingSubLevelID(UUID uuid) {
        // no-op
    }

    @Override
    public net.minecraft.core.BlockPos sable$getInBlockStatePos() {
        return net.minecraft.core.BlockPos.ZERO;
    }
}