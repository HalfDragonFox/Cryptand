package com.hdf.cryptand.neoforge.cryptandsable.core.backend;

/**
 * Rapier3D f64 后端（RapierF64Backend）。
 *
 * <p>精度 = f64。实际加载由 Rapier3DNativeRedirectMixin（enableSableRapier64=true）
 * 把 f64 DLL 替换进官方 Rapier3D；本类只是以 f64 精度标识包装同一反射门面。
 * 后续 Rust fork 内新增批量 JNI（stepBatch/uploadPoseBatch/bakeChunkBatch）后，
 * 在本类对应批量方法内调用同签名 native 即可。
 */
public final class RapierF64Backend extends RapierBackend {

    public RapierF64Backend() {
        super(Precision.F64);
    }

    @Override
    protected Class<?> rapierClass() {
        return com.hdf.cryptand.neoforge.cryptandsable.core.backend.Rapier3DClass.klass();
    }
}