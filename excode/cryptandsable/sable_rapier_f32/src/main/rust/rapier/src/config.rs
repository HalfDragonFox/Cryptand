use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jdouble, jint};
use marten::Real;

use crate::get_physics_state_mut;

/// Global spring frequency for joints (Hz)
pub const JOINT_SPRING_FREQUENCY: Real = 550.0;

/// Global damping ratio for joints
pub const JOINT_SPRING_DAMPING_RATIO: Real = 4.0;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_configFrequencyAndDamping<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    collision_natural_frequency: jdouble,
    collision_damping_ratio: jdouble,
) {
    let mut state = get_physics_state_mut();
    state
        .integration_parameters
        .contact_softness
        .natural_frequency = collision_natural_frequency as Real;
    state.integration_parameters.contact_softness.damping_ratio = collision_damping_ratio as Real;
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_configSolverIterations<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    num_solver_iterations: jint,
    num_internal_pgs_iterations: jint,
    num_internal_stabilization_iterations: jint,
) {
    let mut state = get_physics_state_mut();
    state.integration_parameters.num_solver_iterations = num_solver_iterations as usize;
    state.integration_parameters.num_internal_pgs_iterations = num_internal_pgs_iterations as usize;
    state
        .integration_parameters
        .num_internal_stabilization_iterations = num_internal_stabilization_iterations as usize;
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_configMinIslandSize<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    _island_size: jint,
) {
    // ★ 2026-09-02 (0.35.3)：rapier 0.35.3 已移除 min_island_size 字段（不再支持）。
    //   JNI 符号保留（Java 侧仍声明），此调用为 no-op。
}
