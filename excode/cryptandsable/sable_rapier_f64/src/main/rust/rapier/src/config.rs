use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jdouble, jint};
use crate::prec::Real;
use rapier3d::dynamics::IntegrationParameters;

use crate::scene::PhysicsScene;
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
    // ★ 2026-09-02 全局默认模板（新建 scene 复制）+ 对所有已建 scene 实量应用
    state
        .default_integration_parameters
        .contact_softness
        .natural_frequency = collision_natural_frequency as Real;
    state
        .default_integration_parameters
        .contact_softness
        .damping_ratio = collision_damping_ratio as Real;
    apply_to_scenes(&mut state, |ip| {
        ip.contact_softness.natural_frequency = collision_natural_frequency as Real;
        ip.contact_softness.damping_ratio = collision_damping_ratio as Real;
    });
}

/// ★ 2026-09-02 遍历所有已创建 scene，把配置应用到每个 scene 的 per-scene
///   integration_parameters。必须在持全局写锁时调用（与 initialize/dispose 互斥，
///   保证 scene 指针有效）。
fn apply_to_scenes<F: Fn(&mut IntegrationParameters)>(
    state: &mut std::sync::RwLockWriteGuard<'_, crate::PhysicsState>,
    f: F,
) {
    for handle in &state.scenes {
        unsafe {
            let scene = &*(*handle as *const PhysicsScene);
            let mut ip = scene.integration_parameters.write().unwrap();
            f(&mut *ip);
        }
    }
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
    state
        .default_integration_parameters
        .num_solver_iterations = num_solver_iterations as usize;
    state
        .default_integration_parameters
        .num_internal_pgs_iterations = num_internal_pgs_iterations as usize;
    state
        .default_integration_parameters
        .num_internal_stabilization_iterations = num_internal_stabilization_iterations as usize;
    apply_to_scenes(&mut state, |ip| {
        ip.num_solver_iterations = num_solver_iterations as usize;
        ip.num_internal_pgs_iterations = num_internal_pgs_iterations as usize;
        ip.num_internal_stabilization_iterations = num_internal_stabilization_iterations as usize;
    });
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
