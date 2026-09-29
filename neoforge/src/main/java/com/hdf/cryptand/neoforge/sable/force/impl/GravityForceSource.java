/**
 * ===== 力源：重力（框架内置，2026-09-14） =====
 *
 * <p>官方 ForceGroups 注册了 gravity 但【不记录】（重力由 pipeline.init(gravity,…) 统一施加），
 * 因此由框架自算：`F = m · g`，作用中心 = 质心，方向 = 维度重力矢量。
 *
 * <p>重力永远只有一条样本（点力/合力等价）→ 两档模式都发 {@link ForceSample.Kind#RESULTANT}。
 */
package com.hdf.cryptand.neoforge.sable.force.impl;

import com.hdf.cryptand.neoforge.sable.force.api.CryptandForceDisplay;
import com.hdf.cryptand.neoforge.sable.force.api.ForceContext;
import com.hdf.cryptand.neoforge.sable.force.api.ForceSample;
import com.hdf.cryptand.neoforge.sable.force.api.ForceSource;
import net.minecraft.resources.ResourceLocation;

public final class GravityForceSource implements ForceSource {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("cryptand", "gravity_source");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void collect(final ForceContext ctx) {
        final double mass = ctx.mass();
        if (mass <= 0.0) return;

        final double[] com = ctx.centerOfMass();
        if (com == null) return;

        final double[] g = ctx.gravity();
        ctx.emit(CryptandForceDisplay.GRAVITY, ForceSample.Kind.RESULTANT,
                com[0], com[1], com[2],
                g[0] * mass, g[1] * mass, g[2] * mass);
    }
}
