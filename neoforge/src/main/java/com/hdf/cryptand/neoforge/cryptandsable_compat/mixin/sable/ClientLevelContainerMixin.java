package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 客户端亚层容器挂载（CryptandSable 兼容层）。
 *
 * <p>与 {@link ServerLevelContainerMixin} 对称：官方 {@code LevelsMixin} 会让
 * {@link ClientLevel} 实现 {@link SubLevelContainerHolder}，在 Level 构造时创建
 * 亚层容器。simulated 渲染线程（如 {@code PhysicsStaffRenderHandler.renderAllLocks}）
 * 会对已有亚层/锁数据执行 {@code SubLevelContainer.getContainer(ClientLevel).getSubLevel(id)}，
 * 若容器未挂载则返回 null → NPE。本 mixin 惰性创建 {@link ClientSubLevelContainer}
 * 并挂到 level 上。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelContainerMixin implements SubLevelContainerHolder {

    @Unique
    @Nullable
    private SubLevelContainer sable$plotContainer;

    @Override
    public SubLevelContainer sable$getPlotContainer() {
        // 惰性创建：Level 构造后首个访问才建（避免在字段初始化阶段触碰插值状态）
        if (this.sable$plotContainer == null) {
            this.sable$plotContainer = this.cryptandsable$createPlotContainer();
        }
        return this.sable$plotContainer;
    }

    @Unique
    private SubLevelContainer cryptandsable$createPlotContainer() {
        try {
            return new ClientSubLevelContainer((ClientLevel) (Object) this,
                    SubLevelContainer.DEFAULT_LOG_SIZE_LENGTH,
                    SubLevelContainer.DEFAULT_LOG_PLOT_SIZE,
                    SubLevelContainer.DEFAULT_ORIGIN,
                    SubLevelContainer.DEFAULT_ORIGIN);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn("[CryptandSable] failed to create ClientSubLevelContainer: {}", t);
            return null;
        }
    }
}