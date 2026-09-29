package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import dev.ryanhcode.sable.mixinterface.block_properties.BlockStateExtension;
import dev.ryanhcode.sable.physics.config.block_properties.BlockStateConditionSet;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinition;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

/**
 * Makes block states hold their physics properties.
 *
 * <p>即官方 {@code dev/ryanhcode/sable/mixin/block_properties/BlockStateMixin}。
 * BlockPropertiesTooltip / 物品属性（simulated）会 {@code ((BlockStateExtension) state)} cast，
 * 因此必须让 {@link BlockState} 实现 {@link BlockStateExtension}（兼容 simulated/aeronautics）。
 */
@Mixin(BlockState.class)
public class BlockStateMixin implements BlockStateExtension {

    @Unique
    @Nullable
    private Object[] sable$properties = null;

    @Override
    public void sable$loadProperties(final StateDefinition<Block, BlockState> stateDefinition, final PhysicsBlockPropertiesDefinition definition) {
        if (this.sable$properties == null) {
            this.sable$properties = new Object[PhysicsBlockPropertyTypes.count()];
        }

        this.sable$applyPropertySet(definition.properties());

        if (definition.overrides().isPresent()) {
            for (final Map.Entry<BlockStateConditionSet, Map<ResourceLocation, Object>> override : definition.overrides().get().entrySet()) {
                if (override.getKey().matches(stateDefinition, (BlockState) (Object) this)) {
                    this.sable$applyPropertySet(override.getValue());
                }
            }
        }
    }

    @Unique
    private void sable$applyPropertySet(final Map<ResourceLocation, Object> properties) {
        for (final Map.Entry<ResourceLocation, Object> entry : properties.entrySet()) {
            final int index = PhysicsBlockPropertyTypes.getPropertyType(entry.getKey()).id();
            this.sable$properties[index] = entry.getValue();
        }
    }

    @Override
    public <T> T sable$getProperty(final PhysicsBlockPropertyTypes.PhysicsBlockPropertyType<T> type) {
        // return default if we have no properties or the property is not set on this block
        if (this.sable$properties == null || this.sable$properties[type.id()] == null) {
            return type.defaultValue();
        }

        //noinspection unchecked
        return (T) this.sable$properties[type.id()];
    }

}