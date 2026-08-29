/**
 * Accessor：读取 GeneratorCoupling 的私有 rotor 字段（用于万用表频率显示）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.customcore;

import org.patryk3211.powergrid.electricity.sim.special.GeneratorCoupling;
import org.patryk3211.powergrid.electricity.sim.special.IRotor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = GeneratorCoupling.class, remap = false)
public interface GeneratorCouplingAccessor {

    @Accessor("rotor")
    IRotor cryptand$rotor();
}
