/**
 * ===== 亚层方块渲染光照包装（客户端，2026-08-31） =====
 *
 * 参考原版 sable 的 SingleBlockSubLevelWrapper：
 *  - 查询光照/亮度时使用【全局世界位置】（anchor + 偏移），保证亚层方块在真实世界位置上
 *    正确采光（否则 tesselateBlock 用 (0,0,0) 位置 → 全黑）。
 *  - 邻居方块一律返回 AIR（亚层是独立空间，渲染时只显示自己的方块）。
 *  - getBlockState(本地位置) 返回当前渲染方块状态。
 *
 * 注意：亚层方块随物理位姿运动（旋转/平移）。光照查询以"初始世界位置"为准（MVP，
 * 静止/小范围运动时足够；远距离运动后再按新位置重采光）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SableSubLevelBlockAndTintGetter implements BlockAndTintGetter {

    private ClientLevel level;
    private final BlockPos.MutableBlockPos globalPos;
    private final BlockPos.MutableBlockPos localPos;
    private BlockState state;

    public SableSubLevelBlockAndTintGetter() {
        this.globalPos = new BlockPos.MutableBlockPos();
        this.localPos = new BlockPos.MutableBlockPos();
    }

    public void setup(ClientLevel level, double x, double y, double z, BlockPos localPos, BlockState state) {
        this.level = level;
        this.globalPos.set(x, y, z);
        this.localPos.set(localPos);
        this.state = state;
    }

    public void clear() {
        this.level = null;
    }

    @Override
    public float getShade(Direction direction, boolean bl) {
        // 官方 SingleBlockSubLevelWrapper 同款：直接转发主世界 shade。
        // 注：tesselateWithoutAO 已禁 AO（不读邻居），getShade 只提供方向明暗；
        // 方块虽被物理化拿走（世界 AIR），Level.getShade 返回的是该位置的光照环境
        // （无方块遮挡时 = 全局 shade 值），不会因方块不存在而变 0。
        return this.level != null ? this.level.getShade(direction, bl)
                : net.minecraft.client.Minecraft.getInstance().level != null
                    ? net.minecraft.client.Minecraft.getInstance().level.getShade(direction, bl)
                    : 1.0F;
    }

    @Override
    public @NotNull LevelLightEngine getLightEngine() {
        return this.level != null ? this.level.getLightEngine()
                : net.minecraft.client.Minecraft.getInstance().level != null
                    ? net.minecraft.client.Minecraft.getInstance().level.getLightEngine()
                    : null;
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        // 官方 SingleBlockSubLevelWrapper 同款：全局位置查主世界光照引擎。
        // （方块虽被物理化拿走（世界 AIR），光照引擎在该坐标仍有天空光/方块光值——
        //  物理化位置通常可见天空 → skyLight 高；getShade 提供方向明暗。）
        if (this.level == null && net.minecraft.client.Minecraft.getInstance().level != null) {
            this.level = net.minecraft.client.Minecraft.getInstance().level;
        }
        return this.getLightEngine() != null
                ? this.getLightEngine().getLayerListener(lightLayer).getLightValue(this.globalPos)
                : 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int i) {
        if (this.level == null && net.minecraft.client.Minecraft.getInstance().level != null) {
            this.level = net.minecraft.client.Minecraft.getInstance().level;
        }
        return this.getLightEngine() != null ? this.getLightEngine().getRawBrightness(this.globalPos, i) : 15;
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return this.getBrightness(LightLayer.SKY, this.globalPos) >= this.getMaxLightLevel();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        // ★ 2026-09-01 草方块侧面黑修复：官方用真实方块 pos 查生物群系色；
        //   我们用 BlockPos.ZERO（tesselate 传入）→ (0,0,0) 无生物群系 → 黑。
        //   改用 globalPos（真实世界位置）查 tint（点缀颜色/草/叶）。
        return this.level != null ? this.level.getBlockTint(this.globalPos, colorResolver) : 0xFFFFFF;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return this.level != null ? this.level.getBlockEntity(pos) : null;
    }

    @Override
    public @NotNull BlockState getBlockState(BlockPos pos) {
        if (pos.equals(this.localPos)) {
            return this.state;
        }
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public @NotNull FluidState getFluidState(BlockPos pos) {
        if (pos.equals(this.localPos)) {
            return this.state.getFluidState();
        }
        return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public int getHeight() {
        return this.level != null ? this.level.getHeight() : 384;
    }

    @Override
    public int getMinBuildHeight() {
        return this.level != null ? this.level.getMinBuildHeight() : -64;
    }
}
