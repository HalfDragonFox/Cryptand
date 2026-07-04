/**
 * ===== Create 多线程化 - KineticBlockEntity 通用 Mixin =====
 *
 * 目标类：com.simibubi.create.content.kinetics.base.KineticBlockEntity
 *
 * 处理齿轮颜色和应力阈值逻辑：
 *   - tick() 注入：检测齿轮应力是否超过阈值，超过则销毁方块
 *   - write() 注入：持久化齿轮颜色到 NBT
 *   - read()  注入：从 NBT 恢复齿轮颜色
 *
 * 应力计算：通过 KineticNetwork.calculateStress() 获取网络总应力，
 * 当总应力 >= 1024.0f 时销毁齿轮方块。
 *
 * 此 Mixin 提供 ICryptandGear 接口实现，供 ColoredCogwheelVisual 等
 * 客户端渲染代码查询齿轮颜色。
 */

package com.hdf.cryptand.neoforge.mixin.create;

import com.hdf.cryptand.neoforge.api.Create.ICryptandGear;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements ICryptandGear {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Cryptand-GearStress");

    /** 应力阈值：超过此值则销毁齿轮 */
    @Unique
    private static final float STRESS_THRESHOLD = 1024.0f;

    /** NBT 键名：齿轮颜色（ARGB int） */
    @Unique
    private static final String NBT_GEAR_COLOR = "GearColor";

    /** 齿轮颜色，-1 表示未初始化（使用默认颜色） */
    @Unique
    private int cryptand$gearColor = -1;

    @Shadow
    public abstract KineticNetwork getOrCreateNetwork();

    @Shadow
    protected KineticEffectHandler effects;

    @Override
    public int getGearColor() {
        return cryptand$gearColor;
    }

    /**
     * tick() 头部注入：检测齿轮应力。
     * 仅处理小齿轮（ISmallCog）和大齿轮（ILargeCog）。
     * 在服务端（非客户端）运行。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void cryptand$onTick(CallbackInfo ci) {
        KineticBlockEntity kbe = (KineticBlockEntity) (Object) this;
        BlockState state = kbe.getBlockState();

        // 只处理齿轮
        if (!(ICogWheel.isSmallCog(state) || ICogWheel.isLargeCog(state))) {
            return;
        }

        Level level = kbe.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }

        KineticNetwork network = getOrCreateNetwork();
        if (network == null) {
            return;
        }

        float totalStress = network.calculateStress();

        if (totalStress >= STRESS_THRESHOLD) {
            level.destroyBlock(kbe.getBlockPos(), true);
        }
    }

    /**
     * write() 尾部注入：将齿轮颜色写入 NBT。
     */
    @Inject(method = "write", at = @At("TAIL"))
    private void cryptand$write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        tag.putInt(NBT_GEAR_COLOR, cryptand$gearColor);
    }

    /**
     * read() 尾部注入：从 NBT 恢复齿轮颜色。
     */
    @Inject(method = "read", at = @At("TAIL"))
    private void cryptand$read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains(NBT_GEAR_COLOR)) {
            cryptand$gearColor = tag.getInt(NBT_GEAR_COLOR);
        }
    }
}
