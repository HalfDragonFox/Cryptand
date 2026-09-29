//! Shape / rigid-body extension layer for the sable rapier JNI surface.
//!
//! Adds generic rigid bodies (dynamic / fixed / kinematic) that carry standard
//! rapier shapes (ball, capsule, box, convex hull, trimesh, particle ball for
//! soft-body style cloth/chains) and a spring distance constraint between two
//! bodies. All ids share the same `rigid_bodies` map so generic ops
//! (getPose / teleport / velocities / forces) work on them out of the box.
//!
//! NOTE: this file must stay ASCII (the project had UTF-8 write corruption with
//! Chinese comments; do not add non-ASCII here).

use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jdouble, jint, jlong};
use jni::JNIEnv;
use rapier3d::dynamics::{
    GenericJointBuilder, JointAxesMask, RigidBodyActivation, RigidBodyBuilder, RigidBodyType,
};
use rapier3d::geometry::{ColliderBuilder, ColliderHandle, SharedShape};
use rapier3d::math::Vec3;
use std::sync::atomic::Ordering;

use crate::scene::LevelColliderID;
use crate::with_handle;

// NOTE: SableJointHandle in joints.rs is private; JNI returns of joints are just jlong in
// the C ABI, so we use jlong directly for the spring-link handle.

/// Map a body-type code to a rapier rigid body type.
/// 0 = dynamic, 1 = fixed (static), 2 = kinematic position based, 3 = kinematic velocity based.
fn rigid_body_type(code: i32) -> RigidBodyType {
    match code {
        // 1 => RigidBodyType::Fixed,
        _ => match code {
            0 => RigidBodyType::Dynamic,
            1 => RigidBodyType::Fixed,
            2 => RigidBodyType::KinematicPositionBased,
            3 => RigidBodyType::KinematicVelocityBased,
            _ => RigidBodyType::Dynamic,
        },
    }
}

/// Build a standard shape from a shape-type code + flat param array.
/// shape_type: 0=ball[radius], 1=capsule[halfHeight,radius], 2=box[halfX,halfY,halfZ],
///             3=convex[nv, (x,y,z)*nv], else None.
fn build_shape(shape_type: i32, p: &[jdouble]) -> Option<SharedShape> {
    match shape_type {
        0 => p.first().map(|&r| SharedShape::ball(r as Real)),
        1 => {
            if p.len() >= 2 {
                // capsule(a, b, radius): segment endpoints as vectors + radius (y-aligned, half height p[0])
                let h = p[0] as Real;
                let r = p[1] as Real;
                Some(SharedShape::capsule(Vec3::new(0.0, -h, 0.0), Vec3::new(0.0, h, 0.0), r))
            } else {
                None
            }
        }
        2 => {
            if p.len() >= 3 {
                Some(SharedShape::cuboid(
                    p[0] as Real,
                    p[1] as Real,
                    p[2] as Real,
                ))
            } else {
                None
            }
        }
        3 => {
            if p.len() >= 1 {
                let nv = p[0] as usize;
                if 1 + 3 * nv <= p.len() {
                    let pts: Vec<Vec3> = (0..nv)
                        .map(|i| {
                            Vec3::new(
                                p[1 + 3 * i] as Real,
                                p[2 + 3 * i] as Real,
                                p[3 + 3 * i] as Real,
                            )
                        })
                        .collect();
                    SharedShape::convex_hull(&pts)
                } else {
                    None
                }
            } else {
                None
            }
        }
        _ => None,
    }
}

fn pose_to_arr(env: &JNIEnv, pose: JDoubleArray) -> [jdouble; 7] {
    let mut a: [jdouble; 7] = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0];
    env.get_double_array_region(pose, 0, &mut a).unwrap();
    a
}

fn params_to_vec<'local>(env: &JNIEnv<'local>, params: JDoubleArray<'local>) -> Vec<jdouble> {
    let len = env.get_array_length(&params).unwrap() as usize;
    let mut v = vec![0.0 as jdouble; len];
    env.get_double_array_region(&params, 0, &mut v).unwrap();
    v
}

fn insert_shape_body(
    scene: &crate::scene::PhysicsScene,
    id: i32,
    body_type: i32,
    mass: jdouble,
    shape: SharedShape,
    friction: jdouble,
    restitution: jdouble,
    pose: [jdouble; 7],
) {
    let quat = crate::Quat::from_xyzw(
        pose[3] as Real,
        pose[4] as Real,
        pose[5] as Real,
        pose[6] as Real,
    );
    let mut rigid_body = RigidBodyBuilder::new(rigid_body_type(body_type))
        .translation(Vec3::new(
            pose[0] as Real,
            pose[1] as Real,
            pose[2] as Real,
        ))
        .build();
    rigid_body.set_rotation(quat, false);
    if body_type == 0 {
        // dynamic bodies get CCD to avoid tunnelling
        rigid_body.enable_ccd(true);
        // 2026-09-03: disable sleeping for dynamic shape bodies so structures never
        // "freeze" after settling. Rapier default: speed below threshold for ~1s ->
        // marked sleeping -> no longer integrated -> looks locked/standing still.
        // User wants a real persistent physics room, so keep them always active
        // (they can still rest on the ground, but stay pushable/dynamic).
        *rigid_body.activation_mut() = RigidBodyActivation::cannot_sleep();
    }

    let mut sim_data = scene.sim_data.write().unwrap();
    let sim_data = &mut *sim_data;
    let mut sable_data = scene.sable_data.write().unwrap();

    let body_handle = sim_data.rigid_body_set.insert(rigid_body);

    let collider = if mass > 0.0 {
        ColliderBuilder::new(shape)
            .mass(mass as Real)
            .friction(friction as Real)
            .restitution(restitution as Real)
            .build()
    } else {
        ColliderBuilder::new(shape)
            .density(1.0)
            .friction(friction as Real)
            .restitution(restitution as Real)
            .build()
    };

    sim_data
        .collider_set
        .insert_with_parent(collider, body_handle, &mut sim_data.rigid_body_set);

    sable_data
        .rigid_bodies
        .insert(id as LevelColliderID, body_handle);

    // 2026-09-04 Register shape cache entry for FIXED companion bodies (body_type==1,
    // used as shape companion/ground). Dynamic structures (0) and kinematic (2/3) are
    // NOT cached (never evicted). Weight starts at 1.
    if body_type == 1 {
        sable_data.shape_cache.insert(
            id as LevelColliderID,
            crate::scene::ShapeCacheEntry {
                id: id as LevelColliderID,
                weight: 1,
                footprint: 1,
                colliders: Vec::new(),
                bytes: 0,
            },
        );
    }
}

/// Create a rigid body with one standard shape.
/// createShapeBody(handle, id, bodyType, mass, shapeType, double[] params, friction, restitution, double[] pose)
/// bodyType: 0=dynamic 1=fixed 2=kinematic-position 3=kinematic-velocity
/// shapeType: 0=ball 1=capsule 2=box 3=convex hull
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_createShapeBody<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    body_type: jint,
    mass: jdouble,
    shape_type: jint,
    params: JDoubleArray<'local>,
    friction: jdouble,
    restitution: jdouble,
    pose: JDoubleArray<'local>,
) {
    let parsed = params_to_vec(&env, params);
    let pose_arr = pose_to_arr(&env, pose);
    let Some(shape) = build_shape(shape_type, &parsed) else {
        return;
    };
    with_handle(handle, |scene| {
        insert_shape_body(scene, id, body_type, mass, shape, friction, restitution, pose_arr);
    })
}

/// Create a rigid body with a triangle mesh collider.
/// createTrimeshShapeBody(handle, id, bodyType, mass, double[] vertices, int[] indices, friction, restitution, double[] pose)
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_createTrimeshShapeBody<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    body_type: jint,
    mass: jdouble,
    vertices: JDoubleArray<'local>,
    indices: JIntArray<'local>,
    friction: jdouble,
    restitution: jdouble,
    pose: JDoubleArray<'local>,
) {
    let vlen = env.get_array_length(&vertices).unwrap() as usize;
    let mut v = vec![0.0 as jdouble; vlen];
    env.get_double_array_region(&vertices, 0, &mut v).unwrap();
    let ilen = env.get_array_length(&indices).unwrap() as usize;
    let mut idx = vec![0i32; ilen];
    env.get_int_array_region(&indices, 0, &mut idx).unwrap();

    let verts: Vec<Vec3> = v
        .chunks_exact(3)
        .map(|c| Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real))
        .collect();
    let tris: Vec<[u32; 3]> = idx
        .chunks_exact(3)
        .map(|c| [c[0] as u32, c[1] as u32, c[2] as u32])
        .collect();
    if verts.len() < 3 || tris.is_empty() {
        return;
    }
    let shape = SharedShape::trimesh(verts, tris).expect("trimesh cooking failed");
    let pose_arr = pose_to_arr(&env, pose);
    with_handle(handle, |scene| {
        insert_shape_body(scene, id, body_type, mass, shape, friction, restitution, pose_arr);
    })
}

/// createCompoundShapeBody(handle, id, bodyType, mass, count, double[] halfAndCenter,
///                          double[] boxCoeffs, friction, restitution, double[] pose)
/// Create ONE rigid body and attach `count` axis-aligned box colliders in a single
/// native call (concave compound = merged large boxes, 2026-09-05 user decision).
/// Each entry of halfAndCenter (flat): [halfX, halfY, halfZ, centerX, centerY, centerZ].
/// boxCoeffs (optional, may be null): per-box [friction, restitution] * count;
///   entry missing/out-of-range falls back to the body-level friction/restitution.
/// All colliders use density 0 -> no added mass (body mass provided separately via
/// setMassProperties, or additional_mass when mass > 0 at creation for dynamic).
/// bodyType: 0=dynamic 1=fixed. FIXED bodies register a shape-cache entry.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_createCompoundShapeBody<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    body_type: jint,
    mass: jdouble,
    count: jint,
    half_and_center: JDoubleArray<'local>,
    box_coeffs: JDoubleArray<'local>,
    friction: jdouble,
    restitution: jdouble,
    pose: JDoubleArray<'local>,
) {
    let pose_arr = pose_to_arr(&env, pose);
    let n = count.max(0) as usize;
    if n == 0 {
        return;
    }
    let data_len = env.get_array_length(&half_and_center).unwrap_or(0) as usize;
    let mut data = vec![0.0 as jdouble; data_len];
    env.get_double_array_region(&half_and_center, 0, &mut data).unwrap();
    // Per-box [friction, restitution] * count; empty/null -> fall back to body-level.
    let mut coeffs: Vec<jdouble> = Vec::new();
    if !box_coeffs.is_null() {
        let clen = env.get_array_length(&box_coeffs).unwrap_or(0) as usize;
        coeffs = vec![0.0 as jdouble; clen];
        env.get_double_array_region(&box_coeffs, 0, &mut coeffs).unwrap();
    }

    with_handle(handle, |scene| {
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap();

        let mut builder = RigidBodyBuilder::new(rigid_body_type(body_type))
            .translation(Vec3::new(
                pose_arr[0] as Real,
                pose_arr[1] as Real,
                pose_arr[2] as Real,
            ));
        if body_type == 0 && mass > 0.0 {
            // Safety net for dynamic bodies; setMassProperties overrides later.
            builder = builder.additional_mass(mass as Real);
        }
        let mut rigid_body = builder.build();
        rigid_body.set_rotation(
            crate::Quat::from_xyzw(
                pose_arr[3] as Real,
                pose_arr[4] as Real,
                pose_arr[5] as Real,
                pose_arr[6] as Real,
            ),
            false,
        );
        if body_type == 0 {
            // dynamic bodies: CCD + never sleep (persistent physics room).
            rigid_body.enable_ccd(true);
            *rigid_body.activation_mut() = RigidBodyActivation::cannot_sleep();
        }
        let body_handle = sim_data.rigid_body_set.insert(rigid_body);

        let mut collider_handles: Vec<ColliderHandle> = Vec::with_capacity(n);
        for i in 0..n {
            let base = i * 6;
            if base + 5 >= data.len() {
                break;
            }
            let hx = data[base] as Real;
            let hy = data[base + 1] as Real;
            let hz = data[base + 2] as Real;
            if hx <= 0.0 || hy <= 0.0 || hz <= 0.0 {
                continue;
            }
            // Per-box coefficients (material-aware), fall back to body-level.
            let (bf, br) = if i * 2 + 1 < coeffs.len() {
                (coeffs[i * 2] as Real, coeffs[i * 2 + 1] as Real)
            } else {
                (friction as Real, restitution as Real)
            };
            let collider = ColliderBuilder::cuboid(hx, hy, hz)
                .translation(Vec3::new(
                    data[base + 3] as Real,
                    data[base + 4] as Real,
                    data[base + 5] as Real,
                ))
                .density(0.0)
                .friction(bf)
                .restitution(br)
                .build();
            let ch = sim_data.collider_set.insert_with_parent(
                collider,
                body_handle,
                &mut sim_data.rigid_body_set,
            );
            collider_handles.push(ch);
        }
        if collider_handles.is_empty() {
            sim_data.rigid_body_set.remove(
                body_handle,
                &mut sim_data.island_manager,
                &mut sim_data.collider_set,
                &mut sim_data.impulse_joint_set,
                &mut sim_data.multibody_joint_set,
                true,
            );
            return;
        }
        sable_data
            .rigid_bodies
            .insert(id as LevelColliderID, body_handle);
        // 2026-09-05 Register shape cache for FIXED companion compound bodies.
        if body_type == 1 {
            sable_data.shape_cache.insert(
                id as LevelColliderID,
                crate::scene::ShapeCacheEntry {
                    id: id as LevelColliderID,
                    weight: 1,
                    footprint: collider_handles.len() as u32,
                    colliders: collider_handles
                        .iter()
                        .map(|c| c.into_raw_parts().0 as usize)
                        .collect(),
                    bytes: collider_handles.len() * 96,
                },
            );
        }
        log::info!(
            "[sabledbg] createCompoundShapeBody id={} type={} boxes={} pose=({:.1},{:.1},{:.1})",
            id,
            body_type,
            collider_handles.len(),
            pose_arr[0],
            pose_arr[1],
            pose_arr[2]
        );
    })
}

/// Remove any body by id (shape bodies and box bodies share the same map).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_removeShapeBody<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
) {    with_handle(handle, |scene| {
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap();

        let Some(body_handle) = sable_data.rigid_bodies.remove(&(id as LevelColliderID)) else {
            return;
        };
        // 2026-09-04 Also remove from shape cache (manual delete).
        sable_data.shape_cache.remove(&(id as LevelColliderID));
        sim_data.rigid_body_set.remove(
            body_handle,
            &mut sim_data.island_manager,
            &mut sim_data.collider_set,
            &mut sim_data.impulse_joint_set,
            &mut sim_data.multibody_joint_set,
            true,
        );
    })
}

/// Append another collider shape to an existing body (composite / polyshape).
/// addShapeCollider(handle, id, shapeType, double[] params, friction, restitution)
/// The extra collider contributes no additional mass (density 0).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_addShapeCollider<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    shape_type: jint,
    params: JDoubleArray<'local>,
    friction: jdouble,
    restitution: jdouble,
) {
    let parsed = params_to_vec(&env, params);
    let Some(shape) = build_shape(shape_type, &parsed) else {
        return;
    };
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let Some(&body_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return;
        };
        drop(sable_data);
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        let collider = ColliderBuilder::new(shape)
            .density(0.0)
            .friction(friction as Real)
            .restitution(restitution as Real)
            .build();
        sim_data
            .collider_set
            .insert_with_parent(collider, body_handle, &mut sim_data.rigid_body_set);
    })
}

/// addShapeColliderAt(handle, id, shapeType, double[] params, friction, restitution, px, py, pz)
/// Add an extra collider offset by (px,py,pz) from the body origin (world or local).
/// NOTE 2026-09-03: primary lever for "native shape direct collision":
///   each structure block = one full 1x1x1 box collider positioned at its block
///   coordinate (box params [0.5,0.5,0.5] = full block centered at the offset).
///   Density 0 -> no added mass (body mass set by createShapeBody).
/// NOTE: keep this file ASCII (no non-ASCII comments).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_addShapeColliderAt<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    shape_type: jint,
    params: JDoubleArray<'local>,
    friction: jdouble,
    restitution: jdouble,
    px: jdouble,
    py: jdouble,
    pz: jdouble,
) {
    let parsed = params_to_vec(&env, params);
    let Some(shape) = build_shape(shape_type, &parsed) else {
        return;
    };
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let Some(&body_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return;
        };
        drop(sable_data);
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        let collider = ColliderBuilder::new(shape)
            .translation(Vec3::new(px as Real, py as Real, pz as Real))
            .density(0.0)
            .friction(friction as Real)
            .restitution(restitution as Real)
            .build();
        let collider_handle = sim_data
            .collider_set
            .insert_with_parent(collider, body_handle, &mut sim_data.rigid_body_set);
        drop(sim_data);

        // 2026-09-04 Passive weight bump + footprint + tracking. Only cached (fixed
        // companion) bodies touch; saturating-add (never exceeds 255).
        let mut sable_data = scene.sable_data.write().unwrap();
        if let Some(entry) = sable_data.shape_cache.get_mut(&(id as LevelColliderID)) {
            entry.footprint += 1;
            entry.touch();
            entry.colliders.push(collider_handle.into_raw_parts().0 as usize);
            entry.bytes = entry.colliders.len() * 96;
        }
    })
}

/// removeShapeColliderAt(handle, id, px, py, pz)
/// Remove the extra collider on body `id` whose translation offset from the body
/// origin equals (px,py,pz) (block-grid coordinates used by addShapeColliderAt).
/// Incremental counterpart of addShapeColliderAt: lets the Java layer diff
/// "which boxes should exist" vs "which boxes exist" and apply only the delta.
/// Missing collider / unknown body / no match -> no-op (idempotent).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_removeShapeColliderAt<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    px: jdouble,
    py: jdouble,
    pz: jdouble,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let Some(&body_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return;
        };
        drop(sable_data);

        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;

        let target = Vec3::new(px as Real, py as Real, pz as Real);
        // Find the collider attached to body_handle whose offset matches target.
        let mut to_remove: Option<ColliderHandle> = None;
        // Collect first (iteration while removing can rehash/invalidate).
        let mut candidates: Vec<ColliderHandle> = Vec::new();
        for (handle_c, collider) in sim_data.collider_set.iter() {
            if collider.parent() == Some(body_handle) {
                let t = collider.position().translation;
                let dx = t.x - target.x;
                let dy = t.y - target.y;
                let dz = t.z - target.z;
                if dx.abs() < 1e-4 && dy.abs() < 1e-4 && dz.abs() < 1e-4 {
                    candidates.push(handle_c);
                }
            }
        }
        if let Some(c) = candidates.into_iter().next() {
            to_remove = Some(c);
        }
        if let Some(remove_handle) = to_remove {
            let remove_index = remove_handle.into_raw_parts().0 as usize;
            sim_data.collider_set.remove(
                remove_handle,
                &mut sim_data.island_manager,
                &mut sim_data.rigid_body_set,
                true,
            );
            drop(sim_data);

            // Keep shape cache bookkeeping consistent (drop the collider entry).
            let mut sable_data = scene.sable_data.write().unwrap();
            if let Some(entry) = sable_data.shape_cache.get_mut(&(id as LevelColliderID)) {
                if let Some(pos) = entry.colliders.iter().position(|c| *c == remove_index) {
                    entry.colliders.remove(pos);
                    entry.bytes = entry.colliders.len() * 96;
                }
                if entry.footprint > 0 {
                    entry.footprint -= 1;
                }
            }
        }
    })
}

/// Evict shape cache down to the current limit (weight-first, then smallest
/// footprint; batch removes at least min_evict entries). Called from
/// evictShapeCache JNI and after shrinking (limit lowered).
pub fn evict_shape_cache(scene: &crate::scene::PhysicsScene) -> usize {
    let mut removed: usize = 0;
    let sable_data = scene.sable_data.read().unwrap();
    let limit = sable_data.shape_cache_limit.load(Ordering::Relaxed);
    let min_evict = sable_data
        .shape_cache_min_evict
        .load(Ordering::Relaxed)
        .max(1) as usize;
    if limit < 0 {
        // Unlimited → nothing to evict.
        sable_data
            .shape_cache_shrink_pending
            .store(false, Ordering::Relaxed);
        return 0;
    }
    let current = sable_data.shape_cache.len();
    if (current as i64) <= limit {
        sable_data
            .shape_cache_shrink_pending
            .store(false, Ordering::Relaxed);
        return 0;
    }
    // How many must be removed: at least (current - limit), at least min_evict.
    let over = (current as i64 - limit) as usize;
    let mut want = over.max(min_evict);
    want = want.min(current); // can't remove more than we have

    // Candidate sort: weight asc, then footprint asc (small/inactive first).
    let mut candidates: Vec<usize> = sable_data.shape_cache.keys().copied().collect();
    candidates.sort_by_key(|id| {
        let e = &sable_data.shape_cache[id];
        (e.weight as u32, e.footprint)
    });
    let victim_ids: Vec<usize> = candidates.into_iter().take(want).collect();
    drop(sable_data);

    let mut sim_data = scene.sim_data.write().unwrap();
    let mut sable_data = scene.sable_data.write().unwrap();
    let sim_data = &mut *sim_data;
    for id in victim_ids {
        // Remove body (and its colliders) from the physics world.
        if let Some(body_handle) = sable_data.rigid_bodies.remove(&id) {
            sim_data.rigid_body_set.remove(
                body_handle,
                &mut sim_data.island_manager,
                &mut sim_data.collider_set,
                &mut sim_data.impulse_joint_set,
                &mut sim_data.multibody_joint_set,
                true,
            );
            sable_data.shape_cache.remove(&id);
            removed += 1;
        } else {
            sable_data.shape_cache.remove(&id);
        }
    }
    log::info!(
        "[sabledbg] shape_cache evicted {} (limit={} cache={})",
        removed,
        limit,
        current
    );
    removed
}

/// Convenience: a small dynamic sphere body used as a soft-body particle.
/// createParticleBody(handle, id, radius, mass, double[] pose)
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_createParticleBody<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    radius: jdouble,
    mass: jdouble,
    pose: JDoubleArray<'local>,
) {
    let pose_arr = pose_to_arr(&env, pose);
    let shape = SharedShape::ball(radius as Real);
    with_handle(handle, |scene| {
        insert_shape_body(scene, id, 0, mass, shape, 0.3, 0.0, pose_arr);
    })
}

/// Spring distance link between two bodies (soft-body / cloth / chain edge).
/// linkBodiesSpring(handle, idA, idB, double[] localAnchorA(xyz), double[] localAnchorB(xyz),
///                   frequency, dampingRatio) -> long jointHandle
/// The translation axes are locked + softness spring -> behaves as a stretchy distance joint.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hdf_cryptand_neoforge_cryptandsable_core_backend_official_CryptandRapierNative_linkBodiesSpring<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id_a: jint,
    id_b: jint,
    local_a: JDoubleArray<'local>,
    local_b: JDoubleArray<'local>,
    frequency: jdouble,
    damping_ratio: jdouble,
) -> jlong {
    let la = params_to_vec(&env, local_a);
    let lb = params_to_vec(&env, local_b);
    let anchor_a = Vec3::new(
        las(&la, 0) as Real,
        las(&la, 1) as Real,
        las(&la, 2) as Real,
    );
    let anchor_b = Vec3::new(
        las(&lb, 0) as Real,
        las(&lb, 1) as Real,
        las(&lb, 2) as Real,
    );

    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap();
        let mut sim_data = scene.sim_data.write().unwrap();

        let Some(&rb_a) = sable_data.rigid_bodies.get(&(id_a as LevelColliderID)) else {
            return -1i64;
        };
        let Some(&rb_b) = sable_data.rigid_bodies.get(&(id_b as LevelColliderID)) else {
            return -1i64;
        };

        // Lock the 3 linear axes (distance) + spring softness, leave rotations free (ball-and-socket).
        let mut joint = GenericJointBuilder::new(JointAxesMask::from_bits_truncate(0b0000_0111))
            .softness(rapier3d::dynamics::SpringCoefficients::new(
                frequency as Real,
                damping_ratio as Real,
            ));
        joint.0.local_frame1.translation = anchor_a;
        joint.0.local_frame2.translation = anchor_b;

        let jhandle = sim_data
            .impulse_joint_set
            .insert(rb_a, rb_b, joint.build(), true);
        let (index, generation) = jhandle.0.into_raw_parts();
        (index as i64) | ((generation as i64) << 32)
    })
}

fn las(v: &[jdouble], i: usize) -> jdouble {
    if v.len() > i {
        v[i]
    } else {
        0.0
    }
}

use crate::prec::Real;