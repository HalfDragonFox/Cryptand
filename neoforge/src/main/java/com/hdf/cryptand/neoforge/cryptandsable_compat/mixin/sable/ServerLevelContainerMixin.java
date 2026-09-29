package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Supplier;

/**
 * 服务端亚层容器挂载（CryptandSable 兼容层）。
 *
 * <p>官方 sable 通过 {@code LevelsMixin（@Mixin({ServerLevel, ClientLevel}) implements
 * SubLevelContainerHolder）} 在 Level 构造时创建 {@code SubLevelContainer} 并挂到 level 上，
 * 使 {@code SubLevelContainer.getContainer(ServerLevel)} 返回非 null。simulated 组装/tick
 * 全程依赖该返回值（否则 assembleBlocks 的 {@code assert container != null} 与后续
 * {@code getSubLevel(id)} 失败/空指针）。
 *
 * <p>本实现仅挂 ServerLevel（组装发生在服务端）；未挂载时持有 null，getContainer 返回 null
 * 与旧行为一致。容器默认参数与官方一致（log=7/plot=7/origin=10000）。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelContainerMixin extends Level implements SubLevelContainerHolder {

    @Unique
    private SubLevelContainer sable$plotContainer;

    protected ServerLevelContainerMixin(final WritableLevelData writableLevelData, final ResourceKey<Level> resourceKey,
                                        final RegistryAccess registryAccess, final Holder<DimensionType> holder,
                                        final Supplier<ProfilerFiller> supplier, final boolean bl, final boolean bl2,
                                        final long l, final int i) {
        super(writableLevelData, resourceKey, registryAccess, holder, supplier, bl, bl2, l, i);
    }

    @Unique
    private SubLevelContainer cryptandsable$createPlotContainer() {
        try {
            return new ServerSubLevelContainer((ServerLevel) (Object) this,
                    SubLevelContainer.DEFAULT_LOG_SIZE_LENGTH,
                    SubLevelContainer.DEFAULT_LOG_PLOT_SIZE,
                    SubLevelContainer.DEFAULT_ORIGIN,
                    SubLevelContainer.DEFAULT_ORIGIN);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn("[CryptandSable] failed to create ServerSubLevelContainer: {}", t);
            return null;
        }
    }

    @Override
    public SubLevelContainer sable$getPlotContainer() {
        // 惰性创建：Level 构造后首个访问才建（避免在字段初始化阶段触碰物理系统）
        if (this.sable$plotContainer == null) {
            this.sable$plotContainer = this.cryptandsable$createPlotContainer();
        }
        return this.sable$plotContainer;
    }
}