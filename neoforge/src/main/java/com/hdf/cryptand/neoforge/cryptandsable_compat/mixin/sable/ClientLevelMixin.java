package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.Function;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Implements an extension to allow for storage of a "pose supplier" for raycasts.
 *
 * <p>即官方 {@code dev/ryanhcode/sable/mixin/clip_overwrite/ClientLevelMixin}；
 * simulated/aeronautics 会 {@code ((LevelPoseProviderExtension) ClientLevel)} cast
 * （PhysicsStaffRenderHandler 等），必须让 {@link ClientLevel} 实现该接口。
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin implements LevelPoseProviderExtension {

    @Unique
    private final ObjectList<Function<SubLevel, Pose3dc>> sable$poseSupplierStack = new ObjectArrayList<>() {{
        this.add((subLevel) -> ((SubLevel) subLevel).logicalPose());
    }};

    @Override
    public void sable$pushPoseSupplier(final Function<SubLevel, Pose3dc> supplier) {
        this.sable$poseSupplierStack.add(supplier);
    }

    @Override
    public void sable$popPoseSupplier() {
        this.sable$poseSupplierStack.removeLast();
    }

    @Override
    public Pose3dc sable$getPose(final SubLevel subLevel) {
        return this.sable$poseSupplierStack.getLast().apply(subLevel);
    }
}