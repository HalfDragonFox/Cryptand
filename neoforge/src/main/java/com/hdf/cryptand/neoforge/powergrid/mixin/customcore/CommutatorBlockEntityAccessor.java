/**
 * Accessor：读取 CommutatorBlockEntity 的 protected source 字段
 * （发电机换向器持有的 GeneratorCoupling，用于万用表频率显示）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.customcore;

import org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling;
import org.patryk3211.powergrid.kinetics.generator.inductionrotor.CommutatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CommutatorBlockEntity.class, remap = false)
public interface CommutatorBlockEntityAccessor {

    @Accessor("source")
    GeneratorCoupling cryptand$source();
}
