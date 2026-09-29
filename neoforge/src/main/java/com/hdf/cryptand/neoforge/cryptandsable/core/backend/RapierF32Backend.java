package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

/**
 * Rapier3D f32 后端（RapierF32Backend）。
 *
 * <p>精度 = f32（官方默认；enableSableRapier64=false）。同一反射门面，仅 precision 标识不同。
 */
public final class RapierF32Backend extends RapierBackend {

    public RapierF32Backend() {
        super(Precision.F32);
    }

    @Override
    protected Class<?> rapierClass() {
        return com.hdf.cryptand.neoforge.cryptandsable.core.backend.Rapier3DClass.klass();
    }
}